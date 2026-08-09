package br.com.nora.api.application.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.nora.api.domain.platform.LlmModel;
import br.com.nora.api.domain.platform.Modality;
import br.com.nora.api.domain.platform.UsageEvent;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import br.com.nora.api.infrastructure.platform.PlatformProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;

/** Cost telemetry: catalog cost, stub=0, drop when off, and NEVER propagates an exception. */
class UsageTelemetryServiceTest {

    private final UsageEventRepository events = mock(UsageEventRepository.class);
    private final LlmModelRepository models = mock(LlmModelRepository.class);

    @Test
    void recordExternal_calculaCustoDoCatalogo() {
        when(models.findByProviderAndModel("openai", "gpt-4o-mini"))
                .thenReturn(Optional.of(model("0.15", "0.60")));
        ArgumentCaptor<UsageEvent> captor = ArgumentCaptor.forClass(UsageEvent.class);

        svc(true)
                .recordExternal(
                        "chat",
                        "openai",
                        "gpt-4o-mini",
                        UUID.randomUUID(),
                        1_000_000,
                        1_000_000,
                        null,
                        120,
                        "ok");

        verify(events).insert(captor.capture());
        // (1e6 * 0.15 + 1e6 * 0.60) / 1e6 = 0.75
        assertThat(captor.getValue().costUsd()).isEqualByComparingTo(new BigDecimal("0.75"));
        assertThat(captor.getValue().service()).isEqualTo("chat");
    }

    @Test
    void statusStub_custoZero() {
        ArgumentCaptor<UsageEvent> captor = ArgumentCaptor.forClass(UsageEvent.class);
        svc(true)
                .recordExternal(
                        "analysis", "openai", "gpt-4o-mini", null, 1000, 1000, null, 10, "stub");
        verify(events).insert(captor.capture());
        assertThat(captor.getValue().costUsd()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(captor.getValue().status()).isEqualTo("stub");
    }

    @Test
    void plataformaNaoUsavel_descartaNaoGrava() {
        svc(false).recordExternal("chat", "openai", "gpt-4o-mini", null, 10, 10, null, 1, "ok");
        verify(events, never()).insert(any());
    }

    @Test
    void erroDoRepo_nuncaPropaga() {
        when(models.findByProviderAndModel(any(), any())).thenReturn(Optional.empty());
        doThrow(new RuntimeException("db")).when(events).insert(any());
        assertThatCode(
                        () ->
                                svc(true)
                                        .recordExternal(
                                                "chat",
                                                "openai",
                                                "x",
                                                null,
                                                10,
                                                10,
                                                BigDecimal.ONE,
                                                1,
                                                "ok"))
                .doesNotThrowAnyException();
    }

    @Test
    void recordAnalysisUsage_derivaProviderModelDoModelVersion() {
        when(models.findByProviderAndModel("openai", "gpt-4o-mini"))
                .thenReturn(Optional.of(model("0.15", "0.60")));
        ArgumentCaptor<UsageEvent> captor = ArgumentCaptor.forClass(UsageEvent.class);

        svc(true).recordAnalysisUsage(UUID.randomUUID(), "openai-gpt-4o-mini", 100, 50, 200, false);

        verify(events).insert(captor.capture());
        assertThat(captor.getValue().service()).isEqualTo("analysis");
        assertThat(captor.getValue().provider()).isEqualTo("openai");
        assertThat(captor.getValue().model()).isEqualTo("gpt-4o-mini");
    }

    // ---- helpers ----

    private UsageTelemetryService svc(boolean usable) {
        PlatformProperties p = new PlatformProperties();
        p.setEnabled(true);
        PlatformAvailability av = new PlatformAvailability(p);
        if (usable) {
            av.markHealthy();
        }
        return new UsageTelemetryService(provider(events), provider(models), av);
    }

    private static LlmModel model(String priceIn, String priceOut) {
        return new LlmModel(
                UUID.randomUUID(),
                "openai",
                "gpt-4o-mini",
                "x",
                "u",
                Modality.TEXT,
                true,
                new BigDecimal(priceIn),
                new BigDecimal(priceOut),
                null,
                true,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getObject()).thenReturn(value);
        return p;
    }
}
