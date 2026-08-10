package br.com.nora.api.infrastructure.platform.telemetry;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
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
 * dashboard that silently reads zero, or enforcement that silently does not exist. These tests
 * cover each way it can be half applied, and the case where it must stay out of the way.
 */
class RlsEnforceTelemetryGuardTest {

    private static TelemetryDatasourceProperties configuredTelemetry() {
        TelemetryDatasourceProperties p = new TelemetryDatasourceProperties();
        p.setUrl("jdbc:postgresql://postgres:5432/nora");
        p.setUsername("nora_telemetry");
        p.setPassword("irrelevant");
        return p;
    }

    /** A DataSource whose current_user reports the given privilege bits. */
    private static DataSource roleReporting(boolean superuser, boolean bypassRls)
            throws SQLException {
        ResultSet rs = mock(ResultSet.class);
        when(rs.next()).thenReturn(true);
        when(rs.getBoolean("rolsuper")).thenReturn(superuser);
        when(rs.getBoolean("rolbypassrls")).thenReturn(bypassRls);

        PreparedStatement ps = mock(PreparedStatement.class);
        when(ps.executeQuery()).thenReturn(rs);

        Connection c = mock(Connection.class);
        when(c.prepareStatement(anyString())).thenReturn(ps);

        DataSource ds = mock(DataSource.class);
        when(ds.getConnection()).thenReturn(c);
        return ds;
    }

    private static DataSource unprivilegedRole() throws SQLException {
        return roleReporting(false, false);
    }

    // ---------------------------------------------------------------------------------
    // Check 1 — the flag has to parse
    // ---------------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"1", "yes", "on", "TRUE!", "sim", ""})
    @DisplayName("a value that is neither true nor false is refused, not guessed")
    void refusesUnrecognisedEnforceValues(String raw) throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(raw, configuredTelemetry(), unprivilegedRole());

        assertThatThrownBy(guard::verifyCutoverIsWhole)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("neither 'true' nor 'false'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", " True "})
    @DisplayName("true is accepted in any case, with surrounding whitespace")
    void acceptsTrueRegardlessOfCase(String raw) throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(raw, configuredTelemetry(), unprivilegedRole());

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
    // Check 3 — the telemetry datasource must be complete
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("enforce on, telemetry missing entirely: refused")
    void refusesWhenTelemetryIsAbsent() throws SQLException {
        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(
                        "true", new TelemetryDatasourceProperties(), unprivilegedRole());

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
                new RlsEnforceTelemetryGuard("true", partial, unprivilegedRole());

        assertThatThrownBy(guard::verifyCutoverIsWhole).isInstanceOf(IllegalStateException.class);

        partial.setUsername("nora_telemetry");
        assertThatThrownBy(guard::verifyCutoverIsWhole).isInstanceOf(IllegalStateException.class);

        partial.setPassword("irrelevant");
        assertThatCode(guard::verifyCutoverIsWhole).doesNotThrowAnyException();
    }

    // ---------------------------------------------------------------------------------
    // Off
    // ---------------------------------------------------------------------------------

    @Test
    @DisplayName("enforce off: nothing is required and the database is never touched")
    void staysOutOfTheWayWhenEnforceIsOff() {
        DataSource neverUsed = mock(DataSource.class);

        RlsEnforceTelemetryGuard guard =
                new RlsEnforceTelemetryGuard(
                        "false", new TelemetryDatasourceProperties(), neverUsed);

        assertThatCode(guard::verifyCutoverIsWhole).doesNotThrowAnyException();
        // Not decoration: with enforce off the guard must not open a connection at boot, or a
        // database that is slow to come up becomes a slow start for every deployment.
        verifyNoInteractions(neverUsed);
    }
}
