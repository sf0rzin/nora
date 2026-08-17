package br.com.nora.api.domain.tenant;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry of the company context's immutable history (US31).
 *
 * <p>Metadata only: "which number, by whom, when". The document itself is large and a listing never
 * needs it, so it travels in {@link Detail} and only for the single version being opened.
 *
 * <p>{@code createdBy} is null when the author's user row was removed (the column is {@code ON
 * DELETE SET NULL} — losing the author must not lose the record that a change happened), and {@code
 * createdByName} is null in the same case. Version 1 of a context that predates migration V028
 * carries the parent row's {@code updated_at}/{@code updated_by}, which describe its LAST edit
 * rather than its first — the true values were never recorded. See the V028 header.
 */
public record TenantContextVersion(
        int version, UUID createdBy, String createdByName, OffsetDateTime createdAt) {

    /**
     * A history entry together with the document it froze.
     *
     * <p>The document is rehydrated into the same {@link TenantContext} aggregate the live endpoint
     * returns, so both surfaces share one mapping. Its {@code updatedBy}/{@code updatedAt} are the
     * version's own author and timestamp — reading a version tells you what the document looked
     * like when that version was written, not what it looks like now.
     */
    public record Detail(TenantContextVersion version, TenantContext document) {}
}
