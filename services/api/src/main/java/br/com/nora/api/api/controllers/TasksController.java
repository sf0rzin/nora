package br.com.nora.api.api.controllers;

import br.com.nora.api.api.dto.task.TaskListItem;
import br.com.nora.api.api.dto.task.TaskListResponse;
import br.com.nora.api.api.dto.task.TaskUpdateRequest;
import br.com.nora.api.api.security.CurrentUser;
import br.com.nora.api.application.ports.TaskRepository.TaskRow;
import br.com.nora.api.application.task.TaskService;
import br.com.nora.api.domain.analysis.ActionItemStatus;
import br.com.nora.api.infrastructure.security.JjwtJwtIssuer.AuthenticatedPrincipal;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Endpoints da gestao de tarefas extraidas (US22-US24). Tudo escopado pelo tenant do JWT. */
@RestController
@RequestMapping("/tasks")
public class TasksController {

    private final TaskService tasks;

    public TasksController(TaskService tasks) {
        this.tasks = tasks;
    }

    @GetMapping
    public TaskListResponse list(@RequestParam(name = "status", required = false) String status) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        ActionItemStatus parsed = parseStatus(status);
        List<TaskRow> rows = tasks.list(principal.tenantId(), parsed);
        List<TaskListItem> items = rows.stream().map(TasksController::toDto).toList();
        return new TaskListResponse(items);
    }

    @PatchMapping("/{id}")
    public TaskListItem update(@PathVariable("id") UUID id, @RequestBody TaskUpdateRequest body) {
        AuthenticatedPrincipal principal = CurrentUser.require();
        if ((body.status() == null || body.status().isBlank())
                && (body.title() == null || body.title().isBlank())) {
            throw new IllegalArgumentException("at least one of 'status' or 'title' is required");
        }
        TaskRow row = null;
        if (body.status() != null && !body.status().isBlank()) {
            ActionItemStatus newStatus = parseStatus(body.status());
            if (newStatus == null) {
                throw new IllegalArgumentException("invalid status: " + body.status());
            }
            row = tasks.updateStatus(id, principal.tenantId(), newStatus);
        }
        if (body.title() != null && !body.title().isBlank()) {
            row = tasks.updateTitle(id, principal.tenantId(), body.title());
        }
        return toDto(row);
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
