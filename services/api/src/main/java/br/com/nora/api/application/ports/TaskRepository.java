package br.com.nora.api.application.ports;

import br.com.nora.api.domain.analysis.ActionItemStatus;
import br.com.nora.api.domain.analysis.Priority;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read/write access to the action items extracted from the analysis (US22-US24). This port returns
 * a flattened "task" view (with meetingId and meetingTitle) already scoped by tenant.
 */
public interface TaskRepository {

    record TaskRow(
            UUID id,
            String title,
            String assignee,
            LocalDate dueDate,
            Priority priority,
            ActionItemStatus status,
            UUID meetingId,
            String meetingTitle,
            OffsetDateTime updatedAt) {}

    List<TaskRow> listByTenant(UUID tenantId, ActionItemStatus statusFilter);

    Optional<TaskRow> findByIdAndTenant(UUID id, UUID tenantId);

    /**
     * Moves the task to {@code newStatus} and keeps {@code completed_at} (V030) in step: it is
     * stamped when the task enters DONE and cleared when it leaves. That column, and not {@code
     * updated_at}, is what the trends panel counts completions on — {@code updated_at} moves on a
     * title or due-date edit too, so a task finished in March and renamed in June would otherwise
     * be charted as a June completion.
     */
    void updateStatus(UUID id, UUID tenantId, ActionItemStatus newStatus);

    void updateTitle(UUID id, UUID tenantId, String newTitle);

    /**
     * Sets the task's due date, or clears it when {@code newDueDate} is null. The column is a
     * {@code date}, not a timestamp — see {@link TaskRow#dueDate()}.
     */
    void updateDueDate(UUID id, UUID tenantId, LocalDate newDueDate);
}
