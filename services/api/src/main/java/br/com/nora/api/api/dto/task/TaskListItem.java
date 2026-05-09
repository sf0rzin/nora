package br.com.nora.api.api.dto.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Item da listagem de tarefas. */
@JsonInclude(Include.NON_NULL)
public record TaskListItem(
        UUID id,
        String title,
        String assignee,
        LocalDate dueDate,
        String priority,
        String status,
        UUID meetingId,
        String meetingTitle,
        OffsetDateTime updatedAt) {}
