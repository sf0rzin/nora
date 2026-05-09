package br.com.nora.api.application.ports;

import br.com.nora.api.domain.analysis.ActionItemStatus;
import br.com.nora.api.domain.analysis.Priority;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Acesso de leitura/escrita aos action items extraidos da analise (US22-US24). Esta porta retorna
 * uma visao "tarefa" achatada (com meetingId e meetingTitle) ja escopada por tenant.
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
