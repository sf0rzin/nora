package br.com.nora.api.infrastructure.platform.telemetry;

import br.com.nora.api.application.platform.BusinessMetricsSource;
import br.com.nora.api.domain.platform.BusinessSnapshot;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Métricas de negócio cross-tenant (telemetria (c), CORTÁVEL, ADR 0024). Lê o banco PRIMÁRIO, SEM
 * contexto de tenant — agregação operador-only e intencional (COUNT/COUNT DISTINCT em {@code
 * meeting_analyses}). v1 minimalista: contagens seguras (analyses + tenants ativos); médias deixadas
 * null pra não acoplar a schema de outros módulos. Bean não-gated (não toca o banco de plataforma).
 *
 * <p><b>RLS enforce-safe (ADR 0019, ADR 0026):</b> sob {@code NORA_RLS_ENFORCE=true} o datasource
 * primário roda como {@code nora_app} (NOBYPASSRLS). Como esta leitura NÃO passa por
 * {@code @Transactional} (logo o {@code TenantRlsAspect} não seta o GUC), a policy {@code
 * tenant_isolation} seria fail-closed ⇒ {@code analyses=0/tenants=0} SILENCIOSO. Para evitar isso,
 * quando há um {@link #telemetryJdbc} dedicado configurado ({@code nora.security.rls.telemetry.url},
 * role {@code nora_telemetry} BYPASSRLS — ver {@code db/operational/R001__provision_app_roles.sql}),
 * a agregação usa ESSE caminho. Sem ele (dev/local/CI, ou prod antes do cutover), cai no {@code
 * JdbcTemplate} primário, onde o owner bypassa RLS — comportamento atual intacto.
 */
@Component
public class PrimaryDbBusinessMetricsSource implements BusinessMetricsSource {

    private static final Logger LOG = LoggerFactory.getLogger(PrimaryDbBusinessMetricsSource.class);

    private final JdbcTemplate primaryJdbc;
    private final JdbcTemplate telemetryJdbc;

    public PrimaryDbBusinessMetricsSource(
            @Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("telemetryJdbcTemplate")
                    ObjectProvider<JdbcTemplate> telemetryJdbcProvider) {
        this.primaryJdbc = jdbcTemplate;
        // Optional: presente só quando o datasource BYPASSRLS dedicado está configurado
        // (TelemetryDataSourceConfig, gated por nora.security.rls.telemetry.url).
        this.telemetryJdbc = telemetryJdbcProvider.getIfAvailable();
    }

    @Override
    public BusinessSnapshot fetch(OffsetDateTime from, OffsetDateTime to) {
        // Prefere o caminho BYPASSRLS dedicado quando disponível (RLS enforce ligado);
        // senão usa o primário (owner bypassa RLS em dev/prod-pré-cutover).
        JdbcTemplate jdbc = telemetryJdbc != null ? telemetryJdbc : primaryJdbc;
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
