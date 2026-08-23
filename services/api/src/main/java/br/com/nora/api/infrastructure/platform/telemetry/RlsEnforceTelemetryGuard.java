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
 * <p>Five checks, in the order they can bite:
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
 *   <li><b>The policies are active and the role is granted.</b> {@code CREATE POLICY} without
 *       {@code ENABLE ROW LEVEL SECURITY} is enforcement nothing consults; missing R001 grants are
 *       a green boot followed by {@code permission denied} on the first query. Only the database
 *       can answer either one.
 *   <li><b>Telemetry is configured, all three fields.</b> Under enforce the operator console's
 *       cross-tenant aggregate runs with no tenant GUC, so as {@code nora_app} it reads zero rows
 *       with no error and the dashboard shows a quiet week. ADR 0034 §8 calls this "the most
 *       expensive failure mode to diagnose".
 *   <li><b>Enforce OFF has its own dangerous half.</b> A datasource already moved to {@code
 *       nora_app} while the flag is still {@code false} means the aspect does not exist, the GUC is
 *       never set, and every tenant-scoped read matches nothing: the whole product comes up empty
 *       with no error at all. This is the mirror image of check 2 and it used to be missed entirely
 *       — the guard returned on {@code false} before asking the database anything.
 * </ol>
 *
 * <p>When enforce is off the guard asks the database ONE question — whether row-level security
 * would apply to this connection — and requires nothing else. Development, CI and production before
 * the cutover connect as the owner of the tables, which RLS does not apply to, so the answer is no
 * and the boot proceeds.
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

    /**
     * The table the database-side checks are asked about. {@code meetings} because it has carried
     * {@code ENABLE ROW LEVEL SECURITY} plus a {@code tenant_isolation} policy since V016, it is
     * the busiest tenant-owned table in the product, and it is granted to {@code nora_app} by R001
     * — so one table answers all three questions the guard has. The name is inlined into the SQL
     * rather than bound as a parameter because it is a constant of this class, never input.
     */
    private static final String PROBE_TABLE = "meetings";

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
            verifyRuntimeRoleIsNotAlreadyUnprivileged();
            return;
        }

        verifyRuntimeRoleIsNotPrivileged();
        verifyPoliciesAreActiveForTheRuntimeRole();
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

    /**
     * The mirror image of {@link #verifyRuntimeRoleIsNotPrivileged}, and the half this class was
     * missing.
     *
     * <p>With {@code NORA_RLS_ENFORCE=false} the {@code TenantRlsAspect} bean does not exist at all
     * — it is {@code @ConditionalOnProperty(havingValue = "true")} — so nothing ever runs {@code
     * SET LOCAL nora.current_tenant_id}. If the datasource has ALREADY been moved to a role that
     * RLS applies to, every policy of the form {@code USING (tenant_id = nora.current_tenant_id())}
     * evaluates against a null GUC and returns zero rows. The product comes up healthy and
     * completely empty: no meetings, no tasks, no analyses, and not one error anywhere. The runbook
     * orders both variables changed together and its rollback section warns about exactly this
     * state, but "the operator followed the instructions" is not a mechanism, and this was the one
     * combination the guard did not look at.
     *
     * <p><b>Why it asks the database instead of comparing role names.</b> "Is this connection
     * subject to RLS" is not a property of a name. A superuser is exempt, a {@code BYPASSRLS} role
     * is exempt, and — the case that matters here — a table's OWNER is exempt unless the table
     * carries {@code FORCE ROW LEVEL SECURITY}. Development and CI connect as the owner, so a naive
     * "is this role unprivileged" test would refuse to start every local environment. The query
     * below reproduces Postgres's own rule over a table that has had RLS enabled since V016, which
     * is why it says yes exactly when the danger is real.
     *
     * <p>A connection error is logged rather than fatal, for the same reason as in the enforce
     * branch: a database that is not up yet must not become a boot failure.
     */
    private void verifyRuntimeRoleIsNotAlreadyUnprivileged() {
        String sql =
                "select r.rolsuper, r.rolbypassrls, c.relrowsecurity, c.relforcerowsecurity,"
                        + " pg_catalog.pg_get_userbyid(c.relowner) = current_user as is_owner"
                        + " from pg_class c, pg_roles r"
                        + " where c.relname = '"
                        + PROBE_TABLE
                        + "' and c.relnamespace = 'public'::regnamespace"
                        + " and r.rolname = current_user";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            if (!rs.next()) {
                // Before the first migration the probe table does not exist yet. Nothing to
                // conclude, and refusing here would break a first boot against an empty database.
                return;
            }
            boolean exempt =
                    rs.getBoolean("rolsuper")
                            || rs.getBoolean("rolbypassrls")
                            || (rs.getBoolean("is_owner") && !rs.getBoolean("relforcerowsecurity"))
                            || !rs.getBoolean("relrowsecurity");
            if (exempt) {
                return;
            }
            throw new IllegalStateException(
                    """
                    NORA_RLS_ENFORCE is 'false' but the runtime datasource already connects as a \
                    role that row-level security APPLIES to.

                    This is the cutover applied backwards, and it fails silently in the worst \
                    possible way. With enforce off, TenantRlsAspect is not even instantiated, so \
                    nora.current_tenant_id is never set — and every policy comparing against it \
                    matches nothing. The API starts, answers 200 to everything, and shows an \
                    empty product: no meetings, no tasks, no analyses, no error.

                    Move BOTH together, in either direction: either set NORA_RLS_ENFORCE=true, or \
                    point DATASOURCE_USERNAME / DATASOURCE_PASSWORD back at the owner. See %s."""
                            .formatted(RUNBOOK));
        } catch (java.sql.SQLException e) {
            LOG.warn(
                    "RLS enforce is off and the runtime role could not be verified against the"
                            + " database ({}). Continuing: a database that is not up yet must not"
                            + " become a boot failure.",
                    e.getMessage());
        }
    }

    /**
     * Two things only the database can answer, and both of them fail closed and silent.
     *
     * <p>The first is whether the policies are actually ACTIVE on the table. {@code CREATE POLICY}
     * without {@code ALTER TABLE ... ENABLE ROW LEVEL SECURITY} creates a policy that is never
     * consulted — the migrations do both, but the guard's job is to check the database in front of
     * it, not the migrations in the repository.
     *
     * <p>The second is whether the runtime role has the GRANTs {@code R001} hands out. Under
     * enforce the API is a non-owner, so without them every query fails at runtime with {@code
     * permission denied} — after a boot that looked entirely healthy. Asking here turns a
     * production incident into a refusal to start, which is the trade this whole class makes.
     */
    private void verifyPoliciesAreActiveForTheRuntimeRole() {
        String sql =
                "select c.relrowsecurity,"
                        + " has_table_privilege(current_user, c.oid, 'SELECT') as can_select"
                        + " from pg_class c where c.relname = '"
                        + PROBE_TABLE
                        + "' and c.relnamespace = 'public'::regnamespace";
        try (Connection c = dataSource.getConnection();
                PreparedStatement ps = c.prepareStatement(sql);
                ResultSet rs = ps.executeQuery()) {

            if (!rs.next()) {
                LOG.warn(
                        "RLS enforce is on but table {} was not found — could not verify that the"
                                + " policies are enabled or that the runtime role has its grants.",
                        PROBE_TABLE);
                return;
            }
            boolean rlsActive = rs.getBoolean("relrowsecurity");
            boolean canSelect = rs.getBoolean("can_select");
            if (!canSelect) {
                // Loud, but not fatal, and the asymmetry is deliberate. Grants are provisioning
                // rather than schema, and provisioning can legitimately land after this bean is
                // constructed — the pool only needs to answer a health check to boot. Refusing
                // here would turn a recoverable ordering into a container that cannot start, so
                // the operator gets the exact diagnosis they would otherwise chase through a 500.
                LOG.error(
                        "RLS enforce is ON but the runtime role has no SELECT on {}. Every query"
                                + " will fail with 'permission denied' (SQLSTATE 42501) while the boot"
                                + " looks healthy. Re-run"
                                + " db/operational/R001__provision_app_roles.sql as the database"
                                + " owner. See {}.",
                        PROBE_TABLE,
                        RUNBOOK);
            }
            if (rlsActive) {
                return;
            }
            throw new IllegalStateException(
                    """
                    RLS enforce is ON but row-level security is NOT enabled on table %s.

                    The tenant_isolation policies exist and are never consulted: CREATE POLICY \
                    without ALTER TABLE ... ENABLE ROW LEVEL SECURITY is enforcement that is \
                    configured and not applied, and from inside the application it is \
                    indistinguishable from enforcement that works.

                    Confirm the migrations up to V019/V020 have been applied to this database. \
                    See %s."""
                            .formatted(PROBE_TABLE, RUNBOOK));
        } catch (java.sql.SQLException e) {
            LOG.warn(
                    "RLS enforce is on but the policies and grants could not be verified against"
                            + " the database ({}). Continuing: a database that is not up yet must"
                            + " not become a boot failure.",
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
