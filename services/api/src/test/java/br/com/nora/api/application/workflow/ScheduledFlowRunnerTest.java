package br.com.nora.api.application.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.MeetingAnalysisRepository;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.TenantRlsContext;
import br.com.nora.api.application.ports.WorkflowRepository;
import br.com.nora.api.application.ports.WorkflowScheduleRepository;
import br.com.nora.api.domain.workflow.TriggerType;
import br.com.nora.api.domain.workflow.Workflow;
import br.com.nora.api.domain.workflow.WorkflowSchedule;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;

/**
 * The dispatcher's contract (ADR 0047). Four properties are load-bearing and each is asserted here,
 * because none of them can be seen by reading a green integration test: an occurrence nobody
 * claimed does not run, a run that failed does not advance the window, an empty window costs no
 * execution row, and the tenant is propagated to RLS around every tenant's work.
 */
class ScheduledFlowRunnerTest {

    private final WorkflowScheduleRepository schedules = mock(WorkflowScheduleRepository.class);
    private final WorkflowRepository workflows = mock(WorkflowRepository.class);
    private final MeetingAnalysisRepository analyses = mock(MeetingAnalysisRepository.class);
    private final TenantRepository tenants = mock(TenantRepository.class);
    private final TenantRlsContext rlsContext = mock(TenantRlsContext.class);
    private final WorkflowEngine engine = mock(WorkflowEngine.class);
    private final Clock clock = mock(Clock.class);
    private final ScheduleOccurrences occurrences = new ScheduleOccurrences();

    private final UUID tenantId = UUID.randomUUID();
    private final UUID workflowId = UUID.randomUUID();
    private final Instant now = Instant.parse("2026-08-17T12:00:00Z");

    /**
     * The lease is an assumption about how long a run takes, so a value under the floor is refused
     * rather than obeyed — obeying it would start a second copy of a run that is merely slow.
     */
    @Test
    void leaseBelowTheFloorIsRefusedAndTheFloorIsUsed() {
        assertThat(runner(1).lease()).isEqualTo(ScheduledFlowRunner.MINIMUM_LEASE);
        assertThat(runner(0).lease()).isEqualTo(ScheduledFlowRunner.MINIMUM_LEASE);
        assertThat(runner(45).lease()).isEqualTo(Duration.ofMinutes(45));
    }

    /**
     * Under RLS enforce the scheduler thread carries no JWT. Without the propagation every
     * statement matches zero rows and the job reports "nothing due" forever — the silent failure
     * both existing sweepers document.
     */
    @Test
    void propagatesTheTenantToRlsAroundEachTenantAndClearsIt() {
        when(tenants.allActiveTenantIds()).thenReturn(List.of(tenantId));
        when(schedules.findDue(eq(tenantId), any(), any())).thenReturn(List.of());

        runner(30).tick();

        InOrder order = Mockito.inOrder(rlsContext, schedules);
        order.verify(rlsContext).set(tenantId);
        order.verify(schedules).findDue(eq(tenantId), any(), any());
        order.verify(rlsContext).clear();
    }

