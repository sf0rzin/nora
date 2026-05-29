package br.com.nora.api.infrastructure.platform.persistence;

import br.com.nora.api.application.platform.FeatureFlagRepository;
import br.com.nora.api.domain.platform.FeatureFlag;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Adapter JdbcTemplate de feature flags (tabela feature_flags, ADR 0024). */
@Repository
@ConditionalOnProperty(name = "nora.platform.enabled", havingValue = "true")
public class JdbcFeatureFlagRepository implements FeatureFlagRepository {

    private static final RowMapper<FeatureFlag> MAPPER =
            (rs, n) ->
                    new FeatureFlag(
                            rs.getString("key"),
                            rs.getBoolean("enabled"),
                            rs.getString("description"),
                            rs.getString("updated_by"),
                            rs.getObject("updated_at", OffsetDateTime.class));

    private final NamedParameterJdbcTemplate jdbc;

    public JdbcFeatureFlagRepository(NamedParameterJdbcTemplate platformJdbcTemplate) {
        this.jdbc = platformJdbcTemplate;
    }

    @Override
    public List<FeatureFlag> findAll() {
        return jdbc.query(
                "SELECT key, enabled, description, updated_by, updated_at FROM feature_flags ORDER BY"
                    + " key",
                new MapSqlParameterSource(),
                MAPPER);
    }

    @Override
    public Optional<FeatureFlag> findByKey(String key) {
        return jdbc
                .query(
                        "SELECT key, enabled, description, updated_by, updated_at FROM feature_flags"
                            + " WHERE key = :k",
                        new MapSqlParameterSource("k", key),
                        MAPPER)
                .stream()
                .findFirst();
    }
}
