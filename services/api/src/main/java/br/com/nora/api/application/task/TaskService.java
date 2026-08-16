package br.com.nora.api.application.task;

import br.com.nora.api.application.ports.TaskRepository;
import br.com.nora.api.application.ports.TaskRepository.TaskRow;
import br.com.nora.api.domain.analysis.ActionItemStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Service for extracted tasks (US22-US24). Every operation is scoped by the principal's tenant_id.
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

    /**
     * Sets the task's due date, or clears it when {@code newDueDate} is null. Null is a legitimate
     * value here, not a missing argument: the caller has already decided that the request asked to
     * clear the date (see the controller's due-date semantics).
     *
     * <p>A date in the past is accepted on purpose — the user may be recording a deadline that has
     * already slipped. It does mean the Flows follow-up scheduler will not pick the task up, since
     * it only schedules dates after today.
     */
    @Transactional
    public TaskRow updateDueDate(UUID id, UUID tenantId, LocalDate newDueDate) {
        tasks.findByIdAndTenant(id, tenantId).orElseThrow(TaskException.NotFound::new);
        tasks.updateDueDate(id, tenantId, newDueDate);
        return tasks.findByIdAndTenant(id, tenantId).orElseThrow(TaskException.NotFound::new);
    }
}