    /** A live claim means the previous run is still executing: the occurrence is skipped. */
    @Test
    void anOccurrenceThatCannotBeClaimedDoesNotRunAndIsNotReleased() {
        dueSchedule();
        when(schedules.claim(any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(false);

        runner(30).tick();

        verify(engine, never()).runScheduled(any(), anyList(), any());
        verify(schedules, never()).release(any(), any(), any(), anyString());
    }

    @Test
    void aClaimedOccurrenceRunsOncePerMeetingAnalysedInTheWindowAndThenReleases() {
        dueSchedule();
        when(schedules.claim(any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(true);
        when(workflows.findByIdAndTenant(workflowId, tenantId)).thenReturn(Optional.of(workflow()));
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        when(analyses.meetingIdsAnalysedBetween(eq(tenantId), any(), any(), anyInt()))
                .thenReturn(List.of(first, second));

        runner(30).tick();

        ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.captor();
        verify(engine).runScheduled(any(), ids.capture(), any());
        assertThat(ids.getValue()).containsExactly(first, second);
        verify(schedules).release(eq(workflowId), eq(tenantId), any(), anyString());
    }

    /**
     * The claim advances {@code next_fire_at}; only the release advances {@code window_from}. A run
     * that dies before completing must therefore NOT release, or the meetings it was carrying are
     * dropped with it.
     */
    @Test
    void aRunThatFailsStructurallyLeavesTheWindowOpen() {
        dueSchedule();
        when(schedules.claim(any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(true);
        when(workflows.findByIdAndTenant(workflowId, tenantId)).thenReturn(Optional.of(workflow()));
        when(analyses.meetingIdsAnalysedBetween(eq(tenantId), any(), any(), anyInt()))
                .thenReturn(List.of(UUID.randomUUID()));
        when(engine.runScheduled(any(), anyList(), any()))
                .thenThrow(new IllegalStateException("boom"));

        runner(30).tick();

        verify(schedules, never()).release(any(), any(), any(), anyString());
    }

    /**
     * A window with nothing in it writes no execution row: an hourly schedule on a quiet week would
     * otherwise fill a 50-row history with no-ops and push the real runs off the end. The window
     * still advances, because that occurrence WAS processed.
     */
    @Test
    void anEmptyWindowWritesNoExecutionButStillAdvancesTheWindow() {
        dueSchedule();
        when(schedules.claim(any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(true);
        when(workflows.findByIdAndTenant(workflowId, tenantId)).thenReturn(Optional.of(workflow()));
        when(analyses.meetingIdsAnalysedBetween(eq(tenantId), any(), any(), anyInt()))
                .thenReturn(List.of());

        runner(30).tick();

        verify(engine, never()).runScheduled(any(), anyList(), any());
        verify(schedules).release(eq(workflowId), eq(tenantId), any(), anyString());
    }

    /** After a long outage the window can be days wide; it must not become unbounded work. */
    @Test
    void theWindowIsCappedAtTheConfiguredMaximum() {
        dueSchedule();
        when(schedules.claim(any(), any(), any(), any(), any(), any(), anyString()))
                .thenReturn(true);
        when(workflows.findByIdAndTenant(workflowId, tenantId)).thenReturn(Optional.of(workflow()));
        when(analyses.meetingIdsAnalysedBetween(eq(tenantId), any(), any(), anyInt()))
                .thenReturn(List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID()));

        runner(30, 2).tick();

        ArgumentCaptor<List<UUID>> ids = ArgumentCaptor.captor();
        verify(engine).runScheduled(any(), ids.capture(), any());
        assertThat(ids.getValue()).hasSize(2);
        // The query asks for one more than the cap, which is how truncation is detected at all.
        verify(analyses).meetingIdsAnalysedBetween(eq(tenantId), any(), any(), eq(3));
    }

    /** One tenant failing must not stop the others, exactly as in the two existing sweepers. */
    @Test
    void oneTenantFailingDoesNotStopTheRest() {
        UUID other = UUID.randomUUID();
        when(tenants.allActiveTenantIds()).thenReturn(List.of(tenantId, other));
        when(schedules.findDue(eq(tenantId), any(), any()))
                .thenThrow(new IllegalStateException("boom"));
        when(schedules.findDue(eq(other), any(), any())).thenReturn(List.of());

        runner(30).tick();

        verify(schedules).findDue(eq(other), any(), any());
        verify(rlsContext, times(2)).clear();
    }

    private void dueSchedule() {
        when(tenants.allActiveTenantIds()).thenReturn(List.of(tenantId));
        OffsetDateTime dueAt = now.atOffset(ZoneOffset.UTC).minusMinutes(1);
        when(schedules.findDue(eq(tenantId), any(), any()))
                .thenReturn(
                        List.of(
                                new WorkflowSchedule(
                                        workflowId,
                                        tenantId,
                                        "0 0 9 * * *",
                                        ScheduleOccurrences.ZONE.getId(),
                                        dueAt,
                                        dueAt.minusDays(1),
                                        null,
                                        null,
                                        null)));
    }

    private Workflow workflow() {
        OffsetDateTime createdAt = now.atOffset(ZoneOffset.UTC).minusDays(7);
        return new Workflow(
                workflowId,
                tenantId,
                "Daily digest",
                TriggerType.SCHEDULE_CRON,
                "{\"nodes\":[],\"edges\":[]}",
                true,
                createdAt,
                createdAt);
    }

    private ScheduledFlowRunner runner(long leaseMinutes) {
        return runner(leaseMinutes, 50);
    }

    private ScheduledFlowRunner runner(long leaseMinutes, int maxMeetings) {
        when(clock.now()).thenReturn(now);
        return new ScheduledFlowRunner(
                schedules,
                workflows,
                analyses,
                tenants,
                rlsContext,
                occurrences,
                engine,
                clock,
                leaseMinutes,
                maxMeetings);
    }
}
