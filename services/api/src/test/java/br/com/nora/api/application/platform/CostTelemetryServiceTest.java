package br.com.nora.api.application.platform;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.nora.api.domain.platform.CostReport;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import br.com.nora.api.infrastructure.platform.PlatformProperties;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessResourceFailureException;

/** Agregação de custo: delega ao repo, valida groupBy (400), 503 quando off ou queda de runtime. */
class CostTelemetryServiceTest {

    private final UsageEventRepository events = mock(UsageEventRepository.class);
    private final OffsetDateTime from = OffsetDateTime.parse("2026-05-01T00:00:00Z");
    private final OffsetDateTime to = OffsetDateTime.parse("2026-05-28T00:00:00Z");

    @Test
    void agregaPorService() {
        CostReport report = report("service");
        when(events.aggregate(from, to, "service")).thenReturn(report);
        assertThat(svc(true).cost(from, to, "service")).isEqualTo(report);
    }

    @Test
    void groupByNull_defaultModel() {
        when(events.aggregate(any(), any(), eq("model"))).thenReturn(report("model"));
        svc(true).cost(from, to, null);
        verify(events).aggregate(from, to, "model");
    }

    @Test
    void groupByInvalido_400() {
        assertThatThrownBy(() -> svc(true).cost(from, to, "bogus"))
                .isInstanceOf(PlatformValidationException.class);
    }

    @Test
    void plataformaNaoUsavel_503() {
        assertThatThrownBy(() -> svc(false).cost(from, to, "model"))
                .isInstanceOf(PlatformUnavailableException.class);
    }

    @Test
    void quedaDeRuntime_dataAccess_503() {
        when(events.aggregate(any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));
        assertThatThrownBy(() -> svc(true).cost(from, to, "model"))
                .isInstanceOf(PlatformUnavailableException.class);
    }

    // ---- helpers ----

    private CostReport report(String groupBy) {
        return new CostReport(
                from, to, groupBy, List.of(), new CostReport.Totals(0, 0, BigDecimal.ZERO, 0));
    }

    private CostTelemetryService svc(boolean usable) {
        PlatformProperties p = new PlatformProperties();
        p.setEnabled(true);
        PlatformAvailability av = new PlatformAvailability(p);
        if (usable) {
            av.markHealthy();
        }
        return new CostTelemetryService(provider(events), av);
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getObject()).thenReturn(value);
        return p;
    }
}
