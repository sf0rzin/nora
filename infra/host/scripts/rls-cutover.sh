#!/usr/bin/env bash
# rls-cutover.sh — Step 2 of the RLS enforce cutover (ADR 0026 / ADR 0028) on the production host.
#
# Provisions the runtime roles by running the idempotent R001 script inside the `postgres`
# container of the `nora` compose. Replaces the `provision-roles` job of the old
# .github/workflows/rls-cutover.yml, which did `azure/login`, opened a temporary firewall
# rule for the runner IP and talked to the FQDN of the Postgres Flexible Server.
#
# WHY THE OPERATION LEFT CI (see the header of .github/workflows/rls-cutover.yml):
#   The host's Postgres has no public address. It sits on the `data` bridge
#   (internal: true) and the only published port is 127.0.0.1:5432 — the host loopback.
#   There is no firewall to "open temporarily": there is no inbound path. A GitHub
#   runner would only reach that Postgres with a reverse tunnel, VPN or self-hosted runner —
#   the three things the migration to the pull model exists to eliminate (public repo,
#   ADR 0017). So CI moved to GENERATING the plan and the host moved to EXECUTING it.
#
# THERE ARE THREE ROLES, NOT TWO. This is the mistake that zeroes the operator panel SILENTLY:
#   1. nora_admin (or the database owner) — DDL/Flyway. Already exists; it runs THIS script.
#      R001 uses ALTER DEFAULT PRIVILEGES without FOR ROLE, which is per-CREATOR-role: running
#      it as another superuser makes the tables of the NEXT migrations be born without grants.
#   2. nora_app        LOGIN, NOBYPASSRLS — API runtime. This is the whole point of enforce.
#   3. nora_telemetry  LOGIN, BYPASSRLS   — the panel's aggregated cross-tenant read.
#      If this one is forgotten, nothing breaks with an error: the API falls back to the
#      primary datasource, RLS filters by tenant and the panel shows ZERO. Fail-closed with
#      no message. The smoke below checks all THREE on purpose.
#
# Usage:
#   cd infra/host
#   sops -d secrets.env.sops > /dev/shm/nora.env      # tmpfs, never on disk
#   set -a; . /dev/shm/nora.env; set +a
#   ./scripts/rls-cutover.sh --yes
#   shred -u /dev/shm/nora.env
#
# Environment variables:
#   NORA_APP_PASSWORD        (required) nora_app password. The SAME one the compose
#                            injects into DATASOURCE_PASSWORD when NORA_RLS_ENFORCE=true.
#   NORA_TELEMETRY_PASSWORD  (required) nora_telemetry password. The SAME one that goes
#                            into NORA_TELEMETRY_DATASOURCE_PASSWORD.
#                            Accepts the legacy name RLS_TELEMETRY_PASSWORD (it was the
#                            GitHub Secret) so it does not break whoever copied the old runbook.
#   POSTGRES_ADMIN_USER      (optional) default nora_admin.
#
# Does NOT turn enforce on. That is step 3: NORA_RLS_ENFORCE=true in the .env + recreate the api.
# Running this has ZERO impact on the running app — the roles sit idle until the flip.
# It is idempotent: running it again only reconciles.
#
# Flags:
#   --yes        does not ask for confirmation (use in an automated runbook)
#   --dry-run    validates prerequisites and prints the plan, without touching the database
#   --db NAME    target database (default: nora)

set -euo pipefail

SCRIPT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_DIR="$(cd -- "${SCRIPT_DIR}/.." && pwd)"
REPO_ROOT="$(cd -- "${COMPOSE_DIR}/../.." && pwd)"
R001_REL="services/api/src/main/resources/db/operational/R001__provision_app_roles.sql"
R001="${REPO_ROOT}/${R001_REL}"

PG_SERVICE="postgres"
PG_DB="nora"
PG_ADMIN_USER="${POSTGRES_ADMIN_USER:-nora_admin}"
ASSUME_YES=0
DRY_RUN=0

# Compat with the old Secret name (RLS_TELEMETRY_PASSWORD).
NORA_TELEMETRY_PASSWORD="${NORA_TELEMETRY_PASSWORD:-${RLS_TELEMETRY_PASSWORD:-}}"
NORA_APP_PASSWORD="${NORA_APP_PASSWORD:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --yes)     ASSUME_YES=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --db)      PG_DB="${2:?--db requires a value}"; shift 2 ;;
    -h|--help) sed -n '2,60p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "ERROR: unknown flag: $1" >&2; exit 2 ;;
  esac
done

