package br.com.nora.api.domain.platform;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Modelo do catálogo do control plane (ADR 0024). Pricing em USD por 1M de tokens. Vive no banco de
 * plataforma (ADR 0022) — global, sem tenant. {@code priceCachedInputPerMTok} pode ser null (nem
 * todo provider tem preço de cache-hit).
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
