package br.com.nora.api.application.privacy;

import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.TenantRlsContext;
import jakarta.annotation.PostConstruct;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * RETENTION job (LGPD, ADR 0029): periodically purges meetings older than the configured window —
 * closing the "raw_text = PII at rest with no retention" gap.
 *
 * <p>What {@code NORA_PRIVACY_RETENTION_DAYS} means — the value is an AGE IN DAYS, and the sentinel
 * sits at the bottom of the range:
 *
 * <ul>
 *   <li><b>{@code 0} or any negative value — retention is OFF.</b> Nothing is ever purged. This is
 *       the shipped default, because the purge is an irreversible hard delete with CASCADE (see
 *       {@link PrivacyService}) and no environment should start deleting because a variable went
 *       unset.
 *   <li><b>{@code N >= 1} — retention is ON</b> with an N-day window: every meeting created more
 *       than N days ago is purged, together with its transcript, participants, tags and analyses.
 * </ul>
 *
 * <p>There is deliberately NO value that means "purge immediately": the smallest window is one day.
 * Lowering the number towards zero does not accelerate deletion, it switches deletion off — the
 * public landing page claims the opposite, which is tracked as a frozen decision in GitHub issue
 * #456, not fixed here.
 *
 * <p>The window is GLOBAL — one number for every tenant (ADR 0029). There is no per-tenant column,
 * no per-plan window, and no link to tenant status: a tenant that cancelled is not treated
 * differently from an active one. The rule is a flat age cutoff and nothing else.
 *
 * <p>It iterates per tenant because, under RLS enforce (ADR 0028), the scheduler thread has no JWT
 * — it propagates the tenant via {@link TenantRlsContext} so the aspect applies the GUC on the
 * purge's transaction (the {@code tenants} table is exempt, so listing ids works without the GUC).
 */
@Component
public class RetentionSweeper {

    private static final Logger LOG = LoggerFactory.getLogger(RetentionSweeper.class);

    private final PrivacyService privacy;
    private final TenantRepository tenants;
    private final TenantRlsContext rlsContext;
    private final int retentionDays;

    public RetentionSweeper(
            PrivacyService privacy,
            TenantRepository tenants,
            TenantRlsContext rlsContext,
            @Value("${nora.privacy.retention-days:0}") int retentionDays) {
        this.privacy = privacy;
        this.tenants = tenants;
        this.rlsContext = rlsContext;
        this.retentionDays = retentionDays;
    }

    /** True when a window is configured ({@code >= 1} day). See the class javadoc. */
    public boolean enabled() {
        return retentionDays > 0;
    }

    /**
     * States the retention configuration at boot. Without this line the only way to know whether
     * the job deletes anything is to read the environment of a running container — and a job that
     * is off looks exactly like a job that runs and finds nothing.
     */
    @PostConstruct
    void logConfiguration() {
        if (enabled()) {
            LOG.info(
                    "Retention ON (NORA_PRIVACY_RETENTION_DAYS={}): every sweep purges meetings"
                            + " older than that many days.",
                    retentionDays);
        } else {
            LOG.info(
                    "Retention OFF (NORA_PRIVACY_RETENTION_DAYS={}): nothing is ever purged."
                            + " Set it to 1 or more to turn the purge on.",
                    retentionDays);
        }
    }

    /**
     * Configurable cron (default: daily at 03:30). With retention OFF the run is a no-op — it still
     * says so, at DEBUG, so an operator chasing "why was nothing deleted" gets an answer from the
     * log instead of from silence.
     */
    @Scheduled(cron = "${nora.privacy.retention-cron:0 30 3 * * *}")
    public void sweep() {
        if (!enabled()) {
            LOG.debug(
                    "Retention sweep skipped: retention is OFF (NORA_PRIVACY_RETENTION_DAYS={})",
                    retentionDays);
            return;
        }
        OffsetDateTime cutoff = OffsetDateTime.now().minusDays(retentionDays);
        List<UUID> tenantIds = tenants.allActiveTenantIds();
        int total = 0;
        for (UUID tenantId : tenantIds) {
            rlsContext.set(tenantId);
            try {
                total += privacy.purgeOlderThan(tenantId, cutoff);
            } catch (RuntimeException ex) {
                LOG.warn("Retention failed for tenant={}: {}", tenantId, ex.getMessage());
            } finally {
                rlsContext.clear();
            }
        }
        LOG.info(
                "Retention completed: {} meeting(s) purged across {} tenant(s) (cutoff={} days)",
                total,
                tenantIds.size(),
                retentionDays);
    }
}