log()  { printf '>> %s\n' "$*"; }
fail() { printf 'ERROR: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Prerequisites
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || fail "docker not found in PATH."
[ -f "$R001" ] || fail "R001 not found at ${R001}. Run from the full repo."

if [ -z "$NORA_APP_PASSWORD" ]; then
  fail "NORA_APP_PASSWORD empty. Decrypt secrets.env.sops first (see the header)."
fi
if [ -z "$NORA_TELEMETRY_PASSWORD" ]; then
  fail "NORA_TELEMETRY_PASSWORD empty. There are THREE roles — without this password
      nora_telemetry is not provisioned and the operator panel shows ZERO rows SILENTLY."
fi
if [ "$NORA_APP_PASSWORD" = "$NORA_TELEMETRY_PASSWORD" ]; then
  fail "NORA_APP_PASSWORD and NORA_TELEMETRY_PASSWORD are the same. These are roles with
      OPPOSITE powers (NOBYPASSRLS vs BYPASSRLS): sharing a credential nullifies the isolation."
fi

cd -- "$COMPOSE_DIR" || fail "could not enter ${COMPOSE_DIR}"

# Find the container by its compose LABELS, not with `docker compose ps`.
#
# `docker compose ps -q postgres` has to parse the whole compose file first, and the file uses
# `:?` on several variables (CF_ACCESS_AUD among them). Without `--env-file` those are unset, so
# interpolation aborts, `ps` prints nothing, and this script reported "service 'postgres' is not
# running" at a moment when it was up and healthy — sending the operator to restart a working
# stack in the middle of a cutover. The labels are set by compose itself and need no
# interpolation, so this answers the question actually being asked: is that container running.
COMPOSE_PROJECT="${COMPOSE_PROJECT:-nora}"
PG_CID="$(docker ps -q \
  --filter "label=com.docker.compose.project=${COMPOSE_PROJECT}" \
  --filter "label=com.docker.compose.service=${PG_SERVICE}" 2>/dev/null | head -1 || true)"
[ -n "$PG_CID" ] || fail "no running container for service '${PG_SERVICE}' in compose project '${COMPOSE_PROJECT}'.
      Start the stack first, or set COMPOSE_PROJECT if it is not 'nora':
        docker ps --filter label=com.docker.compose.project=${COMPOSE_PROJECT}"

# psql over a UNIX SOCKET inside the container: the official image's pg_hba uses `local ... trust`,
# so the admin password does not even need to leave SOPS for this part. The smoke, by contrast,
# connects over TCP (127.0.0.1 inside the container) precisely to EXERCISE the password
# authentication of the new roles — which is what the API will do.
# `docker exec` on the resolved container id, not `docker compose exec` — same reason as the
# lookup above: compose has to interpolate the whole file first and cannot, without --env-file.
psql_admin() {
  docker exec -i "$PG_CID" \
    psql -v ON_ERROR_STOP=1 -U "$PG_ADMIN_USER" -d "$PG_DB" "$@"
}

log "Repo:      ${REPO_ROOT}"
log "Compose:   ${COMPOSE_DIR}"
log "Container: ${PG_SERVICE} (${PG_CID:0:12})"
log "Database:  ${PG_DB} as ${PG_ADMIN_USER}"

# ---------------------------------------------------------------------------
# 2. Role 1 of 3 — confirms that whoever runs this is the database OWNER
# ---------------------------------------------------------------------------
# R001 depends on this: ALTER DEFAULT PRIVILEGES without FOR ROLE only covers the tables
# created by WHOEVER RUNS the script. If Flyway runs as nora_admin and R001 runs as
# another role, every future migration is born invisible to nora_app.
log "Checking role 1/3 (owner/DDL)..."
DB_OWNER="$(psql_admin -tAc "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()" | tr -d '\r')"
CUR_USER="$(psql_admin -tAc "SELECT current_user" | tr -d '\r')"
log "  database owner: ${DB_OWNER} | current_user: ${CUR_USER}"
if [ "$DB_OWNER" != "$CUR_USER" ]; then
  fail "current_user (${CUR_USER}) is not the database owner (${DB_OWNER}).
      Running R001 like this makes the tables of the NEXT migrations get born without a
      grant for nora_app. Set POSTGRES_ADMIN_USER to the owner and run again."
fi

if [ "$DRY_RUN" -eq 1 ]; then
  log "DRY RUN — prerequisites OK. Would execute:"
  log "  psql -U ${PG_ADMIN_USER} -d ${PG_DB} -f ${R001_REL}"
  log "  creating/reconciling: nora_app (NOBYPASSRLS) and nora_telemetry (BYPASSRLS)"
  exit 0
fi

if [ "$ASSUME_YES" -ne 1 ]; then
  printf 'Provision the roles in %s? Type PROVISION: ' "$PG_DB"
  read -r answer
  [ "$answer" = "PROVISION" ] || fail "invalid confirmation — aborted."
fi

# ---------------------------------------------------------------------------
# 3. Runs R001 (creates/reconciles roles 2 and 3 + grants + default privileges)
# ---------------------------------------------------------------------------
# Passwords passed RAW (no quotes): R001 uses :'app_password', which psql already turns into
# an escaped SQL literal. Passing them quoted makes the role password include the quotes.
# The SQL comes in via STDIN so there is no volume to mount nor file to copy into the container.
log "Running R001 (idempotent)..."
docker exec -i "$PG_CID" \
  psql -v ON_ERROR_STOP=1 -U "$PG_ADMIN_USER" -d "$PG_DB" \
    -v app_password="$NORA_APP_PASSWORD" \
    -v telemetry_password="$NORA_TELEMETRY_PASSWORD" \
  < "$R001"
log "R001 applied."

# ---------------------------------------------------------------------------
# 4. Smoke of all THREE roles
# ---------------------------------------------------------------------------
log "Smoke 1/3 — role flags (expected: nora_app f, nora_telemetry t)"
psql_admin -tA -F'|' -c \
  "SELECT rolname, rolcanlogin, rolbypassrls FROM pg_roles
    WHERE rolname IN ('nora_app','nora_telemetry') ORDER BY rolname"

FLAGS_OK="$(psql_admin -tAc \
  "SELECT count(*) FROM pg_roles
    WHERE (rolname='nora_app'       AND rolcanlogin AND NOT rolbypassrls)
       OR (rolname='nora_telemetry' AND rolcanlogin AND     rolbypassrls)" | tr -d '\r')"
[ "$FLAGS_OK" = "2" ] || fail "role flags incorrect (expected 2 matches, got ${FLAGS_OK})."

log "Smoke 2/3 — nora_app connects by password, reads an EXEMPT table and an ENFORCED table"
docker exec -i -e PGPASSWORD="$NORA_APP_PASSWORD" "$PG_CID" \
  psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U nora_app -d "$PG_DB" -tA <<'SQL'
SELECT 'users_visible=' || count(*) FROM users;
SET nora.current_tenant_id = '00000000-0000-0000-0000-000000000000';
SELECT 'meetings_of_random_tenant=' || count(*) FROM meetings;
SQL

# THIS is the smoke the old workflow was missing. It checked the nora_telemetry flags
# in pg_roles but NEVER tested the connection nor the SELECT — exactly the path that fails
# silently. A missing GRANT on meeting_analyses would only show up as a zeroed panel.
log "Smoke 3/3 — nora_telemetry connects and reads meeting_analyses CROSS-TENANT (BYPASSRLS)"
TEL_OUT="$(docker exec -i -e PGPASSWORD="$NORA_TELEMETRY_PASSWORD" "$PG_CID" \
  psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U nora_telemetry -d "$PG_DB" -tAc \
  "SELECT count(*) FROM meeting_analyses" | tr -d '\r')"
log "  meeting_analyses visible to nora_telemetry: ${TEL_OUT}"

# Without a tenant SET, a NOBYPASSRLS role would see 0. nora_telemetry has BYPASSRLS, so
# we only validate that the query raised no permission error (count 0 is legit on a fresh base).
case "$TEL_OUT" in
  ''|*[!0-9]*) fail "nora_telemetry did not return a valid count ('${TEL_OUT}'). Check
      R001's GRANT SELECT ON meeting_analyses — without it the panel zeroes out silently." ;;
esac

log "OK — all THREE roles check out."
log ""
log "Next step (step 3, SEPARATE): turn enforce on."
log "  1. in .env:  NORA_RLS_ENFORCE=true"
log "               DATASOURCE_USERNAME=nora_app"
log "               DATASOURCE_PASSWORD=<NORA_APP_PASSWORD>"
log "               NORA_TELEMETRY_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${PG_DB}"
log "               NORA_TELEMETRY_DATASOURCE_USERNAME=nora_telemetry"
log "               NORA_TELEMETRY_DATASOURCE_PASSWORD=<NORA_TELEMETRY_PASSWORD>"
log "               SPRING_FLYWAY_USER=${PG_ADMIN_USER}   (Flyway keeps running as admin)"
log "  2. docker compose up -d --wait api"
log "  3. check the operator panel — if it zeroes out, it is nora_telemetry."
log ""
log "Reminder: the JDBC URL does NOT carry ?sslmode=require. The official image comes up with SSL OFF"
log "and Hikari fails at boot; the traffic never leaves the 'data' bridge (internal: true)."
