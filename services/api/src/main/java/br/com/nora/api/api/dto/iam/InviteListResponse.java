package br.com.nora.api.api.dto.iam;

import java.util.List;

/**
 * Invite listing response. Schema in {@code docs/api/examples/iam-invite-list-response.json}. In
 * the MVP, pagination is implemented client-side: the backend always returns {@code page=1} and
 * {@code total} reflects the list size — server-side pagination is Should/Could.
 */
public record InviteListResponse(List<InviteResponse> items, int total, int page, int size) {}
