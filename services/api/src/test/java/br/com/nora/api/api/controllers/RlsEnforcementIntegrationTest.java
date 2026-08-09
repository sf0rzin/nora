package br.com.nora.api.api.controllers;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Proof of REAL enforcement of the full RLS (V016/V017/V019, ADR 0002/0019/0026).
 *
 * <p><b>Why this test differs from the other ITs:</b> the app (and the autowired {@code
 * JdbcTemplate}) connects as the container superuser, which <b>bypasses RLS by default</b> — so the
 * policies stay inert on the normal path and the remaining ITs carry on unchanged. To exercise
 * enforcement for real, this test:
 *
 * <ol>
 *   <li>lets Flyway (via the app boot) create all the policies, including those from V019;
 *   <li>seeds 2 tenants with {@code transcripts} and {@code meeting_action_items} through the owner
 *       connection (which bypasses RLS);
 *   <li>creates a NOBYPASSRLS {@code rls_probe} role, gives it grants and opens a dedicated JDBC
 *       connection as it — replicating what {@code nora_app} does in prod under {@code
 *       NORA_RLS_ENFORCE=true};
 *   <li>sets the GUC {@code nora.current_tenant_id} = tenant A and asserts that A only sees A's
 *       rows, never B's (and vice versa). Covers {@code transcripts} (raw_text = PII at rest, the
 *       table V019 closed as a priority) and {@code meeting_action_items}.
 * </ol>
 *
 * <p>Without V019, this test WOULD FAIL: {@code transcripts}/{@code meeting_action_items} had no
 * policy, so the NOBYPASSRLS role would read everything cross-tenant.
 */
