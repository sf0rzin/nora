package br.com.nora.api.application.project;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.meeting.MeetingException;
import br.com.nora.api.application.ports.AuditPort;
import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.domain.meeting.Meeting;
import br.com.nora.api.domain.meeting.ProcessingStatus;
import br.com.nora.api.domain.meeting.TranscriptFormat;
import br.com.nora.api.domain.project.Project;
import br.com.nora.api.domain.project.ProjectStatus;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ProjectServiceTest {

    private ProjectService service;
    private InMemoryProjectRepo projectRepo;
    private InMemoryMeetingRepo meetingRepo;
    private final UUID tenant = UUID.randomUUID();
    private final UUID otherTenant = UUID.randomUUID();
    private final UUID actor = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        projectRepo = new InMemoryProjectRepo();
        meetingRepo = new InMemoryMeetingRepo();
        AuditPort noOpAudit = (a, b, c, d, e, f) -> {};
        service = new ProjectService(projectRepo, meetingRepo, noOpAudit);
    }

    @Test
    void createAndListAreTenantScoped() {
        Project a = service.create(tenant, actor, "Alpha", "desc");
        service.create(otherTenant, actor, "Beta", null);

        assertThat(a.status()).isEqualTo(ProjectStatus.ACTIVE);
        List<Project> mine = service.list(tenant);
        assertThat(mine).hasSize(1);
        assertThat(mine.get(0).name()).isEqualTo("Alpha");
    }

    @Test
    void updateRenamesAndChangesStatus() {
        Project p = service.create(tenant, actor, "Alpha", null);
        Project updated =
                service.update(
                        p.id(), tenant, actor, "Renamed", "now described", ProjectStatus.ARCHIVED);
        assertThat(updated.name()).isEqualTo("Renamed");
        assertThat(updated.description()).isEqualTo("now described");
        assertThat(updated.status()).isEqualTo(ProjectStatus.ARCHIVED);
    }

    @Test
    void updateOnForeignTenantThrowsNotFound() {
        Project p = service.create(tenant, actor, "Alpha", null);
        assertThatThrownBy(() -> service.update(p.id(), otherTenant, actor, "Hijack", null, null))
                .isInstanceOf(ProjectException.NotFound.class);
    }

    @Test
    void softDeleteRemovesFromList() {
        Project p = service.create(tenant, actor, "Alpha", null);
        service.softDelete(p.id(), tenant, actor);
        assertThat(service.list(tenant)).isEmpty();
    }

    @Test
    void assignMeetingLinksWhenBothBelongToTenant() {
        Project p = service.create(tenant, actor, "Alpha", null);
        Meeting m = persistMeeting(tenant);

        Meeting assigned = service.assignMeeting(tenant, actor, m.id(), p.id());
        assertThat(assigned.projectId()).isEqualTo(p.id());
        assertThat(meetingRepo.findByIdAndTenant(m.id(), tenant).orElseThrow().projectId())
                .isEqualTo(p.id());
    }

    @Test
    void assignMeetingWithNullProjectUnassigns() {
        Project p = service.create(tenant, actor, "Alpha", null);
        Meeting m = persistMeeting(tenant);
        service.assignMeeting(tenant, actor, m.id(), p.id());

        Meeting unassigned = service.assignMeeting(tenant, actor, m.id(), null);
        assertThat(unassigned.projectId()).isNull();
    }

    @Test
    void assignRejectsProjectFromAnotherTenant() {
        Project foreign = service.create(otherTenant, actor, "Foreign", null);
        Meeting m = persistMeeting(tenant);
        assertThatThrownBy(() -> service.assignMeeting(tenant, actor, m.id(), foreign.id()))
                .isInstanceOf(ProjectException.NotFound.class);
        // Meeting stays unlinked.
        assertThat(meetingRepo.findByIdAndTenant(m.id(), tenant).orElseThrow().projectId())
                .isNull();
    }

    @Test
    void assignRejectsMeetingFromAnotherTenant() {
        Project p = service.create(tenant, actor, "Alpha", null);
        Meeting foreignMeeting = persistMeeting(otherTenant);
        assertThatThrownBy(() -> service.assignMeeting(tenant, actor, foreignMeeting.id(), p.id()))
                .isInstanceOf(MeetingException.NotFound.class);
    }

    private Meeting persistMeeting(UUID tenantId) {
        Meeting m =
                Meeting.newPending(
                        tenantId,
                        UUID.randomUUID(),
                        "Reuniao",
                        null,
                        null,
                        "pt-BR",
                        TranscriptFormat.TXT,
                        List.of(),
                        List.of());
        return meetingRepo.save(m);
    }

    /* ---------- in-memory fakes ---------- */

    static final class InMemoryProjectRepo
            implements br.com.nora.api.application.ports.ProjectRepository {
        private final Map<UUID, Project> store = new HashMap<>();

        @Override
        public Project save(Project project) {
            store.put(project.id(), project);
            return project;
        }

        @Override
        public Optional<Project> findByIdAndTenant(UUID id, UUID tenantId) {
            Project p = store.get(id);
            return (p != null && p.tenantId().equals(tenantId) && !p.isDeleted())
                    ? Optional.of(p)
                    : Optional.empty();
        }

        @Override
        public List<Project> listByTenant(UUID tenantId) {
            List<Project> all = new ArrayList<>();
            for (Project p : store.values()) {
                if (p.tenantId().equals(tenantId) && !p.isDeleted()) {
                    all.add(p);
                }
            }
            all.sort((a, b) -> b.createdAt().compareTo(a.createdAt()));
            return all;
        }

        @Override
        public void softDelete(UUID id, UUID tenantId) {
            Project p = store.get(id);
            if (p != null && p.tenantId().equals(tenantId)) {
                store.put(
                        id,
                        new Project(
                                p.id(),
                                p.tenantId(),
                                p.name(),
                                p.description(),
                                p.status(),
                                p.createdAt(),
                                OffsetDateTime.now(),
                                OffsetDateTime.now()));
            }
        }
    }

    static final class InMemoryMeetingRepo implements MeetingRepository {
        private final Map<UUID, Meeting> store = new HashMap<>();

        @Override
        public Meeting save(Meeting meeting) {
            store.put(meeting.id(), meeting);
            return meeting;
        }

        @Override
        public Optional<Meeting> findByIdAndTenant(UUID id, UUID tenantId) {
            Meeting m = store.get(id);
            return (m != null && m.tenantId().equals(tenantId)) ? Optional.of(m) : Optional.empty();
        }

        @Override
        public PagedMeetings listByTenant(UUID tenantId, MeetingFilter filter, int page, int size) {
            return new PagedMeetings(List.of(), 0, page, size);
        }

        @Override
        public long countByTenant(UUID tenantId) {
            return 0;
        }

        @Override
        public long countByTenantAndStatus(UUID tenantId, ProcessingStatus status) {
            return 0;
        }

        @Override
        public long countByTenantCreatedSince(UUID tenantId, OffsetDateTime from) {
            return 0;
        }
    }
}
