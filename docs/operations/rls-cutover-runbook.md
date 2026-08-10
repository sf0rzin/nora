# RLS Enforce — Cutover Runbook (ADR 0028)

Turns on Postgres **real Row Level Security** as defense in depth for `tenant_id`
(on top of the app-level filter, which is already 100% disciplined). Operational companion to
[ADR 0028](../adr/0028-rls-enforcement-auth-aware.md).

> Careful: the **live flip** changes how the API connects to the database in production. It is reversible
> (one redeploy), but do it while watching the dashboard. Do not skip the smoke test.

## Sequence status

| Step | What | Status |
|---|---|---|
| 1 | Mechanism (V019/V020 + Flyway-admin + onboarding GUC + Bicep switch + test gate) | Done (PR #197), enforce **default OFF** |
| 2 | Provision the `nora_app` / `nora_telemetry` roles in Postgres | Pending — `rls-cutover.yml` |
| 3 | Flip `rlsEnforce=true` in the bicepparam → deploy | Pending — requires a "go" from the owner |
| 4 | Live smoke test + monitoring | Pending |

Running Step 2 has **zero impact** on the running app — the roles stay idle until the flip.

## Prerequisites (once per environment)

Two GitHub Secrets with strong passwords (the `nora_app` password is the **same** one used by R001 and
by the deployment — the single source of truth is the secret):

```bash
# Generate and set it WITHOUT echoing the value (hex = alphanumeric, no quoting headaches):
openssl rand -hex 24 | gh secret set NORA_APP_PASSWORD
openssl rand -hex 24 | gh secret set RLS_TELEMETRY_PASSWORD
gh secret list | grep -E "NORA_APP_PASSWORD|RLS_TELEMETRY_PASSWORD"
```

`deploy-infra.yml` already injects those two secrets as env vars (the bicepparam reads them via
`readEnvironmentVariable` at the flip). Already existing: `AZURE_*`, `PG_ADMIN_PASSWORD`.

## Step 2 — Provision roles (`rls-cutover.yml`)

```bash
gh workflow run rls-cutover.yml -f confirm=PROVISION
gh run watch $(gh run list --workflow=rls-cutover.yml --limit 1 --json databaseId --jq '.[0].databaseId')
```

The workflow (as **admin** `nora_admin`, via OIDC):
1. runs `db/operational/R001` → creates `nora_app` (NOBYPASSRLS) + `nora_telemetry` (BYPASSRLS)
   + GRANTs + DEFAULT PRIVILEGES;
2. smoke test: checks the role flags, that `nora_app` connects, reads an **exempt** table (`users`)
   and that an **enforced** table (`meetings`) under a random tenant returns 0 rows without error;
3. closes the runner's temporary firewall rule (always).

It is **idempotent** — it can be re-run. The admin password never leaves GitHub.

## Step 3 — Flip (requires a "go" from the owner)

Add at the end of `infra/bicep/main.dev.bicepparam`:

```bicep
// ---- RLS enforce (ADR 0028) — cutover flip ----
// nora_app (NOBYPASSRLS) + nora_telemetry (BYPASSRLS) provisioned by rls-cutover.yml.
param rlsEnforce = true
param appDbPassword = readEnvironmentVariable('NORA_APP_PASSWORD')
param rlsTelemetryDatasourceUrl = 'jdbc:postgresql://nora-pg-dev-wgl3a3.postgres.database.azure.com:5432/nora?sslmode=require'
param rlsTelemetryPassword = readEnvironmentVariable('RLS_TELEMETRY_PASSWORD')
```

What the flip turns on in the `nora-api-dev` Container App (via `main.bicep`):
- `DATASOURCE_USERNAME=nora_app` + `DATASOURCE_PASSWORD`←KV `nora-app-password` (NOBYPASSRLS → RLS applies);
- Flyway separated as **admin**: `SPRING_FLYWAY_USER=nora_admin` + password←KV `postgres-password`
  (DDL + table owner);
- `NORA_RLS_ENFORCE=true` (turns on the `TenantRlsAspect`);
- the telemetry BYPASSRLS path: `NORA_TELEMETRY_DATASOURCE_*` as `nora_telemetry`
  (the operator panel keeps aggregating cross-tenant).

A PR with **only** that change → merge → `deploy-infra.yml` applies it. The API restarts connecting
as `nora_app`.

> Do not include the flip in the same PR as the provisioning: merging the flip **before** Step 2 brings
> the API up pointing at a `nora_app` that does not exist → the boot breaks.

## Step 4 — Live smoke test

- **Auth (exempt tables):** signup → email verification → login → invitation acceptance → password reset.
- **Enforced tables:** transcript upload → list/detail show up → async analysis completes (COMPLETED).
- **Isolation:** tenant B does **not** see tenant A's meeting/transcript.
- **Operator:** the admin panel still aggregates metrics (BYPASSRLS telemetry).

## Rollback (trivial, reversible)

Remove the 4 flip lines from the bicepparam (or `param rlsEnforce = false`) → merge → redeploy.
The API goes back to connecting as `nora_admin` (bypassing RLS). The schema (V019/V020) and the roles stay —
with no effect while enforce is OFF.

## Operational notes

- **`ServerIsBusy` on `azure.extensions`:** `deploy-infra.yml` rewrites the `azure.extensions`
  parameter (already at `PGCRYPTO,CITEXT`) on every deployment; on a busy B1ms server this yields
  `ServerIsBusy` (a transient no-op). Remedy: confirm `state=Ready` on both Postgres servers
  (`az postgres flexible-server show ... --query state`) and re-run **once** (do not repeat
  excessively — each attempt restarts the server and keeps the next one busy).
- **Why two roles:** `nora_app` is NOBYPASSRLS (RLS applies to tenant data); `nora_telemetry`
  is BYPASSRLS (operator-only aggregate reads, intentionally cross-tenant). See ADR 0028 §telemetry.
- **Auth-aware scope:** identity (users/tenants/tokens/invitations) and IAM authz (groups/policies/…)
  have RLS **disabled** (V020) — auth is cross-tenant by design. Business/PII stays enforced.
