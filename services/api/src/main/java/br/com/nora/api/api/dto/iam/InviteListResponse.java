package br.com.nora.api.api.dto.iam;

import java.util.List;

/**
 * Invite listing response. Schema in {@code docs/api/examples/iam-invite-list-response.json}.
 *
 * <p><b>What the three numbers actually mean, because the shape suggests otherwise.</b> This is a
 * page envelope with exactly one page in it: the handler returns the newest {@code
 * InvitationService.LIST_LIMIT} invitations of the tenant and reports {@code page=1}, {@code size}
 * and {@code total} as the size of what it returned. There is no second page to fetch, and {@code
 * total} is NOT a count of everything the tenant has — it is the count of what came back.
 *
 * <p>The fields are kept rather than removed because the web client reads them, and made explicit
 * rather than left implied because a caller that treats {@code total} as a tenant-wide count while
 * the ceiling is in play would be quietly wrong. Filtering by status happens after the cap, on the
 * page, which is the other consequence worth stating.
 */
public record InviteListResponse(List<InviteResponse> items, int total, int page, int size) {}
