package br.com.nora.api.api.dto.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/** Body of PATCH /tasks/{id}. At least one of the fields must be filled in. */
@JsonInclude(Include.NON_NULL)
public record TaskUpdateRequest(String status, String title) {}
