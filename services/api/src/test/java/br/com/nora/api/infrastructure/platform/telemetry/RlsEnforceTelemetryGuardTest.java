package br.com.nora.api.infrastructure.platform.telemetry;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The guard exists so a half-applied RLS cutover fails loudly instead of producing an operator
 * dashboard that silently reads zero, enforcement that silently does not exist, or a product that
 * silently reads empty. These tests cover each way it can be half applied, in BOTH directions, and
 * the cases where it must stay out of the way.
 */
class RlsEnforceTelemetryGuardTest {

    private static TelemetryDatasourceProperties configuredTelemetry() {
        TelemetryDatasourceProperties p = new TelemetryDatasourceProperties();
        p.setUrl("jdbc:postgresql://postgres:5432/nora");
        p.setUsername("nora_telemetry");
        p.setPassword("irrelevant");
        return p;
    }

    /**
     * A DataSource answering every column the guard asks for, from one mocked ResultSet.
     *
     * <p>One stub serves all three queries because they read disjoint column names; a query the
     * test is not exercising simply reads the neutral values set here. The parameters are named
     * after the database facts they represent rather than after outcomes, so a test reads as a
     * description of a real environment.
     */
    private static DataSource database(
            boolean superuser,
            boolean bypassRls,
            boolean rlsEnabled,
            boolean isOwner,
            boolean forceRls,
            boolean canSelect)
            throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean("rolsuper")).thenReturn(superuser);
        when(rs.getBoolean("rolbypassrls")).thenReturn(bypassRls);
        when(rs.getBoolean("relrowsecurity")).thenReturn(rlsEnabled);
        when(rs.getBoolean("relforcerowsecurity")).thenReturn(forceRls);
        when(rs.getBoolean("is_owner")).thenReturn(isOwner);
        when(rs.getBoolean("can_select")).thenReturn(canSelect);

        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);

        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);

        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        return ds;
    }

    /** The cutover applied correctly: nora_app, RLS enabled on the table, grants in place. */
    private static DataSource cutoverApplied() throws SQLException {
        return database(false, false, true, false, false, true);
    }

    /** Development and CI: the connection owns the tables, so RLS does not apply to it. */
    private static DataSource ownerRole() throws SQLException {
        return database(false, false, true, true, false, true);
    }

    private static DataSource roleReporting(boolean superuser, boolean bypassRls)
            throws SQLException {
        return database(superuser, bypassRls, true, false, false, true);
    }

    // ---------------------------------------------------------------------------------
    // Check 1 — the flag has to parse
    // ---------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"1", "yes", "on", "TRUE!", "sim", ""})
    @DisplayName("a value that is neither true nor false is refused, not guessed")
    void refusesUnrecognisedEnforceValues(String raw) throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(raw, configuredTelemetry(), cutoverApplied());

        assertThatThrownBy(guard::verifyCutoverIsWhole)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("neither 'true' nor 'false'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", " True "})
    @DisplayName("true is accepted in any case, with surrounding whitespace")
    void acceptsTrueRegardlessOfCase(String raw) throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(raw, configuredTelemetry(), cutoverApplied());

        assertThatCode(guard::verifyCutoverIsWhole).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------
    // Check 2 — the runtime role must not bypass RLS
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("enforce on while connected as a superuser: refused")
    void refusesSuperuserRuntimeRole() throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(
                        "true", configuredTelemetry(), roleReporting(true, false));

        assertThatThrownBy(guard::verifyCutoverIsWhole)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BYPASSES");
    }

    @Test
    @DisplayName("enforce on while connected as a BYPASSRLS role: refused")
    void refusesBypassRlsRuntimeRole() throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(
                        "true", configuredTelemetry(), roleReporting(false, true));

        assertThatThrownBy(guard::verifyCutoverIsWhole)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BYPASSES");
    }

    @Test
    @DisplayName("a database that cannot be reached is a warning, not a boot failure")
    void toleratesAnUnreachableDatabase() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection refused"));

        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard("true", configuredTelemetry(), ds);

        assertThatCode(guard::verifyCutoverIsWhole).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------
    // Check 3 — the policies have to be active and the role has to be granted
    // ---------------------------------------------------------------------------------

    /**
     * CREATE POLICY without ENABLE ROW LEVEL SECURITY is enforcement that is configured and never
     * consulted — indistinguishable from working, from inside the application.
     */
    @Test
    @DisplayName("enforce on with the policies not enabled on the table: refused")
    void refusesWhenRowSecurityIsNotEnabled() throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(
                        "true",
                        configuredTelemetry(),
                        database(false, false, false, false, false, true));

        assertThatThrownBy(guard::verifyCutoverIsWhole)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("row-level security is NOT enabled");
    }

    /**
     * Missing R001 grants are reported and NOT fatal, which is the one asymmetry in this class.
     * Grants are provisioning, and provisioning can legitimately land after the bean is built — the
     * pool boots on a health check alone. The test pins the choice so a later "tighten this up"
     * change has to argue with it rather than silently break every deployment whose grants arrive a
     * second late.
     */
    @Test
    @DisplayName("enforce on with the runtime role missing its grants: logged, not refused")
    void doesNotRefuseWhenTheRuntimeRoleCannotReadYet() throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(
                        "true",
                        configuredTelemetry(),
                        database(false, false, true, false, false, false));

        assertThatCode(guard::verifyCutoverIsWhole).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------
    // Check 4 — the telemetry datasource must be complete
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("enforce on, telemetry missing entirely: refused")
    void refusesWhenTelemetryIsAbsent() throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(
                        "true", new TelemetryDatasourceProperties(), cutoverApplied());

        assertThatThrownBy(guard::verifyCutoverIsWhole)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("NORA_TELEMETRY_DATASOURCE_URL");
    }

    @Test
    @DisplayName("a url without a username or password does not count as configured")
    void refusesPartiallyConfiguredTelemetry() throws SQLException {
        TelemetryDatasourceProperties partial = new TelemetryDatasourceProperties();
        partial.setUrl("jdbc:postgresql://postgres:5432/nora");

        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard("true", partial, cutoverApplied());

        assertThatThrownBy(guard::verifyCutoverIsWhole).isInstanceOf(IllegalStateException.class);

        partial.setUsername("nora_telemetry");
        assertThatThrownBy(guard::verifyCutoverIsWhole).isInstanceOf(IllegalStateException.class);

        partial.setPassword("irrelevant");
        assertThatCode(guard::verifyCutoverIsWhole).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------
    // Off — and the half the guard used to ignore entirely
    // ---------------------------------------------------------------------------------

    /**
     * The everyday case: development, CI and production before the cutover all connect as the owner
     * of the tables, which RLS does not apply to. Nothing is required and nothing is refused.
     */
    @Test
    @DisplayName("enforce off while connected as the owner: allowed, telemetry not required")
    void staysOutOfTheWayWhenEnforceIsOff() throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(
                        "false", new TelemetryDatasourceProperties(), ownerRole());

        assertThatCode(guard::verifyCutoverIsWhole).doesNotThrowAnyException();
    }

    /**
     * The cutover applied backwards, and the reason this branch stopped returning early. With
     * enforce off the aspect does not exist, so no GUC is ever set; with the datasource already on
     * a role RLS applies to, every tenant-scoped read matches nothing. The product comes up healthy
     * and completely empty, with no error anywhere — which is why the boot has to refuse.
     */
    @Test
    @DisplayName("enforce off while already connected as an unprivileged role: refused")
    void refusesEnforceOffWithAnAlreadyUnprivilegedRole() throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(
                        "false", new TelemetryDatasourceProperties(), cutoverApplied());

        assertThatThrownBy(guard::verifyCutoverIsWhole)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cutover applied backwards");
    }

    /**
     * A superuser with enforce off is the ordinary local setup and must not be refused: the point
     * of the check above is that RLS APPLIES to the connection, not that the role has a name.
     */
    @Test
    @DisplayName("enforce off while connected as a superuser: allowed")
    void allowsEnforceOffWithASuperuser() throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(
                        "false", new TelemetryDatasourceProperties(), roleReporting(true, false));

        assertThatCode(guard::verifyCutoverIsWhole).doesNotThrowAnyException();
    }

    /** With enforce off, an unreachable database is still a warning and never a boot failure. */
    @Test
    @DisplayName("enforce off with an unreachable database: a warning, not a failure")
    void toleratesAnUnreachableDatabaseWhenEnforceIsOff() throws SQLException {
        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenThrow(new SQLException("connection refused"));

        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard("false", new TelemetryDatasourceProperties(), ds);

        assertThatCode(guard::verifyCutoverIsWhole).doesNotThrowAnyException();
    }
}
