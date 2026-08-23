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

    /**
     * Hard ceiling on {@code size}, mirroring {@code GET /meetings}. A caller asking for more gets
     * this many rather than an error: the parameter is a hint about a page, not an assertion the
     * request depends on.
     */
    public static final int MAX_PAGE_SIZE = 100;

    @Transactional(readOnly = true)
    public List<TaskRow> list(UUID tenantId, ActionItemStatus statusFilter) {
        return tasks.listByTenant(tenantId, statusFilter);
    }

    /**
     * One page, cut in SQL. The caller is responsible for having established that the IAM decision
     * is the same for every task of the tenant — otherwise the page is a slice of rows the caller
     * may not be allowed to see, and only {@link #list} plus a per-item filter can answer.
     */
    @Transactional(readOnly = true)
    public TaskRepository.PagedTasks list(
            UUID tenantId, ActionItemStatus statusFilter, int page, int size) {
        int safePage = Math.max(0, page);
        int safeSize = Math.min(MAX_PAGE_SIZE, Math.max(1, size));
        return tasks.listByTenant(tenantId, statusFilter, safePage, safeSize);
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
