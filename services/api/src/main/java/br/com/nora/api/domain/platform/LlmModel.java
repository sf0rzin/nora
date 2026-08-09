package br.com.nora.api.domain.platform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Control plane catalog model (ADR 0024). Pricing in USD per 1M tokens. Lives in the platform
 * database (ADR 0022) — global, no tenant. {@code priceCachedInputPerMTok} may be null (not every
 * provider has a cache-hit price).
 */
public record LlmModel(
        UUID id,
        String provider,
        String modelId,
        String displayName,
        String baseUrl,
        Modality modality,
        boolean supportsStrictJsonSchema,
        BigDecimal priceInputPerMTok,
        BigDecimal priceOutputPerMTok,
        BigDecimal priceCachedInputPerMTok,
        boolean enabled,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {}
