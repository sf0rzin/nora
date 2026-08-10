package br.com.nora.api.infrastructure.platform;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Second datasource — platform database (ADR 0022). Gated by {@code nora.platform.enabled=true}: in
 * local/test/CI none of this is created (Boot's single-datasource autoconfig stays intact).
 *
 * <p><b>Why JdbcTemplate and not a 2nd EntityManagerFactory:</b> the API has no explicit datasource
 * config today; a 2nd EMF would force making the primary one @Primary +
 * segmenting @EnableJpaRepositories, touching the JPA that already runs. Here we expose ONLY a
 * {@link NamedParameterJdbcTemplate} over a dedicated Hikari pool — the {@code HikariDataSource} is
 * <b>not</b> registered as a bean of type {@code DataSource}, so the primary datasource autoconfig
 * does <b>not</b> back off.
 *
 * <p><b>Soft-fail:</b> {@code initializationFailTimeout=-1} makes the pool not validate a
 * connection at boot; the migration runs in an {@link ApplicationRunner} with try/catch. Platform
 * database down ⇒ API comes up in degraded mode, without taking down the customer path.
 */
@Configuration
@ConditionalOnProperty(name = "nora.platform.enabled", havingValue = "true")
public class PlatformDataSourceConfig {

    private static final Logger LOG = LoggerFactory.getLogger(PlatformDataSourceConfig.class);

    private HikariDataSource dataSource;

    @Bean
    public NamedParameterJdbcTemplate platformJdbcTemplate(PlatformProperties props) {
        PlatformProperties.Datasource ds = props.getDatasource();
        HikariConfig cfg = new HikariConfig();
        cfg.setJdbcUrl(ds.getUrl());
        cfg.setUsername(ds.getUsername());
        cfg.setPassword(ds.getPassword());
        cfg.setMaximumPoolSize(ds.getMaxPoolSize());
        cfg.setPoolName("nora-platform-pool");
        cfg.setConnectionTimeout(ds.getConnectionTimeoutMs());
        // -1: does not try to create/validate a connection at startup (lazy pool). Boot does not
        // fail if the platform database is unavailable.
        cfg.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(cfg);
        return new NamedParameterJdbcTemplate(this.dataSource);
    }

    @Bean
    public ApplicationRunner platformFlywayMigrator(
            NamedParameterJdbcTemplate platformJdbcTemplate, PlatformAvailability availability) {
        return args -> {
            DataSource ds = platformJdbcTemplate.getJdbcTemplate().getDataSource();
            try {
                Flyway.configure()
                        .dataSource(ds)
                        .locations("classpath:db/platform")
                        .baselineOnMigrate(true)
                        .load()
                        .migrate();
                availability.markHealthy();
                LOG.info("Control plane: platform database migration OK — module HEALTHY.");
            } catch (RuntimeException ex) {
                availability.markDegraded();
                LOG.error(
                        "Control plane: platform database migration FAILED — module DEGRADED"
                                + " (admin -> 503; llm-config -> fallback env; usage -> discarded)."
                                + " Cause: {}",
                        ex.getMessage());
            }
        };
    }

    @PreDestroy
    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }
}
