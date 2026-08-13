package br.com.nora.api.infrastructure.platform;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Control plane config (ADR 0022/0023/0024). Registered via @ConfigurationPropertiesScan
 * (NoraApiApplication). Default {@code enabled=false} — in local/test/CI the platform module is
 * entirely inert (does not connect to the platform database, does not run Flyway, does not create a
 * datasource).
 */
@ConfigurationProperties("nora.platform")
public class PlatformProperties {

    /** Turns on the platform module (2nd datasource, Flyway, admin/telemetry). Prod = true. */
    private boolean enabled = false;

    /** Service token (worker/BFF → /internal/platform/**). */
    private String internalToken = "";

    /**
     * Admin token (nora-admin → /admin/platform/**). Empty = falls back to internalToken, which
     * means the worker's service credential authenticates the operator console. Warned about in
     * PlatformSecurityConfig's constructor, and refused outright by the production compose, which
     * marks this variable `:?`.
     */
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

    /** Effective token for /admin/** — falls back to internalToken if adminToken is not set. */
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

    /** Platform database connection (PLATFORM_DATASOURCE_*). */
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

    /** Default SOFT fallback config of the resolver (ADR 0024) — used when the catalog fails. */
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
     * Health source (Prometheus HTTP query API, ADR 0034). Empty = health telemetry unavailable —
     * the same degradation the App Insights config had before (the /admin/platform/telemetry/health
     * endpoint still answers 200).
     *
     * <p>No api-key: Prometheus lives on the compose {@code internal} bridge, with no public
     * exposure. The dashboard's authentication is the API's own admin token.
     */
    public static class Health {
        /** Prometheus base URL (e.g. http://prometheus:9090). Empty = unavailable. */
        private String prometheusUrl = "";

        /** ISO-8601 window (e.g. PT1H). Translated into a PromQL range vector. */
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

    /** Business telemetry (c) — cuttable; on by default if the platform is enabled. */
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
