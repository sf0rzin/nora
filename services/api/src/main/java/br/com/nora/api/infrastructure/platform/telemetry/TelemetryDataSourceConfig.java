package br.com.nora.api.infrastructure.platform.telemetry;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Dedicated (optional) datasource for cross-tenant business telemetry, turned on in the SAME
 * cutover step as RLS enforce (ADR 0019, ADR 0026).
 *
 * <p>Gated by a <b>non-empty</b> {@code nora.security.rls.telemetry.url} ({@link
 * TelemetryConfigured}). We do not use {@code @ConditionalOnProperty} because the default in {@code
 * application.yml} is an empty string ({@code ${NORA_TELEMETRY_DATASOURCE_URL:}}) — and
 * {@code @ConditionalOnProperty} without {@code havingValue} treats "" as present (it would match
 * wrongly). In local/test/CI and in prod BEFORE the cutover, the URL is empty ⇒ this bean does not
 * exist and {@code PrimaryDbBusinessMetricsSource} uses the primary {@code JdbcTemplate} (current
 * behavior, owner bypasses RLS).
 *
 * <p>When configured, it exposes a {@link JdbcTemplate} {@code telemetryJdbcTemplate} over a small
 * Hikari pool, connecting as the {@code nora_telemetry} role (BYPASSRLS). Same technique as {@code
 * PlatformDataSourceConfig}: the {@code HikariDataSource} is <b>not</b> exposed as a bean of type
 * {@code DataSource}, so the primary datasource autoconfig does not back off. {@code
 * initializationFailTimeout=-1}: lazy pool, boot does not fail if the database is down.
 */
@Configuration
@Conditional(TelemetryDataSourceConfig.TelemetryConfigured.class)
public class TelemetryDataSourceConfig {

    private static final Logger LOG = LoggerFactory.getLogger(TelemetryDataSourceConfig.class);

    private HikariDataSource dataSource;

    @Bean
    public JdbcTemplate telemetryJdbcTemplate(TelemetryDatasourceProperties props) {
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(props.getUrl());
        cfg.setUsername(props.getUsername());
        cfg.setPassword(props.getPassword());
        cfg.setMaximumPoolSize(props.getMaxPoolSize());
        cfg.setPoolName("nora-telemetry-pool");
        cfg.setConnectionTimeout(props.getConnectionTimeoutMs());
        cfg.setReadOnly(true);
        // -1: lazy pool, does not validate a connection at startup. Telemetry down ⇒ the dashboard
        // falls back to a disabled snapshot (try/catch in the source), without taking down the API.
        cfg.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(cfg);
        LOG.info(
                "Business telemetry: dedicated BYPASSRLS datasource ACTIVE (role={}, pool={}). RLS"
                        + " enforce-safe (ADR 0026).",
                props.getUsername(),
                props.getMaxPoolSize());
        return new JdbcTemplate(this.dataSource);
    }

    @PreDestroy
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    /**
     * Turns on the telemetry datasource only when {@code nora.security.rls.telemetry.url} is set
     * and non-empty. Replaces {@code @ConditionalOnProperty} (which would treat an empty string as
     * present).
     */
    static final class TelemetryConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String url = context.getEnvironment().getProperty("nora.security.rls.telemetry.url");
            return url != null && !url.isBlank();
        }
    }
}
