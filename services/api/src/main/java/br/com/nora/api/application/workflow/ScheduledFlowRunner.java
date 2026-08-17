package br.com.nora.api.application.workflow;

import br.com.nora.api.application.ports.Clock;
import br.com.nora.api.application.ports.MeetingAnalysisRepository;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.TenantRlsContext;
import br.com.nora.api.application.ports.WorkflowRepository;
import br.com.nora.api.application.ports.WorkflowScheduleRepository;
import br.com.nora.api.domain.workflow.Workflow;
import br.com.nora.api.domain.workflow.WorkflowSchedule;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * The dispatcher of {@code schedule.cron} (US75, ADR 0047). It is what makes {@code
 * TriggerType.SCHEDULE_CRON.hasDispatcher()} true: until it existed, a flow saved with that trigger
 * would have sat ACTIVE and never run, which is why the parser refused it.
 *
 * <p>Every minute it asks each active tenant for its due schedules, CLAIMS each due run in the
 * database, and executes the flow once per meeting analysed since the run's window opened.
 *
 * <p><b>The claim is not an optimisation.</b> ADR 0036 says the substrate is a single host with one
 * API container, so {@code @Scheduled} alone would fire each run once — but that is a deployment
 * fact, not a property of the code. {@link WorkflowScheduleRepository#claim} is a compare-and-swap
 * against the committed row, so a second replica costs a lost race instead of a duplicate Slack
 * message, and a live claim makes an overlapping occurrence SKIP rather than queue.
 *
 * <p><b>Tenant context.</b> A scheduled run has no HTTP request and no principal, so under RLS
 * enforce (ADR 0028) nothing populates the GUC the {@code TenantRlsAspect} applies. It iterates per
 * tenant and propagates through {@link TenantRlsContext}, exactly as {@code RetentionSweeper} and
 * {@code StuckAnalysisSweeper} do; without it every statement would match zero rows and the job
 * would report "nothing due" forever. The {@code tenants} table is exempt, so listing the ids works
 * without the GUC.
 *
 * <p><b>Under whose authority a run acts.</b> No IAM decision is made here, because there is no
 * principal to make one about. The actions resolve the TENANT's integration connections — the same
 * ones the async event listener has used since ADR 0030 — and the authorization happened at save
 * time, where a principal existed and needed {@code workflow:write}. The consequence, named in ADR
 * 0047 §6 rather than engineered around: revoking that permission does not stop a flow the user
 * already created. Deactivating the flow does, and so does a tenant leaving {@code
 * allActiveTenantIds}.
 */
@Component
public class ScheduledFlowRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ScheduledFlowRunner.class);

    /**
     * Smallest claim lease this job accepts. The lease is how long a claim is believed before it is
     * presumed abandoned, so it is an assumption about how long a run takes — a flow can wire up to
     * fourteen HTTP actions. Set it below the real duration and a healthy long run gets a
     * concurrent second copy, which is the failure this whole guard exists to prevent. Same shape
     * as {@code StuckAnalysisSweeper.MINIMUM_MARGIN}: a value below the floor is refused with a
     * WARN, not obeyed.
     */
    static final Duration MINIMUM_LEASE = Duration.ofMinutes(5);

    private final WorkflowScheduleRepository schedules;
    private final WorkflowRepository workflows;
    private final MeetingAnalysisRepository analyses;
    private final TenantRepository tenants;
    private final TenantRlsContext rlsContext;
    private final ScheduleOccurrences occurrences;
    private final WorkflowEngine engine;
    private final Clock clock;
    private final Duration lease;
    private final int maxMeetingsPerRun;

    /**
     * Identity of this process inside a claim. Minted per boot rather than configured: its only job
     * is to tell two live containers apart in a log line, and a value an operator has to remember
     * to set would be identical on both of them exactly when it mattered.
     */
    private final String owner = UUID.randomUUID().toString();

    public ScheduledFlowRunner(
            WorkflowScheduleRepository schedules,
            WorkflowRepository workflows,
            MeetingAnalysisRepository analyses,
            TenantRepository tenants,
            TenantRlsContext rlsContext,
            ScheduleOccurrences occurrences,
            WorkflowEngine engine,
            Clock clock,
            @Value("${nora.flows.schedule.claim-lease-minutes:30}") long claimLeaseMinutes,
            @Value("${nora.flows.schedule.max-meetings-per-run:50}") int maxMeetingsPerRun) {
        this.schedules = schedules;
        this.workflows = workflows;
        this.analyses = analyses;
        this.tenants = tenants;
        this.rlsContext = rlsContext;
        this.occurrences = occurrences;
        this.engine = engine;
        this.clock = clock;
        this.lease = resolveLease(claimLeaseMinutes);
        this.maxMeetingsPerRun = Math.max(1, maxMeetingsPerRun);
    }

    private static Duration resolveLease(long claimLeaseMinutes) {
        Duration configured = Duration.ofMinutes(Math.max(0L, claimLeaseMinutes));
        if (configured.compareTo(MINIMUM_LEASE) < 0) {
            LOG.warn(
                    "nora.flows.schedule.claim-lease-minutes={} is below the floor of {}min and"
                            + " would let a healthy long run be treated as abandoned, starting a"
                            + " second copy of it; using the floor instead",
                    claimLeaseMinutes,
                    MINIMUM_LEASE.toMinutes());
            return MINIMUM_LEASE;
        }
        return configured;
    }

    /** The lease actually in force, after the floor was applied. Visible for tests and logs. */
    Duration lease() {
        return lease;
    }

    /**
     * Configurable tick (default: every minute, on the second). One minute is the finest resolution
     * the schedule vocabulary can express, so a faster tick would find nothing and a slower one
     * would make every schedule late by up to its own period.
     */
    @Scheduled(cron = "${nora.flows.schedule.tick-cron:0 * * * * *}")
    public void tick() {
        OffsetDateTime now = clock.now().atOffset(ZoneOffset.UTC);
        OffsetDateTime leaseExpiredBefore = now.minus(lease);
        List<UUID> tenantIds = tenants.allActiveTenantIds();
        int fired = 0;
        for (UUID tenantId : tenantIds) {
            rlsContext.set(tenantId);
            try {
                fired += runDueSchedules(tenantId, now, leaseExpiredBefore);
            } catch (RuntimeException ex) {
                // One tenant failing must not stop the others, exactly as in the two sweepers.
                LOG.warn(
                        "Scheduled flows: tick failed for tenant={}: {}",
                        tenantId,
                        ex.getMessage());
            } finally {
                rlsContext.clear();
            }
        }
        if (fired > 0) {
            LOG.info(
                    "Scheduled flows: {} run(s) fired across {} tenant(s)",
                    fired,
                    tenantIds.size());
        }
    }

    private int runDueSchedules(
            UUID tenantId, OffsetDateTime now, OffsetDateTime leaseExpiredBefore) {
        List<WorkflowSchedule> due = schedules.findDue(tenantId, now, leaseExpiredBefore);
        int fired = 0;
        for (WorkflowSchedule schedule : due) {
            OffsetDateTime nextFireAt = occurrences.nextAfter(schedule.cron(), now);
            if (nextFireAt == null) {
                // Only reachable for a row written around the API: the parser is the single writer
                // of this column and only ever emits the three compiled shapes. Loud on purpose.
                LOG.error(
                        "Scheduled flows: workflow={} carries an expression the scheduler cannot"
                                + " read ({}); the schedule will not advance until it is fixed",
                        schedule.workflowId(),
                        schedule.cron());
                continue;
            }
            boolean claimed =
                    schedules.claim(
                            schedule.workflowId(),
                            tenantId,
                            schedule.nextFireAt(),
                            now,
                            nextFireAt,
                            leaseExpiredBefore,
                            owner);
            if (!claimed) {
                LOG.debug(
                        "Scheduled flows: workflow={} not claimed — the previous run is still in"
                                + " flight, or another process took it; this occurrence is skipped",
                        schedule.workflowId());
                continue;
            }
            try {
                runClaimed(tenantId, schedule, now);
                // Only a run that COMPLETED advances the window. A structural failure below leaves
                // windowFrom where it was, so the next occurrence carries the same meetings.
                schedules.release(schedule.workflowId(), tenantId, now, owner);
                fired++;
            } catch (RuntimeException ex) {
                LOG.error(
                        "Scheduled flows: run of workflow={} failed before completing; the window"
                                + " stays open and the claim lapses in {}min. cause={}",
                        schedule.workflowId(),
                        lease.toMinutes(),
                        ex.getMessage());
            }
        }
        return fired;
    }

    /**
     * Executes one claimed occurrence: the flow runs once per meeting analysed in {@code
     * [windowFrom, firedAt)}, most recent first.
     *
     * <p>The fan-out is the same shape {@code action_item.created} already has, and it is what lets
     * every condition and all fourteen actions work unchanged — each of them consumes a {@link
     * WorkflowEventContext} built from ONE meeting. It is deliberately not a digest: no aggregate
     * placeholder exists, and shipping half of one would be a trigger whose copy over-promises (ADR
     * 0047 §7).
     *
     * <p>A window with no analysed meetings writes NO execution row. One empty row per occurrence
     * would be a truer record and was rejected on arithmetic: an hourly schedule on a quiet week
     * writes 168 of them against a history capped at {@link WorkflowService#EXECUTIONS_LIMIT}, so
     * the real runs would be pushed off the end of the list the user opens.
     */
    private void runClaimed(UUID tenantId, WorkflowSchedule schedule, OffsetDateTime firedAt) {
        Workflow workflow =
                workflows.findByIdAndTenant(schedule.workflowId(), tenantId).orElse(null);
        if (workflow == null) {
            LOG.warn(
                    "Scheduled flows: workflow={} vanished between the due query and the claim",
                    schedule.workflowId());
            return;
        }
        List<UUID> meetingIds =
                analyses.meetingIdsAnalysedBetween(
                        tenantId, schedule.windowFrom(), firedAt, maxMeetingsPerRun + 1);
        if (meetingIds.size() > maxMeetingsPerRun) {
            LOG.warn(
                    "Scheduled flows: workflow={} matched more than {} meeting(s) since {} — only"
                            + " the most recent {} run. A window is never allowed to become"
                            + " unbounded work; raise nora.flows.schedule.max-meetings-per-run if"
                            + " this is expected",
                    workflow.id(),
                    maxMeetingsPerRun,
                    schedule.windowFrom(),
                    maxMeetingsPerRun);
            meetingIds = meetingIds.subList(0, maxMeetingsPerRun);
        }
        if (meetingIds.isEmpty()) {
            LOG.debug(
                    "Scheduled flows: workflow={} fired with no meeting analysed since {} —"
                            + " nothing to execute, and no execution row is written",
                    workflow.id(),
                    schedule.windowFrom());
            return;
        }
        int executed = engine.runScheduled(workflow, meetingIds, firedAt);
        LOG.info(
                "Scheduled flows: workflow={} executed over {} meeting(s) analysed since {}",
                workflow.id(),
                executed,
                schedule.windowFrom());
    }
}
