package br.com.nora.api.api.dto.platform;

import br.com.nora.api.domain.platform.LlmModel;
import br.com.nora.api.domain.platform.ServiceBinding;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/** DTOs de fronteira HTTP do control plane (contrato platform-control-plane.md). */
public final class PlatformDtos {

    private PlatformDtos() {}

    /**
     * Resposta do catálogo (GET /admin/platform/models). Espelha o contrato (model/modality wire).
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

    /** POST /admin/platform/models. Preços/flags opcionais (default 0 / false / true). */
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

    /**
     * Resposta de binding (GET /admin/platform/config), enriquecida com provider/model do modelo.
     */
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
