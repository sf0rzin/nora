package br.com.nora.api.api.dto.task;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonInclude.Include;

/**
 * Body of PATCH /tasks/{id}. At least one of the fields must be filled in.
 *
 * <p>{@code dueDate} is an ISO date ({@code yyyy-MM-dd}). Absent leaves the stored value alone; an
 * empty string clears it. Both cases have to be expressible: the date is written by the extraction,
 * not by the user, so being able to correct a wrong one but never to remove it is only half a fix.
 *
 * <p>It is typed as {@code String} rather than {@code LocalDate} so "absent" and "clear" stay
 * distinguishable, and so a malformed date is rejected by the controller with a task error code
 * instead of by the deserializer with a bare 400.
 */
@JsonInclude(Include.NON_NULL)
public record TaskUpdateRequest(String status, String title, String dueDate) {}
