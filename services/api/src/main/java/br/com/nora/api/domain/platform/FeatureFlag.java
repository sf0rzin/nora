package br.com.nora.api.domain.platform;

import java.time.OffsetDateTime;

/** Feature flag on/off por serviço (tabela feature_flags, ADR 0024). */
public record FeatureFlag(
        String key,
        boolean enabled,
        String description,
        String updatedBy,
        OffsetDateTime updatedAt) {}
