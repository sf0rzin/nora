#!/usr/bin/env bash
#
# restore-into-proxmox.sh — restores the Azure dumps into the local stack's containers.
#
# Consumes `pg_dump -Fc` dumps (the ones the compose `backup` service produces) and rehydrates:
#   nora.dump           -> service `postgres`           (nora database)
#   nora_platform.dump  -> service `postgres-platform`  (nora_platform database, platform profile)
#
# ORDER (and why it matters):
#
#   1. ROLES BEFORE DATA. The three ADR 0026/0028 roles are created empty first:
#        nora_app       LOGIN NOBYPASSRLS  -> API runtime under enforce
#        nora_telemetry LOGIN BYPASSRLS    -> operator panel, cross-tenant read
#        (the admin/owner is the container's POSTGRES_USER, owner of the schema and of Flyway)
#      Without them in place, any dump object referencing a role blows the restore up
#      halfway through. And worse: OMITTING nora_telemetry raises no error at all — the
#      operator panel simply starts seeing ZERO rows, silently (fail-closed).
#
#   2. DATA with --no-owner --no-privileges. On Azure the owner was the Flexible Server's
#      `nora_admin`; here it is the container's POSTGRES_USER. Mapping is precisely not
#      loading owner/ACL from the dump and letting it all be born from the connected role.
#
#   3. GRANTS AFTERWARDS. R001 (db/operational) only runs at the end because it references
#      the `nora` schema and the nora.current_tenant_id() function, which only exist AFTER
#      the data/DDL went in (V016). Running R001 first fails with "schema nora does not exist".
#
# FINAL VALIDATION (a restore is only good once proven):
#   - table count in public, compared against the <db>-counts.tsv baseline, when the backup wrote one
#   - flyway_schema_history: last applied version, zero migrations with success=false,
#     and comparison against the highest V### present in the repo
#   - per-tenant smoke: meetings/users/meeting_analyses rows per tenant
#
set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROXMOX_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROXMOX_DIR/../.." && pwd)"

COMPOSE_FILE="${COMPOSE_FILE:-$PROXMOX_DIR/docker-compose.yml}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-nora}"
R001_SQL="${R001_SQL:-$REPO_ROOT/services/api/src/main/resources/db/operational/R001__provision_app_roles.sql}"
MIGRATION_DIR="${MIGRATION_DIR:-$REPO_ROOT/services/api/src/main/resources/db/migration}"

FROM_DIR=""
DUMP_PRIMARY=""
DUMP_PLATFORM=""
ENV_FILE=""
SOPS_FILE="${SOPS_FILE:-$PROXMOX_DIR/secrets.env.sops}"
USE_SOPS=0
FORCE=0
SKIP_PLATFORM=0
SKIP_ROLES=0
ALLOW_COUNT_DRIFT=0
JOBS="${RESTORE_JOBS:-2}"

usage() {
  cat <<EOF
$SCRIPT_NAME — restores the Azure dumps into the postgres/postgres-platform containers

USAGE
  $SCRIPT_NAME --from-dir <backup-dir> [options]
  $SCRIPT_NAME --dump <nora.dump> [--platform-dump <nora_platform.dump>] [options]

OPTIONS
  --from-dir <dir>        Directory with the dumps (e.g.: a folder from $BACKUP_DIR). Looks
                          for nora.dump and nora_platform.dump inside it.
  --dump <file>           Dump of the primary database (nora)
  --platform-dump <file>  Dump of the platform database (nora_platform)
  --env-file <file>       Already-decrypted .env file (e.g.: the one in /dev/shm from deploy.sh)
  --sops                  Decrypts $SOPS_FILE with SOPS/age into a tmpfs
  --skip-platform         Does not restore nora_platform
  --skip-roles            Does not create/adjust roles (assumes they already exist)
  --jobs <n>              pg_restore parallelism (default: $JOBS)
  --allow-count-drift     Count divergence becomes a warning, not an error
  --force                 Overwrites a target database that ALREADY HAS tables
                          (uses pg_restore --clean --if-exists)
  -h, --help              This help

REQUIRED PASSWORDS (env var or --env-file/--sops)
  POSTGRES_ADMIN_USER          default: nora_admin
  POSTGRES_ADMIN_PASSWORD      required
  POSTGRES_PLATFORM_ADMIN_PASSWORD  (default: same as primary, as in the compose)
  NORA_APP_PASSWORD            password for role nora_app       (required, unless --skip-roles)
  RLS_TELEMETRY_PASSWORD       password for role nora_telemetry (required, unless --skip-roles)

IDEMPOTENCY
  Running again over an already-restored database requires --force (otherwise it aborts
  to avoid destroying data). With --force the result is the same across any number of runs.
  Role creation and R001 are idempotent by nature.

EXAMPLE
  $SCRIPT_NAME --from-dir /srv/nora/backups/20260807T031500Z --sops
EOF
}

