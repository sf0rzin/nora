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
 * Prova de enforcement REAL do RLS completo (V016/V017/V019, ADR 0002/0019/0026).
 *
 * <p><b>Por que este teste é diferente dos outros ITs:</b> o app (e o {@code JdbcTemplate}
 * autowired) conecta como o superuser do container, que <b>bypassa RLS por default</b> — então as
 * policies ficam inertes no caminho normal e os demais ITs seguem sem mudança. Para exercitar o
 * enforce de verdade, este teste:
 *
 * <ol>
 *   <li>deixa o Flyway (via boot do app) criar todas as policies, incluindo as de V019;
 *   <li>semeia 2 tenants com {@code transcripts} e {@code meeting_action_items} via a conexão owner
 *       (que bypassa RLS);
 *   <li>cria um role {@code rls_probe} NOBYPASSRLS, dá grants e abre uma conexão JDBC dedicada como
 *       ele — replicando o que {@code nora_app} faz em prod sob {@code NORA_RLS_ENFORCE=true};
 *   <li>seta o GUC {@code nora.current_tenant_id} = tenant A e afirma que A só enxerga as linhas de
 *       A, nunca as de B (e vice-versa). Cobre {@code transcripts} (raw_text = PII em repouso, a
 *       tabela que V019 fechou com prioridade) e {@code meeting_action_items}.
 * </ol>
 *
 * <p>Sem V019, este teste FALHARIA: {@code transcripts}/{@code meeting_action_items} não tinham
 * policy, então o role NOBYPASSRLS leria tudo cross-tenant.
 */
// RANDOM_PORT (não NONE): o contexto tem SecurityFilterChain beans (SecurityConfig +
// PlatformSecurityConfig) que dependem do HttpSecurity, que só existe num contexto web. Com NONE o
// contexto nem sobe. O teste não faz HTTP — só precisa do app bootado (Flyway cria as policies) +
// JDBC direto como o role NOBYPASSRLS. Alinhado aos demais ITs.
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
     * Semeia dados como owner (bypassa RLS) + cria o role NOBYPASSRLS. Idempotente entre os testes
     * desta classe (flag {@link #seeded}).
     */
    private void seedIfNeeded() {
        if (seeded) {
            return;
        }
        tenantA = UUID.randomUUID();
        tenantB = UUID.randomUUID();

        seedTenant(tenantA, "Tenant A RLS", "acme-rls", "Owner A", "ownera@rls.dev");
        seedTenant(tenantB, "Tenant B RLS", "globex-rls", "Owner B", "ownerb@rls.dev");

        // Role NOBYPASSRLS + grants — espelha o provisionamento de nora_app (R001).
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

    /** Cria tenant + user + meeting + transcript + action_item, tudo via owner (RLS inerte). */
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

        // Owner enxerga as duas (RLS bypass) — sanidade da semeadura.
        assertThat(countAsOwner("transcripts")).isGreaterThanOrEqualTo(2);

        // Role NOBYPASSRLS com GUC = tenant A só enxerga a transcript de A.
        try (Connection conn = probeConnection()) {
            setTenant(conn, tenantA);
            assertThat(countTranscripts(conn)).isEqualTo(1);
            assertThat(rawTextsVisible(conn))
                    .allSatisfy(t -> assertThat(t).contains("Tenant A"))
                    .noneSatisfy(t -> assertThat(t).contains("Tenant B"));

            // Troca de tenant na mesma conexão: agora só vê B.
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

        // Sem GUC setado, o role NOBYPASSRLS não enxerga NENHUMA linha (fail-closed).
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
