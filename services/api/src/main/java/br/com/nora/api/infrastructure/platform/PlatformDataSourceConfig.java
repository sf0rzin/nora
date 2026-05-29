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
 * Segundo datasource — banco de plataforma (ADR 0022). Gated por {@code nora.platform.enabled=true}:
 * em local/test/CI nada disto é criado (autoconfig single-datasource do Boot fica intacto).
 *
 * <p><b>Por que JdbcTemplate e não um 2º EntityManagerFactory:</b> a API não tem config explícita de
 * datasource hoje; um 2º EMF forçaria tornar o primário @Primary + segmentar @EnableJpaRepositories,
 * mexendo no JPA que já roda. Aqui expomos APENAS um {@link NamedParameterJdbcTemplate} sobre um pool
 * Hikari dedicado — o {@code HikariDataSource} <b>não</b> é registrado como bean do tipo {@code
 * DataSource}, então o autoconfig do datasource primário <b>não</b> sofre backoff.
 *
 * <p><b>Soft-fail:</b> {@code initializationFailTimeout=-1} faz o pool não validar conexão no boot;
 * a migração roda num {@link ApplicationRunner} com try/catch. Banco de plataforma fora ⇒ API sobe
 * em modo degradado, sem derrubar o caminho do cliente.
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
        // -1: não tenta criar/validar conexão no startup (pool lazy). Boot não falha se o banco de
        // plataforma estiver indisponível.
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
                LOG.info("Control plane: migração do banco de plataforma OK — módulo HEALTHY.");
            } catch (RuntimeException ex) {
                availability.markDegraded();
                LOG.error(
                        "Control plane: migração do banco de plataforma FALHOU — módulo DEGRADADO"
                            + " (admin -> 503; llm-config -> fallback env; usage -> descartado)."
                            + " Causa: {}",
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