_ts() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
log()  { printf '[%s] %s\n'        "$(_ts)" "$*" >&2; }
ok()   { printf '[%s] OK    %s\n'  "$(_ts)" "$*" >&2; }
warn() { printf '[%s] WARN  %s\n'  "$(_ts)" "$*" >&2; }
err()  { printf '[%s] ERROR %s\n'  "$(_ts)" "$*" >&2; }
die()  { err "$*"; exit 1; }
hr()   { printf '%s\n' "------------------------------------------------------------" >&2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --from-dir)         FROM_DIR="${2:?--from-dir requires a value}"; shift 2 ;;
    --dump)             DUMP_PRIMARY="${2:?--dump requires a value}"; shift 2 ;;
    --platform-dump)    DUMP_PLATFORM="${2:?--platform-dump requires a value}"; shift 2 ;;
    --env-file)         ENV_FILE="${2:?--env-file requires a value}"; shift 2 ;;
    --sops)             USE_SOPS=1; shift ;;
    --sops-file)        SOPS_FILE="${2:?--sops-file requires a value}"; USE_SOPS=1; shift 2 ;;
    --skip-platform)    SKIP_PLATFORM=1; shift ;;
    --skip-roles)       SKIP_ROLES=1; shift ;;
    --jobs)             JOBS="${2:?--jobs requires a value}"; shift 2 ;;
    --allow-count-drift) ALLOW_COUNT_DRIFT=1; shift ;;
    --force)            FORCE=1; shift ;;
    -h|--help)          usage; exit 0 ;;
    *) err "unknown option: $1"; echo >&2; usage; exit 1 ;;
  esac
done

umask 077

# ---------------------------------------------------------------------------
# Secrets: --sops (tmpfs) > --env-file > environment
# ---------------------------------------------------------------------------
TMP_ENV=""
cleanup() {
  local rc=$?
  set +e
  [ -n "$TMP_ENV" ] && [ -f "$TMP_ENV" ] && rm -f "$TMP_ENV"
  [ -n "${TMP_ENV_DIR:-}" ] && [ -d "$TMP_ENV_DIR" ] && rmdir "$TMP_ENV_DIR" 2>/dev/null
  exit "$rc"
}
trap cleanup EXIT INT TERM

if [ "$USE_SOPS" -eq 1 ]; then
  command -v sops >/dev/null 2>&1 || die "sops not found. Run bootstrap-host.sh or install manually."
  [ -f "$SOPS_FILE" ] || die "encrypted file does not exist: $SOPS_FILE"
  [ -d /dev/shm ] || die "/dev/shm does not exist — refusing to decrypt secrets to disk."
  TMP_ENV_DIR="$(mktemp -d /dev/shm/nora-restore.XXXXXX)"
  chmod 700 "$TMP_ENV_DIR"
  TMP_ENV="$TMP_ENV_DIR/env"
  export SOPS_AGE_KEY_FILE="${SOPS_AGE_KEY_FILE:-/etc/nora/age.key}"
  log "decrypting $SOPS_FILE (key: $SOPS_AGE_KEY_FILE) to tmpfs..."
  sops --decrypt --input-type dotenv --output-type dotenv "$SOPS_FILE" > "$TMP_ENV" \
    || die "failed to decrypt. Is the age private key at $SOPS_AGE_KEY_FILE and readable?"
  ENV_FILE="$TMP_ENV"
  ok "secrets decrypted in tmpfs"
fi

if [ -n "$ENV_FILE" ]; then
  [ -f "$ENV_FILE" ] || die "--env-file does not exist: $ENV_FILE"
  set -a
  # shellcheck disable=SC1090
  . "$ENV_FILE"
  set +a
fi

