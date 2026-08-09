package br.com.nora.api.infrastructure.platform.persistence;

import br.com.nora.api.application.platform.LlmModelRepository;
import br.com.nora.api.domain.platform.LlmModel;
import br.com.nora.api.domain.platform.Modality;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** JdbcTemplate adapter for the model catalog (platform database, ADR 0022). */
@Repository
@ConditionalOnProperty(name = "nora.platform.enabled", havingValue = "true")
public class JdbcLlmModelRepository implements LlmModelRepository {

    private static final String COLS =
            "id, provider, model_id, display_name, base_url, modality, supports_strict_json_schema,"
                    + " price_input_per_mtok, price_output_per_mtok, price_cached_input_per_mtok, enabled,"
                    + " created_at, updated_at";

    private static final RowMapper<LlmModel> MAPPER =
            (rs, n) ->
                    new LlmModel(
                            rs.getObject("id", UUID.class),
                            rs.getString("provider"),
                            rs.getString("model_id"),
                            rs.getString("display_name"),
                            rs.getString("base_url"),
                            Modality.fromWire(rs.getString("modality")),
                            rs.getBoolean("supports_strict_json_schema"),
                            rs.getBigDecimal("price_input_per_mtok"),
                            rs.getBigDecimal("price_output_per_mtok"),
                            rs.getBigDecimal("price_cached_input_per_mtok"),
                            rs.getBoolean("enabled"),
                            rs.getObject("created_at", OffsetDateTime.class),
                            rs.getObject("updated_at", OffsetDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcLlmModelRepository(NamedParameterJdbcTemplate platformJdbcTemplate) {
        this.jdbc = platformJdbcTemplate;
    }

    @Override
    public List<LlmModel> findAll() {
        return jdbc.query(
                "SELECT " + COLS + " FROM llm_models ORDER BY provider, model_id",
                new MapSqlParameterSource(),
                MAPPER);
    }

    @Override
    public Optional<LlmModel> findById(UUID id) {
        return jdbc
                .query(
                        "SELECT " + COLS + " FROM llm_models WHERE id = :id",
                        new MapSqlParameterSource("id", id),
                        MAPPER)
                .stream()
                .findFirst();
    }

    @Override
    public Optional<LlmModel> findByProviderAndModel(String provider, String modelId) {
        return jdbc
                .query(
                        "SELECT " + COLS + " FROM llm_models WHERE provider = :p AND model_id = :m",
                        new MapSqlParameterSource().addValue("p", provider).addValue("m", modelId),
                        MAPPER)
                .stream()
                .findFirst();
    }

    @Override
    public LlmModel insert(LlmModel m) {
        MapSqlParameterSource params =
                new MapSqlParameterSource()
                        .addValue("provider", m.provider())
                        .addValue("modelId", m.modelId())
                        .addValue("displayName", m.displayName())
                        .addValue("baseUrl", m.baseUrl())
                        .addValue("modality", m.modality().wire())
                        .addValue("strict", m.supportsStrictJsonSchema())
                        .addValue("pin", m.priceInputPerMTok())
                        .addValue("pout", m.priceOutputPerMTok())
                        .addValue("pcached", m.priceCachedInputPerMTok())
                        .addValue("enabled", m.enabled());
        return jdbc.queryForObject(
                "INSERT INTO llm_models (provider, model_id, display_name, base_url, modality,"
                        + " supports_strict_json_schema, price_input_per_mtok, price_output_per_mtok,"
                        + " price_cached_input_per_mtok, enabled) VALUES (:provider, :modelId,"
                        + " :displayName, :baseUrl, :modality, :strict, :pin, :pout, :pcached, :enabled)"
                        + " RETURNING "
                        + COLS,
                params,
                MAPPER);
    }

    @Override
    public void deleteById(UUID id) {
        jdbc.update("DELETE FROM llm_models WHERE id = :id", new MapSqlParameterSource("id", id));
    }

    @Override
    public boolean isBound(UUID modelId) {
        Boolean bound =
                jdbc.queryForObject(
                        "SELECT EXISTS(SELECT 1 FROM llm_config WHERE model_id = :id)",
                        new MapSqlParameterSource("id", modelId),
                        Boolean.class);
        return Boolean.TRUE.equals(bound);
    }
}
