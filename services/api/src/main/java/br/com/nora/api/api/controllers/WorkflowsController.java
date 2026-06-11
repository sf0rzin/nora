package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.workflow.WorkflowExecutionResponse;
import br.com.nora.api.api.dto.workflow.WorkflowResponse;
import br.com.nora.api.api.dto.workflow.WorkflowUpsertRequest;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.workflow.WorkflowService;
import br.com.nora.api.domain.workflow.Workflow;
import br.com.nora.api.domain.workflow.WorkflowExecution;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.util.List;
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
 * API do NORA Flows: CRUD de workflows + teste manual + histórico de execuções. Escopo por
 * tenant_id do principal (ADR 0002 + RLS ADR 0028); autenticação exigida pelo SecurityConfig (rota
 * não pública). Sem gate IAM fino — Flows é recurso do Core individual (mesmo padrão do
 * ChatSessionController).
 */
@RestController
@RequestMapping("/workflows")
public class WorkflowsController {

    private final WorkflowService service;
    private final ObjectMapper mapper;

    public WorkflowsController(WorkflowService service, ObjectMapper mapper) {
        this.service = service;
        this.mapper = mapper;
    }

    @GetMapping
    public List<WorkflowResponse> list() {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return service.list(principal.tenantId()).stream().map(this::toResponse).toList();
    }

    @PostMapping
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
    public WorkflowResponse get(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return toResponse(service.get(id, principal.tenantId()));
    }

    @PutMapping("/{id}")
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
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        service.delete(id, principal.tenantId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Executa o workflow agora (síncrono) e devolve a execução com o log — o "Testar" do canvas.
     */
    @PostMapping("/{id}/test")
    public WorkflowExecutionResponse test(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        return toExecutionResponse(service.test(id, principal.tenantId()));
    }

    @GetMapping("/{id}/executions")
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
