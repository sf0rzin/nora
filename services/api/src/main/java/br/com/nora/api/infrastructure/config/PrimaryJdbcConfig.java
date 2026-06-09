package br.com.nora.api.infrastructure.config;

import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * {@link JdbcTemplate} explícito sobre o DataSource primário, nomeado {@code jdbcTemplate}.
 *
 * <p><b>Por que existe:</b> o {@code JdbcTemplateAutoConfiguration} do Spring Boot é
 * {@code @ConditionalOnMissingBean(JdbcOperations.class)}. Quando o {@code telemetryJdbcTemplate}
 * ({@link br.com.nora.api.infrastructure.platform.telemetry.TelemetryDataSourceConfig}, ADR
 * 0026/0028) está ativo, ele já é um {@code JdbcOperations} — então o autoconfig <b>não</b> cria o
 * bean {@code jdbcTemplate} default, e o {@code PrimaryDbBusinessMetricsSource}
 * ({@code @Qualifier("jdbcTemplate")}) falha no boot por dependência insatisfeita.
 *
 * <p>Declarando o {@code jdbcTemplate} primário aqui (sem condição), o bean existe <b>sempre</b> —
 * com ou sem o datasource de telemetria. Os datasources de plataforma/telemetria não registram bean
 * do tipo {@code DataSource} (mantêm o {@code HikariDataSource} privado), então o {@code
 * DataSource} injetado aqui é o primário, sem ambiguidade. {@code @Primary} garante que injeções de
 * {@code JdbcTemplate} sem qualifier resolvam para o primário.
 */
@Configuration
public class PrimaryJdbcConfig {

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
