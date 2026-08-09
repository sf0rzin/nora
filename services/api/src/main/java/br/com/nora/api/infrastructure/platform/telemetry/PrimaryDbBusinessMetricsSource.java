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
 * Cross-tenant business metrics (telemetry (c), CUTTABLE, ADR 0024). Reads the PRIMARY database,
 * WITHOUT tenant context — an operator-only, intentional aggregation (COUNT/COUNT DISTINCT on
 * {@code meeting_analyses}). Minimal v1: safe counts (analyses + active tenants); averages left
 * null so as not to couple to other modules' schemas. Non-gated bean (does not touch the platform
 * database).
 *
 * <p><b>RLS enforce-safe (ADR 0019, ADR 0026):</b> under {@code NORA_RLS_ENFORCE=true} the primary
 * datasource runs as {@code nora_app} (NOBYPASSRLS). Since this read does NOT go through
 * {@code @Transactional} (so the {@code TenantRlsAspect} does not set the GUC), the {@code
 * tenant_isolation} policy would be fail-closed ⇒ SILENTLY {@code analyses=0/tenants=0}. To avoid
 * that, when a dedicated {@link #telemetryJdbc} is configured ({@code
 * nora.security.rls.telemetry.url}, role {@code nora_telemetry} BYPASSRLS — see {@code
 * db/operational/R001__provision_app_roles.sql}), the aggregation uses THAT path. Without it
 * (dev/local/CI, or prod before the cutover), it falls back to the primary {@code JdbcTemplate},
 * where the owner bypasses RLS — current behavior intact.
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
        // Optional: present only when the dedicated BYPASSRLS datasource is configured
        // (TelemetryDataSourceConfig, gated by nora.security.rls.telemetry.url).
        this.telemetryJdbc = telemetryJdbcProvider.getIfAvailable();
    }

    @Override
    public BusinessSnapshot fetch(OffsetDateTime from, OffsetDateTime to) {
        // Prefers the dedicated BYPASSRLS path when available (RLS enforce on);
        // otherwise uses the primary one (owner bypasses RLS in dev/prod-pre-cutover).
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
