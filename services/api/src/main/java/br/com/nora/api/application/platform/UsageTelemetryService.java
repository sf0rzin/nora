package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.LlmModel;
import br.com.nora.api.domain.platform.UsageEvent;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Escritor único de telemetria de custo (ADR 0024). Implementa {@link UsageRecorder}; ativo só quando
 * {@code nora.platform.enabled=true} (quando off, o bean ativo é {@code NoOpUsageRecorder}). Calcula
 * o custo a partir do pricing do catálogo (fonte da verdade) — o {@code costUsdHint} do caller é só
 * fallback. <b>Nunca lança</b>: em plataforma degradada ou erro de query, descarta silenciosamente.
 */
@Service
@ConditionalOnProperty(name = "nora.platform.enabled", havingValue = "true")
public class UsageTelemetryService implements UsageRecorder {

    private static final Logger LOG = LoggerFactory.getLogger(UsageTelemetryService.class);
    private static final BigDecimal MILLION = BigDecimal.valueOf(1_000_000L);

    private final ObjectProvider<UsageEventRepository> eventsProvider;
    private final ObjectProvider<LlmModelRepository> modelsProvider;
    private final PlatformAvailability availability;

    public UsageTelemetryService(
            ObjectProvider<UsageEventRepository> eventsProvider,
            ObjectProvider<LlmModelRepository> modelsProvider,
            PlatformAvailability availability) {
        this.eventsProvider = eventsProvider;
        this.modelsProvider = modelsProvider;
        this.availability = availability;
    }

    @Override
    public void recordAnalysisUsage(
            UUID tenantId,
            String modelVersion,
            int promptTokens,
            int completionTokens,
            Integer latencyMs,
            boolean stub) {
        String[] pm = splitProviderModel(modelVersion);
        record(
                "analysis",
                pm[0],
                pm[1],
                tenantId,
                promptTokens,
                completionTokens,
                null,
                latencyMs,
                stub ? "stub" : "ok");
    }

    @Override
    public void recordExternal(
            String service,
            String provider,
            String model,
            UUID tenantId,
            int promptTokens,
            int completionTokens,
            BigDecimal costUsdHint,
            Integer latencyMs,
            String status) {
        record(
                service,
                provider,
                model,
                tenantId,
                promptTokens,
                completionTokens,
                costUsdHint,
                latencyMs,
                normalizeStatus(status));
    }

    /** Constrange status ao enum documentado (ok|error|stub|fallback); desconhecido vira "ok". */
    private static String normalizeStatus(String status) {
        if (status == null) {
            return "ok";
        }
        return switch (status.trim().toLowerCase()) {
            case "ok", "error", "stub", "fallback" -> status.trim().toLowerCase();
            default -> "ok";
        };
    }

    private void record(
            String service,
            String provider,
            String model,
            UUID tenantId,
            int promptTokens,
            int completionTokens,
            BigDecimal costUsdHint,
            Integer latencyMs,
            String status) {
        if (!availability.isUsable()) {
            return; // plataforma degradada/off — fire-and-forget: descarta
        }
        try {
            int in = Math.max(0, promptTokens);
            int out = Math.max(0, completionTokens);
            BigDecimal cost = computeCost(provider, model, in, out, status, costUsdHint);
            UsageEvent event =
                    new UsageEvent(
                            null,
                            null,
                            service,
                            nz(provider),
                            nz(model),
                            tenantId,
                            in,
                            out,
                            cost,
                            latencyMs,
                            status);
            eventsProvider.getObject().insert(event);
        } catch (RuntimeException ex) {
            LOG.warn("Usage descartado (service={} model={}): {}", service, model, ex.getMessage());
        }
    }

    private BigDecimal computeCost(
            String provider, String model, int in, int out, String status, BigDecimal hint) {
        if ("stub".equals(status)) {
            return BigDecimal.ZERO;
        }
        Optional<LlmModel> m = safeFindModel(provider, model);
        if (m.isPresent()) {
            BigDecimal inputCost = m.get().priceInputPerMTok().multiply(BigDecimal.valueOf(in));
            BigDecimal outputCost = m.get().priceOutputPerMTok().multiply(BigDecimal.valueOf(out));
            return inputCost.add(outputCost).divide(MILLION, 8, RoundingMode.HALF_UP);
        }
        return hint != null ? hint : BigDecimal.ZERO;
    }

    private Optional<LlmModel> safeFindModel(String provider, String model) {
        try {
            return modelsProvider.getObject().findByProviderAndModel(provider, model);
        } catch (RuntimeException ex) {
            return Optional.empty();
        }
    }

    /**
     * Deriva {@code (provider, model)} do {@code modelVersion} do worker (ex.: {@code
     * "openai-gpt-4o-mini"} → openai / gpt-4o-mini; {@code "stub-deterministic-v1"} → stub /
     * deterministic-v1). Split no PRIMEIRO hífen.
     */
    static String[] splitProviderModel(String modelVersion) {
        if (modelVersion == null || modelVersion.isBlank()) {
            return new String[] {"unknown", "unknown"};
        }
        int i = modelVersion.indexOf('-');
        if (i <= 0) {
            return new String[] {"unknown", modelVersion};
        }
        return new String[] {modelVersion.substring(0, i), modelVersion.substring(i + 1)};
    }

    private static String nz(String s) {
        return s == null || s.isBlank() ? "unknown" : s;
    }
}
