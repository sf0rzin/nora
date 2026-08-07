package br.com.nora.api.infrastructure.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Config do control plane (ADR 0022/0023/0024). Registrada via @ConfigurationPropertiesScan
 * (NoraApiApplication). Default {@code enabled=false} — em local/test/CI o módulo platform fica
 * inteiramente inerte (não conecta no banco de plataforma, não roda Flyway, não cria datasource).
 */
@ConfigurationProperties("nora.platform")
public class PlatformProperties {

    /** Liga o módulo platform (2º datasource, Flyway, admin/telemetria). Prod = true. */
    private boolean enabled = false;

    /** Token de serviço (worker/BFF → /internal/platform/**). */
    private String internalToken = "";

    /** Token de admin (nora-admin → /admin/platform/**). Vazio = cai no internalToken (WARN). */
    private String adminToken = "";

    private final Datasource datasource = new Datasource();
    private final Fallback fallback = new Fallback();
    private final Health health = new Health();
    private final Business business = new Business();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public String getAdminToken() {
        return adminToken;
    }

    public void setAdminToken(String adminToken) {
        this.adminToken = adminToken;
    }

    /** Token efetivo para /admin/** — cai no internalToken se adminToken não setado. */
    public String adminTokenResolved() {
        return adminToken == null || adminToken.isBlank() ? internalToken : adminToken;
    }

    public Datasource getDatasource() {
        return datasource;
    }

    public Fallback getFallback() {
        return fallback;
    }

    public Health getHealth() {
        return health;
    }

    public Business getBusiness() {
        return business;
    }

    /** Conexão do banco de plataforma (PLATFORM_DATASOURCE_*). */
    public static class Datasource {
        private String url = "jdbc:postgresql://localhost:5433/nora_platform";
        private String username = "nora_platform";
        private String password = "nora_platform_dev";
        private int maxPoolSize = 4;
        private long connectionTimeoutMs = 5_000;

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

    /** Config default de fallback SOFT do resolver (ADR 0024) — usada quando o catálogo falha. */
    public static class Fallback {
        private String provider = "openai";
        private String model = "gpt-4o-mini";
        private String baseUrl = "https://api.openai.com/v1";

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }
    }

    /**
     * Fonte de saúde (Prometheus HTTP query API, ADR 0034). Vazio = telemetria health unavailable —
     * mesma degradação que a config do App Insights tinha antes (o endpoint
     * /admin/platform/telemetry/health continua respondendo 200).
     *
     * <p>Sem api-key: o Prometheus vive na bridge {@code internal} do compose, sem exposição
     * pública. A autenticação do painel é o token de admin da própria API.
     */
    public static class Health {
        /** Base URL do Prometheus (ex.: http://prometheus:9090). Vazio = unavailable. */
        private String prometheusUrl = "";

        /** Janela ISO-8601 (ex.: PT1H). Traduzida para range vector do PromQL. */
        private String window = "PT1H";

        public String getPrometheusUrl() {
            return prometheusUrl;
        }

        public void setPrometheusUrl(String prometheusUrl) {
            this.prometheusUrl = prometheusUrl;
        }

        public String getWindow() {
            return window;
        }

        public void setWindow(String window) {
            this.window = window;
        }
    }

    /** Telemetria de negócio (c) — cortável; default ligada se a plataforma estiver enabled. */
    public static class Business {
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }
}
