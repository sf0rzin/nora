package br.com.nora.api.domain.customer;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Conta de cliente (lead/account) de um tenant (ADR 0015). Imutavel.
 *
 * <p>Dedup por {@code (tenantId, LOWER(name))} via get-or-create no repositorio. {@code
 * ownerUserId} e {@code stage} sao opcionais (CRM-lite).
 */
public record CustomerAccount(
        UUID id,
        UUID tenantId,
        String name,
        UUID ownerUserId,
        String stage,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    private static final int NAME_MIN = 1;
    private static final int NAME_MAX = 240;
    private static final int STAGE_MAX = 60;

    public CustomerAccount {
        if (id == null) {
            throw new IllegalArgumentException("id is required");
        }
        if (tenantId == null) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        String trimmedName = name.trim();
        if (trimmedName.length() < NAME_MIN || trimmedName.length() > NAME_MAX) {
            throw new IllegalArgumentException(
                    "name length must be between " + NAME_MIN + " and " + NAME_MAX);
        }
        String trimmedStage = stage == null ? null : stage.trim();
        if (trimmedStage != null && trimmedStage.isEmpty()) {
            trimmedStage = null;
        }
        if (trimmedStage != null && trimmedStage.length() > STAGE_MAX) {
            throw new IllegalArgumentException("stage must be at most " + STAGE_MAX + " chars");
        }
        OffsetDateTime resolvedCreatedAt = createdAt == null ? OffsetDateTime.now() : createdAt;
        name = trimmedName;
        stage = trimmedStage;
        createdAt = resolvedCreatedAt;
        updatedAt = updatedAt == null ? resolvedCreatedAt : updatedAt;
    }

    /** Factory para criar uma conta nova (id gerado, timestamps = now). */
    public static CustomerAccount create(
            UUID tenantId, String name, UUID ownerUserId, String stage) {
        OffsetDateTime now = OffsetDateTime.now();
        return new CustomerAccount(UUID.randomUUID(), tenantId, name, ownerUserId, stage, now, now);
    }
}
