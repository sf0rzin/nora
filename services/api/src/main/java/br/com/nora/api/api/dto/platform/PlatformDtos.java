package br.com.nora.api.api.dto.platform;

import br.com.nora.api.domain.platform.LlmModel;
import br.com.nora.api.domain.platform.ServiceBinding;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** HTTP boundary DTOs of the control plane (platform-control-plane.md contract). */
public final class PlatformDtos {

    private PlatformDtos() {}

    /**
     * Catalog response (GET /admin/platform/models). Mirrors the contract (model/modality wire).
     */
    public record ModelResponse(
            String id,
            String provider,
            String model,
            String displayName,
            String baseUrl,
            String modality,
            boolean supportsStrictJsonSchema,
            BigDecimal priceInputPerMTok,
            BigDecimal priceOutputPerMTok,
            BigDecimal priceCachedInputPerMTok,
            boolean enabled,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt) {

        public static ModelResponse from(LlmModel m) {
            return new ModelResponse(
                    m.id() == null ? null : m.id().toString(),
                    m.provider(),
                    m.modelId(),
                    m.displayName(),
                    m.baseUrl(),
                    m.modality().wire(),
                    m.supportsStrictJsonSchema(),
                    m.priceInputPerMTok(),
                    m.priceOutputPerMTok(),
                    m.priceCachedInputPerMTok(),
                    m.enabled(),
                    m.createdAt(),
                    m.updatedAt());
        }
    }

    /** POST /admin/platform/models. Prices/flags optional (default 0 / false / true). */
    public record CreateModelRequest(
            @NotBlank String provider,
            @NotBlank String model,
            @NotBlank String displayName,
            @NotBlank String baseUrl,
            String modality,
            Boolean supportsStrictJsonSchema,
            BigDecimal priceInputPerMTok,
            BigDecimal priceOutputPerMTok,
            BigDecimal priceCachedInputPerMTok,
            Boolean enabled) {}

    /** Binding response (GET /admin/platform/config), enriched with the model's provider/model. */
    public record BindingResponse(
            String service,
            String modelId,
            String provider,
            String model,
            boolean enabled,
            OffsetDateTime updatedAt,
            String updatedBy) {

        public static BindingResponse from(ServiceBinding b, LlmModel model) {
            return new BindingResponse(
                    b.service(),
                    b.modelId() == null ? null : b.modelId().toString(),
                    model == null ? null : model.provider(),
                    model == null ? null : model.modelId(),
                    b.enabled(),
                    b.updatedAt(),
                    b.updatedBy());
        }
    }

    /** PUT /admin/platform/config/{service}. */
    public record BindRequest(UUID modelId, Boolean enabled) {}

    /**
     * POST /admin/platform/embeddings/backfill. {@code tenantId} is required — a backfill spends
     * money per meeting, so it is never implicitly "everyone". {@code limit} is optional and
     * clamped by the service.
     */
    public record BackfillRequest(UUID tenantId, Integer limit) {}

    /** POST /internal/platform/usage. */
    public record UsageRequest(
            @NotBlank String service,
            String provider,
            String model,
            String tenantId,
            @PositiveOrZero Integer promptTokens,
            @PositiveOrZero Integer completionTokens,
            BigDecimal costUsd,
            Integer latencyMs,
            String status) {}
}
