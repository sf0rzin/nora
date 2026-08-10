package br.com.nora.api.infrastructure.platform.telemetry;

import jakarta.annotation.PostConstruct;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Locale;
import java.util.Set;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;

/**
 * Refuses to start when the RLS enforce cutover is half applied.
 *
 * <p>The cutover (ADR 0026 / ADR 0028) moves four things at once: the runtime datasource to {@code
 * nora_app} (NOBYPASSRLS), Flyway staying on the owner, the telemetry datasource to {@code
 * nora_telemetry} (BYPASSRLS), and the {@code NORA_RLS_ENFORCE} flag. Every partial combination
 * fails <b>silently</b>, which is why this class exists rather than a paragraph in a runbook.
 *
 * <p>Three checks, in the order they can bite:
 *
 * <ol>
 *   <li><b>The flag parses.</b> {@code NORA_RLS_ENFORCE=1} or {@code =yes} are values Spring
 *       accepts as true in other places, and {@code @ConditionalOnProperty} does not: it compares
 *       with {@code equalsIgnoreCase("true")}. So {@code 1} would leave {@link
 *       br.com.nora.api.infrastructure.security.TenantRlsAspect} switched off — no GUC ever set —
 *       while an operator who set the datasource to {@code nora_app} believes enforcement is on.
 *       Every tenant-scoped read then returns zero rows, fail-closed and silent. Anything that is
 *       not exactly {@code true} or {@code false} is refused here.
 *   <li><b>The connection is not privileged.</b> This is the dangerous half the flag alone cannot
 *       see: {@code NORA_RLS_ENFORCE=true} with the datasource still on the owner or a superuser
 *       means RLS is bypassed outright and <b>everything looks green</b>. `.env.example` names this
 *       exact scenario. Asked of the database rather than inferred from configuration.
 *   <li><b>Telemetry is configured, all three fields.</b> Under enforce the operator console's
 *       cross-tenant aggregate runs with no tenant GUC, so as {@code nora_app} it reads zero rows
 *       with no error and the dashboard shows a quiet week. ADR 0034 §8 calls this "the most
 *       expensive failure mode to diagnose".
 * </ol>
 *
 * <p>Costs nothing when enforce is off: check 1 accepts {@code false} and returns, before anything
 * touches the database.
 *
 * <p><b>If this stops the API from starting</b>, the recovery is in {@code
 * docs/operations/rls-cutover-runbook.md} §"The API refuses to start". It is a configuration
 * failure, so {@code deploy.sh}'s rollback — which reverts image tags — cannot undo it, and the fix
 * needs a shell on the host.
 */
@Configuration
public class RlsEnforceTelemetryGuard {

    private static final Logger LOG = LoggerFactory.getLogger(RlsEnforceTelemetryGuard.class);

    /** The only two values accepted. See check 1 in the class javadoc. */
    private static final Set<String> ACCEPTED = Set.of("true", "false");

    private static final String RUNBOOK = "docs/operations/rls-cutover-runbook.md";

    private final String enforceRaw;
    private final TelemetryDatasourceProperties telemetry;
    private final DataSource dataSource;

    public RlsEnforceTelemetryGuard(
            @Value("${nora.security.rls.enforce:false}") String enforceRaw,
            TelemetryDatasourceProperties telemetry,
            DataSource dataSource) {
        this.enforceRaw = enforceRaw;
        this.telemetry = telemetry;
        this.dataSource = dataSource;
    }

    @PostConstruct
    void verifyCutoverIsWhole() {
        String value = enforceRaw == null ? "false" : enforceRaw.trim().toLowerCase(Locale.ROOT);

        if (!ACCEPTED.contains(value)) {
            throw new IllegalStateException(
                    """
                    NORA_RLS_ENFORCE is '%s', which is neither 'true' nor 'false'.

                    This is refused rather than guessed. The RLS machinery is switched on by \
                    @ConditionalOnProperty(havingValue = "true"), which compares the literal \
                    string — so '1', 'yes' and 'on' switch it OFF while looking like they \
                    switch it on. Combined with a datasource already pointed at nora_app, that \
                    is enforcement that does not exist and reads that return nothing.

                    Set it to exactly 'true' or 'false'. See %s."""
                            .formatted(enforceRaw, RUNBOOK));
        }

        if (value.equals("false")) {
            return;
        }

        verifyRuntimeRoleIsNotPrivileged();
        verifyTelemetryDatasourceIsConfigured();

        LOG.info(
                "RLS enforce is ON: runtime role is unprivileged and the telemetry datasource is"
                        + " configured (role={}).",
                telemetry.getUsername());
    }

    /**
     * Asks the database what the pool actually connected as. A superuser bypasses RLS outright and
     * a BYPASSRLS role does too, so either one means the policies are decorative while every
     * healthcheck stays green — the failure that motivated the whole cutover.
     *
     * <p>A connection error is <b>not</b> treated as a failure: Postgres may legitimately not be up
     * yet at this point in the boot, and refusing to start over that would turn a slow database
     * into an outage. It logs loudly instead, which is the honest trade.
     */
    private void verifyRuntimeRoleIsNotPrivileged() {
        String sql = "select rolsuper, rolbypassrls from pg_roles where rolname = current_user";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            if (!rs.next()) {
                LOG.warn(
                        "RLS enforce is on but current_user was not found in pg_roles — could not"
                                + " verify that the runtime role is unprivileged.");
                return;
            }
            boolean superuser = rs.getBoolean("rolsuper");
            boolean bypassRls = rs.getBoolean("rolbypassrls");
            if (!superuser && !bypassRls) {
                return;
            }
            throw new IllegalStateException(
                    """
                    RLS enforce is ON but the runtime datasource connects as a role that BYPASSES \
                    row-level security (rolsuper=%s, rolbypassrls=%s).

                    Every policy is inert and nothing reports an error: the application looks \
                    exactly as it does with enforcement working. This is the state the cutover \
                    exists to avoid.

                    Point DATASOURCE_USERNAME / DATASOURCE_PASSWORD at nora_app, which is \
                    NOBYPASSRLS and owns nothing. Flyway keeps the owner via SPRING_FLYWAY_USER. \
                    See %s."""
                            .formatted(superuser, bypassRls, RUNBOOK));
        } catch (java.sql.SQLException e) {
            LOG.warn(
                    "RLS enforce is on but the runtime role could not be verified against the"
                            + " database ({}). Continuing: a database that is not up yet must not"
                            + " become a boot failure.",
                    e.getMessage());
        }
    }

    private void verifyTelemetryDatasourceIsConfigured() {
        if (telemetry.isConfigured()) {
            return;
        }
        throw new IllegalStateException(
                """
                RLS enforce is ON but the telemetry datasource is not fully configured.

                Under enforce the API connects as nora_app (NOBYPASSRLS). The operator console's \
                cross-tenant aggregate runs with no tenant context, so RLS returns zero rows with \
                no error and the dashboard silently shows nothing.

                Set all three, to the nora_telemetry role provisioned by \
                db/operational/R001__provision_app_roles.sql:
                  NORA_TELEMETRY_DATASOURCE_URL
                  NORA_TELEMETRY_DATASOURCE_USERNAME
                  NORA_TELEMETRY_DATASOURCE_PASSWORD

                Or set NORA_RLS_ENFORCE=false to roll the cutover back. See %s."""
                        .formatted(RUNBOOK));
    }
}
