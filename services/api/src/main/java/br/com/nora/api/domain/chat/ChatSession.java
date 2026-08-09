package br.com.nora.api.domain.chat;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Chat session of the NORA assistant. Belongs to a user (ownerUserId) inside a tenant (tenantId,
 * ADR 0002). The title can be null until the first user message, from which it is derived. The
 * messages live in {@link ChatMessage} (referenced by sessionId) to avoid loading the whole
 * conversation in listings. Immutable.
 */
public record ChatSession(
        UUID id,
        UUID tenantId,
        UUID ownerUserId,
        String title,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    /** Maximum length of the title derived from the first user message. */
    public static final int DERIVED_TITLE_MAX = 48;

    /**
     * Derives a short title from the content of the first user message: collapses whitespace, cuts
     * at {@value #DERIVED_TITLE_MAX} characters (with an ellipsis when it truncates). Returns
     * {@code null} for empty content — the title stays undefined in that case.
     */
    public static String deriveTitle(String firstUserMessage) {
        if (firstUserMessage == null) {
            return null;
        }
        String normalized = firstUserMessage.strip().replaceAll("\\s+", " ");
        if (normalized.isEmpty()) {
            return null;
        }
        if (normalized.length() <= DERIVED_TITLE_MAX) {
            return normalized;
        }
        return normalized.substring(0, DERIVED_TITLE_MAX - 1).stripTrailing() + "…";
    }
}
