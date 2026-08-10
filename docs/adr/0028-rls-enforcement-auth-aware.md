# 0028 — Auth-aware RLS enforcement: scope by data, Flyway-as-admin and cutover

- Status: accepted
- Date: 2026-06-04
- Related: supersedes the **enforce design + cutover** part of ADR 0026 (keeps V019 and the role provisioning); extends ADR 0002 / 0019; related to ADR 0024 (telemetry)

## Context

ADR 0026 (proposed) delivered V019 (RLS policies on all ~30 tenant-owned tables, closing the critical `transcripts` gap) + the `R001` roles script + the BYPASSRLS telemetry path. But, while preparing the actual cutover, a code investigation (2026-06-04) found that **the enforce design in 0026 was incomplete and would break the app**. Three problems:

1. **Flyway-DDL.** 0026 has the API connect as `nora_app` (NOBYPASSRLS), and Flyway runs at API boot. But `nora_app` only has `USAGE` on the schema (no `CREATE`/`ALTER`) — the next deploy with a new migration **breaks the API at boot**. (The tables use `ENABLE`, not `FORCE`, RLS — so the owner bypasses; whoever creates the table is the owner.)
2. **Missing connection switch.** `main.bicep` has no way to point the API's `DATASOURCE` at `nora_app` (the template itself admits this). Turning on `rlsEnforce=true` alone is a **no-op** (the aspect sets the GUC, but the admin connection bypasses it).
3. **🔴 Auth breaks under enforce (0026 did not see this).** `AuthService.login`/`signup` use `UserRepository.findByEmail` — a **global, cross-tenant** lookup (find the user by email *before* knowing the tenant). The `TenantRlsAspect` only sets the GUC when there is an authenticated tenant (`if tenantId != null`). Under enforce with `nora_app`, unauthenticated requests (login, signup, invitation acceptance, email verification, password reset) end up **without a GUC → fail-closed → the whole auth breaks**.

RLS only applies to **non-owner** and **NOBYPASSRLS** roles. The app needs to be that role for tenant data, but it needs cross-tenant access for auth. With a single connection role, these conflict.

## Decision

### 1. Enforce scope: by data sensitivity, not "all tables"

RLS enforce applies to the tables of **business data + PII + IAM authorization** — where the value of defense in depth lies:

