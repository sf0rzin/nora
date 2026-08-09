package br.com.nora.api.domain.platform;

import java.time.OffsetDateTime;

/** On/off feature flag per service (feature_flags table, ADR 0024). */
public record FeatureFlag(
        String key,
        boolean enabled,
        String description,
        String updatedBy,
        OffsetDateTime updatedAt) {}
