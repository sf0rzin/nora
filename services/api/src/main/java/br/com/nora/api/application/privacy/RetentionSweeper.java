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
 * Job de RETENÇÃO (LGPD, ADR 0029): purga periodicamente meetings mais antigos que a janela
 * configurada — fechando o gap de "raw_text = PII em repouso sem retenção".
 *
 * <p>Configurável e DESLIGADO por default ({@code nora.privacy.retention-days=0}): retenção é
 * destrutiva, então só liga por opt-in explícito do ambiente. Itera por tenant porque, sob RLS
 * enforce (ADR 0028), a thread do scheduler não tem JWT — propaga o tenant via {@link
 * TenantRlsContext} pra que o aspect aplique o GUC na transação da purga (a tabela {@code tenants}
 * é exempta, então a listagem de ids funciona sem GUC).
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

    /** Cron configurável (default: diário às 03:30). {@code retention-days <= 0} = no-op. */
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
