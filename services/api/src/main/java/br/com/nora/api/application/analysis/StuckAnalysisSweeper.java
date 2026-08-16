package br.com.nora.api.application.analysis;

import br.com.nora.api.application.ports.MeetingRepository;
import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.TenantRlsContext;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Releases meetings abandoned in PROCESSING: periodically moves to FAILED every meeting that has
 * sat in PROCESSING, untouched, for longer than the configured window.
 *
 * <p>Why it has to exist: PROCESSING is written by {@link AnalysisService}, and only that service
 * ever leaves it — COMPLETED on success, FAILED from its own catch. A JVM that dies during the
 * worker roundtrip (deploy, OOM, container restart) never reaches that catch, so the row keeps
 * asserting that an analysis is in flight when no thread is left to finish it. Re-analysis
 * deliberately refuses a PROCESSING meeting, because a conditional claim cannot tell "running" from
 * "abandoned", so the meeting stayed stuck with its button disabled and no way out through the
 * product. Time is the only signal that separates the two cases, and this job is where it is read.
 *
 * <p>The window is therefore a safety property, not a tuning knob: set it too low and a slow but
 * healthy analysis is reaped as a dead one, and the user is told it failed while it was about to
 * succeed. It is floored at the worker timeout plus {@link #MINIMUM_MARGIN} for that reason: a
 * configuration below the floor is refused, with a WARN, rather than obeyed.
 *
 * <p>Iterates per tenant because, under RLS enforce (ADR 0028), the scheduler thread carries no
 * JWT: without {@link TenantRlsContext#set(UUID)} the UPDATE would match no row at all and the job
 * would report zero forever — a silent failure, the same one {@code RetentionSweeper} documents.
 * The {@code tenants} table is exempt, so listing the ids works without the GUC.
 *
 * <p>Unlike retention, this job is not destructive: a status is rewritten, nothing is deleted, and
 * the meeting becomes claimable again. That is why it is ON by default rather than opt-in.
 */
@Component
public class StuckAnalysisSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(StuckAnalysisSweeper.class);

    /**
     * Head-room added on top of the worker timeout to obtain the smallest window this job accepts.
     * The timeout bounds the single blocking call; the margin covers what surrounds it inside the
     * PROCESSING span — loading the transcript, persisting the analysis, and a queued executor
     * thread that has not started yet.
     */
    static final Duration MINIMUM_MARGIN = Duration.ofMinutes(5);

    private final MeetingRepository meetings;
    private final TenantRepository tenants;
    private final TenantRlsContext rlsContext;
    private final Duration window;

    // The worker timeout arrives as a raw property rather than through NlpWorkerProperties: that
    // class lives in infrastructure, and the application layer does not import infrastructure.
    // Both defaults below must stay equal to the ones the properties themselves declare.
    public StuckAnalysisSweeper(
            MeetingRepository meetings,
            TenantRepository tenants,
            TenantRlsContext rlsContext,
            @Value("${nora.analysis.stuck-window-minutes:30}") long stuckWindowMinutes,
            @Value("${nora.worker.timeout-millis:120000}") long workerTimeoutMillis) {
        this.meetings = meetings;
        this.tenants = tenants;
        this.rlsContext = rlsContext;
        this.window = resolveWindow(stuckWindowMinutes, workerTimeoutMillis);
    }

    private static Duration resolveWindow(long stuckWindowMinutes, long workerTimeoutMillis) {
        Duration floor = Duration.ofMillis(Math.max(0L, workerTimeoutMillis)).plus(MINIMUM_MARGIN);
        Duration configured = Duration.ofMinutes(Math.max(0L, stuckWindowMinutes));
        if (configured.compareTo(floor) < 0) {
            LOG.warn(
                    "nora.analysis.stuck-window-minutes={} is below the safe floor of {}s (worker"
                            + " timeout {}ms + {}s margin) and would fail analyses that are merely"
                            + " slow; using the floor instead",
                    stuckWindowMinutes,
                    floor.toSeconds(),
                    workerTimeoutMillis,
                    MINIMUM_MARGIN.toSeconds());
            return floor;
        }
        return configured;
    }

    /** The window actually in force, after the floor was applied. Visible for tests and logs. */
    Duration window() {
        return window;
    }

    /** Configurable cron (default: every 5 minutes). */
    @Scheduled(cron = "${nora.analysis.stuck-sweep-cron:0 */5 * * * *}")
    public void sweep() {
        OffsetDateTime staleBefore = OffsetDateTime.now().minus(window);
        List<UUID> tenantIds = tenants.allActiveTenantIds();
        int total = 0;
        for (UUID tenantId : tenantIds) {
            rlsContext.set(tenantId);
            try {
                total += meetings.failStuckProcessing(tenantId, staleBefore);
            } catch (RuntimeException ex) {
                // One tenant failing must not stop the others, exactly as in the retention sweep.
                LOG.warn("Stuck sweep failed for tenant={}: {}", tenantId, ex.getMessage());
            } finally {
                rlsContext.clear();
            }
        }
        if (total > 0) {
            LOG.warn(
                    "Stuck-analysis sweep: {} meeting(s) abandoned in PROCESSING for more than {}s"
                            + " were marked FAILED across {} tenant(s); they can be re-analysed"
                            + " again",
                    total,
                    window.toSeconds(),
                    tenantIds.size());
        } else {
            LOG.debug(
                    "Stuck-analysis sweep: nothing abandoned across {} tenant(s) (window={}s)",
                    tenantIds.size(),
                    window.toSeconds());
        }
    }
}
