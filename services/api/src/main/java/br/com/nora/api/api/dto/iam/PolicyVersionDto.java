package br.com.nora.api.api.dto.iam;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One revision of a policy, as returned by {@code GET /iam/policies/{id}/versions} (US36).
 *
 * <p>{@code document} is serialized in the SAME shape the write endpoints accept — {@code action}
 * and {@code resource} singular, {@code effect} as {@code Allow}/{@code Deny} — through the same
 * {@code documentToJson} the current-document reads use. That is what makes a revision usable as
 * more than a picture: it can be pasted back into {@code PUT /iam/policies/{id}} to roll a policy
 * back, which is the only reason an operator opens a history in the first place.
 *
 * @param version 1-based; the highest one is the policy's current document
 * @param createdBy who wrote this revision, or null when that user has since been deleted
 */
public record PolicyVersionDto(
        int version, JsonNode document, UUID createdBy, OffsetDateTime createdAt) {}
