#!/usr/bin/env bash
#
# restore-drill.sh — restore drill in a DISPOSABLE container, timed.
#
# WHY THIS SCRIPT EXISTS
# ----------------------
# ADR 0016 (Gap 3) declares an RTO of 2h and an RPO of 5 min. The 5 min RPO died with the
# Flexible Server PITR: in this stack the RPO is BACKUP_INTERVAL_SECONDS (1h by default) —
# it is in ADR 0034 §disponibilidade. And the 2h RTO was never verified:
# `docs/operations/production-readiness-gaps.md:67` says, in so many words, "nobody
# actually tested a restore".
#
# An RTO that was never measured is not an objective, it is a guess. This script measures.
#
# WHAT IT MEASURES (and, more importantly, WHAT IT DOES NOT)
# ----------------------------------------------------------
# MEASURES — the DATA recovery path, with the dump that exists right now:
#     phase 1  bring up a virgin Postgres              (proxy for "provision the database")
#     phase 2  provision the three RLS roles
#     phase 3  pg_restore of the dump
#     phase 4  validate (counts, Flyway, per-tenant smoke, nora_app GRANTs)
#
# DOES NOT MEASURE — and the real RTO is the SUM of all this:
#     - incident detection and human decision (in practice, the largest term of all)
#     - provisioning/restoring the VM on Proxmox, host boot, `docker compose pull`
#     - stack boot (Spring + Flyway ~30s, see ADR 0034 §disponibilidade)
#     - DNS/Cloudflare Tunnel routing again
#   The COMPLETE drill is the VM clone described in proxmox-deploy.md §Restore drill.
#   This script is the piece that can be run every week without cloning a machine — and it
#   is the piece that usually hides the surprises (corrupted dump, missing role, divergent
#   Flyway version). Treat the number from here as the RTO FLOOR, never as the RTO.
#
# ISOLATION
# ---------
# The drill container comes up with `--network none`, its own name and an anonymous volume.
# It does not talk to the stack, does not join the compose networks and has no way to
# register on the tunnel — the most dangerous accident of the VM-clone drill (a SECOND
# connector on the same tunnel, making Cloudflare balance production traffic into the drill)
# is impossible here by construction.
#
# NO SECRET NEEDED. The official Postgres image authenticates local unix socket connections
# with `trust`; the drill talks to the database only through `docker exec`. Consequence:
# the drill proves SCHEMA, DATA and GRANTS — it does not prove role passwords (that is
# rls-cutover.sh, on the real database).
#
# EXIT CODES
#   0  restore ok, validation ok, within the RTO target
#   1  usage / pre-flight error
#   2  the RESTORE failed — the backup is no good (this is what the drill is for)
#   3  restored, but the VALIDATION failed
#   4  restored and validated, but BLEW the RTO target
#
set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROXMOX_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$PROXMOX_DIR/../.." && pwd)"

BACKUP_DIR="${BACKUP_DIR:-/srv/nora/backups}"
STATE_DIR="${NORA_STATE_DIR:-/srv/nora/state}"
DRILL_LOG="${DRILL_LOG:-$STATE_DIR/restore-drills.tsv}"
R001_SQL="${R001_SQL:-$REPO_ROOT/services/api/src/main/resources/db/operational/R001__provision_app_roles.sql}"
MIGRATION_DIR="${MIGRATION_DIR:-$REPO_ROOT/services/api/src/main/resources/db/migration}"

# Same image as production: measuring with another Postgres version measures another thing.
DRILL_IMAGE="${DRILL_IMAGE:-pgvector/pgvector:pg16}"
PG_USER="${POSTGRES_ADMIN_USER:-nora_admin}"
PRIMARY_DB="nora"
PLATFORM_DB="nora_platform"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-nora}"

TARGET_RTO="${TARGET_RTO_SECONDS:-7200}"     # ADR 0016 Gap 3
JOBS="${RESTORE_JOBS:-2}"
BOOT_TIMEOUT="${DRILL_BOOT_TIMEOUT:-120}"

DUMP_PRIMARY=""
DUMP_PLATFORM=""
FROM_DIR=""
USE_TMPFS=0
TMPFS_SIZE="${DRILL_TMPFS_SIZE:-2048}"
KEEP=0
DO_LIST=0
SKIP_PLATFORM=0
COMPARE_LIVE=auto
NOTE=""

