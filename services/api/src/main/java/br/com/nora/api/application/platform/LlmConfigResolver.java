package br.com.nora.api.application.platform;

import br.com.nora.api.domain.platform.FeatureFlag;
import br.com.nora.api.domain.platform.ResolvedLlmConfig;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import br.com.nora.api.infrastructure.platform.PlatformProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

/**
 * Resolve o modelo ativo por serviço no hot-path (ADR 0024). Cache Caffeine ~60s (mesmo padrão do
 * AuthRateLimiter). <b>Fallback SOFT</b>: qualquer falha (plataforma off/degradada, binding ausente,
 * exceção de query) devolve a config do env default — <b>nunca</b> lança, nunca derruba chat/worker.
 *
 * <p>Não é gated: existe sempre, e degrada para o env quando os repos do banco de plataforma não
 * estão presentes/usáveis.
 */
@Service
public class LlmConfigResolver {

    private static final Logger LOG = LoggerFactory.getLogger(LlmConfigResolver.class);
    public static final Set<String> SERVICES = Set.of("chat", "analysis", "multimodal");

    private final ObjectProvider<LlmConfigRepository> configProvider;
    private final ObjectProvider<FeatureFlagRepository> flagProvider;
    private final PlatformAvailability availability;
    private final PlatformProperties props;

    private final Cache<String, ResolvedLlmConfig> cache =
            Caffeine.newBuilder().expireAfterWrite(Duration.ofSeconds(60)).maximumSize(16).build();

    public LlmConfigResolver(
            ObjectProvider<LlmConfigRepository> configProvider,
            ObjectProvider<FeatureFlagRepository> flagProvider,
            PlatformAvailability availability,
            PlatformProperties props) {
        this.configProvider = configProvider;
        this.flagProvider = flagProvider;
        this.availability = availability;
        this.props = props;
    }

    public boolean isValidService(String service) {
        return SERVICES.contains(service);
    }

    public ResolvedLlmConfig resolve(String service) {
        return cache.get(service, this::resolveUncached);
    }

    public void invalidate(String service) {
        cache.invalidate(service);
    }

    private ResolvedLlmConfig resolveUncached(String service) {
        if (!availability.isUsable()) {
            // Plataforma off/degradada: a flag vive no banco inalcançável — assume enabled=true.
            return fallback(true);
        }
        try {
            Optional<ResolvedLlmConfig> active = configProvider.getObject().resolveActive(service);
            if (active.isEmpty()) {
                // Binding ausente mas plataforma HEALTHY: a feature flag É legível, então o env
                // default respeita a flag (contrato §2: "enabled conforme feature flag").
                return fallback(featureEnabled(service));
            }
            ResolvedLlmConfig r = active.get();
            return new ResolvedLlmConfig(
                    r.provider(), r.model(), r.baseUrl(), r.enabled() && featureEnabled(service));
        } catch (RuntimeException ex) {
            LOG.warn("Resolver: fallback SOFT p/ env (service={}). Causa: {}", service, ex.getMessage());
            return fallback(true);
        }
    }

    private boolean featureEnabled(String service) {
        try {
            return flagProvider
                    .getObject()
                    .findByKey("service." + service)
                    .map(FeatureFlag::enabled)
                    .orElse(true);
        } catch (RuntimeException ex) {
            return true;
        }
    }

    private ResolvedLlmConfig fallback(boolean enabled) {
        PlatformProperties.Fallback fb = props.getFallback();
        return new ResolvedLlmConfig(fb.getProvider(), fb.getModel(), fb.getBaseUrl(), enabled);
    }
}
