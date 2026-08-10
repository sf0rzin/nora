package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.workflow.WorkflowExecutionResponse;
import br.com.nora.api.api.dto.workflow.WorkflowResponse;
import br.com.nora.api.api.dto.workflow.WorkflowUpsertRequest;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.RequiresPermission;
import br.com.nora.api.api.security.RequiresPermission.ResourceType;
import br.com.nora.api.api.security.ResourceArns;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.workflow.WorkflowService;
import br.com.nora.api.domain.workflow.Workflow;
import br.com.nora.api.domain.workflow.WorkflowExecution;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * NORA Flows API: workflow CRUD + manual test + execution history. Scoped by the principal's
 * tenant_id (ADR 0002 + RLS ADR 0028).
 *
 * <p>Every handler is gated by IAM (#51). Before that, authentication was the only barrier: any
 * member of the tenant could read, rewrite, delete and run every automation of the workspace.
 * Workflows have no owner column, so the whole set is tenant-wide and the resource ARN is {@code
 * nora:tenant/{t}:workflow/{id|*}}.
 *
 * <p>Running a workflow is a distinct action ({@code workflow:test}) rather than a read or a write:
 * it executes the wired actions for real (e-mail, Slack, issue creation) against the tenant's own
 * integrations, so it is neither of the two.
 */
@RestController
@RequestMapping("/workflows")
public class WorkflowsController {

    private final WorkflowService service;
    private final ObjectMapper mapper;
    private final AuthorizationService authz;

    public WorkflowsController(
            WorkflowService service, ObjectMapper mapper, AuthorizationService authz) {
        this.service = service;
        this.mapper = mapper;
        this.authz = authz;
    }

    /**
     * Listing: the annotation is the cheap pre-gate ({@code requireAnyAllow} reasons about sets),
     * and the visible set is decided per item below. The strict check over the wildcard ARN would
     * match the {@code *} as plain text, so a Deny written against one workflow id would never fire
     * against the list.
     */
    @GetMapping
    @RequiresPermission(action = "workflow:read", resource = ResourceType.WORKFLOW, anyAllow = true)
    public List<WorkflowResponse> list() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        List<Workflow> visible =
                authz.filterAllowed(
                        principal.userId(),
                        principal.tenantId(),
                        "workflow:read",
                        service.list(principal.tenantId()),
                        w -> ResourceArns.workflow(principal.tenantId(), w.id()),
                        w -> Map.of());
        return visible.stream().map(this::toResponse).toList();
    }

    @PostMapping
    @RequiresPermission(action = "workflow:write", resource = ResourceType.WORKFLOW)
    public ResponseEntity<WorkflowResponse> create(@Valid @RequestBody WorkflowUpsertRequest body) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        Workflow created =
                service.create(
                        principal.tenantId(),
                        body.name(),
                        body.definition(),
                        body.activeOrDefault());
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(created));
    }

    @GetMapping("/{id}")
    @RequiresPermission(action = "workflow:read", resource = ResourceType.WORKFLOW, idParam = "id")
    public WorkflowResponse get(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return toResponse(service.get(id, principal.tenantId()));
    }

    @PutMapping("/{id}")
    @RequiresPermission(action = "workflow:write", resource = ResourceType.WORKFLOW, idParam = "id")
    public WorkflowResponse update(
            @PathVariable("id") UUID id, @Valid @RequestBody WorkflowUpsertRequest body) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        Workflow updated =
                service.update(
                        id,
                        principal.tenantId(),
                        body.name(),
                        body.definition(),
                        body.activeOrDefault());
        return toResponse(updated);
    }

    @DeleteMapping("/{id}")
    @RequiresPermission(action = "workflow:write", resource = ResourceType.WORKFLOW, idParam = "id")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        service.delete(id, principal.tenantId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Runs the workflow now (synchronous) and returns the execution with the log — the canvas
     * "Test".
     */
    @PostMapping("/{id}/test")
    @RequiresPermission(action = "workflow:test", resource = ResourceType.WORKFLOW, idParam = "id")
    public WorkflowExecutionResponse test(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return toExecutionResponse(service.test(id, principal.tenantId()));
    }

    @GetMapping("/{id}/executions")
    @RequiresPermission(action = "workflow:read", resource = ResourceType.WORKFLOW, idParam = "id")
    public List<WorkflowExecutionResponse> executions(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return service.listExecutions(id, principal.tenantId()).stream()
                .map(this::toExecutionResponse)
                .toList();
    }

    private WorkflowResponse toResponse(Workflow workflow) {
        return new WorkflowResponse(
                workflow.id(),
                workflow.name(),
                workflow.triggerType().wire(),
                workflow.active(),
                readJson(workflow.definitionJson()),
                workflow.createdAt(),
                workflow.updatedAt());
    }

    private WorkflowExecutionResponse toExecutionResponse(WorkflowExecution execution) {
        return new WorkflowExecutionResponse(
                execution.id(),
                execution.workflowId(),
                execution.eventType(),
                execution.status().name(),
                readJson(execution.logJson()),
                execution.createdAt(),
                execution.finishedAt());
    }

    private JsonNode readJson(String json) {
        try {
            return mapper.readTree(json == null ? "[]" : json);
        } catch (Exception ex) {
            return mapper.createArrayNode();
        }
    }
}