usage() {
  cat <<EOF
$SCRIPT_NAME — ensaio de restore cronometrado num container descartável

USO
  $SCRIPT_NAME [opções]

  Sem argumentos: pega o dump mais recente de $BACKUP_DIR,
  restaura num Postgres descartável e imprime o RTO medido.

OPÇÕES
  --dump <arquivo>       Dump do banco primário (default: o mais recente em --backup-dir)
  --platform-dump <arq>  Dump do nora_platform (default: o mais recente, se houver)
  --from-dir <dir>       Diretório com dumps `pg_dump -Fc` (usa nora.dump/nora_platform.dump
                         e o baseline <db>-counts.tsv que estiver lá)
  --backup-dir <dir>     Onde procurar dumps (default: $BACKUP_DIR)
  --list                 Lista os backups disponíveis, com idade, e sai
  --skip-platform        Não ensaia o nora_platform
  --image <ref>          Imagem do Postgres do drill (default: $DRILL_IMAGE)
  --jobs <n>             Paralelismo do pg_restore (default: $JOBS)
  --target-rto <s>       Alvo de RTO em segundos (default: $TARGET_RTO = 2h, ADR 0016)
  --tmpfs                Data dir em RAM. Mais rápido, e por isso SUBESTIMA o RTO:
                         num desastre real o disco é disco. Use só pra smoke rápido.
  --compare-live <auto|yes|no>
                         Compara as contagens com o banco de produção que está de pé
                         (SELECT count(*), somente leitura). Default: $COMPARE_LIVE.
  --keep                 Não destrói o container ao final (para inspecionar). Ele fica
                         parado e ocupando disco — remova você mesmo.
  --note <texto>         Observação livre gravada na linha do histórico
  -h, --help             Esta ajuda

HISTÓRICO
  $DRILL_LOG
  Uma linha TSV por ensaio. O ADR 0016 pede drill trimestral; a tabela de
  proxmox-deploy.md §Restore drill se alimenta daqui.

EXEMPLOS
  $SCRIPT_NAME                                   # ensaio do backup mais recente
  $SCRIPT_NAME --list
  $SCRIPT_NAME --dump /srv/nora/backups/nora-20260807T030000Z.dump
  $SCRIPT_NAME --from-dir /srv/nora/backups/20260807T031500Z --note "ensaio mensal"
EOF
}

_ts()  { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
log()  { printf '[%s] %s\n'        "$(_ts)" "$*" >&2; }
ok()   { printf '[%s] OK    %s\n'  "$(_ts)" "$*" >&2; }
warn() { printf '[%s] AVISO %s\n'  "$(_ts)" "$*" >&2; }
err()  { printf '[%s] ERRO  %s\n'  "$(_ts)" "$*" >&2; }
die()  { err "$*"; exit "${2:-1}"; }
hr()   { printf '%s\n' "------------------------------------------------------------" >&2; }

now_ms() { date +%s%3N 2>/dev/null || printf '%s000' "$(date +%s)"; }
dur_s()  { awk -v a="$1" -v b="$2" 'BEGIN{ printf "%.1f", (b-a)/1000 }'; }
human_bytes() {
  awk -v b="${1:-0}" 'BEGIN{
    split("B KiB MiB GiB TiB", u, " "); i=1
    while (b >= 1024 && i < 5) { b /= 1024; i++ }
    printf (i==1 ? "%d %s" : "%.1f %s"), b, u[i]
  }'
}
human_secs() {
  awk -v s="${1:-0}" 'BEGIN{
    h = int(s/3600); m = int((s%3600)/60); r = s - h*3600 - m*60
    if (h > 0)      printf "%dh%02dm%02ds", h, m, r
    else if (m > 0) printf "%dm%02ds", m, r
    else            printf "%.1fs", s
  }'
}
file_size() { wc -c < "$1" | tr -d '[:space:]'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --dump)           DUMP_PRIMARY="${2:?--dump exige um valor}"; shift 2 ;;
    --platform-dump)  DUMP_PLATFORM="${2:?--platform-dump exige um valor}"; shift 2 ;;
    --from-dir)       FROM_DIR="${2:?--from-dir exige um valor}"; shift 2 ;;
    --backup-dir)     BACKUP_DIR="${2:?--backup-dir exige um valor}"; shift 2 ;;
    --list)           DO_LIST=1; shift ;;
    --skip-platform)  SKIP_PLATFORM=1; shift ;;
    --image)          DRILL_IMAGE="${2:?--image exige um valor}"; shift 2 ;;
    --jobs)           JOBS="${2:?--jobs exige um valor}"; shift 2 ;;
    --target-rto)     TARGET_RTO="${2:?--target-rto exige um valor}"; shift 2 ;;
    --tmpfs)          USE_TMPFS=1; shift ;;
    --compare-live)   COMPARE_LIVE="${2:?--compare-live exige auto|yes|no}"; shift 2 ;;
    --keep)           KEEP=1; shift ;;
    --note)           NOTE="${2:?--note exige um valor}"; shift 2 ;;
    -h|--help)        usage; exit 0 ;;
    *) err "opção desconhecida: $1"; echo >&2; usage >&2; exit 1 ;;
  esac
done

umask 077

case "$COMPARE_LIVE" in auto|yes|no) : ;; *) die "--compare-live aceita auto|yes|no" ;; esac