PG_USER="${POSTGRES_ADMIN_USER:-nora_admin}"
PG_PW="${POSTGRES_ADMIN_PASSWORD:-}"
PG_PLATFORM_PW="${POSTGRES_PLATFORM_ADMIN_PASSWORD:-$PG_PW}"
APP_PW="${NORA_APP_PASSWORD:-}"
TEL_PW="${RLS_TELEMETRY_PASSWORD:-}"

[ -n "$PG_PW" ] || die "POSTGRES_ADMIN_PASSWORD not set (use --env-file, --sops, or export)."

if [ "$SKIP_ROLES" -eq 0 ]; then
  miss=0
  [ -n "$APP_PW" ] || { err "NORA_APP_PASSWORD not set — without it the nora_app role is not created"; miss=1; }
  [ -n "$TEL_PW" ] || {
    err "RLS_TELEMETRY_PASSWORD not set."
    err "  WARNING: omitting nora_telemetry does NOT raise an error at runtime — the"
    err "  operator panel simply starts showing ZERO everywhere, silently (ADR 0026/0028)."
    err "  Generate one:  openssl rand -hex 24"
    miss=1
  }
  [ "$miss" -eq 0 ] || die "missing role passwords. Use --skip-roles if the roles already exist."
fi

# ---------------------------------------------------------------------------
# Locating the dumps
# ---------------------------------------------------------------------------
if [ -n "$FROM_DIR" ]; then
  [ -d "$FROM_DIR" ] || die "--from-dir is not a directory: $FROM_DIR"
  [ -n "$DUMP_PRIMARY" ]  || DUMP_PRIMARY="$FROM_DIR/nora.dump"
  [ -n "$DUMP_PLATFORM" ] || DUMP_PLATFORM="$FROM_DIR/nora_platform.dump"
fi

[ -n "$DUMP_PRIMARY" ] || { err "provide --from-dir or --dump"; usage; exit 1; }
[ -s "$DUMP_PRIMARY" ] || die "primary dump does not exist or is empty: $DUMP_PRIMARY"

if [ "$SKIP_PLATFORM" -eq 0 ]; then
  if [ ! -s "${DUMP_PLATFORM:-}" ]; then
    warn "platform dump missing (${DUMP_PLATFORM:-<not provided>}) — skipping."
    warn "The control plane is rebuildable: db/platform's V001 recreates the schema on boot."
    SKIP_PLATFORM=1
  fi
fi

# ---------------------------------------------------------------------------
# Compose helpers
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "docker not found."
docker compose version >/dev/null 2>&1 || die "'docker compose' (v2) plugin not found. Run bootstrap-host.sh."
[ -f "$COMPOSE_FILE" ] || die "compose not found: $COMPOSE_FILE"

COMPOSE_ARGS=(--project-name "$COMPOSE_PROJECT" --project-directory "$PROXMOX_DIR" -f "$COMPOSE_FILE")
[ "$SKIP_PLATFORM" -eq 0 ] && COMPOSE_ARGS+=(--profile platform)

dc() { docker compose "${COMPOSE_ARGS[@]}" "$@"; }

# svc_running <service> -> 0 if there is a running container
svc_running() {
  local cid
  cid="$(dc ps -q "$1" 2>/dev/null | head -1)"
  [ -n "$cid" ] && [ "$(docker inspect -f '{{.State.Running}}' "$cid" 2>/dev/null)" = "true" ]
}

# psql_in <service> <db> [args...] — psql inside the container, as admin.
psql_in() {
  local svc="$1" db="$2"; shift 2
  local pw="$PG_PW"
  [ "$svc" = "postgres-platform" ] && pw="$PG_PLATFORM_PW"
  dc exec -T -e PGPASSWORD="$pw" "$svc" \
     psql -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$db" "$@"
}

