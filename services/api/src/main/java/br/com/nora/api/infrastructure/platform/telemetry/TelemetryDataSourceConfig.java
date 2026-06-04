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
 * Datasource dedicado (opcional) da telemetria de negócio cross-tenant, ligado no MESMO passo de
 * cutover do RLS enforce (ADR 0019, ADR 0026).
 *
 * <p>Gated por {@code nora.security.rls.telemetry.url} <b>não-vazia</b> ({@link
 * TelemetryConfigured}). Não usamos {@code @ConditionalOnProperty} porque o default em {@code
 * application.yml} é string vazia ({@code ${NORA_TELEMETRY_DATASOURCE_URL:}}) — e
 * {@code @ConditionalOnProperty} sem {@code havingValue} considera "" como presente (matcharia
 * indevidamente). Em local/test/CI e em prod ANTES do cutover, a URL é vazia ⇒ este bean não existe
 * e {@code PrimaryDbBusinessMetricsSource} usa o {@code JdbcTemplate} primário (comportamento
 * atual, owner bypassa RLS).
 *
 * <p>Quando configurado, expõe um {@link JdbcTemplate} {@code telemetryJdbcTemplate} sobre um pool
 * Hikari pequeno, conectando como o role {@code nora_telemetry} (BYPASSRLS). Mesma técnica do
 * {@code PlatformDataSourceConfig}: o {@code HikariDataSource} <b>não</b> é exposto como bean do
 * tipo {@code DataSource}, então o autoconfig do datasource primário não sofre backoff. {@code
 * initializationFailTimeout=-1}: pool lazy, boot não falha se o banco estiver fora.
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
        // -1: pool lazy, não valida conexão no startup. Telemetria fora ⇒ painel cai em
        // snapshot disabled (try/catch no source), sem derrubar a API.
        cfg.setInitializationFailTimeout(-1);
        this.dataSource = new HikariDataSource(cfg);
        LOG.info(
                "Telemetria de negócio: datasource BYPASSRLS dedicado ATIVO (role={}, pool={}). RLS"
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
     * Liga o datasource de telemetria apenas quando {@code nora.security.rls.telemetry.url} está
     * setada e não-vazia. Substitui {@code @ConditionalOnProperty} (que trataria string vazia como
     * presente).
     */
    static final class TelemetryConfigured implements Condition {
        @Override
        public boolean matches(ConditionContext context, AnnotatedTypeMetadata metadata) {
            String url = context.getEnvironment().getProperty("nora.security.rls.telemetry.url");
            return url != null && !url.isBlank();
        }
    }
}