# ---------------------------------------------------------------------------
# Dump discovery
# ---------------------------------------------------------------------------
# dump_epoch <file> — the dump's instant. We prefer the timestamp in the NAME (it is UTC and
# survives copy/rsync, which touches the mtime); mtime is the fallback.
dump_epoch() {
  local f="$1" base stamp iso e
  base="$(basename "$f")"
  stamp="$(printf '%s' "$base" | sed -n 's/.*-\([0-9]\{8\}T[0-9]\{6\}Z\)\.dump$/\1/p')"
  if [ -n "$stamp" ]; then
    iso="$(printf '%s' "$stamp" | sed -E 's/^(....)(..)(..)T(..)(..)(..)Z$/\1-\2-\3T\4:\5:\6Z/')"
    if e="$(date -u -d "$iso" +%s 2>/dev/null)"; then printf '%s' "$e"; return; fi
  fi
  stat -c %Y "$f" 2>/dev/null || printf '0'
}

latest_dump() {  # <dir> <database-prefix>
  ls -1 "$1/$2"-*.dump 2>/dev/null | sort | tail -1 || true
}

if [ "$DO_LIST" -eq 1 ]; then
  [ -d "$BACKUP_DIR" ] || die "diretório de backups não existe: $BACKUP_DIR"
  printf '%-46s %10s %12s  %s\n' "ARQUIVO" "TAMANHO" "IDADE" "VERIFICADO" >&2
  found=0
  for f in $(ls -1 "$BACKUP_DIR"/*.dump 2>/dev/null | sort || true); do
    found=1
    age=$(( $(date +%s) - $(dump_epoch "$f") ))
    v="?"
    [ -f "$f.toc" ] && v="toc"
    [ -f "$f.sha256" ] && v="$v+sha256"
    printf '%-46s %10s %12s  %s\n' "$(basename "$f")" \
      "$(human_bytes "$(file_size "$f")")" "$(human_secs "$age")" "$v" >&2
  done
  [ "$found" -eq 1 ] || warn "nenhum *.dump em $BACKUP_DIR — o serviço 'backup' está de pé?"
  exit 0
fi

if [ -n "$FROM_DIR" ]; then
  [ -d "$FROM_DIR" ] || die "--from-dir não é um diretório: $FROM_DIR"
  [ -n "$DUMP_PRIMARY" ]  || DUMP_PRIMARY="$FROM_DIR/$PRIMARY_DB.dump"
  [ -n "$DUMP_PLATFORM" ] || DUMP_PLATFORM="$FROM_DIR/$PLATFORM_DB.dump"
fi

if [ -z "$DUMP_PRIMARY" ]; then
  [ -d "$BACKUP_DIR" ] || die "diretório de backups não existe: $BACKUP_DIR
       Informe --dump <arquivo> ou --backup-dir <dir>."
  DUMP_PRIMARY="$(latest_dump "$BACKUP_DIR" "$PRIMARY_DB")"
  [ -n "$DUMP_PRIMARY" ] || die "nenhum $PRIMARY_DB-*.dump em $BACKUP_DIR.
       O serviço 'backup' está rodando?  docker compose -p $COMPOSE_PROJECT ps backup
       Forçar um agora:  docker compose -p $COMPOSE_PROJECT exec backup /usr/local/bin/run-backup.sh --once"
fi
[ -s "$DUMP_PRIMARY" ] || die "dump primário vazio ou inexistente: $DUMP_PRIMARY"
if [ ! -r "$DUMP_PRIMARY" ]; then
  err "sem permissão de leitura em $DUMP_PRIMARY"
  err "  Causa provável: o serviço 'backup' roda como root (o compose sobrescreve o"
  err "  entrypoint e pula o gosu da imagem), então o dump nasce 0640 root:nora."
  err "  Conserto: entre no grupo 'nora' (relogin necessário)  ->  sudo usermod -aG nora \$USER"
  err "  ou rode o ensaio com sudo."
  die "abortando: não dá pra ensaiar um backup que não se consegue ler"
fi

if [ "$SKIP_PLATFORM" -eq 0 ] && [ -z "$DUMP_PLATFORM" ] && [ -d "$BACKUP_DIR" ]; then
  DUMP_PLATFORM="$(latest_dump "$BACKUP_DIR" "$PLATFORM_DB")"
fi
if [ "$SKIP_PLATFORM" -eq 0 ] && { [ -z "$DUMP_PLATFORM" ] || [ ! -s "$DUMP_PLATFORM" ]; }; then
  warn "sem dump de plataforma — ensaio só do banco primário."
  SKIP_PLATFORM=1
fi

# ---------------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "docker não encontrado. Rode o bootstrap-host.sh."
docker info >/dev/null 2>&1 || die "o daemon do Docker não responde (usuário no grupo 'docker'?)."

DUMP_BYTES="$(file_size "$DUMP_PRIMARY")"
DUMP_AGE=$(( $(date +%s) - $(dump_epoch "$DUMP_PRIMARY") ))

hr
log "ENSAIO DE RESTORE — NORA"
log "  dump:        $DUMP_PRIMARY"
log "  tamanho:     $(human_bytes "$DUMP_BYTES")"
log "  idade:       $(human_secs "$DUMP_AGE")   <- este é o RPO real neste instante"
[ "$SKIP_PLATFORM" -eq 0 ] && log "  plataforma:  $DUMP_PLATFORM"
log "  imagem:      $DRILL_IMAGE"
log "  alvo de RTO: $(human_secs "$TARGET_RTO") (ADR 0016 Gap 3)"

# The RPO declared in ADR 0034 is "up to 1 hour" (the BACKUP_INTERVAL_SECONDS). A dump much
# older than that means the backup service stopped producing — and nobody noticed.
if [ "$DUMP_AGE" -gt 7200 ]; then
  warn "o backup mais recente tem $(human_secs "$DUMP_AGE"). O ADR 0034 promete RPO de até 1h."
  warn "Ou o serviço 'backup' está parado, ou os dumps estão sendo reprovados na verificação:"
  warn "  docker compose -p $COMPOSE_PROJECT logs --tail 50 backup"
fi

# Checksum before any stopwatch: verifying integrity is not part of the RTO,
# it is part of knowing whether the file is a backup at all.
if [ -f "$DUMP_PRIMARY.sha256" ] && command -v sha256sum >/dev/null 2>&1; then
  if ( cd "$(dirname "$DUMP_PRIMARY")" && sha256sum -c --status "$(basename "$DUMP_PRIMARY").sha256" ); then
    ok "checksum do dump confere"
  else
    die "CHECKSUM NÃO CONFERE em $DUMP_PRIMARY — o arquivo corrompeu. Este backup não serve." 2
  fi
else
  warn "sem .sha256 ao lado do dump — integridade não conferida"
fi

mkdir -p "$STATE_DIR" 2>/dev/null || warn "não consegui criar $STATE_DIR — histórico não será gravado"

# ---------------------------------------------------------------------------
# Disposable container
# ---------------------------------------------------------------------------
DRILL_NAME="nora-drill-$(date -u '+%Y%m%dT%H%M%SZ')-$$"
DRILL_STARTED=0

# Guardrail: however improbable, a name colliding with production would be catastrophic.
case "$DRILL_NAME" in
  nora-postgres|nora-postgres-platform|nora-api|nora-web|nora-admin|nora-worker)
    die "nome de container do drill colidiu com um de produção — abortando" ;;
esac

cleanup() {
  local rc=$?
  set +e
  if [ "$DRILL_STARTED" -eq 1 ]; then
    if [ "$KEEP" -eq 1 ]; then
      warn "--keep: container '$DRILL_NAME' MANTIDO. Inspecione e remova depois:"
      warn "    docker exec -it $DRILL_NAME psql -U $PG_USER -d $PRIMARY_DB"
      warn "    docker rm -fv $DRILL_NAME"
    else
      log "destruindo o container do drill..."
      docker rm -fv "$DRILL_NAME" >/dev/null 2>&1 \
        && ok "container e volume anônimo removidos" \
        || warn "não consegui remover '$DRILL_NAME' — remova na mão: docker rm -fv $DRILL_NAME"
    fi
  fi
  exit "$rc"
}
trap cleanup EXIT INT TERM

dex()  { docker exec -i "$DRILL_NAME" "$@"; }
dpsql(){ local db="$1"; shift; docker exec -i "$DRILL_NAME" psql -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$db" "$@"; }
dq()   { local db="$1"; shift; docker exec -i "$DRILL_NAME" psql -tAq -U "$PG_USER" -d "$db" -c "$*" 2>/dev/null | tr -d '\r'; }

# ===========================================================================
# STOPWATCH RUNNING
# ===========================================================================
T_START="$(now_ms)"

# ---- PHASE 1: bring up a virgin Postgres ----------------------------------
hr
log "FASE 1/4 — subindo Postgres descartável ($DRILL_IMAGE)"
P1_A="$(now_ms)"

DOCKER_RUN=(docker run -d --name "$DRILL_NAME"
  --network none                     # total isolation: talks neither to the stack nor to the tunnel
  --label nora.role=restore-drill
  --shm-size 256m
  -e POSTGRES_DB="$PRIMARY_DB"
  -e POSTGRES_USER="$PG_USER"
  -e POSTGRES_PASSWORD="drill-$(date +%s)-descartavel"
  -e POSTGRES_INITDB_ARGS="--encoding=UTF8 --locale=C.UTF-8")

if [ "$USE_TMPFS" -eq 1 ]; then
  warn "--tmpfs: data dir em RAM. O número medido SUBESTIMA o RTO real (disco é mais lento)."
  DOCKER_RUN+=(--tmpfs "/var/lib/postgresql/data:rw,size=${TMPFS_SIZE}m,mode=1777")
fi
DOCKER_RUN+=("$DRILL_IMAGE")

if ! "${DOCKER_RUN[@]}" >/dev/null 2>&1; then
  err "não consegui subir o container do drill."
  err "A imagem existe localmente?  docker image inspect $DRILL_IMAGE"
  err "Se não:                      docker pull $DRILL_IMAGE"
  exit 1
fi
DRILL_STARTED=1

log "  aguardando o initdb (timeout ${BOOT_TIMEOUT}s)..."
READY=0
for _ in $(seq 1 "$BOOT_TIMEOUT"); do
  if docker exec "$DRILL_NAME" pg_isready -U "$PG_USER" -d "$PRIMARY_DB" -q >/dev/null 2>&1; then
    READY=1; break
  fi
  if [ "$(docker inspect -f '{{.State.Running}}' "$DRILL_NAME" 2>/dev/null)" != "true" ]; then
    err "o container do drill morreu durante o boot. Log:"
    docker logs --tail 30 "$DRILL_NAME" 2>&1 | sed 's/^/      /' >&2 || true
    exit 1
  fi
  sleep 1
done
[ "$READY" -eq 1 ] || {
  err "o Postgres do drill não ficou pronto em ${BOOT_TIMEOUT}s. Log:"
  docker logs --tail 30 "$DRILL_NAME" 2>&1 | sed 's/^/      /' >&2 || true
  exit 1
}
P1_B="$(now_ms)"
ok "  banco virgem pronto em $(dur_s "$P1_A" "$P1_B")s"

# ---- PHASE 2: roles BEFORE the data ---------------------------------------
# Same order as restore-into-proxmox.sh, for the same reason: a dump object that
# references a nonexistent role blows up the restore halfway through. And nora_telemetry is
# the one that goes missing without any error, zeroing the operator panel in silence (ADR 0026/0028).
hr
log "FASE 2/4 — provisionando os três roles do RLS"
P2_A="$(now_ms)"

rand_pw() {
  if command -v openssl >/dev/null 2>&1; then openssl rand -hex 16
  else head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \n'; fi
}
DRILL_APP_PW="$(rand_pw)"
DRILL_TEL_PW="$(rand_pw)"

dpsql "$PRIMARY_DB" -q -v app_password="$DRILL_APP_PW" -v telemetry_password="$DRILL_TEL_PW" <<'SQL'
SELECT 'CREATE ROLE nora_app LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_app')
\gexec
SELECT 'CREATE ROLE nora_telemetry LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_telemetry')
\gexec
SQL
dpsql "$PRIMARY_DB" -q -v app_password="$DRILL_APP_PW" \
  -c "ALTER ROLE nora_app WITH LOGIN PASSWORD :'app_password' NOBYPASSRLS"
dpsql "$PRIMARY_DB" -q -v telemetry_password="$DRILL_TEL_PW" \
  -c "ALTER ROLE nora_telemetry WITH LOGIN PASSWORD :'telemetry_password' BYPASSRLS"

P2_B="$(now_ms)"
ok "  nora_app (NOBYPASSRLS) e nora_telemetry (BYPASSRLS) criados em $(dur_s "$P2_A" "$P2_B")s"

# ---- PHASE 3: the restore --------------------------------------------------
hr
log "FASE 3/4 — pg_restore"
P3_A="$(now_ms)"
RESTORE_RC=0

restore_into() {  # <db> <file> <label>
  local db="$1" file="$2" label="$3" inner="/tmp/drill-$1.dump" rc=0
  log "  $label: copiando $(human_bytes "$(file_size "$file")") para o container..."
  docker cp "$file" "$DRILL_NAME:$inner" >/dev/null || { err "  $label: docker cp falhou"; return 1; }

  log "  $label: pg_restore --no-owner --no-privileges -j $JOBS ..."
  docker exec -i "$DRILL_NAME" pg_restore \
      --no-owner --no-privileges --exit-on-error \
      -j "$JOBS" -U "$PG_USER" -d "$db" "$inner" >/dev/null 2>"/tmp/drill-$db.err" || rc=$?
  docker exec -i "$DRILL_NAME" rm -f "$inner" >/dev/null 2>&1 || true

  if [ "$rc" -ne 0 ]; then
    err "  $label: pg_restore falhou (código $rc):"
    sed 's/^/      /' "/tmp/drill-$db.err" 2>/dev/null | tail -20 >&2 || true
    rm -f "/tmp/drill-$db.err"
    return 1
  fi
  rm -f "/tmp/drill-$db.err"
  ok "  $label: restaurado"
  return 0
}

restore_into "$PRIMARY_DB" "$DUMP_PRIMARY" "PRIMÁRIO" || RESTORE_RC=1

if [ "$SKIP_PLATFORM" -eq 0 ] && [ "$RESTORE_RC" -eq 0 ]; then
  # Partial fidelity, on purpose: in production there are TWO servers (ADR 0022, isolated
  # blast radius). Here nora_platform comes in as a second DATABASE in the same cluster.
  # What is measured — restore time and integrity — does not change; the isolation does.
  dpsql postgres -q -c "CREATE DATABASE $PLATFORM_DB" >/dev/null 2>&1 || true
  restore_into "$PLATFORM_DB" "$DUMP_PLATFORM" "PLATAFORMA" \
    || warn "  restore do nora_platform falhou — o control plane sobe degradado (admin -> 503)"
fi

# GRANTs after the data: R001 references the `nora` schema and the function
# nora.current_tenant_id(), which only exist after V016 comes in with the dump.
if [ "$RESTORE_RC" -eq 0 ] && [ -f "$R001_SQL" ]; then
  log "  aplicando R001 (GRANTs + DEFAULT PRIVILEGES)..."
  if dpsql "$PRIMARY_DB" -q -v app_password="$DRILL_APP_PW" -v telemetry_password="$DRILL_TEL_PW" \
       < "$R001_SQL" >/dev/null 2>&1; then
    ok "  R001 aplicado"
  else
    warn "  R001 falhou no drill — investigue ANTES de precisar dele num restore real:"
    warn "    $R001_SQL"
  fi
elif [ ! -f "$R001_SQL" ]; then
  warn "  R001 não encontrado ($R001_SQL) — GRANTs não aplicados; a validação do nora_app vai reprovar"
fi

P3_B="$(now_ms)"
[ "$RESTORE_RC" -eq 0 ] && ok "  restore concluído em $(dur_s "$P3_A" "$P3_B")s"

# ---- PHASE 4: validation ---------------------------------------------------
hr
log "FASE 4/4 — validação"
P4_A="$(now_ms)"
VALIDATION_FAILED=0
vfail() { err "  VALIDAÇÃO: $*"; VALIDATION_FAILED=1; }

TABLES=0; TENANTS=0; FLYWAY_VER="-"

if [ "$RESTORE_RC" -eq 0 ]; then
  TABLES="$(dq "$PRIMARY_DB" "SELECT count(*) FROM information_schema.tables WHERE table_schema='public' AND table_type='BASE TABLE'")"
  TABLES="${TABLES:-0}"
  log "  tabelas em public: $TABLES"
  [ "$TABLES" -gt 0 ] || vfail "nenhuma tabela em public — o dump não trouxe nada"

  # -- Flyway --
  if [ "$(dq "$PRIMARY_DB" "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL")" = "t" ]; then
    failed="$(dq "$PRIMARY_DB" "SELECT count(*) FROM flyway_schema_history WHERE success = false")"
    [ "${failed:-0}" -eq 0 ] || vfail "$failed migration(s) com success=false no histórico do dump"
    FLYWAY_VER="$(dq "$PRIMARY_DB" "SELECT version FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank DESC LIMIT 1")"
    FLYWAY_VER="${FLYWAY_VER:--}"
    log "  flyway: última versão aplicada = V$FLYWAY_VER"
    if [ -d "$MIGRATION_DIR" ]; then
      repo_ver="$(ls "$MIGRATION_DIR" 2>/dev/null | sed -n 's/^V0*\([0-9][0-9]*\)__.*\.sql$/\1/p' | sort -n | tail -1)"
      db_ver="$(printf '%s' "$FLYWAY_VER" | sed 's/^0*//')"
      if [ -n "$repo_ver" ] && [ -n "$db_ver" ] && [ "$db_ver" -eq "$db_ver" ] 2>/dev/null; then
        if   [ "$db_ver" -eq "$repo_ver" ]; then ok "  schema na última versão do repo (V$repo_ver)"
        elif [ "$db_ver" -lt "$repo_ver" ]; then warn "  dump em V$db_ver, repo em V$repo_ver — o Flyway da API aplicaria $(( repo_ver - db_ver )) migration(s) no boot; esse tempo NÃO está medido aqui"
        else vfail "dump em V$db_ver, MAIOR que a V$repo_ver do repo — a imagem da API está atrás do dado"
        fi
      fi
    fi
  else
    vfail "flyway_schema_history não existe — o dump veio de um banco não migrado"
  fi

  # -- per-tenant smoke --
  if [ "$(dq "$PRIMARY_DB" "SELECT to_regclass('public.tenants') IS NOT NULL")" = "t" ]; then
    TENANTS="$(dq "$PRIMARY_DB" "SELECT count(*) FROM tenants")"
    TENANTS="${TENANTS:-0}"
    if [ "$TENANTS" -gt 0 ]; then
      log "  smoke por tenant ($TENANTS tenants):"
      docker exec -i "$DRILL_NAME" psql -U "$PG_USER" -d "$PRIMARY_DB" -P pager=off -c \
        "SELECT t.slug, t.status,
                (SELECT count(*) FROM users u            WHERE u.tenant_id = t.id) AS usuarios,
                (SELECT count(*) FROM meetings m         WHERE m.tenant_id = t.id) AS reunioes,
                (SELECT count(*) FROM meeting_analyses a WHERE a.tenant_id = t.id) AS analises
           FROM tenants t ORDER BY t.slug" 2>/dev/null | sed 's/^/      /' >&2 || \
        warn "  smoke por tenant falhou (tabelas users/meetings/meeting_analyses ausentes?)"
    else
      vfail "ZERO tenants — ou a origem estava vazia, ou o restore falhou em silêncio"
    fi
  else
    vfail "tabela 'tenants' não existe — restore incompleto"
  fi

  # -- roles: the point of ADR 0028 --
  roles_out="$(dq "$PRIMARY_DB" "SELECT rolname||':'||rolcanlogin::text||':'||rolbypassrls::text FROM pg_roles WHERE rolname IN ('nora_app','nora_telemetry') ORDER BY rolname")"
  printf '%s\n' "$roles_out" | sed 's/^/      /' >&2
  printf '%s' "$roles_out" | grep -q 'nora_app:t:f' \
    || vfail "nora_app precisa ser LOGIN e NOBYPASSRLS (senão o RLS não vale nada)"
  printf '%s' "$roles_out" | grep -q 'nora_telemetry:t:t' \
    || vfail "nora_telemetry precisa ser LOGIN e BYPASSRLS (senão o painel do operador zera EM SILÊNCIO)"

  # Does nora_app see any table? Proves the R001 GRANTs took hold over the restored dump.
  vis="$(docker exec -i "$DRILL_NAME" psql -tAq -U nora_app -d "$PRIMARY_DB" \
          -c "SELECT count(*) FROM information_schema.table_privileges WHERE grantee='nora_app' AND privilege_type='SELECT'" 2>/dev/null | tr -d '[:space:]' || echo 0)"
  if [ "${vis:-0}" -gt 0 ]; then
    ok "  nora_app conecta e tem SELECT em $vis tabelas"
  else
    vfail "nora_app sem GRANT nenhum — a API não subiria contra este restore"
  fi

  # -- comparison with live production (read only) --
  do_cmp=0
  if [ "$COMPARE_LIVE" = "yes" ]; then do_cmp=1
  elif [ "$COMPARE_LIVE" = "auto" ] && \
       [ "$(docker inspect -f '{{.State.Running}}' nora-postgres 2>/dev/null || echo false)" = "true" ]; then
    do_cmp=1
  fi
  if [ "$do_cmp" -eq 1 ]; then
    log "  comparando com a produção (nora-postgres, somente leitura)..."
    for t in tenants users meetings; do
      live="$(docker exec -i nora-postgres psql -tAq -U "$PG_USER" -d "$PRIMARY_DB" -c "SELECT count(*) FROM $t" 2>/dev/null | tr -d '[:space:]' || echo '')"
      drill="$(dq "$PRIMARY_DB" "SELECT count(*) FROM $t")"
      if [ -z "$live" ]; then
        warn "    $t: não consegui ler a produção"
      elif [ "$live" = "$drill" ]; then
        ok "    $t: $drill (igual à produção)"
      else
        # Divergence here is EXPECTED: the dump is up to 1h old and production kept
        # writing. What matters is the ORDER OF MAGNITUDE, not equality.
        log "    $t: drill=$drill producao=$live (delta=$(( live - drill )) linhas escritas desde o dump)"
      fi
    done
  fi
else
  vfail "restore falhou — nada a validar"
fi

P4_B="$(now_ms)"
T_END="$(now_ms)"

# ===========================================================================
# REPORT
# ===========================================================================
S1="$(dur_s "$P1_A" "$P1_B")"
S2="$(dur_s "$P2_A" "$P2_B")"
S3="$(dur_s "$P3_A" "$P3_B")"
S4="$(dur_s "$P4_A" "$P4_B")"
TOTAL="$(dur_s "$T_START" "$T_END")"
TOTAL_INT="$(printf '%.0f' "$TOTAL")"

pct() { awk -v p="$1" -v t="$2" 'BEGIN{ if (t+0 == 0) print "0"; else printf "%.0f", (p/t)*100 }'; }

hr
printf '\n' >&2
printf '  RTO MEDIDO — ensaio de restore NORA\n' >&2
printf '  %s\n' "$(date -u '+%Y-%m-%d %H:%M:%SZ')" >&2
printf '  ----------------------------------------------------------\n' >&2
printf '  fase 1  subir Postgres virgem .......... %8ss  (%s%%)\n' "$S1" "$(pct "$S1" "$TOTAL")" >&2
printf '  fase 2  provisionar roles do RLS ....... %8ss  (%s%%)\n' "$S2" "$(pct "$S2" "$TOTAL")" >&2
printf '  fase 3  pg_restore ..................... %8ss  (%s%%)\n' "$S3" "$(pct "$S3" "$TOTAL")" >&2
printf '  fase 4  validação ...................... %8ss  (%s%%)\n' "$S4" "$(pct "$S4" "$TOTAL")" >&2
printf '  ----------------------------------------------------------\n' >&2
printf '  TOTAL (camada de dado) ................. %8ss  = %s\n' "$TOTAL" "$(human_secs "$TOTAL_INT")" >&2
printf '  alvo do ADR 0016 ....................... %8ss  = %s\n' "$TARGET_RTO" "$(human_secs "$TARGET_RTO")" >&2
printf '\n' >&2
printf '  dump ....... %s, %s de idade\n' "$(human_bytes "$DUMP_BYTES")" "$(human_secs "$DUMP_AGE")" >&2
printf '  restaurado . %s tabelas, %s tenants, flyway V%s\n' "$TABLES" "$TENANTS" "$FLYWAY_VER" >&2
printf '\n' >&2

RTO_OK=1
if [ "$TOTAL_INT" -gt "$TARGET_RTO" ]; then RTO_OK=0; fi

# The warning that keeps this number from becoming internal propaganda.
warn "ESTE NÚMERO É O PISO DO RTO, NÃO O RTO."
warn "Não estão medidos aqui: detecção do incidente e decisão humana, restaurar a VM no"
warn "Proxmox, boot do host, docker compose pull, boot da stack (Spring+Flyway ~30s) e a"
warn "volta do DNS/túnel. O ensaio completo é o clone de VM em proxmox-deploy.md"
warn "§Restore drill — este script cobre a parte que dá pra repetir toda semana."
[ "$USE_TMPFS" -eq 1 ] && warn "E foi rodado com --tmpfs: some ainda mais do disco real."

# ---------------------------------------------------------------------------
# History (append-only)
# ---------------------------------------------------------------------------
VERDICT="OK"
[ "$RESTORE_RC" -ne 0 ] && VERDICT="RESTORE_FALHOU"
[ "$RESTORE_RC" -eq 0 ] && [ "$VALIDATION_FAILED" -ne 0 ] && VERDICT="VALIDACAO_FALHOU"
[ "$RESTORE_RC" -eq 0 ] && [ "$VALIDATION_FAILED" -eq 0 ] && [ "$RTO_OK" -eq 0 ] && VERDICT="RTO_ESTOUROU"

if [ -d "$STATE_DIR" ] && [ -w "$STATE_DIR" ]; then
  if [ ! -f "$DRILL_LOG" ]; then
    printf 'data_utc\tdump\tbytes\tidade_s\tfase1_s\tfase2_s\tfase3_s\tfase4_s\ttotal_s\talvo_s\tveredito\ttabelas\ttenants\tflyway\tnota\n' > "$DRILL_LOG"
  fi
  printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
    "$(_ts)" "$(basename "$DUMP_PRIMARY")" "$DUMP_BYTES" "$DUMP_AGE" \
    "$S1" "$S2" "$S3" "$S4" "$TOTAL" "$TARGET_RTO" "$VERDICT" \
    "$TABLES" "$TENANTS" "$FLYWAY_VER" "${NOTE:--}" >> "$DRILL_LOG"
  log "histórico: $DRILL_LOG"
fi

hr
if [ "$RESTORE_RC" -ne 0 ]; then
  err "ENSAIO REPROVADO: o RESTORE falhou. O backup em $DUMP_PRIMARY NÃO é recuperável."
  err "Isto é exatamente o que o drill existe pra descobrir — antes do incidente."
  exit 2
fi
if [ "$VALIDATION_FAILED" -ne 0 ]; then
  err "ENSAIO REPROVADO na VALIDAÇÃO: os dados voltaram, mas não íntegros/utilizáveis."
  err "Reveja os itens marcados 'VALIDAÇÃO' acima antes de confiar neste backup."
  exit 3
fi
if [ "$RTO_OK" -eq 0 ]; then
  warn "ENSAIO OK, mas o RTO da camada de dado ($(human_secs "$TOTAL_INT")) já estourou"
  warn "sozinho o alvo de $(human_secs "$TARGET_RTO") do ADR 0016 — sem contar VM, boot e DNS."
  warn "Ou o alvo muda (ADR sucessor: ADRs aceitos são imutáveis), ou o procedimento muda."
  exit 4
fi
ok "ENSAIO APROVADO — restore íntegro em $(human_secs "$TOTAL_INT") na camada de dado."
log "Próximo: rodar o ensaio COMPLETO (clone de VM) trimestralmente, ADR 0016 Gap 3."
exit 0
