package br.com.nora.api.infrastructure.platform.persistence;

import br.com.nora.api.application.platform.LlmConfigRepository;
import br.com.nora.api.domain.platform.ResolvedLlmConfig;
import br.com.nora.api.domain.platform.ServiceBinding;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate adapter for the service→model bindings (llm_config table, ADR 0024). */
@Repository
@ConditionalOnProperty(name = "nora.platform.enabled", havingValue = "true")
public class JdbcLlmConfigRepository implements LlmConfigRepository {

    private static final RowMapper<ServiceBinding> MAPPER =
            (rs, n) ->
                    new ServiceBinding(
                            rs.getString("service"),
                            rs.getObject("model_id", UUID.class),
                            rs.getBoolean("enabled"),
                            rs.getString("updated_by"),
                            rs.getObject("updated_at", OffsetDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLlmConfigRepository(NamedParameterJdbcTemplate platformJdbcTemplate) {
        this.jdbc = platformJdbcTemplate;
    }

    @Override
    public List<ServiceBinding> findAll() {
        return jdbc.query(
                "SELECT service, model_id, enabled, updated_by, updated_at FROM llm_config ORDER BY"
                        + " service",
                new MapSqlParameterSource(),
                MAPPER);
    }

    @Override
    public Optional<ServiceBinding> findByService(String service) {
        return jdbc
                .query(
                        "SELECT service, model_id, enabled, updated_by, updated_at FROM llm_config"
                                + " WHERE service = :s",
                        new MapSqlParameterSource("s", service),
                        MAPPER)
                .stream()
                .findFirst();
    }

    @Override
    public ServiceBinding upsert(String service, UUID modelId, boolean enabled, String updatedBy) {
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("service", service)
                        .addValue("modelId", modelId)
                        .addValue("enabled", enabled)
                        .addValue("by", updatedBy);
        return jdbc.queryForObject(
                "INSERT INTO llm_config (service, model_id, enabled, updated_by) VALUES (:service,"
                        + " :modelId, :enabled, :by) ON CONFLICT (service) DO UPDATE SET model_id ="
                        + " EXCLUDED.model_id, enabled = EXCLUDED.enabled, updated_by ="
                        + " EXCLUDED.updated_by, updated_at = NOW() RETURNING service, model_id, enabled,"
                        + " updated_by, updated_at",
                params,
                MAPPER);
    }

    @Override
    public Optional<ResolvedLlmConfig> resolveActive(String service) {
        return jdbc
                .query(
                        "SELECT m.provider AS provider, m.model_id AS model, m.base_url AS base_url,"
                                + " (c.enabled AND m.enabled) AS enabled FROM llm_config c JOIN llm_models"
                                + " m ON m.id = c.model_id WHERE c.service = :s",
                        new MapSqlParameterSource("s", service),
                        (rs, n) ->
                                new ResolvedLlmConfig(
                                        rs.getString("provider"),
                                        rs.getString("model"),
                                        rs.getString("base_url"),
                                        rs.getBoolean("enabled")))
                .stream()
                .findFirst();
    }
}
