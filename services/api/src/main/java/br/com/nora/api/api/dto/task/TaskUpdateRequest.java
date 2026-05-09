package br.com.nora.api.api.dto.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Body de PATCH /tasks/{id}. Pelo menos um dos campos deve vir preenchido. */
@JsonInclude(Include.NON_NULL)
public record TaskUpdateRequest(String status, String title) {}
