package br.com.nora.api.application.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.nora.api.domain.platform.FeatureFlag;
import br.com.nora.api.domain.platform.ResolvedLlmConfig;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import br.com.nora.api.infrastructure.platform.PlatformProperties;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The resolver is the most critical point of the fail-soft (ADR 0024): the hot-path can NEVER throw
 * nor be left without config. Covers the 3 fallback paths, the feature flag kill-switch and the 60s
 * cache.
 */
class LlmConfigResolverTest {

    @Test
    void plataformaOff_devolveFallbackDoEnvComEnabledTrue() {
        PlatformProperties p = props(false);
        LlmConfigResolver resolver =
                new LlmConfigResolver(
                        provider(mock(LlmConfigRepository.class)),
                        provider(mock(FeatureFlagRepository.class)),
                        new PlatformAvailability(p), // DISABLED
                        p);

        ResolvedLlmConfig r = resolver.resolve("chat");

        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.model()).isEqualTo("gpt-4o-mini");
        assertThat(r.baseUrl()).isEqualTo("https://api.openai.com/v1");
        assertThat(r.enabled()).isTrue();
    }

    @Test
    void bindingPresenteEFlagOn_modeloAtivoEnabled() {
        PlatformProperties p = props(true);
        LlmConfigRepository cfg = mock(LlmConfigRepository.class);
        when(cfg.resolveActive("chat"))
                .thenReturn(
                        Optional.of(
                                new ResolvedLlmConfig(
                                        "deepseek",
                                        "deepseek-v4-flash",
                                        "https://api.deepseek.com/v1",
                                        true)));
        FeatureFlagRepository flags = mock(FeatureFlagRepository.class);
        when(flags.findByKey("service.chat")).thenReturn(Optional.of(flag("service.chat", true)));

        ResolvedLlmConfig r =
                new LlmConfigResolver(provider(cfg), provider(flags), healthy(p), p)
                        .resolve("chat");

        assertThat(r.provider()).isEqualTo("deepseek");
        assertThat(r.model()).isEqualTo("deepseek-v4-flash");
        assertThat(r.enabled()).isTrue();
    }

    @Test
    void bindingPresenteMasFlagOff_killSwitch_enabledFalse() {
        PlatformProperties p = props(true);
        LlmConfigRepository cfg = mock(LlmConfigRepository.class);
        when(cfg.resolveActive("chat"))
                .thenReturn(
                        Optional.of(
                                new ResolvedLlmConfig("deepseek", "deepseek-v4-flash", "u", true)));
        FeatureFlagRepository flags = mock(FeatureFlagRepository.class);
        when(flags.findByKey("service.chat")).thenReturn(Optional.of(flag("service.chat", false)));

        ResolvedLlmConfig r =
                new LlmConfigResolver(provider(cfg), provider(flags), healthy(p), p)
                        .resolve("chat");

        assertThat(r.enabled()).isFalse();
    }

    @Test
    void bindingAusenteComPlataformaHealthy_fallbackRespeitaFlag() {
        PlatformProperties p = props(true);
        LlmConfigRepository cfg = mock(LlmConfigRepository.class);
        when(cfg.resolveActive("chat")).thenReturn(Optional.empty());
        FeatureFlagRepository flags = mock(FeatureFlagRepository.class);
        when(flags.findByKey("service.chat")).thenReturn(Optional.of(flag("service.chat", false)));

        ResolvedLlmConfig r =
                new LlmConfigResolver(provider(cfg), provider(flags), healthy(p), p)
                        .resolve("chat");

        assertThat(r.provider()).isEqualTo("openai"); // env fallback
        assertThat(r.enabled()).isFalse(); // respects the feature flag (review fix)
    }

    @Test
    void excecaoDoRepo_caiNoFallbackNuncaLanca() {
        PlatformProperties p = props(true);
        LlmConfigRepository cfg = mock(LlmConfigRepository.class);
        when(cfg.resolveActive("chat")).thenThrow(new RuntimeException("db down"));

        ResolvedLlmConfig r =
                new LlmConfigResolver(
                                provider(cfg),
                                provider(mock(FeatureFlagRepository.class)),
                                healthy(p),
                                p)
                        .resolve("chat");

        assertThat(r.provider()).isEqualTo("openai");
        assertThat(r.enabled()).isTrue();
    }

    @Test
    void cache60s_resolveDuasVezesBateNoRepoUmaVez() {
        PlatformProperties p = props(true);
        LlmConfigRepository cfg = mock(LlmConfigRepository.class);
        when(cfg.resolveActive("chat"))
                .thenReturn(Optional.of(new ResolvedLlmConfig("deepseek", "d", "u", true)));
        FeatureFlagRepository flags = mock(FeatureFlagRepository.class);
        when(flags.findByKey("service.chat")).thenReturn(Optional.of(flag("service.chat", true)));
        LlmConfigResolver resolver =
                new LlmConfigResolver(provider(cfg), provider(flags), healthy(p), p);

        resolver.resolve("chat");
        resolver.resolve("chat");

        verify(cfg, times(1)).resolveActive("chat");
    }

    @Test
    void invalidate_forcaNovaResolucao() {
        PlatformProperties p = props(true);
        LlmConfigRepository cfg = mock(LlmConfigRepository.class);
        when(cfg.resolveActive("chat"))
                .thenReturn(Optional.of(new ResolvedLlmConfig("deepseek", "d", "u", true)));
        FeatureFlagRepository flags = mock(FeatureFlagRepository.class);
        when(flags.findByKey("service.chat")).thenReturn(Optional.of(flag("service.chat", true)));
        LlmConfigResolver resolver =
                new LlmConfigResolver(provider(cfg), provider(flags), healthy(p), p);

        resolver.resolve("chat");
        resolver.invalidate("chat");
        resolver.resolve("chat");

        verify(cfg, times(2)).resolveActive("chat");
    }

    @Test
    void isValidService() {
        PlatformProperties p = props(false);
        LlmConfigResolver resolver =
                new LlmConfigResolver(
                        provider(mock(LlmConfigRepository.class)),
                        provider(mock(FeatureFlagRepository.class)),
                        new PlatformAvailability(p),
                        p);
        assertThat(resolver.isValidService("chat")).isTrue();
        assertThat(resolver.isValidService("analysis")).isTrue();
        assertThat(resolver.isValidService("bogus")).isFalse();
    }

    // ---- helpers ----

    private static PlatformProperties props(boolean enabled) {
        PlatformProperties p = new PlatformProperties();
        p.setEnabled(enabled);
        return p;
    }

    private static PlatformAvailability healthy(PlatformProperties p) {
        PlatformAvailability a = new PlatformAvailability(p);
        a.markHealthy();
        return a;
    }

    private static FeatureFlag flag(String key, boolean enabled) {
        return new FeatureFlag(key, enabled, null, null, OffsetDateTime.now());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getObject()).thenReturn(value);
        return p;
    }
}
