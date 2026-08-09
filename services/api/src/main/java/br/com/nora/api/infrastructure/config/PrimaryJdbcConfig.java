package br.com.nora.api.infrastructure.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Explicit {@link JdbcTemplate} over the primary DataSource, named {@code jdbcTemplate}.
 *
 * <p><b>Why it exists:</b> Spring Boot's {@code JdbcTemplateAutoConfiguration} is
 * {@code @ConditionalOnMissingBean(JdbcOperations.class)}. When {@code telemetryJdbcTemplate}
 * ({@link br.com.nora.api.infrastructure.platform.telemetry.TelemetryDataSourceConfig}, ADR
 * 0026/0028) is active, it already is a {@code JdbcOperations} — so the autoconfig does <b>not</b>
 * create the default {@code jdbcTemplate} bean, and {@code PrimaryDbBusinessMetricsSource}
 * ({@code @Qualifier("jdbcTemplate")}) fails at boot on an unsatisfied dependency.
 *
 * <p>By declaring the primary {@code jdbcTemplate} here (with no condition), the bean <b>always</b>
 * exists — with or without the telemetry datasource. The platform/telemetry datasources do not
 * register a bean of type {@code DataSource} (they keep the {@code HikariDataSource} private), so
 * the {@code DataSource} injected here is the primary one, with no ambiguity. {@code @Primary}
 * guarantees that {@code JdbcTemplate} injections without a qualifier resolve to the primary one.
 */
@Configuration
public class PrimaryJdbcConfig {

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
