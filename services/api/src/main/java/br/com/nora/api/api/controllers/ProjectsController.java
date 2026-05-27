package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.project.AssignProjectRequest;
import br.com.nora.api.api.dto.project.CreateProjectRequest;
import br.com.nora.api.api.dto.project.ProjectListResponse;
import br.com.nora.api.api.dto.project.ProjectResponse;
import br.com.nora.api.api.dto.project.UpdateProjectRequest;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.meeting.MeetingService;
import br.com.nora.api.application.project.ProjectService;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.project.Project;
import br.com.nora.api.domain.project.ProjectStatus;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints de project tracking: agrupa reunioes de um tenant em projetos.
 *
 * <p>Reusa as actions IAM de meeting (ADR 0002 / IAM AWS-style): leituras exigem {@code
 * meeting:read} e escritas {@code meeting:update} sobre o recurso {@code
 * nora:tenant/{tid}:meeting/*}. Root bypassa. Tudo escopado por tenant_id do JWT.
 *
 * <p>Sem {@code @RequestMapping} de classe: hospeda tanto {@code /projects} quanto o endpoint de
 * atribuicao {@code /meetings/{id}/project} (que conceitualmente pertence a este dominio).
 */
@RestController
public class ProjectsController {

    private final ProjectService projects;
    private final MeetingService meetings;
    private final AuthorizationService authz;

    public ProjectsController(
            ProjectService projects, MeetingService meetings, AuthorizationService authz) {
        this.projects = projects;
        this.meetings = meetings;
        this.authz = authz;
    }

    private static String meetingResource(UUID tenantId, UUID meetingId) {
        return "nora:tenant/" + tenantId + ":meeting/" + (meetingId == null ? "*" : meetingId);
    }

    @GetMapping("/projects")
    public ProjectListResponse list() {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(
                p.userId(), p.tenantId(), "meeting:read", meetingResource(p.tenantId(), null));
        List<ProjectResponse> items =
                projects.list(p.tenantId()).stream().map(ProjectResponse::from).toList();
        return new ProjectListResponse(items);
    }

    @PostMapping("/projects")
    public ResponseEntity<ProjectResponse> create(
            @Valid @RequestBody CreateProjectRequest request) {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(
                p.userId(), p.tenantId(), "meeting:update", meetingResource(p.tenantId(), null));
        Project saved =
                projects.create(p.tenantId(), p.userId(), request.name(), request.description());
        return ResponseEntity.status(HttpStatus.CREATED).body(ProjectResponse.from(saved));
    }

    @PatchMapping("/projects/{id}")
    public ProjectResponse update(
            @PathVariable("id") UUID id, @Valid @RequestBody UpdateProjectRequest request) {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(
                p.userId(), p.tenantId(), "meeting:update", meetingResource(p.tenantId(), null));
        ProjectStatus status =
                request.status() == null ? null : ProjectStatus.fromString(request.status());
        Project updated =
                projects.update(
                        id,
                        p.tenantId(),
                        p.userId(),
                        request.name(),
                        request.description(),
                        status);
        return ProjectResponse.from(updated);
    }

    @DeleteMapping("/projects/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") UUID id) {
        AuthenticatedPrincipal p = CurrentUser.require();
        authz.require(
                p.userId(), p.tenantId(), "meeting:update", meetingResource(p.tenantId(), null));
        projects.softDelete(id, p.tenantId(), p.userId());
        return ResponseEntity.noContent().build();
    }

    /**
     * Vincula (ou desvincula, quando {@code projectId} nulo) uma meeting a um project. Gate de
     * escrita com {@code meeting:update} usando os attributes da propria meeting (conditions IAM
     * por item). O service verifica que meeting e project pertencem ao tenant antes de linkar.
     */
    @PutMapping("/meetings/{id}/project")
    public ResponseEntity<ProjectResponse> assign(
            @PathVariable("id") UUID id, @RequestBody AssignProjectRequest request) {
        AuthenticatedPrincipal p = CurrentUser.require();
        // Resolve a meeting primeiro (404 se de outro tenant) e usa attributes no context da authz.
        Meeting meeting = meetings.getById(id, p.tenantId());
        authz.require(
                p.userId(),
                p.tenantId(),
                "meeting:update",
                meetingResource(p.tenantId(), meeting.id()),
                meeting.attributes());
        Meeting updated =
                projects.assignMeeting(
                        p.tenantId(), p.userId(), id, request == null ? null : request.projectId());
        if (updated.projectId() == null) {
            return ResponseEntity.noContent().build();
        }
        Project project = projects.getById(updated.projectId(), p.tenantId());
        return ResponseEntity.ok(ProjectResponse.from(project));
    }
}
