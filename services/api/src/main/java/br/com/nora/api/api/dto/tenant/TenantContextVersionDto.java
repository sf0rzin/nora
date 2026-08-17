package br.com.nora.api.api.dto.tenant;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One entry of the company context's history (US31), without the document.
 *
 * <p>{@code createdBy} and {@code createdByName} are both null when the author's user row was
 * removed — the record of the change outlives the record of who made it.
 */
public record TenantContextVersionDto(
        int version, UUID createdBy, String createdByName, OffsetDateTime createdAt) {}
