package br.com.nora.api.api.dto.task;

import java.util.List;

/** Response of GET /tasks. */
public record TaskListResponse(List<TaskListItem> items) {}
