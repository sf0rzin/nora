package br.com.nora.api.infrastructure.platform.telemetry;

import br.com.nora.api.application.platform.BusinessMetricsSource;
import br.com.nora.api.domain.platform.BusinessSnapshot;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Métricas de negócio cross-tenant (telemetria (c), CORTÁVEL, ADR 0024). Lê o banco PRIMÁRIO via o
 * {@code JdbcTemplate} autoconfigurado, SEM contexto de tenant — agregação operador-only e
 * intencional (não passa por @Transactional, então o TenantRlsAspect não aplica; com RLS enforce
 * desligado, a leitura cross-tenant funciona). v1 minimalista: contagens seguras (analyses + tenants
 * ativos); médias deixadas null pra não acoplar a schema de outros módulos. Bean não-gated (não toca
 * o banco de plataforma).
 *
 * <p><b>CAVEAT RLS (ADR 0019):</b> esta leitura cross-tenant depende do datasource primário rodar
 * com role BYPASSRLS (estado atual: owner, NORA_RLS_ENFORCE=false). Se o opt-in de RLS enforce for
 * ativado (role nora_app NOBYPASSRLS + flag), estas queries rodam SEM GUC de tenant ⇒ a policy
 * fail-closed esconde TODAS as linhas ⇒ analyses=0/tenantsActive=0 SILENCIOSO (sem erro). Antes de
 * ligar RLS enforce, dar a este caminho um role/visão BYPASSRLS dedicado. Ver
 * docs/operations/production-readiness-gaps.md.
 */
@Component
public class PrimaryDbBusinessMetricsSource implements BusinessMetricsSource {

    private static final Logger LOG = LoggerFactory.getLogger(PrimaryDbBusinessMetricsSource.class);

    private final JdbcTemplate jdbc;

    public PrimaryDbBusinessMetricsSource(JdbcTemplate jdbcTemplate) {
        this.jdbc = jdbcTemplate;
    }

    @Override
    public BusinessSnapshot fetch(OffsetDateTime from, OffsetDateTime to) {
        try {
            Long analyses =
                    jdbc.queryForObject(
                            "SELECT COUNT(*) FROM meeting_analyses WHERE generated_at >= ? AND"
                                + " generated_at < ?",
                            Long.class,
                            from,
                            to);
            Long tenants =
                    jdbc.queryForObject(
                            "SELECT COUNT(DISTINCT tenant_id) FROM meeting_analyses WHERE generated_at"
                                + " >= ? AND generated_at < ?",
                            Long.class,
                            from,
                            to);
            return new BusinessSnapshot(from, to, true, nz(analyses), nz(tenants), null, null);
        } catch (RuntimeException ex) {
            LOG.warn("Business telemetry: falha ao agregar do banco primário: {}", ex.getMessage());
            return BusinessSnapshot.disabled(from, to);
        }
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }
}
