# `postgres/init` — bootstrap scripts of the primary Postgres

This directory is mounted at `/docker-entrypoint-initdb.d` in the `postgres` container
(database `nora`). Only that one — `postgres-platform` mounts **nothing** here.

## The rule that bites: this runs ONCE

The official image's entrypoint executes the scripts in this directory **only when
`PGDATA` is empty**, that is, on the first boot with the `pgdata` volume freshly created.
On every subsequent boot the entrypoint finds the cluster already initialised, silently skips
the whole directory and starts Postgres.

Practical consequences:

- Editing `01-roles-and-db.sql` and running `docker compose up -d` **does nothing**.
- Adding an `02-*.sql` in an environment already in production **does nothing**.
- An error in any script **aborts initdb**: the container does not become healthy,
  the volume stays in a half-initialised state and the API never starts (the `postgres`
  healthcheck is a `depends_on` dependency of `api`).

Execution order: alphabetical. The `.sql`, `.sql.gz` and `.sh` extensions are supported;
the `.sql` ones run as the superuser `${POSTGRES_ADMIN_USER}` connected to `nora`, with
`ON_ERROR_STOP` enabled.

## How to know whether it ran

```bash
docker compose logs postgres | grep -F 'NORA initdb'
# or, straight against the database:
docker compose exec -T postgres psql -U nora_admin -d nora \
  -c "\du nora_app" -c "\dx"
```

If `nora_app` and `nora_telemetry` appear in `\du`, and `pgcrypto`/`citext` in `\dx`,
the script ran.

## Database already exists: what to do

The scripts were written to be **idempotent**, so the way forward is to apply them
by hand. Do not recreate the volume just to "run the init" — that erases the database.

```bash
# from the host, in the infra/proxmox folder:
docker compose exec -T postgres \
  psql -v ON_ERROR_STOP=1 -U nora_admin -d nora < postgres/init/01-roles-and-db.sql
```

Running it on an already-migrated database is in fact **better** than during initdb: the tables
exist, so the `GRANT ... ON ALL TABLES` and the `GRANT SELECT ON meeting_analyses`
(the `nora_telemetry` one) stop being no-ops.

### The grant that stays pending on the first boot

`meeting_analyses` is created in migration `V005`, after initdb. The script warns with a
`WARNING` and carries on. Close the grant **after the first `flyway migrate`**:

```bash
docker compose exec -T postgres psql -U nora_admin -d nora \
  -c "GRANT SELECT ON meeting_analyses TO nora_telemetry"
```

Without that grant the operator's business panel returns **zero** — no error, no log,
no alert. It is the most expensive silent failure in this stack.

### Role passwords

The compose does not inject `NORA_APP_PASSWORD` / `RLS_TELEMETRY_PASSWORD` into the Postgres
container, so by default the two roles are created **without a password** (fail-closed: with
`scram-sha-256` a role without a password does not authenticate). Set them during the RLS cutover:

```bash
docker compose exec -T postgres psql -U nora_admin -d nora \
  -c "ALTER ROLE nora_app       WITH PASSWORD 'xxx'" \
  -c "ALTER ROLE nora_telemetry WITH PASSWORD 'yyy'"
```

And replicate the same values in the `.env` (`DATASOURCE_PASSWORD` via
`NORA_APP_PASSWORD`, and `NORA_TELEMETRY_DATASOURCE_PASSWORD`). Full sequence in
[`docs/operations/rls-cutover-runbook.md`](../../../../docs/operations/rls-cutover-runbook.md).

## Relationship with the application's `R001`

The source of truth for the semantics of the roles is
[`services/api/src/main/resources/db/operational/R001__provision_app_roles.sql`](../../../../services/api/src/main/resources/db/operational/R001__provision_app_roles.sql)
(ADR 0026 / 0028). `01-roles-and-db.sql` is its adaptation for the initdb moment,
where no table exists yet — the section "Differences from R001"
in the script's header lists item by item what changes and why. Did R001 change?
Re-evaluate the two files together.

## Starting over from scratch (destructive)

```bash
docker compose down
docker volume rm nora_pgdata     # APAGA O BANCO
docker compose up -d --wait
```

It only makes sense in a disposable environment. In production, restore from
`${BACKUP_DIR}` (hourly logical dump) or from the Proxmox Backup Server snapshot.