- `transcripts` (raw PII — priority), `meetings`, `meeting_analyses` + children (`meeting_decisions`, `meeting_action_items`, `meeting_risks`, `meeting_opportunities`, `meeting_participants`), `meeting_tags`, `meeting_goals`, `meeting_productivity_assessments`, `meeting_account_links`;
- `customer_accounts`, `customer_confidence_assessments`;
- `tenant_contexts` (the tenant's company/product context).

The **IDENTITY** and **IAM AUTHORIZATION** tables are left with **application scope** (RLS disabled via `V020`):

- **Identity** — auth is cross-tenant by nature (read/written without tenant context): `users` (login by email is global), `tenants`, `email_verification_tokens`, `password_reset_tokens`, `refresh_tokens`, `iam_user_invitations`.
- **IAM authorization** — authorization config (not customer PII), written by onboarding flows **without a JWT** (invitation acceptance attaches the user to groups → `iam_user_groups`; signup writes an audit record → `iam_audit_events`): `iam_groups`, `iam_policies`, `iam_user_groups`, `iam_group_policies`, `iam_user_policies`, `iam_policy_versions`, `iam_audit_events`. Exempting them eliminates GUC wiring in those flows; isolation continues via the `PolicyEvaluator` + tenant-scoped queries (disciplined).

> This **revises** the "100% of the tables" from ADR 0026 to **"100% of business data/PII; identity with application scope"** — the standard market posture for multi-tenant SaaS with self-service signup. The application-level `tenant_id` filter on the identity tables is already 100% disciplined (audit 2026-06-03), and the critical gap (`transcripts` PII) is genuinely closed.

### 2. Flyway-as-admin, runtime-as-nora_app

The API runtime connects as `nora_app` (NOBYPASSRLS → RLS applies), but **Flyway uses separate admin credentials** (Spring Boot supports `spring.flyway.{url,user,password}` independently of `spring.datasource`). The owner (admin) creates/alters the schema and **owns the tables** (including future ones) → `nora_app` (non-owner) is subject to RLS. This solves the Flyway-DDL issue **and** the owner-bypass semantics in one go. Default (without the Flyway vars set): Flyway = datasource (dev/test/pre-cutover untouched).

### 3. The analysis pipeline sets the tenant context (the only enforced write outside a request-with-JWT)

With identity + IAM exempt, the **only** path that writes to an enforced table without a JWT on the thread is the **analysis pipeline**: `AnalysisService.run` (and the live analysis) runs **async** on an executor thread — the `TenantContextHolder` (ThreadLocal) is not propagated, so the `TenantRlsAspect` does not set the GUC. Since the pipeline receives the `tenantId`, it calls the helper `TenantRlsContext.runWithTenant(tenantId, ...)` (which executes `set_config('nora.current_tenant_id', ?, true)` in the current transaction) before reading the `transcript` and writing `meeting_analyses` + children. All other enforced tables are touched only by authenticated requests (GUC set by the aspect). Genuinely cross-tenant lookups (login by email, invitation/token by hash) only hit exempt tables → they work without a GUC.

### 4. Bicep: connection switch + Flyway admin (enforce default OFF)

`main.bicep` gains the param `appDbUsername` (default `nora_admin`) + the secret `nora-app-password`. When `rlsEnforce=true`: `DATASOURCE_USERNAME=nora_app` + `DATASOURCE_PASSWORD=nora-app-password` (KV) + `FLYWAY_DATASOURCE_USERNAME/PASSWORD` = admin + `NORA_RLS_ENFORCE=true` + BYPASSRLS telemetry path (from 0026). Default OFF keeps prod as it is.

### 5. Mandatory proof: app integration test under enforce

A test (`RlsAppEnforcementIntegrationTest`, Testcontainers) **boots the app under enforce** (datasource = NOBYPASSRLS role, Flyway = admin, V020 applied) and validates, end to end: **signup, login, invitation acceptance, email verification and password reset work** AND cross-tenant isolation **holds** (tenant A does not see B's `transcripts`/`meetings`). This test is the "done" and the safety net — the live cutover only happens with it green.

### 6. Cutover sequence (corrected)

1. Merge this sub-phase (V020 + Flyway-admin + onboarding GUC + Bicep + green test in CI). Enforce still OFF — zero change in prod.
2. Provision roles: run `R001` as Postgres **admin** (creates `nora_app` NOBYPASSRLS + `nora_telemetry` BYPASSRLS + grants). Populate `nora-app-password`, `rls-telemetry-password` in Key Vault.
3. Flip it in the `bicepparam`: `rlsEnforce=true` + `rlsTelemetryDatasourceUrl` → deploy. The API starts connecting as `nora_app`; Flyway continues as admin.
4. Live smoke test: signup/login/invitation/reset work + 2 isolated tenants + operator dashboard still aggregating.
5. Trivial rollback: `rlsEnforce=false` in the bicepparam + redeploy (back to admin; schema stays).

## Consequences

**Positive:**
- Closes the critical hole (`transcripts` PII readable cross-tenant under enforce) **without breaking auth**.
- Real defense in depth where it matters (data/PII/authz), with the only safety net where the app filter can fail.
- Flyway-as-admin solves DDL + owner-bypass idiomatically (Spring config, no separate job).
- Reversible cutover, proven by an integration test (not "hope it works").

**Negative / trade-offs:**
- Identity tables do **not** have an RLS net — they depend on the application-level filter (already disciplined). Accepted: auth is cross-tenant by design; the alternative (a dedicated bypass datasource for AuthService) is more plumbing for marginal gain on those tables.
- Onboarding needs to set the GUC explicitly — a small, localized coupling (signup/invite), covered by a test.
- `V020` disables RLS on 6 tables that `V016`/`V019` had enabled — recorded and justified (it is not a regression; it is the scope correction).

## Alternatives Considered

1. **A dedicated BYPASSRLS datasource for AuthService** (routing `findByEmail` etc.) — correct but requires a 2nd EntityManager/adapter; more plumbing for marginal gain (identity is not the PII target). Passed over in favor of exempting identity.
2. **Enforce on 100% (incl. identity) + permissive policies for auth** — this would open the identity tables to anyone without a GUC (cross-tenant enumeration of users). Worse than scoping by data.
3. **`nora_app` with `CREATE` on the schema (Flyway as nora_app)** — `nora_app` would become the owner of future tables → RLS bypass on them (owner-exempt). It defeats the enforce itself. Passed over in favor of Flyway-as-admin.
4. **Postponing until after the pitch** — the PO chose to do it now and do it right (time available); the risk is mitigated by the test + enforce default OFF until the flip.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-06-04 | Architect + Stratfy (PO) | Creation. Fixes the enforce design of ADR 0026 after an investigation found 3 holes (Flyway-DDL, missing switch, and auth breaking under enforce). Scope by data sensitivity (identity with app-scope), Flyway-as-admin, onboarding sets the GUC, Bicep switch, app test under enforce as proof. Keeps V019 + R001 from 0026. |
