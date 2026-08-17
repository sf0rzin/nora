package br.com.nora.api.application.usage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.platform.UsageEventRepository;
import br.com.nora.api.application.platform.UsageEventRepository.TenantUsageRow;
import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.TrendsRepository;
import br.com.nora.api.application.ports.TrendsRepository.BucketCount;
import br.com.nora.api.application.ports.TrendsRepository.Granularity;
import br.com.nora.api.application.ports.TrendsRepository.MeetingCoverage;
import br.com.nora.api.application.ports.TrendsRepository.Scope;
import br.com.nora.api.infrastructure.platform.PlatformAvailability;
import br.com.nora.api.infrastructure.platform.PlatformProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * The tenant usage panel, and the four things it must never get wrong: it must not hand a
 * restricted caller a tenant-wide AI total, it must not report ignorance as a zero, it must not
 * call the transcription figure a cost, and it must not call a chat-only tenant empty.
 */
class UsageServiceTest {

    private static final UUID TENANT = UUID.randomUUID();
    private static final Instant NOW = Instant.parse("2026-08-17T12:00:00Z");

    private final TrendsRepository meetings = mock(TrendsRepository.class);
    private final UsageEventRepository events = mock(UsageEventRepository.class);
    private final Clock clock = () -> NOW;

    @BeforeEach
    void emptyByDefault() {
        when(meetings.meetingCoverage(any(), any())).thenReturn(new MeetingCoverage(0, 0));
        when(meetings.meetingsPerBucket(any(), any())).thenReturn(List.of());
        when(meetings.analysedMeetingsPerBucket(any(), any())).thenReturn(List.of());
        when(events.tenantSeries(any(), any(), any(), any(), any())).thenReturn(List.of());
    }

    @Test
    void restrictedScopeWithholdsTheAiHalfInsteadOfServingTheTenantWideTotal() {
        // usage_events carries no meeting id, so there is no honest per-item answer to give.
        UsageView view = service(true).compute(restricted(), Granularity.MONTH, null, null);

        assertThat(view.ai().state()).isEqualTo(UsageView.AiState.WITHHELD_RESTRICTED_SCOPE);
        assertThat(view.ai().calls()).isZero();
        assertThat(view.scopeStrategy()).isEqualTo(UsageView.ScopeStrategy.PER_MEETING_FILTER);
        verify(events, never()).tenantSeries(any(), any(), any(), any(), any());
    }

    @Test
    void controlPlaneOffReportsUnavailableAndNotAZero() {
        UsageView view = service(false).compute(whole(), Granularity.MONTH, null, null);

        assertThat(view.ai().state()).isEqualTo(UsageView.AiState.UNAVAILABLE);
        assertThat(view.ai().buckets()).isEmpty();
        verify(events, never()).tenantSeries(any(), any(), any(), any(), any());
    }

    @Test
    void aControlPlaneErrorDegradesInsteadOfFailingTheTenantScreen() {
        when(events.tenantSeries(any(), any(), any(), any(), any()))
                .thenThrow(new IllegalStateException("platform database unreachable"));

        UsageView view = service(true).compute(whole(), Granularity.MONTH, null, null);

        assertThat(view.ai().state()).isEqualTo(UsageView.AiState.UNAVAILABLE);
    }

    @Test
    void sttCallsAreCountedAndReportedAsUnmeteredRatherThanAsZeroCost() {
        when(events.tenantSeries(any(), any(), any(), any(), any()))
                .thenReturn(
                        List.of(
                                row("2026-08-01", "analysis", 4, 1000, 500, "0.002"),
                                row("2026-08-01", "stt", 9, 0, 0, "0"),
                                row("2026-07-01", "analysis", 2, 400, 100, "0.001")));

        UsageView.Ai ai = service(true).compute(whole(), Granularity.MONTH, null, null).ai();

        assertThat(ai.state()).isEqualTo(UsageView.AiState.AVAILABLE);
        assertThat(ai.calls()).isEqualTo(15);
        assertThat(ai.unmeteredCalls()).isEqualTo(9);
        assertThat(ai.promptTokens()).isEqualTo(1400);
        assertThat(ai.completionTokens()).isEqualTo(600);
        assertThat(ai.costUsd()).isEqualByComparingTo(new BigDecimal("0.003"));
        assertThat(ai.costBasis()).isEqualTo(UsageView.COST_BASIS_CATALOG);

        // Busiest first, and the transcription row is flagged as not measurable rather than free.
        assertThat(ai.byService()).hasSize(2);
        assertThat(ai.byService().get(0).service()).isEqualTo("stt");
        assertThat(ai.byService().get(0).metered()).isFalse();
        assertThat(ai.byService().get(1).service()).isEqualTo("analysis");
        assertThat(ai.byService().get(1).metered()).isTrue();
    }

