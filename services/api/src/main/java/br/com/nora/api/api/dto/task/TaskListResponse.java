package br.com.nora.api.api.dto.task;

import java.util.List;

/** Resposta de GET /tasks. */
public record TaskListResponse(List<TaskListItem> items) {}
