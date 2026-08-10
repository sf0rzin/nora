# 0026 — Complete RLS, versioned role provisioning and enforce cutover

- Status: partially superseded by 0028 (the enforce design + the cutover sequence were fixed in ADR 0028 after 3 holes were found; the V019 and the R001 script from this ADR remain valid)
- Date: 2026-06-04
- Related: ADR 0002 (multi-tenancy), ADR 0019 (tenant isolation in depth: RLS + composite FK), ADR 0024 (business telemetry)

## Context

ADR 0019 delivered Postgres RLS as defense in depth for the `tenant_id` filter, but with **partial coverage**: `V016` enabled `ENABLE ROW LEVEL SECURITY` + `POLICY tenant_isolation` on 12 tables and `V017` on 3 more (customer confidence). A schema audit (2026-06-04) found **~19 tenant-owned tables still WITHOUT a policy**, including:

- **`transcripts`** (V004) — stores `raw_text` = raw transcription = **PII at rest**;
- analysis children: `meeting_decisions`, `meeting_action_items`, `meeting_risks`, `meeting_opportunities` (V005);
- `meeting_tags` (V004), `meeting_goals`, `meeting_productivity_assessments` (V012);
- IAM: `iam_user_groups`, `iam_group_policies`, `iam_user_policies`, `iam_policy_versions`, `iam_audit_events` (V006);
- auth tokens: `email_verification_tokens`, `password_reset_tokens` (V003).

In Postgres, a tenant-owned table **without** `ENABLE ROW LEVEL SECURITY` is fully open to the connected role. That is: if RLS enforce were turned on today (role `nora_app` NOBYPASSRLS), the tables with a policy would be isolated, but those **without** a policy would remain readable cross-tenant. The worst case is exactly `transcripts` — turning enforce on would protect `meetings` but leave the raw PII leaking between tenants. Coverage must be complete **before** any cutover.

Three additional couplings were identified:

1. **Unversioned role.** The provisioning of the `nora_app` role (CREATE ROLE + GRANTs + NOBYPASSRLS) existed only as a **comment** in `V016` — it was neither reproducible nor versioned.
2. **Fail-closed telemetry.** `PrimaryDbBusinessMetricsSource` (operator-only business telemetry, ADR 0024) aggregates `meeting_analyses` with `COUNT(*)` / `COUNT(DISTINCT tenant_id)` **without tenant context** and **outside `@Transactional`** — therefore the `TenantRlsAspect` does not set the `nora.current_tenant_id` GUC. Under enforce, the fail-closed policy would hide **all** rows ⇒ the dashboard would show `analyses=0 / tenants=0` **silently** (no error).
3. **Cutover sequence.** Turning enforce on involves role + grants + connection string + flag + telemetry, **in that order**. Without a runbook, it is easy to flip the flag before the role and take the product down.

## Decision

### 1. Complete RLS coverage (`V019`)

`V019__row_level_security_complete.sql` enables `ENABLE ROW LEVEL SECURITY` + `CREATE POLICY tenant_isolation` (same predicate and style as V016/V017: `tenant_id = nora.current_tenant_id()` with `USING` + `WITH CHECK`) on the **15 remaining** tenant-owned tables that carry their own `tenant_id`, with **priority for `transcripts`**.

Coverage after V019: V016 (12) + V017 (3) + V019 (15) = **30 tables** with a direct policy.

**Cascade boundaries (no policy, by design).** Three child tables do **not** have their own `tenant_id` and are accessed exclusively via the already-isolated parent (cascade FK `ON DELETE CASCADE`): `iam_invitation_groups` (child of `iam_user_invitations`), `meeting_goal_expected_outcomes` (child of `meeting_goals`) and `meeting_outcome_coverage` (child of `meeting_productivity_assessments`). They follow the same convention already adopted in V017 for `customer_buying_signals` / `customer_objections`: isolation comes from the cascade + the fact that all access goes through the isolated parent. Documented explicitly as a boundary in the V019 header — if any of them gains direct access (without going through the parent), it will need a policy via JOIN.

**Legacy tables out of scope.** `roles` (has global rows `tenant_id IS NULL` — a per-tenant policy would hide them) and `user_roles` (V002, deprecated, unused in the new IAM model) do **not** get RLS; they will be removed in a future cleanup migration.

### 2. Versioned role provisioning (`db/operational/R001`)

`services/api/src/main/resources/db/operational/R001__provision_app_roles.sql` — an **idempotent** script that:

- creates `nora_app` (LOGIN, **NOBYPASSRLS**) — the API's runtime role;
- creates `nora_telemetry` (LOGIN, **BYPASSRLS**) — operator-only cross-tenant reads (item 3);
- `GRANT SELECT/INSERT/UPDATE/DELETE` on all tables of the `public` schema + `USAGE/SELECT` on sequences to `nora_app`; `GRANT EXECUTE` on `nora.current_tenant_id()`;
- minimal (least-privilege) `GRANT SELECT` on `meeting_analyses` to `nora_telemetry`;
- `ALTER DEFAULT PRIVILEGES` so that future tables inherit the grants automatically.

**This script is NOT an application Flyway migration.** It creates roles and default privileges — operations that require Postgres **admin** privilege (the owner / `postgresAdminLogin`), not `nora_app`. The API runs Flyway as `nora_app` and cannot (and should not) execute it. It is run manually (or via an infra pipeline with admin credentials), once per environment, before the cutover.

### 3. BYPASSRLS-safe telemetry