    @Test
    void theAiAxisIsDenseSoAQuietMonthIsAZeroAndNotAMissingPoint() {
        when(events.tenantSeries(any(), any(), any(), any(), any()))
                .thenReturn(List.of(row("2026-08-01", "chat", 3, 30, 10, "0.0001")));

        UsageView view = service(true).compute(whole(), Granularity.MONTH, null, null);

        // Six months back, inclusive of the current one: March through August 2026.
        assertThat(view.ai().buckets()).hasSize(6);
        assertThat(view.ai().buckets().get(0).bucketStart()).isEqualTo(LocalDate.of(2026, 3, 1));
        assertThat(view.ai().buckets().get(0).calls()).isZero();
        assertThat(view.ai().buckets().get(5).calls()).isEqualTo(3);
        assertThat(view.meetingBuckets()).hasSize(6);
    }

    @Test
    void nothingAnywhereIsNoDataAndNotAPeriodOfZeroActivity() {
        UsageView view = service(true).compute(whole(), Granularity.MONTH, null, null);

        assertThat(view.dataState()).isEqualTo(UsageView.DataState.NO_DATA);
    }

    @Test
    void meetingsWithNoAnalysisSayThatRatherThanShowingZeros() {
        when(meetings.meetingCoverage(any(), any())).thenReturn(new MeetingCoverage(7, 0));

        UsageView view = service(true).compute(whole(), Granularity.MONTH, null, null);

        assertThat(view.dataState()).isEqualTo(UsageView.DataState.NO_ANALYSED_MEETINGS);
        assertThat(view.meetings()).isEqualTo(7);
    }

    @Test
    void aTenantThatOnlyUsedTheChatIsNotEmpty() {
        // No meeting at all in the range, but real AI consumption: "no data yet" would be false.
        when(events.tenantSeries(any(), any(), any(), any(), any()))
                .thenReturn(List.of(row("2026-08-01", "chat", 12, 900, 400, "0.004")));

        UsageView view = service(true).compute(whole(), Granularity.MONTH, null, null);

        assertThat(view.dataState()).isEqualTo(UsageView.DataState.OK);
        assertThat(view.meetings()).isZero();
    }

    @Test
    void meetingBucketsCarryHeldAndAnalysedSideBySide() {
        when(meetings.meetingCoverage(any(), any())).thenReturn(new MeetingCoverage(10, 4));
        when(meetings.meetingsPerBucket(any(), any()))
                .thenReturn(List.of(new BucketCount(LocalDate.of(2026, 8, 1), 10)));
        when(meetings.analysedMeetingsPerBucket(any(), any()))
                .thenReturn(List.of(new BucketCount(LocalDate.of(2026, 8, 1), 4)));

        UsageView view = service(true).compute(whole(), Granularity.MONTH, null, null);

        UsageView.MeetingBucket august = view.meetingBuckets().get(5);
        assertThat(august.bucketStart()).isEqualTo(LocalDate.of(2026, 8, 1));
        assertThat(august.meetings()).isEqualTo(10);
        assertThat(august.analysedMeetings()).isEqualTo(4);
    }

    @Test
    void anAbsentGranularityReportsOverMonthsBecauseConsumptionIsReadPerBillingPeriod() {
        UsageView view = service(true).compute(whole(), null, null, null);

        assertThat(view.granularity()).isEqualTo(Granularity.MONTH);
        assertThat(view.timezone()).isEqualTo("America/Sao_Paulo");
    }

    // ---- helpers ----

    private UsageService service(boolean platformUsable) {
        PlatformProperties props = new PlatformProperties();
        props.setEnabled(platformUsable);
        PlatformAvailability availability = new PlatformAvailability(props);
        if (platformUsable) {
            availability.markHealthy();
        }
        ObjectProvider<UsageEventRepository> repo = provider(platformUsable ? events : null);
        return new UsageService(meetings, repo, availability, clock);
    }

    private static Scope whole() {
        return Scope.wholeTenant(TENANT);
    }

    private static Scope restricted() {
        return Scope.ofMeetings(TENANT, List.of(UUID.randomUUID()));
    }

    private static TenantUsageRow row(
            String bucket, String service, long calls, long in, long out, String cost) {
        return new TenantUsageRow(
                LocalDate.parse(bucket), service, calls, in, out, new BigDecimal(cost));
    }

    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> provider(T value) {
        ObjectProvider<T> p = mock(ObjectProvider.class);
        when(p.getIfAvailable()).thenReturn(value);
        return p;
    }
}
