package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.task.TaskListItem;
import br.com.nora.api.api.dto.task.TaskListResponse;
import br.com.nora.api.api.dto.task.TaskUpdateRequest;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.api.security.RequiresPermission;
import br.com.nora.api.api.security.RequiresPermission.ResourceType;
import br.com.nora.api.api.security.ResourceArns;
import br.com.nora.api.application.iam.AuthorizationService;
import br.com.nora.api.application.ports.TaskRepository.TaskRow;
import br.com.nora.api.application.task.TaskException;
import br.com.nora.api.application.task.TaskService;
import br.com.nora.api.domain.analysis.ActionItemStatus;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints for managing extracted tasks (US22-US24). Everything scoped by the tenant from the JWT.
 */
@RestController
@RequestMapping("/tasks")
public class TasksController {

    private final TaskService tasks;
    private final AuthorizationService authz;

    public TasksController(TaskService tasks, AuthorizationService authz) {
        this.tasks = tasks;
        this.authz = authz;
    }

    /**
     * Listing: the annotation is only the pre-gate ({@code requireAnyAllow} reasons about sets) and
     * the visible set is decided per item below.
     *
     * <p>The strict check used to run here against the literal ARN {@code ...:task/*}. The {@code
     * *} goes into the evaluator as a value, matched as plain text on the resource side, so a Deny
     * written against one specific task id never fired — and the handler then returned every action
     * item of the tenant with no filtering at all. Same shape {@code GET /meetings} already uses.
     */
    @GetMapping
    @RequiresPermission(action = "task:read", resource = ResourceType.TASK, anyAllow = true)
    public TaskListResponse list(@RequestParam(name = "status", required = false) String status) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        ActionItemStatus parsed = parseStatus(status);
        List<TaskRow> rows = tasks.list(principal.tenantId(), parsed);
        List<TaskRow> visible =
                authz.filterAllowed(
                        principal.userId(),
                        principal.tenantId(),
                        "task:read",
                        rows,
                        r -> ResourceArns.task(principal.tenantId(), r.id()),
                        r -> Map.of());
        List<TaskListItem> items = visible.stream().map(TasksController::toDto).toList();
        return new TaskListResponse(items);
    }

    /**
     * Partial update: at least one of {@code status}, {@code title} or {@code dueDate} must be
     * present, and the request is rejected when none is.
     *
     * <p>Due-date semantics, spelled out because a nullable column cannot express them on its own:
     * an ABSENT {@code dueDate} leaves the stored value alone, and an EMPTY string clears it. Both
     * are needed — the date is written by the extraction rather than by the user, so being able to
     * correct a wrong one but never to remove it would only be half a fix.
     *
     * <p>Everything is validated before anything is written. Each field is its own statement, so
     * parsing a bad value halfway through would otherwise leave the task partially updated. The
     * returned row comes from the last write, which re-reads the task and therefore carries every
     * field touched by this call.
     */
    @PatchMapping("/{id}")
    @RequiresPermission(action = "task:write", resource = ResourceType.TASK, idParam = "id")
    public TaskListItem update(@PathVariable("id") UUID id, @RequestBody TaskUpdateRequest body) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        boolean hasStatus = body.status() != null && !body.status().isBlank();
        boolean hasTitle = body.title() != null && !body.title().isBlank();
        // Presence, not blankness: "" is the documented way to clear the date.
        boolean hasDueDate = body.dueDate() != null;
        if (!hasStatus && !hasTitle && !hasDueDate) {
            throw new IllegalArgumentException(
                    "at least one of 'status', 'title' or 'dueDate' is required");
        }
        ActionItemStatus newStatus = null;
        if (hasStatus) {
            newStatus = parseStatus(body.status());
            if (newStatus == null) {
                throw new IllegalArgumentException("invalid status: " + body.status());
            }
        }
        LocalDate newDueDate = hasDueDate ? parseDueDate(body.dueDate()) : null;

        TaskRow row = null;
        if (hasStatus) {
            row = tasks.updateStatus(id, principal.tenantId(), newStatus);
        }
        if (hasTitle) {
            row = tasks.updateTitle(id, principal.tenantId(), body.title());
        }
        if (hasDueDate) {
            row = tasks.updateDueDate(id, principal.tenantId(), newDueDate);
        }
        return toDto(row);
    }

    /** Empty clears the date (null); anything else must parse as an ISO {@code yyyy-MM-dd}. */
    private LocalDate parseDueDate(String raw) {
        if (raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.trim());
        } catch (DateTimeParseException ex) {
            throw new TaskException.InvalidDueDate();
        }
    }

    private ActionItemStatus parseStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return ActionItemStatus.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("invalid status: " + raw);
        }
    }

    private static TaskListItem toDto(TaskRow r) {
        return new TaskListItem(
                r.id(),
                r.title(),
                r.assignee(),
                r.dueDate(),
                r.priority().name(),
                r.status().name(),
                r.meetingId(),
                r.meetingTitle(),
                r.updatedAt());
    }
}
