package br.com.nora.api.api.dto.task;

import java.util.List;

/**
 * Response of GET /tasks.
 *
 * <p>The pagination fields are the real ones, in the same shape {@code MeetingListResponse} uses:
 * {@code totalItems} is what the tenant has after the IAM filter, not the size of {@code items}.
 * The listing was unpaginated until 2026-08-23 and the fields are additive, so a client that only
 * reads {@code items} keeps working — it simply now receives at most one page of them.
 */
public record TaskListResponse(
        List<TaskListItem> items, int page, int size, long totalItems, int totalPages) {}
