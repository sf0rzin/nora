package br.com.nora.api.application.task;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.com.nora.api.application.ports.TaskRepository;
import br.com.nora.api.domain.analysis.ActionItemStatus;
import br.com.nora.api.domain.analysis.Priority;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TaskServiceTest {

    private TaskService service;
    private InMemoryTaskRepo repo;
    private final UUID tenant = UUID.randomUUID();
    private final UUID otherTenant = UUID.randomUUID();
    private final UUID meetingId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        repo = new InMemoryTaskRepo();
        service = new TaskService(repo);
    }

    @Test
    void list_returnsOnlyTenantScopedRows() {
        UUID t1 = repo.seed(tenant, meetingId, "Send proposal", ActionItemStatus.OPEN);
        UUID t2 = repo.seed(tenant, meetingId, "Schedule follow-up", ActionItemStatus.DONE);
        UUID t3 =
                repo.seed(
                        otherTenant, UUID.randomUUID(), "Other tenant task", ActionItemStatus.OPEN);

        var rows = service.list(tenant, null);
        assertThat(rows).extracting(TaskRepository.TaskRow::id).containsExactlyInAnyOrder(t1, t2);

        var openOnly = service.list(tenant, ActionItemStatus.OPEN);
        assertThat(openOnly).extracting(TaskRepository.TaskRow::id).containsExactly(t1);

        assertThat(service.list(otherTenant, null))
                .extracting(TaskRepository.TaskRow::id)
                .containsExactly(t3);
    }

    @Test
    void updateStatus_changesStatus_andRespectsTenantScope() {
        UUID id = repo.seed(tenant, meetingId, "Task X", ActionItemStatus.OPEN);

        var updated = service.updateStatus(id, tenant, ActionItemStatus.DONE);
        assertThat(updated.status()).isEqualTo(ActionItemStatus.DONE);

        assertThatThrownBy(() -> service.updateStatus(id, otherTenant, ActionItemStatus.OPEN))
                .isInstanceOf(TaskException.NotFound.class);
    }

    @Test
    void updateTitle_rejectsBlank() {
        UUID id = repo.seed(tenant, meetingId, "Task Y", ActionItemStatus.OPEN);
        assertThatThrownBy(() -> service.updateTitle(id, tenant, "   "))
                .isInstanceOf(TaskException.InvalidTitle.class);
    }

    @Test
    void updateTitle_persistsTrimmedValue() {
        UUID id = repo.seed(tenant, meetingId, "Old", ActionItemStatus.OPEN);
        var row = service.updateTitle(id, tenant, "  New title  ");
        assertThat(row.title()).isEqualTo("New title");
    }

    /* ---------- in-memory repo ---------- */

    static final class InMemoryTaskRepo implements TaskRepository {
        private final Map<UUID, TaskRow> store = new HashMap<>();
        private final Map<UUID, UUID> tenantOf = new HashMap<>();

        UUID seed(UUID tenantId, UUID meetingId, String title, ActionItemStatus status) {
            UUID id = UUID.randomUUID();
            store.put(
                    id,
                    new TaskRow(
                            id,
                            title,
                            null,
                            null,
                            Priority.MEDIUM,
                            status,
                            meetingId,
                            "Meeting " + meetingId.toString().substring(0, 4),
                            OffsetDateTime.now()));
            tenantOf.put(id, tenantId);
            return id;
        }

        @Override
        public List<TaskRow> listByTenant(UUID tenantId, ActionItemStatus statusFilter) {
            List<TaskRow> rows = new ArrayList<>();
            for (TaskRow r : store.values()) {
                if (!belongsToTenant(r, tenantId)) {
                    continue;
                }
                if (statusFilter != null && r.status() != statusFilter) {
                    continue;
                }
                rows.add(r);
            }
            return rows;
        }

        @Override
        public Optional<TaskRow> findByIdAndTenant(UUID id, UUID tenantId) {
            TaskRow r = store.get(id);
            return r != null && belongsToTenant(r, tenantId) ? Optional.of(r) : Optional.empty();
        }

        @Override
        public void updateStatus(UUID id, UUID tenantId, ActionItemStatus newStatus) {
            TaskRow r = store.get(id);
            if (r != null && belongsToTenant(r, tenantId)) {
                store.put(
                        id,
                        new TaskRow(
                                r.id(),
                                r.title(),
                                r.assignee(),
                                r.dueDate(),
                                r.priority(),
                                newStatus,
                                r.meetingId(),
                                r.meetingTitle(),
                                OffsetDateTime.now()));
            }
        }

        @Override
        public void updateTitle(UUID id, UUID tenantId, String newTitle) {
            TaskRow r = store.get(id);
            if (r != null && belongsToTenant(r, tenantId)) {
                store.put(
                        id,
                        new TaskRow(
                                r.id(),
                                newTitle,
                                r.assignee(),
                                r.dueDate(),
                                r.priority(),
                                r.status(),
                                r.meetingId(),
                                r.meetingTitle(),
                                OffsetDateTime.now()));
            }
        }

        private boolean belongsToTenant(TaskRow r, UUID tenantId) {
            return tenantId.equals(tenantOf.get(r.id()));
        }
    }
}