# ---------------------------------------------------------------------------
# Dump verification BEFORE touching the database
# ---------------------------------------------------------------------------
verify_dump() {
  local file="$1" label="$2"
  log "$label: verifying $file"

  # checksum, when the backup wrote one
  local sumfile="$file.sha256"
  if [ -f "$sumfile" ]; then
    if command -v sha256sum >/dev/null 2>&1; then
      (cd "$(dirname "$file")" && sha256sum -c --status "$(basename "$sumfile")") \
        || die "$label: CHECKSUM MISMATCH. The file was corrupted in transit. Re-copy it."
      ok "$label: checksum matches"
    else
      warn "$label: sha256sum missing — checksum not verified"
    fi
  else
    warn "$label: no .sha256 file next to the dump"
  fi

  # does the dump open?
  local toc
  toc="$(mktemp)"
  if command -v pg_restore >/dev/null 2>&1; then
    pg_restore --list "$file" > "$toc" 2>/dev/null \
      || die "$label: pg_restore --list failed — dump unusable."
  else
    # no local client: use the one from the postgres container
    dc exec -T postgres sh -c 'cat > /tmp/verify.dump && pg_restore --list /tmp/verify.dump; rc=$?; rm -f /tmp/verify.dump; exit $rc' \
      < "$file" > "$toc" 2>/dev/null \
      || die "$label: pg_restore --list failed (via container) — dump unusable."
  fi
  local entries
  entries="$(grep -cv '^;' "$toc" || true)"
  rm -f "$toc"
  [ "${entries:-0}" -gt 0 ] || die "$label: empty TOC — the dump has no objects at all."
  ok "$label: dump opens — $entries entries in the TOC"
}

# ---------------------------------------------------------------------------
# Step 1 — ROLES (before the data)
# ---------------------------------------------------------------------------
create_roles_bare() {
  log "creating roles (nora_app NOBYPASSRLS, nora_telemetry BYPASSRLS)..."
  # Passwords passed RAW via -v: psql turns :'var' into an escaped SQL literal.
  # (same convention as R001 and rls-cutover.yml — do not put quotes around them)
  psql_in postgres nora -v app_password="$APP_PW" -v telemetry_password="$TEL_PW" -q <<'SQL'
SELECT 'CREATE ROLE nora_app LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_app')
\gexec
SELECT 'CREATE ROLE nora_telemetry LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_telemetry')
\gexec
SQL
  psql_in postgres nora -v app_password="$APP_PW" -q \
    -c "ALTER ROLE nora_app WITH LOGIN PASSWORD :'app_password' NOBYPASSRLS"
  psql_in postgres nora -v telemetry_password="$TEL_PW" -q \
    -c "ALTER ROLE nora_telemetry WITH LOGIN PASSWORD :'telemetry_password' BYPASSRLS"
  ok "roles created/adjusted"
}

apply_r001() {
  [ -f "$R001_SQL" ] || {
    warn "R001 not found at $R001_SQL — GRANTs and DEFAULT PRIVILEGES NOT applied."
    warn "Without them, the API connecting as nora_app sees no tables at all."
    return 1
  }
  log "applying GRANTs/DEFAULT PRIVILEGES (R001)..."
  # R001 has to run as the SAME role that owns the tables (the container admin),
  # because ALTER DEFAULT PRIVILEGES is per-creator-role. See the R001 header.
  psql_in postgres nora -v app_password="$APP_PW" -v telemetry_password="$TEL_PW" -q < "$R001_SQL"
  ok "R001 applied"
}

# ---------------------------------------------------------------------------
# Step 2 — DATA
# ---------------------------------------------------------------------------
target_table_count() {  # <service> <db>
  psql_in "$1" "$2" -tAc \
    "SELECT count(*) FROM information_schema.tables
      WHERE table_schema='public' AND table_type='BASE TABLE'" 2>/dev/null | tr -d '[:space:]'
}

