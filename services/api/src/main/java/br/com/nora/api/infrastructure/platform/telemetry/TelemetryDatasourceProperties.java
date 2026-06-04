package br.com.nora.api.infrastructure.platform.telemetry;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Conexão dedicada (opcional) para a leitura operador-only de métricas de negócio cross-tenant
 * (telemetria (c), ADR 0024) quando o RLS enforce está ligado (ADR 0019, ADR 0026).
 *
 * <p><b>Por que existe:</b> {@code PrimaryDbBusinessMetricsSource} agrega {@code meeting_analyses}
 * SEM contexto de tenant (COUNT/COUNT DISTINCT cross-tenant). Sob {@code NORA_RLS_ENFORCE=true}, o
 * datasource primário roda como {@code nora_app} (NOBYPASSRLS) e, sem GUC de tenant setado, a policy
 * {@code tenant_isolation} é fail-closed ⇒ a query veria 0 linhas SILENCIOSAMENTE (painel mostraria
 * 0). Para preservar a leitura agregada, esta config aponta para um role {@code nora_telemetry}
 * (BYPASSRLS), provisionado por {@code db/operational/R001__provision_app_roles.sql}.
 *
 * <p><b>Default vazio = desligado:</b> sem {@code url} setada (dev/local/test/CI, ou prod antes do
 * cutover de RLS), {@code PrimaryDbBusinessMetricsSource} cai no {@code JdbcTemplate} primário — o
 * comportamento atual (owner bypassa RLS) segue intacto. Liga-se via {@code
 * NORA_TELEMETRY_DATASOURCE_URL/USERNAME/PASSWORD} no MESMO passo de cutover do enforce.
 */
@ConfigurationProperties("nora.security.rls.telemetry")
public class TelemetryDatasourceProperties {

    /** JDBC URL do banco primário, conectando como o role BYPASSRLS. Vazio = desligado. */
    private String url = "";

    /** Role BYPASSRLS (ex.: nora_telemetry). */
    private String username = "";

    /** Senha do role de telemetria (via Key Vault/secret em prod). */
    private String password = "";

    /** Pool pequeno: telemetria roda no scheduler, baixa concorrência. */
    private int maxPoolSize = 2;

    private long connectionTimeoutMs = 5_000;

    /** True quando há um datasource de telemetria dedicado configurado (url não-vazia). */
    public boolean isConfigured() {
        return url != null && !url.isBlank();
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
