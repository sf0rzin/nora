package br.com.nora.api.api.dto.tenant;

/**
 * One version of the company context together with the document it froze (US31).
 *
 * <p>{@code document} is the same shape the live {@code GET /tenant/context} returns, so a client
 * can render a past version with the code it already has. Its {@code updatedAt} is the version's
 * own timestamp, not the current row's.
 */
public record TenantContextVersionDetailResponse(
        TenantContextVersionDto version, TenantContextResponse document) {}
