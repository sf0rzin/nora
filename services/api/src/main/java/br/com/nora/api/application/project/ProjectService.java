package br.com.nora.api.application.project;

import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.ports.AuditPort;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.ProjectRepository;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.project.Project;
import br.com.nora.api.domain.project.ProjectStatus;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servico de aplicacao para projects (project tracking).
 *
 * <p>Regras chave:
 *
 * <ul>
 *   <li>Toda escrita/leitura recebe tenantId explicito vindo do JWT do chamador (ADR 0002).
 *   <li>{@link #assignMeeting} verifica que <b>tanto</b> a meeting quanto o project pertencem ao
 *       tenant antes de vincular — bloqueio cross-tenant na camada de aplicacao (a FK composta de
 *       V019 e a defesa em profundidade no banco).
 * </ul>
 */
@Service
public class ProjectService {

    private final ProjectRepository projects;
    private final MeetingRepository meetings;
    private final AuditPort audit;

    public ProjectService(ProjectRepository projects, MeetingRepository meetings, AuditPort audit) {
        this.projects = projects;
        this.meetings = meetings;
        this.audit = audit;
    }

    @Transactional
    public Project create(UUID tenantId, UUID actorUserId, String name, String description) {
        Project saved = projects.save(Project.create(tenantId, name, description));
        Map<String, Object> payload = new HashMap<>();
        payload.put("name", saved.name());
        audit.record(tenantId, actorUserId, "project.created", "PROJECT", saved.id(), payload);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Project> list(UUID tenantId) {
        return projects.listByTenant(tenantId);
    }

    @Transactional(readOnly = true)
    public Project getById(UUID projectId, UUID tenantId) {
        return projects.findByIdAndTenant(projectId, tenantId)
                .orElseThrow(ProjectException.NotFound::new);
    }

    /**
     * Renomeia e/ou atualiza status do projeto. {@code newName}/{@code newDescription} nulos mantem
     * o valor atual do nome (description nula limpa). {@code newStatus} nulo mantem o status atual.
     */
    @Transactional
    public Project update(
            UUID projectId,
            UUID tenantId,
            UUID actorUserId,
            String newName,
            String newDescription,
            ProjectStatus newStatus) {
        Project current =
                projects.findByIdAndTenant(projectId, tenantId)
                        .orElseThrow(ProjectException.NotFound::new);
        Project updated = current;
        if (newName != null || newDescription != null) {
            String name = newName == null ? current.name() : newName;
            updated = updated.withDetails(name, newDescription);
        }
        if (newStatus != null) {
            updated = updated.withStatus(newStatus);
        }
        Project saved = projects.save(updated);
        audit.record(
                tenantId,
                actorUserId,
                "project.updated",
                "PROJECT",
                saved.id(),
                Map.of("name", saved.name(), "status", saved.status().name()));
        return saved;
    }

    @Transactional
    public void softDelete(UUID projectId, UUID tenantId, UUID actorUserId) {
        Project current =
                projects.findByIdAndTenant(projectId, tenantId)
                        .orElseThrow(ProjectException.NotFound::new);
        projects.softDelete(current.id(), tenantId);
        audit.record(tenantId, actorUserId, "project.deleted", "PROJECT", current.id(), Map.of());
    }

    /**
     * Vincula (ou desvincula, quando {@code projectId} e nulo) uma meeting a um project.
     *
     * <p>Verifica que a meeting pertence ao tenant; quando {@code projectId} nao e nulo, verifica
     * tambem que o project pertence ao tenant. Sem essa dupla checagem um payload forjado poderia
     * tentar linkar recursos de outro tenant (a FK composta de V019 e a defesa final no banco).
     */
    @Transactional
    public Meeting assignMeeting(UUID tenantId, UUID actorUserId, UUID meetingId, UUID projectId) {
        Meeting meeting =
                meetings.findByIdAndTenant(meetingId, tenantId)
                        .orElseThrow(MeetingException.NotFound::new);
        if (projectId != null) {
            // Garante que o project existe e pertence ao tenant antes de vincular.
            projects.findByIdAndTenant(projectId, tenantId)
                    .orElseThrow(ProjectException.NotFound::new);
        }
        Meeting updated = meeting.withProject(projectId);
        Meeting saved = meetings.save(updated);
        audit.record(
                tenantId,
                actorUserId,
                projectId == null ? "meeting.project.unassigned" : "meeting.project.assigned",
                "MEETING",
                meetingId,
                projectId == null ? Map.of() : Map.of("projectId", projectId.toString()));
        return saved;
    }
}