restore_db() {  # <service> <db> <dump> <label>
  local svc="$1" db="$2" file="$3" label="$4"
  hr
  log "RESTORING $label — $file -> service '$svc', database '$db'"

  svc_running "$svc" || die "$label: service '$svc' is not running. Start it with:
       docker compose ${COMPOSE_ARGS[*]} up -d $svc"

  local existing
  existing="$(target_table_count "$svc" "$db")"
  existing="${existing:-0}"
  if [ "$existing" -gt 0 ]; then
    if [ "$FORCE" -eq 0 ]; then
      err "$label: database '$db' ALREADY HAS $existing tables."
      err "        Restoring over it without --force would destroy existing data."
      err "        If that is really what you want:  $SCRIPT_NAME ... --force"
      err "        If the database should start clean:   docker compose down -v  (WIPES the volumes)"
      return 1
    fi
    warn "$label: $existing tables present — --force active, using --clean --if-exists"
  fi

  # Copies the dump into the container. Avoids depending on a local client and enables -j.
  local inner="/tmp/nora-restore-$db.dump"
  log "$label: copying dump to the container..."
  dc cp "$file" "$svc:$inner" >/dev/null

  local restore_args=(--no-owner --no-privileges --exit-on-error -d "$db" -U "$PG_USER")
  [ "$existing" -gt 0 ] && restore_args+=(--clean --if-exists)
  [ "$JOBS" -gt 1 ] && restore_args+=(-j "$JOBS")

  log "$label: pg_restore --no-owner --no-privileges (jobs=$JOBS)..."
  local t0 t1 rc=0
  t0="$(date +%s)"
  local pw="$PG_PW"
  [ "$svc" = "postgres-platform" ] && pw="$PG_PLATFORM_PW"
  dc exec -T -e PGPASSWORD="$pw" "$svc" pg_restore "${restore_args[@]}" "$inner" || rc=$?
  t1="$(date +%s)"

  dc exec -T "$svc" rm -f "$inner" >/dev/null 2>&1 || true

  if [ "$rc" -ne 0 ]; then
    err "$label: pg_restore exited with code $rc."
    err "        Typical causes:"
    err "          - extension missing from the image (pgcrypto/citext ship in pg16's contrib: ok)"
    err "          - referenced role does not exist -> run WITHOUT --skip-roles"
    err "          - object already exists -> use --force (adds --clean --if-exists)"
    return 1
  fi
  ok "$label: restored in $((t1 - t0))s"
  return 0
}

# ---------------------------------------------------------------------------
# Step 3 — VALIDATION
# ---------------------------------------------------------------------------
VALIDATION_FAILED=0
vfail() { err "VALIDATION: $*"; VALIDATION_FAILED=1; }

validate_counts() {  # <service> <db> <baseline.tsv|""> <label>
  local svc="$1" db="$2" baseline="$3" label="$4"
  local n
  n="$(target_table_count "$svc" "$db")"
  log "$label: $n tables in public"
  [ "${n:-0}" -gt 0 ] || { vfail "$label: no tables in public — the restore brought nothing."; return; }

  if [ -z "$baseline" ] || [ ! -s "$baseline" ]; then
    warn "$label: no count baseline (<db>-counts.tsv) — comparison skipped."
    return
  fi

  log "$label: comparing row counts against the backup's baseline..."
  local live drift=0 checked=0
  live="$(mktemp)"
  psql_in "$svc" "$db" -tA -F$'\t' -c \
    "SELECT c.relname,
            (xpath('/row/c/text()',
                   query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
                                false, true, '')))[1]::text::bigint
     FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
     WHERE c.relkind='r' AND n.nspname='public'
     ORDER BY 1" > "$live" 2>/dev/null || true

  while IFS=$'\t' read -r tbl expected; do
    [ -n "$tbl" ] || continue
    local got
    got="$(awk -F'\t' -v t="$tbl" '$1==t{print $2; exit}' "$live")"
    checked=$((checked + 1))
    if [ -z "$got" ]; then
      err "  table MISSING in the target: $tbl (expected $expected rows)"
      drift=$((drift + 1))
    elif [ "$got" != "$expected" ]; then
      err "  divergence in $tbl: source=$expected target=$got"
      drift=$((drift + 1))
    fi
  done < "$baseline"
  rm -f "$live"

  if [ "$drift" -eq 0 ]; then
    ok "$label: $checked tables match the source row for row"
  elif [ "$ALLOW_COUNT_DRIFT" -eq 1 ]; then
    warn "$label: $drift divergences tolerated via --allow-count-drift"
  else
    vfail "$label: $drift tables diverge from the source. Use --allow-count-drift if that is expected."
  fi
}

