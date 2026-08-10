package br.com.nora.api.infrastructure.platform.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Dedicated (optional) connection for the operator-only read of cross-tenant business metrics
 * (telemetry (c), ADR 0024) when RLS enforce is on (ADR 0019, ADR 0026).
 *
 * <p><b>Why it exists:</b> {@code PrimaryDbBusinessMetricsSource} aggregates {@code
 * meeting_analyses} WITHOUT tenant context (cross-tenant COUNT/COUNT DISTINCT). Under {@code
 * NORA_RLS_ENFORCE=true}, the primary datasource runs as {@code nora_app} (NOBYPASSRLS) and, with
 * no tenant GUC set, the {@code tenant_isolation} policy is fail-closed ⇒ the query would SILENTLY
 * see 0 rows (the dashboard would show 0). To preserve the aggregated read, this config points at a
 * {@code nora_telemetry} role (BYPASSRLS), provisioned by {@code
 * db/operational/R001__provision_app_roles.sql}.
 *
 * <p><b>Empty default = off:</b> with no {@code url} set (dev/local/test/CI, or prod before the RLS
 * cutover), {@code PrimaryDbBusinessMetricsSource} falls back to the primary {@code JdbcTemplate} —
 * current behavior (owner bypasses RLS) stays intact. It is turned on via {@code
 * NORA_TELEMETRY_DATASOURCE_URL/USERNAME/PASSWORD} in the SAME enforce cutover step.
 */
@ConfigurationProperties("nora.security.rls.telemetry")
public class TelemetryDatasourceProperties {

    /** JDBC URL of the primary database, connecting as the BYPASSRLS role. Empty = off. */
    private String url = "";

    /** BYPASSRLS role (e.g. nora_telemetry). */
    private String username = "";

    /** Telemetry role password (via Key Vault/secret in prod). */
    private String password = "";

    /** Small pool: telemetry runs on the scheduler, low concurrency. */
    private int maxPoolSize = 2;

    private long connectionTimeoutMs = 5_000;

    /**
     * True when the dedicated telemetry datasource is <b>fully</b> configured.
     *
     * <p>All three fields, not just the url. With only the url set, {@code
     * TelemetryDataSourceConfig} builds a Hikari pool with an empty username, every connection
     * fails lazily, and {@code PrimaryDbBusinessMetricsSource} swallows the failure into a disabled
     * snapshot — the same silently-empty dashboard the telemetry role exists to prevent, reached by
     * a different route.
     */
    public boolean isConfigured() {
        return notBlank(url) && notBlank(username) && notBlank(password);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }

    public int getMaxPoolSize() {
        return maxPoolSize;
    }

    public void setMaxPoolSize(int maxPoolSize) {
        this.maxPoolSize = maxPoolSize;
    }

    public long getConnectionTimeoutMs() {
        return connectionTimeoutMs;
    }

    public void setConnectionTimeoutMs(long connectionTimeoutMs) {
        this.connectionTimeoutMs = connectionTimeoutMs;
    }
}
