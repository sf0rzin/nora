package br.com.nora.api.application.privacy;

import br.com.nora.api.application.ports.TenantRepository;
import br.com.nora.api.application.ports.TenantRlsContext;
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
 * <p>Configurable and OFF by default ({@code nora.privacy.retention-days=0}): retention is
 * destructive, so it only turns on by explicit opt-in from the environment. It iterates per tenant
 * because, under RLS enforce (ADR 0028), the scheduler thread has no JWT — it propagates the tenant
 * via {@link TenantRlsContext} so the aspect applies the GUC on the purge's transaction (the {@code
 * tenants} table is exempt, so listing ids works without the GUC).
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

    /** Configurable cron (default: daily at 03:30). {@code retention-days <= 0} = no-op. */
    @Scheduled(cron = "${nora.privacy.retention-cron:0 30 3 * * *}")
    public void sweep() {
        if (retentionDays <= 0) {
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
                LOG.warn("Retenção falhou pro tenant={}: {}", tenantId, ex.getMessage());
            } finally {
                rlsContext.clear();
            }
        }
        LOG.info(
                "Retenção concluída: {} meeting(s) purgado(s) em {} tenant(s) (corte={} dias)",
                total,
                tenantIds.size(),
                retentionDays);
    }
}
