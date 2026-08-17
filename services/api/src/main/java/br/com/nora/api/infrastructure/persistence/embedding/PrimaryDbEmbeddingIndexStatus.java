package br.com.nora.api.infrastructure.persistence.embedding;

import br.com.nora.api.application.ports.EmbeddingIndexStatusSource;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

/**
 * Cross-tenant read of how complete the RAG index is, for the operator backfill preview. Aggregates
 * {@code meetings} LEFT JOIN {@code meeting_embeddings} with no tenant context — the same
 * deliberate, operator-only crossing of the tenant boundary that {@code
 * PrimaryDbBusinessMetricsSource} performs (ADR 0024), and for the same reason: the question is
 * about every tenant at once, so it cannot be asked one tenant at a time.
 *
 * <p><b>Which role answers, and why the answer says so.</b> Under {@code NORA_RLS_ENFORCE=true} the
 * primary datasource runs as {@code nora_app} (NOBYPASSRLS) and this read does not go through a
 * {@code @Transactional}, so no GUC is set and the {@code tenant_isolation} policy returns nothing
 * — a silent there-is-nothing-to-backfill that means the opposite. When the dedicated
 * {@code nora_telemetry} datasource (BYPASSRLS, read-only) is configured it answers instead;
 * otherwise the primary template does, where in dev and CI the owner bypasses RLS anyway.
 * {@link #source()} names whichever answered, so a real zero is distinguishable from a fail-closed
 * one.
 */
@Component
public class PrimaryDbEmbeddingIndexStatus implements EmbeddingIndexStatusSource {

    /** The four counters, in the order both row mappers read them. */
    private static final String COUNTERS =
            "COUNT(*) AS analysed,"
                    + " COUNT(*) FILTER (WHERE e.model = ?) AS indexed,"
                    + " COUNT(*) FILTER (WHERE e.meeting_id IS NULL) AS missing,"
                    + " COUNT(*) FILTER (WHERE e.meeting_id IS NOT NULL AND e.model <> ?) AS stale";

    /**
     * A meeting counts as analysed when it carries a summary snippet: only the analysis pipeline
     * ever writes that column, and it is also the exact text the indexing consumes.
     */
    private static final String FROM_ANALYSED_MEETINGS =
            " FROM meetings m LEFT JOIN meeting_embeddings e ON e.meeting_id = m.id"
                    + " WHERE m.summary_snippet IS NOT NULL AND btrim(m.summary_snippet) <> ''";

    private static final RowMapper<Totals> TOTALS_ROW = PrimaryDbEmbeddingIndexStatus::totalsRow;

    private static final RowMapper<TenantIndexStatus> TENANT_ROW =
            PrimaryDbEmbeddingIndexStatus::tenantRow;

    private final JdbcTemplate primaryJdbc;
    private final JdbcTemplate telemetryJdbc;

    public PrimaryDbEmbeddingIndexStatus(
            @Qualifier("jdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("telemetryJdbcTemplate")
                    ObjectProvider<JdbcTemplate> telemetryJdbcProvider) {
        this.primaryJdbc = jdbcTemplate;
        // Optional: present only when the dedicated BYPASSRLS datasource is configured
        // (TelemetryDataSourceConfig, gated by nora.security.rls.telemetry.url).
        this.telemetryJdbc = telemetryJdbcProvider.getIfAvailable();
    }

    @Override
    public String source() {
        return telemetryJdbc != null ? "telemetry" : "primary";
    }

    @Override
    public Totals totals(String modelId) {
        String sql = "SELECT " + COUNTERS + FROM_ANALYSED_MEETINGS;
        Totals totals = jdbc().queryForObject(sql, TOTALS_ROW, modelId, modelId);
        return totals == null ? new Totals(0, 0, 0, 0) : totals;
    }

    @Override
    public List<TenantIndexStatus> byTenant(String modelId, int maxRows) {
        String sql =
                "SELECT m.tenant_id, "
                        + COUNTERS
                        + FROM_ANALYSED_MEETINGS
                        + " GROUP BY m.tenant_id ORDER BY missing DESC, stale DESC LIMIT ?";
        return jdbc().query(sql, TENANT_ROW, modelId, modelId, maxRows);
    }

    private JdbcTemplate jdbc() {
        return telemetryJdbc != null ? telemetryJdbc : primaryJdbc;
    }

    private static Totals totalsRow(ResultSet rs, int rowNum) throws SQLException {
        return new Totals(
                rs.getLong("analysed"),
                rs.getLong("indexed"),
                rs.getLong("missing"),
                rs.getLong("stale"));
    }

    private static TenantIndexStatus tenantRow(ResultSet rs, int rowNum) throws SQLException {
        return new TenantIndexStatus(
                rs.getObject("tenant_id", UUID.class),
                rs.getLong("analysed"),
                rs.getLong("indexed"),
                rs.getLong("missing"),
                rs.getLong("stale"));
    }
}
