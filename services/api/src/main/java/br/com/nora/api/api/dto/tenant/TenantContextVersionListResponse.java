package br.com.nora.api.api.dto.tenant;

import java.util.List;

/**
 * Company-context history, newest version first (US31).
 *
 * <p>{@code currentVersion} is the number in force — the one the live {@code GET /tenant/context}
 * returns a document for. Because the list is ordered newest-first it is today always {@code
 * items[0].version}; the field exists so a client is not forced to depend on that ordering, and so
 * that the "never saved anything" case has an answer of its own: {@code 0}, alongside an empty
 * {@code items}.
 */
public record TenantContextVersionListResponse(
        List<TenantContextVersionDto> items, int total, int currentVersion) {}