validate_flyway() {  # <service> <db> <label> <migrations-dir|"">
  local svc="$1" db="$2" label="$3" migdir="${4:-}"

  local exists
  exists="$(psql_in "$svc" "$db" -tAc "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL" 2>/dev/null | tr -d '[:space:]')"
  if [ "$exists" != "t" ]; then
    vfail "$label: flyway_schema_history does not exist. Did the dump come from an unmigrated database?"
    return
  fi

  local failed
  failed="$(psql_in "$svc" "$db" -tAc "SELECT count(*) FROM flyway_schema_history WHERE success = false" | tr -d '[:space:]')"
  if [ "${failed:-0}" -ne 0 ]; then
    vfail "$label: $failed migrations with success=false in the history. The source database was broken."
    psql_in "$svc" "$db" -c \
      "SELECT installed_rank, version, description, installed_on FROM flyway_schema_history WHERE success=false ORDER BY installed_rank" >&2 || true
  fi

  local last
  last="$(psql_in "$svc" "$db" -tA -F'|' -c \
    "SELECT version, description, installed_on FROM flyway_schema_history
      WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1" | head -1)"
  local last_ver last_desc
  last_ver="$(printf '%s' "$last" | cut -d'|' -f1)"
  last_desc="$(printf '%s' "$last" | cut -d'|' -f2)"
  log "$label: last applied migration = V$last_ver ($last_desc)"

  if [ -n "$migdir" ] && [ -d "$migdir" ]; then
    local repo_ver
    repo_ver="$(ls "$migdir" 2>/dev/null | sed -n 's/^V0*\([0-9][0-9]*\)__.*\.sql$/\1/p' | sort -n | tail -1)"
    local db_ver
    db_ver="$(printf '%s' "$last_ver" | sed 's/^0*//')"
    if [ -n "$repo_ver" ] && [ -n "$db_ver" ]; then
      if [ "$db_ver" -eq "$repo_ver" ]; then
        ok "$label: schema at the repo's latest version (V$repo_ver)"
      elif [ "$db_ver" -lt "$repo_ver" ]; then
        warn "$label: database at V$db_ver, repo has up to V$repo_ver."
        warn "         Expected if the dump predates the latest migrations: the API's Flyway"
        warn "         applies the pending ones on first boot. Check before releasing traffic."
      else
        vfail "$label: database at V$db_ver, HIGHER than the repo's V$repo_ver. The API image"
        vfail "         is out of date relative to the dump — do not deploy like this."
      fi
    fi
  fi
}

validate_tenants_smoke() {  # <service> <db> <label>
  local svc="$1" db="$2" label="$3"
  local has_tenants
  has_tenants="$(psql_in "$svc" "$db" -tAc "SELECT to_regclass('public.tenants') IS NOT NULL" 2>/dev/null | tr -d '[:space:]')"
  if [ "$has_tenants" != "t" ]; then
    vfail "$label: table 'tenants' does not exist — incomplete restore."
    return
  fi

  local n
  n="$(psql_in "$svc" "$db" -tAc "SELECT count(*) FROM tenants" | tr -d '[:space:]')"
  if [ "${n:-0}" -eq 0 ]; then
    vfail "$label: ZERO tenants. Either the source database was empty, or the restore failed silently."
    return
  fi

  log "$label: per-tenant smoke ($n tenants)"
  # Connected as owner/admin: RLS does not apply (the policies are fail-closed only for
  # non-owner roles without the nora.current_tenant_id GUC). Here we want exactly the real total.
  psql_in "$svc" "$db" -P pager=off -c \
    "SELECT t.slug,
            t.status,
            (SELECT count(*) FROM users u            WHERE u.tenant_id = t.id) AS users,
            (SELECT count(*) FROM meetings m         WHERE m.tenant_id = t.id) AS meetings,
            (SELECT count(*) FROM meeting_analyses a WHERE a.tenant_id = t.id) AS analyses
       FROM tenants t
      ORDER BY t.slug" >&2
  ok "$label: per-tenant smoke executed"

  # A tenant with NO user at all is a sign of a partial restore (FK users->tenants is RESTRICT).
  local orfaos
  orfaos="$(psql_in "$svc" "$db" -tAc \
    "SELECT count(*) FROM tenants t WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.tenant_id = t.id)" | tr -d '[:space:]')"
  [ "${orfaos:-0}" -eq 0 ] || warn "$label: $orfaos tenant(s) with no user at all — check whether that is expected."
}

