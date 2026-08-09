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

    void updateStatus(UUID id, UUID tenantId, ActionItemStatus newStatus);

    void updateTitle(UUID id, UUID tenantId, String newTitle);
}
