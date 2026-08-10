# RLS Enforce — Cutover Runbook (ADR 0028)

> **Rewritten for the self-hosted stack on 2026-08-10.** The procedure below is what runs on the
> production host today. The Azure mechanics this document used to describe — Bicep params,
> `deploy-infra.yml`, a Postgres Flexible Server — went with the subscription (ADR 0036) and are
> not preserved here; `git log` has them. What did not change is the reasoning, which lives in
> ADR 0028: why there are three roles, why identity and IAM are exempt, and what rollback costs.

Turns on Postgres **real Row Level Security** as defense in depth for `tenant_id`
(on top of the app-level filter, which is already 100% disciplined). Operational companion to
[ADR 0028](../adr/0028-rls-enforcement-auth-aware.md).

> Careful: the **live flip** changes how the API connects to the database in production. It is reversible
> (one redeploy), but do it while watching the dashboard. Do not skip the smoke test.

## Sequence status

| Step | What | Status |
|---|---|---|
| 1 | Mechanism: V019/V020, Flyway separated from the runtime role, onboarding GUC, the enforce switch, the test gate | Done (PR #197), enforce **default OFF** |
| 2 | Provision `nora_app` / `nora_telemetry` in Postgres | Done on the current host — `01-roles-and-db.sql` runs them at initdb, and `scripts/rls-cutover.sh` reconciles them on an existing database |
| 3 | Flip the six variables and redeploy the API | The step below |
| 4 | Smoke test and watch | `scripts/smoke-e2e.sh` |

Step 2 has **zero impact** on the running app — the roles sit idle until the flip.

## Prerequisites

`NORA_APP_PASSWORD` and `RLS_TELEMETRY_PASSWORD` in `infra/host/secrets.env.sops`, each generated
with `openssl rand -hex 24`. They are the single source of truth: the same value goes into the
`ALTER ROLE` in the database and into the API's environment. Generating one and setting only one
half is the most common way to arrive at step 3 with an authentication failure.

## Step 2 — Provision the roles

On a **fresh** database this has already happened: `infra/host/postgres/init/01-roles-and-db.sql`
runs at initdb, on an empty volume, and creates both roles with the grants and default privileges.
Note that the compose does **not** pass the password variables into the `postgres` container, so
the roles are created without a password — deliberately fail-closed. Step 2 is what gives them one.

On an existing database, or to give the initdb-created roles their passwords:

```bash
cd /opt/nora/infra/host
sops -d secrets.env.sops > /dev/shm/nora.env      # tmpfs, never on disk
set -a; . /dev/shm/nora.env; set +a
sudo -E ./scripts/rls-cutover.sh --yes
shred -u /dev/shm/nora.env
```

It runs `db/operational/R001` as the owner, is idempotent, and reconciles rather than recreates.
It does **not** turn enforce on: the roles stay idle until step 3.

Confirm all three roles before going further — a missing `nora_telemetry` is the failure that
silently zeroes the operator dashboard:

```bash
sudo docker exec nora-postgres psql -U nora_admin -d nora -c \
  "select rolname, rolcanlogin, rolbypassrls from pg_roles where rolname like 'nora%' order by 1"
```

Expected: `nora_admin` (bypass `t`, it is the owner), `nora_app` (`f`), `nora_telemetry` (`t`).

## Step 3 — The flip

Six variables, in `secrets.env.sops`. **All six or none** — every partial combination fails
silently, which is why the API now refuses to start on several of them rather than running wrong.

```bash
cd /opt/nora/infra/host
sops secrets.env.sops        # edits in place, re-encrypts on save
```

```dotenv
DATASOURCE_USERNAME=nora_app
DATASOURCE_PASSWORD=<the same value as NORA_APP_PASSWORD>
SPRING_FLYWAY_USER=nora_admin
NORA_TELEMETRY_DATASOURCE_URL=jdbc:postgresql://postgres:5432/nora
NORA_TELEMETRY_DATASOURCE_USERNAME=nora_telemetry
NORA_TELEMETRY_DATASOURCE_PASSWORD=<the same value as RLS_TELEMETRY_PASSWORD>
NORA_RLS_ENFORCE=true
```

Why each one is in the list:

- **`DATASOURCE_*`** is the point of the exercise: the runtime pool connects as a NOBYPASSRLS role
  that owns nothing, so the policies actually apply to it.
- **`SPRING_FLYWAY_USER`** must stay the owner. It defaults to `POSTGRES_ADMIN_USER`, so setting it
  is belt and braces — but it is listed because it used to default to `DATASOURCE_USERNAME`, which
  made this exact procedure a boot failure: Flyway would follow the runtime role to `nora_app`,
  with the admin's password, and a Flyway that authenticated anyway would be a non-owner unable to
  do DDL. Flyway being the owner is *what makes* `nora_app` subject to the policies.
- **The telemetry trio** is not optional. Under enforce the operator console's cross-tenant
  aggregate runs with no tenant GUC, so as `nora_app` it reads zero rows with no error.
- **`NORA_RLS_ENFORCE=true`** switches on `TenantRlsAspect`, which sets the GUC per transaction.
  It must be exactly `true` — see below.

Then roll the API:

```bash
sudo env SOPS_AGE_KEY_FILE=/etc/nora/age.key ./scripts/deploy.sh --service api
```

## The API refuses to start

`RlsEnforceTelemetryGuard` fails the boot on three states, each of which would otherwise run
wrong and look healthy. The message names the variables; this is the context behind it.

| Message says | What is wrong | Fix |
|---|---|---|
| `NORA_RLS_ENFORCE is '…', which is neither 'true' nor 'false'` | `1`, `yes` and `on` are values Spring accepts as true elsewhere, and `@ConditionalOnProperty` does not. `1` leaves `TenantRlsAspect` switched **off**, so no GUC is ever set, while the datasource is already `nora_app` — every tenant-scoped read returns zero rows | Set it to exactly `true` |
| `the runtime datasource connects as a role that BYPASSES row-level security` | `NORA_RLS_ENFORCE=true` with `DATASOURCE_USERNAME` still the owner or a superuser. Policies inert, everything green | Point `DATASOURCE_*` at `nora_app` |
| `the telemetry datasource is not fully configured` | One or more of the three telemetry variables missing. All three are checked, not just the url | Set all three |

**Recovery needs a shell on the host.** This is a configuration failure, and `deploy.sh`'s
automatic rollback reverts *image tags* — it says so in its own header: "the automatic rollback
covers image tags, not a broken compose". With the tag unchanged it either gives up or rolls the
API back to an older image against the same broken environment. `web` and `admin` both
`depends_on: api: condition: service_healthy`, so they do not come up either, and the only ingress
is the tunnel, so there is no remote escape hatch.

```bash
cd /opt/nora/infra/host
sops secrets.env.sops                       # fix, or set NORA_RLS_ENFORCE=false
sudo env SOPS_AGE_KEY_FILE=/etc/nora/age.key ./scripts/deploy.sh --service api
sudo docker logs --tail 40 nora-api         # the guard's message names the variable
```

## Step 4 — Smoke test

```bash
API_BASE=https://api.nora.systems \
NORA_SMOKE_CONFIRM_CMD="sudo /opt/nora/infra/host/scripts/smoke-confirm.sh" \
/opt/nora/scripts/smoke-e2e.sh
```

It covers what matters here, in one run: signup and login (the **exempt** identity tables — if RLS
had been enabled on those, authentication would fail closed), upload and analysis (the **enforced**
tables, through the aspect and through the async pipeline), and a second tenant getting 404 on the
first tenant's meeting.

Then confirm the operator dashboard still aggregates. It reads through the BYPASSRLS role, and a
zero there with everything else working is the telemetry misconfiguration, not a quiet week.

## Rollback

`NORA_RLS_ENFORCE=false` in `secrets.env.sops`, then redeploy the API. It goes back to connecting
as the owner and bypassing RLS. The schema (V019/V020) and both roles stay, inert.

Reverting `DATASOURCE_USERNAME` at the same time is optional and harmless; leaving it on `nora_app`
with enforce off means the app runs unprivileged with no GUC, which fails closed on every enforced
table. Roll both back together.

## Operational notes

- **Why two roles:** `nora_app` is NOBYPASSRLS (RLS applies to tenant data); `nora_telemetry`
  is BYPASSRLS (operator-only aggregate reads, intentionally cross-tenant). See ADR 0028 §telemetry.
- **Auth-aware scope:** identity (users/tenants/tokens/invitations) and IAM authz (groups/policies/…)
  have RLS **disabled** (V020) — auth is cross-tenant by design. Business/PII stays enforced.
