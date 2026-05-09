package br.com.nora.api.application.task;

import br.com.nora.api.application.ports.TaskRepository;
import br.com.nora.api.application.ports.TaskRepository.TaskRow;
import br.com.nora.api.domain.analysis.ActionItemStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servico de tarefas extraidas (US22-US24). Toda operacao e escopada pelo tenant_id do principal.
 */
@Service
public class TaskService {

    private final TaskRepository tasks;

    public TaskService(TaskRepository tasks) {
        this.tasks = tasks;
    }

    @Transactional(readOnly = true)
    public List<TaskRow> list(UUID tenantId, ActionItemStatus statusFilter) {
        return tasks.listByTenant(tenantId, statusFilter);
    }

    @Transactional
    public TaskRow updateStatus(UUID id, UUID tenantId, ActionItemStatus newStatus) {
        tasks.findByIdAndTenant(id, tenantId).orElseThrow(TaskException.NotFound::new);
        tasks.updateStatus(id, tenantId, newStatus);
        return tasks.findByIdAndTenant(id, tenantId).orElseThrow(TaskException.NotFound::new);
    }

    @Transactional
    public TaskRow updateTitle(UUID id, UUID tenantId, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new TaskException.InvalidTitle();
        }
        tasks.findByIdAndTenant(id, tenantId).orElseThrow(TaskException.NotFound::new);
        tasks.updateTitle(id, tenantId, newTitle.trim());
        return tasks.findByIdAndTenant(id, tenantId).orElseThrow(TaskException.NotFound::new);
    }
}