`PrimaryDbBusinessMetricsSource` now uses, **when configured**, a dedicated `JdbcTemplate` (`telemetryJdbcTemplate`) over a pool connecting as `nora_telemetry` (BYPASSRLS). The config is gated by `nora.security.rls.telemetry.url` (`TelemetryDataSourceConfig`, `@ConditionalOnProperty`): **empty by default** (dev/local/test/CI and prod **before** the cutover) ⇒ the source falls back to the primary `JdbcTemplate`, where the owner bypasses RLS — current behavior untouched. By setting the 3 vars (`NORA_TELEMETRY_DATASOURCE_URL/USERNAME/PASSWORD`) in the cutover step, cross-tenant aggregation keeps working under enforce. Without it, under enforce the dashboard would see 0 rows (fail-closed).

Alternative considered and rejected: a `SECURITY DEFINER` function owned by a privileged role with a GRANT to `nora_app`. It would give the same effect without a 2nd pool, but it couples the aggregation logic to SQL in the database and makes it harder to evolve the queries (today in Java). The dedicated datasource keeps the query in code and is symmetric to the already existing `PlatformDataSourceConfig`.

### 4. Bicep path (enforce default OFF)

`main.bicep` gains the params `rlsEnforce` (bool, **default false**), `rlsTelemetryDatasourceUrl` and `rlsTelemetryPassword` (secure, goes to the KV `rls-telemetry-password`). When `rlsEnforce=true`, it injects `NORA_RLS_ENFORCE=true` into the `apiApp`; when the telemetry URL is set, it injects the dedicated BYPASSRLS path. **The default keeps production exactly as it is** — this PR delivers everything ready to turn on, without turning it on. Changing `DATASOURCE_USERNAME/PASSWORD` to the `nora_app` role is a separate, controlled cutover step, **not** done in the template.

### 5. Cutover sequence (the order matters)

1. **Apply V019** (normal API deploy — Flyway creates the policies; enforce still OFF, no effect because the API runs as owner).
2. **Provision roles**: run `R001` as Postgres **admin** (creates `nora_app` NOBYPASSRLS + `nora_telemetry` BYPASSRLS + grants + default privileges). Populate the secrets in Key Vault (`nora_app` password, `rls-telemetry-password`).
3. **Validate BYPASSRLS telemetry** in staging: set the 3 `NORA_TELEMETRY_DATASOURCE_*` vars pointing at the primary database with `nora_telemetry`; confirm the business dashboard keeps adding up (analyses/tenants > 0) **before** touching enforce.
4. **Turn enforce on in staging**: point `DATASOURCE_USERNAME/PASSWORD` at `nora_app` + `NORA_RLS_ENFORCE=true`. Exercise the cross-tenant smoke test (log in to 2 tenants, each one sees only its own meeting/transcript/task) and confirm the operator dashboard still adds up.
5. **Promote to prod** by repeating (2)–(4). Rollback is trivial: revert the connection string to the owner and `NORA_RLS_ENFORCE=false` — the schema (policies) stays and does not get in the way.

## Consequences

**Positive:**

- The most serious hole (transcriptions with raw PII readable cross-tenant under enforce) is closed.
- RLS coverage is no longer partial — 30 tables with a policy + 3 documented cascade boundaries = 100% of the tenant-owned ones.
- Versioned and idempotent role provisioning — reproducible per environment, without relying on a comment.
- Operator-only telemetry keeps working under enforce, without silently becoming 0.
- Documented and reversible cutover: enforce is opt-in by flag + role; rollback is just swapping credential/flag.

**Negative / trade-offs:**

- Under enforce, there are **two** roles and (optionally) **two** pools in the API path (`nora_app` + `nora_telemetry`). More operational surface; mitigated by least-privilege (telemetry only reads `meeting_analyses`).
- `R001` requires admin privilege and a manual step outside Flyway — risk of forgetting it at cutover. Mitigated by the runbook (item 5) and by the explicit ordering.
- The enforcement test (`RlsEnforcementIntegrationTest`) requires Docker/Testcontainers; in dev without Docker it goes unrun (CI validates it).
- `nora_telemetry` is BYPASSRLS — any misuse of that role would see everything cross-tenant. Mitigated: grant restricted to `SELECT` on `meeting_analyses`, used only by telemetry.

## Alternatives Considered

1. **Turning enforce on with only partial coverage (V016/V017)** — rejected: it would leave `transcripts` (raw PII) and 18 other tables readable cross-tenant — worse than not turning it on.
2. **Policy via JOIN to the parent on child tables without `tenant_id`** — rejected for now: cost/complexity with no real gain, since the only access path is via the isolated parent. The V017 cascade convention is kept, documented as a boundary.
3. **Telemetry via `SECURITY DEFINER`** — rejected (see §3): couples aggregation to SQL in the database; the dedicated datasource keeps the query in Java and is symmetric to the existing control plane.
4. **Always-on enforce (no flag/role)** — rejected (inherited from ADR 0019): it would break dev/Testcontainers, which connect as owner; it requires a dedicated role before it is worth it.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-06-04 | Architect + Stratfy (PO) | ADR created. V019 (complete RLS coverage, `transcripts` priority), `db/operational/R001` (versioned provisioning of `nora_app`/`nora_telemetry`), BYPASSRLS-safe telemetry (`TelemetryDataSourceConfig` + `PrimaryDbBusinessMetricsSource`), Bicep params `rlsEnforce`/telemetry (default OFF) and cutover sequence. Enforce **not** turned on in prod in this step. Extends ADR 0019 |