// RANDOM_PORT (not NONE): the context has SecurityFilterChain beans (SecurityConfig +
// PlatformSecurityConfig) that depend on HttpSecurity, which only exists in a web context. With
// NONE the context does not even come up. The test does no HTTP — it only needs the app booted
// (Flyway creates the policies) + direct JDBC as the NOBYPASSRLS role. Aligned with the other ITs.
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Testcontainers
class RlsEnforcementIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("nora")
                    .withUsername("nora")
                    .withPassword("nora_dev");

    @DynamicPropertySource
    static void registerProps(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    private static final String PROBE_ROLE = "rls_probe";
    private static final String PROBE_PASSWORD = "rls_probe_pwd";

    @Autowired JdbcTemplate jdbc;

    private static UUID tenantA;
    private static UUID tenantB;
    private static boolean seeded;

    @BeforeAll
    static void resetState() {
        seeded = false;
    }

    /**
     * Seeds data as owner (bypasses RLS) + creates the NOBYPASSRLS role. Idempotent across the
     * tests in this class (flag {@link #seeded}).
     */
    private void seedIfNeeded() {
        if (seeded) {
            return;
        }
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        seedTenant(tenantA, "Tenant A RLS", "acme-rls", "Owner A", "ownera@rls.dev");
        seedTenant(tenantB, "Tenant B RLS", "globex-rls", "Owner B", "ownerb@rls.dev");

        // NOBYPASSRLS role + grants — mirrors the provisioning of nora_app (R001).
        jdbc.execute("DROP ROLE IF EXISTS " + PROBE_ROLE);
        jdbc.execute(
                "CREATE ROLE "
                        + PROBE_ROLE
                        + " WITH LOGIN PASSWORD '"
                        + PROBE_PASSWORD
                        + "' NOBYPASSRLS");
        jdbc.execute("GRANT USAGE ON SCHEMA public TO " + PROBE_ROLE);
        jdbc.execute("GRANT USAGE ON SCHEMA nora TO " + PROBE_ROLE);
        jdbc.execute("GRANT EXECUTE ON FUNCTION nora.current_tenant_id() TO " + PROBE_ROLE);
        jdbc.execute(
                "GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO "
                        + PROBE_ROLE);

        seeded = true;
    }

    /** Creates tenant + user + meeting + transcript + action_item, all via owner (RLS inert). */
    private void seedTenant(
            UUID tenantId, String tenantName, String slug, String ownerName, String ownerEmail) {
        jdbc.update(
                "INSERT INTO tenants (id, name, slug) VALUES (?, ?, ?)",
                tenantId,
                tenantName,
                slug);
        UUID ownerId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO users (id, tenant_id, email, password_hash, display_name)"
                        + " VALUES (?, ?, ?, 'x', ?)",
                ownerId,
                tenantId,
                ownerEmail,
                ownerName);
        UUID meetingId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO meetings (id, tenant_id, owner_user_id, title, transcript_format)"
                        + " VALUES (?, ?, ?, ?, 'TXT')",
                meetingId,
                tenantId,
                ownerId,
                "Reuniao " + tenantName);
        jdbc.update(
                "INSERT INTO transcripts (id, meeting_id, tenant_id, format, raw_text, char_count)"
                        + " VALUES (?, ?, ?, 'TXT', ?, 10)",
                UUID.randomUUID(),
                meetingId,
                tenantId,
                "PII secreta do " + tenantName);
        UUID analysisId = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO meeting_analyses (id, meeting_id, tenant_id, summary, sentiment_overall)"
                        + " VALUES (?, ?, ?, 'resumo', 'NEUTRAL')",
                analysisId,
                meetingId,
                tenantId);
        jdbc.update(
                "INSERT INTO meeting_action_items"
                        + " (id, analysis_id, tenant_id, title, priority, source_quote, position)"
                        + " VALUES (?, ?, ?, ?, 'MEDIUM', 'quote', 0)",
                UUID.randomUUID(),
                analysisId,
                tenantId,
                "Tarefa do " + tenantName);
    }

    @Test
    void transcriptsIsolatedUnderEnforce() throws Exception {
        seedIfNeeded();

        // Owner sees both (RLS bypass) — sanity check on the seeding.
        assertThat(countAsOwner("transcripts")).isGreaterThanOrEqualTo(2);

        // NOBYPASSRLS role with GUC = tenant A only sees A's transcript.
        try (Connection conn = probeConnection()) {
            setTenant(conn, tenantA);
            assertThat(countTranscripts(conn)).isEqualTo(1);
            assertThat(rawTextsVisible(conn))
                    .allSatisfy(t -> assertThat(t).contains("Tenant A"))
                    .noneSatisfy(t -> assertThat(t).contains("Tenant B"));

            // Tenant switch on the same connection: now it only sees B.
            setTenant(conn, tenantB);
            assertThat(countTranscripts(conn)).isEqualTo(1);
            assertThat(rawTextsVisible(conn)).allSatisfy(t -> assertThat(t).contains("Tenant B"));
        }
    }

    @Test
    void actionItemsIsolatedUnderEnforce() throws Exception {
        seedIfNeeded();

        try (Connection conn = probeConnection()) {
            setTenant(conn, tenantA);
            assertThat(countActionItems(conn)).isEqualTo(1);

            setTenant(conn, tenantB);
            assertThat(countActionItems(conn)).isEqualTo(1);
        }
    }

    @Test
    void noTenantGucIsFailClosed() throws Exception {
        seedIfNeeded();

        // With no GUC set, the NOBYPASSRLS role sees NO rows at all (fail-closed).
        try (Connection conn = probeConnection()) {
            assertThat(countTranscripts(conn)).isZero();
            assertThat(countActionItems(conn)).isZero();
        }
    }

    /* ---------- helpers ---------- */

    private Connection probeConnection() throws Exception {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), PROBE_ROLE, PROBE_PASSWORD);
    }

    private void setTenant(Connection conn, UUID tenantId) throws Exception {
        try (PreparedStatement ps =
                conn.prepareStatement("SELECT set_config('nora.current_tenant_id', ?, false)")) {
            ps.setString(1, tenantId.toString());
            ps.executeQuery().close();
        }
    }

    private long countTranscripts(Connection conn) throws Exception {
        return countOn(conn, "transcripts");
    }

    private long countActionItems(Connection conn) throws Exception {
        return countOn(conn, "meeting_action_items");
    }

    private long countOn(Connection conn, String table) throws Exception {
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private List<String> rawTextsVisible(Connection conn) throws Exception {
        List<String> out = new ArrayList<>();
        try (Statement st = conn.createStatement();
                ResultSet rs = st.executeQuery("SELECT raw_text FROM transcripts")) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        }
        return out;
    }

    private long countAsOwner(String table) {
        Long c = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        return c == null ? 0 : c;
    }
}