validate_roles() {
  log "checking role flags (the point of ADR 0028)..."
  local out
  out="$(psql_in postgres nora -tA -F'|' -c \
    "SELECT rolname, rolcanlogin, rolbypassrls FROM pg_roles
      WHERE rolname IN ('nora_app','nora_telemetry') ORDER BY rolname")"
  printf '%s\n' "$out" | sed 's/^/    /' >&2

  printf '%s' "$out" | grep -q '^nora_app|t|f$' \
    || vfail "nora_app must be LOGIN and NOBYPASSRLS (otherwise RLS is worthless)."
  printf '%s' "$out" | grep -q '^nora_telemetry|t|t$' \
    || vfail "nora_telemetry must be LOGIN and BYPASSRLS (otherwise the operator panel zeroes out SILENTLY)."

  # can nora_app see the tables? (proof that the R001 GRANTs took)
  local visiveis
  visiveis="$(dc exec -T -e PGPASSWORD="$APP_PW" postgres \
      psql -tAc "SELECT count(*) FROM information_schema.table_privileges
                  WHERE grantee='nora_app' AND privilege_type='SELECT'" \
      -U nora_app -d nora 2>/dev/null | tr -d '[:space:]' || echo 0)"
  if [ "${visiveis:-0}" -gt 0 ]; then
    ok "nora_app connects and has SELECT on $visiveis tables"
  else
    vfail "nora_app cannot connect or has no GRANT at all — the API will not come up."
  fi
}

# ---------------------------------------------------------------------------
# Execution
# ---------------------------------------------------------------------------
hr
log "restore-into-proxmox — compose project '$COMPOSE_PROJECT'"
log "  primary:    $DUMP_PRIMARY"
[ "$SKIP_PLATFORM" -eq 0 ] && log "  platform:   $DUMP_PLATFORM"

verify_dump "$DUMP_PRIMARY" "PRIMARY"
[ "$SKIP_PLATFORM" -eq 0 ] && verify_dump "$DUMP_PLATFORM" "PLATFORM"

# 1) roles before the data
if [ "$SKIP_ROLES" -eq 0 ]; then
  hr
  svc_running postgres || die "service 'postgres' is not running — start the stack first."
  create_roles_bare
else
  log "--skip-roles: role creation skipped"
fi

# 2) data
restore_db postgres nora "$DUMP_PRIMARY" "PRIMARY" || die "primary database restore failed."

if [ "$SKIP_PLATFORM" -eq 0 ]; then
  if svc_running postgres-platform; then
    restore_db postgres-platform nora_platform "$DUMP_PLATFORM" "PLATFORM" \
      || warn "nora_platform restore failed — the control plane comes up DEGRADED (admin -> 503)."
  else
    warn "service 'postgres-platform' is not running (profile 'platform' off?)."
    warn "Start it with:  docker compose --profile platform up -d postgres-platform"
    SKIP_PLATFORM=1
  fi
fi

# 3) grants afterwards (R001 depends on the nora schema, created by V016 that came in the dump)
if [ "$SKIP_ROLES" -eq 0 ]; then
  hr
  apply_r001 || VALIDATION_FAILED=1
fi

# 4) validation
hr
log "VALIDATION"
BASELINE_PRIMARY=""
BASELINE_PLATFORM=""
if [ -n "$FROM_DIR" ]; then
  [ -f "$FROM_DIR/nora-counts.tsv" ]          && BASELINE_PRIMARY="$FROM_DIR/nora-counts.tsv"
  [ -f "$FROM_DIR/nora_platform-counts.tsv" ] && BASELINE_PLATFORM="$FROM_DIR/nora_platform-counts.tsv"
fi

validate_counts  postgres nora "$BASELINE_PRIMARY" "PRIMARY"
validate_flyway  postgres nora "PRIMARY" "$MIGRATION_DIR"
validate_tenants_smoke postgres nora "PRIMARY"
[ "$SKIP_ROLES" -eq 0 ] && validate_roles

if [ "$SKIP_PLATFORM" -eq 0 ]; then
  validate_counts postgres-platform nora_platform "$BASELINE_PLATFORM" "PLATFORM"
  # The platform Flyway has its own history table (locations classpath:db/platform).
  validate_flyway postgres-platform nora_platform "PLATFORM" ""
fi

hr
if [ "$VALIDATION_FAILED" -ne 0 ]; then
  err "RESTORE COMPLETED WITH VALIDATION FAILURES. Do NOT release traffic yet."
  err "Review the items marked as VALIDATION above."
  exit 1
fi

ok "RESTORE VALIDATED."
log "Next steps:"
log "  1. ./deploy.sh --tag <sha-xxxxxxx>            (brings up the stack pointing at the local database)"
log "  2. ./restore-drill.sh                          (measures the real RTO — closes gap 3 of ADR 0016)"
exit 0
