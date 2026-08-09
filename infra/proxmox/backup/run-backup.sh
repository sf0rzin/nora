#!/bin/sh
#
# run-backup.sh — entrypoint of the `backup` service (docker-compose.yml §backup).
#
# Replaces the 7-day PITR of Postgres Flexible Server with a periodic logical dump.
# It is not equivalent: PITR recovered any point in time; here the RPO is, worst case,
# BACKUP_INTERVAL_SECONDS (1h by default). Recorded in ADR 0034 §Availability.
#
# WHAT IT DOES, EVERY CYCLE
#   1. `pg_dump -Fc` of `nora` (postgres service) and `nora_platform` (postgres-platform).
#   2. VERIFIES the freshly written file with `pg_restore --list`. Only after it passes
#      does the `.part` become a `.dump`. A dump that does not open never gets the final
#      name — an unverified backup is not a backup, it is a file that gives hope.
#   3. Writes the SHA-256 and a `.toc` (the dump index, useful to inspect without restoring).
#   4. Prunes by BACKUP_RETENTION_DAYS, with TWO brakes (see §PRUNING).
#   5. Sleeps BACKUP_INTERVAL_SECONDS and repeats.
#
# WHY /bin/sh AND NOT BASH
#   The image is `postgres:16-alpine` and the compose calls `["/bin/sh", ".../run-backup.sh"]`.
#   There is no bash here. Everything in this file is POSIX + what busybox ash guarantees.
#
# LOGS
#   One logfmt line per event on STDOUT. Alloy reads the Docker socket and ships it to
#   Loki (observability/config.alloy); the `level=` field is what Loki 3.x uses to
#   derive `detected_level`. No logging to a file: a file inside a container is a
#   log nobody reads.
#
# §PRUNING — why it is not just a `find -mtime +N -delete`
#   Two real failure modes, both already seen in someone else's production:
#     (a) pg_dump breaks (rotated password, full disk, database down) and nobody looks.
#         After RETENTION days, pruning deletes the last good backup. The next incident
#         finds an empty directory. Brake: pruning a database ONLY runs if the current
#         cycle produced a verified dump OF THAT database.
#     (b) a misconfigured interval (e.g. 24h with 1-day retention) leaves the window with
#         zero files. Brake: BACKUP_MIN_KEEP (default 3) survives retention,
#         however old they are.
#
# A BACKUP ON THE SAME HOST IS NOT A BACKUP
#   These dumps land in ${BACKUP_DIR:-/srv/nora/backups} — the SAME disk as Postgres.
#   They cover: a wrong DROP TABLE, a destructive migration, logical corruption.
#   They do NOT cover: loss of the host. For that there is the Proxmox Backup Server
#   snapshot and an external copy (rclone/rsync). See proxmox-deploy.md §On-demand manual backup.
#
set -eu

# `pipefail` is not POSIX. The busybox ash in postgres:16-alpine has it, but we test
# before turning it on so the script stays valid under any /bin/sh.
# shellcheck disable=SC3040
if (set -o pipefail) 2>/dev/null; then set -o pipefail; fi

SCRIPT_NAME="$(basename "$0")"

# ---------------------------------------------------------------------------
# Configuration — all via env (it is a compose service), with flags for manual use.
# ---------------------------------------------------------------------------
BACKUP_DIR="${BACKUP_TARGET_DIR:-/backups}"
INTERVAL="${BACKUP_INTERVAL_SECONDS:-3600}"
RETENTION="${BACKUP_RETENTION_DAYS:-14}"
MIN_KEEP="${BACKUP_MIN_KEEP:-3}"
COMPRESS="${BACKUP_COMPRESS_LEVEL:-6}"

PRIMARY_HOST="${PGHOST:-postgres}"
PRIMARY_DB="${BACKUP_PRIMARY_DB:-nora}"
PLATFORM_HOST="${PLATFORM_PGHOST:-postgres-platform}"
PLATFORM_DB="${BACKUP_PLATFORM_DB:-nora_platform}"
PG_USER="${PGUSER:-nora_admin}"
PRIMARY_PW="${PGPASSWORD:-}"
PLATFORM_PW="${PLATFORM_PGPASSWORD:-$PRIMARY_PW}"

# auto      -> if postgres-platform does not answer, the 'platform' profile is off: warn and go on
# required  -> a platform failure fails the cycle
# off       -> does not even try
PLATFORM_MODE="${BACKUP_PLATFORM_MODE:-auto}"

# Absolute floor of free space (KiB) to even attempt a dump. Filling the /backups
# disk is worse than losing a cycle: in the default layout /srv is the same filesystem
# as the Postgres volume, and Postgres out of space stops accepting writes.
MIN_FREE_KB="${BACKUP_MIN_FREE_KB:-262144}"   # 256 MiB

ONCE=0
PRUNE_ONLY=0

usage() {
  cat <<EOF
$SCRIPT_NAME — dump lógico periódico e verificado dos bancos da stack NORA

USO
  $SCRIPT_NAME [--once] [--prune-only] [opções]

  Sem argumentos é o modo serviço: laço infinito a cada BACKUP_INTERVAL_SECONDS.
  É assim que o compose o executa (entrypoint do serviço 'backup').

OPÇÕES
  --once                 Roda UM ciclo e sai. Código de saída != 0 se algum dump
                         falhou — é o modo pra backup manual antes de migration
                         destrutiva, e o que o restore-drill.sh usa.
  --prune-only           Só aplica a retenção, não gera dump novo.
  --dir <caminho>        Diretório de saída (default: $BACKUP_DIR)
  --interval <segundos>  Intervalo entre ciclos (default: $INTERVAL)
  --retention <dias>     Retenção em dias; 0 desliga a poda (default: $RETENTION)
  --min-keep <n>         Nº de dumps por banco que a poda NUNCA apaga (default: $MIN_KEEP)
  --platform <auto|required|off>
                         Tratamento do banco de plataforma (default: $PLATFORM_MODE)
  -h, --help             Esta ajuda

VARIÁVEIS (vêm do docker-compose.yml)
  PGHOST=$PRIMARY_HOST  PGUSER=$PG_USER  PGPASSWORD=<oculta>
  PLATFORM_PGHOST=$PLATFORM_HOST  PLATFORM_PGPASSWORD=<oculta>
  BACKUP_INTERVAL_SECONDS  BACKUP_RETENTION_DAYS
  BACKUP_MIN_KEEP  BACKUP_MIN_FREE_KB  BACKUP_COMPRESS_LEVEL  BACKUP_PLATFORM_MODE

SAÍDA (em $BACKUP_DIR)
  <banco>-<UTC>.dump          pg_dump -Fc, JÁ verificado com pg_restore --list
  <banco>-<UTC>.dump.sha256   checksum
  <banco>-<UTC>.dump.toc      índice do dump (inspeção sem restaurar)
  <banco>-<UTC>.dump.part     dump em andamento ou REPROVADO (nunca é um backup)

EXEMPLOS
  # immediate backup before a destructive migration
  docker compose -p nora exec backup /usr/local/bin/run-backup.sh --once

  # what is left after retention
  ls -lh /srv/nora/backups | tail
EOF
}

while [ $# -gt 0 ]; do
  case "$1" in
    --once)        ONCE=1; shift ;;
    --prune-only)  PRUNE_ONLY=1; ONCE=1; shift ;;
    --dir)         BACKUP_DIR="${2:?--dir exige um valor}"; shift 2 ;;
    --interval)    INTERVAL="${2:?--interval exige um valor}"; shift 2 ;;
    --retention)   RETENTION="${2:?--retention exige um valor}"; shift 2 ;;
    --min-keep)    MIN_KEEP="${2:?--min-keep exige um valor}"; shift 2 ;;
    --platform)    PLATFORM_MODE="${2:?--platform exige auto|required|off}"; shift 2 ;;
    -h|--help)     usage; exit 0 ;;
    *) printf 'opção desconhecida: %s\n\n' "$1" >&2; usage >&2; exit 1 ;;
  esac
done

# Dumps carry tenant PII (ADR 0029 / LGPD): nothing world-readable. But 077 would be
# TOO TIGHT here, and the reason is not obvious: the compose overrides the entrypoint
# (docker-compose.yml §backup), which SKIPS the official image's docker-entrypoint.sh —
# that is what would do the `gosu postgres`. Without it, this process runs as ROOT, and
# with 077 the dumps are born 0600 root:root. Practical result: restore-drill.sh and the
# operator, running as a normal user on the host, get "Permission denied" on the backup itself.
# 027 + the setgid bit on the directory (bootstrap-host.sh creates /srv/nora/backups as
# root:nora 2750) gives 0640 root:nora — readable by whoever operates, invisible to the rest.
umask 027

# ---------------------------------------------------------------------------
# Log — logfmt, one line per event, on stdout.
# ---------------------------------------------------------------------------
_ts() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
logf() {
  _lvl="$1"; shift
  printf 'ts=%s level=%s component=backup %s\n' "$(_ts)" "$_lvl" "$*"
}
info()  { logf info  "$@"; }
warn()  { logf warn  "$@"; }
error() { logf error "$@"; }

# A value with a space in logfmt needs quotes.
q() { printf '"%s"' "$*"; }

human_kb() {
  awk -v k="${1:-0}" 'BEGIN{
    b = k * 1024; split("B KiB MiB GiB TiB", u, " "); i = 1
    while (b >= 1024 && i < 5) { b /= 1024; i++ }
    printf (i == 1 ? "%d%s" : "%.1f%s"), b, u[i]
  }'
}
human_bytes() {
  awk -v b="${1:-0}" 'BEGIN{
    split("B KiB MiB GiB TiB", u, " "); i = 1
    while (b >= 1024 && i < 5) { b /= 1024; i++ }
    printf (i == 1 ? "%d%s" : "%.1f%s"), b, u[i]
  }'
}
file_size() { wc -c < "$1" 2>/dev/null | tr -d '[:space:]'; }

# ---------------------------------------------------------------------------
# Clean shutdown.
#
# `docker stop` sends SIGTERM and waits 10s before SIGKILL. A foreground `sleep 3600`
# would only hand control back to the shell at the end of the sleep — the trap would
# sit in the queue and the container would die of SIGKILL, potentially mid pg_dump.
# That is why the sleep is a child + `wait`: `wait` IS interrupted by a trapped signal.
# ---------------------------------------------------------------------------
STOP=0
SLEEP_PID=""
on_term() {
  STOP=1
  [ -n "$SLEEP_PID" ] && kill "$SLEEP_PID" 2>/dev/null || true
  info "event=shutdown.signal msg=$(q 'sinal recebido; encerrando após o ciclo atual')"
}
trap on_term TERM INT

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
for _bin in pg_dump pg_restore pg_isready psql; do
  command -v "$_bin" >/dev/null 2>&1 || {
    error "event=preflight.fail bin=$_bin msg=$(q 'binário ausente na imagem — o serviço backup precisa de postgres:16-alpine')"
    exit 1
  }
done

if [ ! -d "$BACKUP_DIR" ]; then
  error "event=preflight.fail dir=$BACKUP_DIR msg=$(q 'diretório de backup não existe; confira o bind mount BACKUP_DIR do compose')"
  exit 1
fi
if [ ! -w "$BACKUP_DIR" ]; then
  error "event=preflight.fail dir=$BACKUP_DIR msg=$(q 'diretório de backup não é gravável; no host: chown do BACKUP_DIR para o uid do container')"
  exit 1
fi
if [ -z "$PRIMARY_PW" ]; then
  error "event=preflight.fail msg=$(q 'PGPASSWORD vazia — o compose injeta POSTGRES_ADMIN_PASSWORD; o .env decifrou certo?')"
  exit 1
fi
case "$PLATFORM_MODE" in
  auto|required|off) : ;;
  *) error "event=preflight.fail msg=$(q "BACKUP_PLATFORM_MODE inválido: $PLATFORM_MODE (use auto|required|off)")"; exit 1 ;;
esac

info "event=start dir=$BACKUP_DIR interval_s=$INTERVAL retention_d=$RETENTION min_keep=$MIN_KEEP platform_mode=$PLATFORM_MODE once=$ONCE"
if [ "$RETENTION" -eq 0 ]; then
  warn "event=config.notice msg=$(q 'BACKUP_RETENTION_DAYS=0: poda DESLIGADA, o diretório cresce até encher o disco')"
fi

# ---------------------------------------------------------------------------
# Disk space
# ---------------------------------------------------------------------------
free_kb() { df -P "$BACKUP_DIR" 2>/dev/null | awk 'NR==2 {print $4}'; }

# last_dump_kb <database> — size of the most recent dump of that database, in KiB (0 if none).
last_dump_kb() {
  _f="$(ls -1 "$BACKUP_DIR/$1"-*.dump 2>/dev/null | sort | tail -1 || true)"
  [ -n "$_f" ] || { printf '0'; return; }
  _b="$(file_size "$_f")"
  printf '%s' "$(( ${_b:-0} / 1024 ))"
}

# ---------------------------------------------------------------------------
# One backup
# ---------------------------------------------------------------------------
# backup_one <label> <host> <db> <password> -> 0 ok | 1 failed | 2 unavailable (skip)
backup_one() {
  _label="$1"; _host="$2"; _db="$3"; _pw="$4"
  _stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  _out="$BACKUP_DIR/$_db-$_stamp.dump"
  _part="$_out.part"

  # 1) is the database up? pg_isready fails in 5s; pg_dump would hang on the timeout.
  if ! PGPASSWORD="$_pw" pg_isready -h "$_host" -U "$PG_USER" -d "$_db" -t 5 >/dev/null 2>&1; then
    error "event=dump.unreachable db=$_db host=$_host msg=$(q 'pg_isready falhou: banco fora, DNS interno, ou senha/rede')"
    return 2
  fi

  # 2) space. Estimate = size of this database's last dump (the base only grows).
  _need_kb="$(last_dump_kb "$_db")"
  _free_kb="$(free_kb)"
  _free_kb="${_free_kb:-0}"
  if [ "$_free_kb" -lt "$MIN_FREE_KB" ]; then
    error "event=dump.nospace db=$_db free=$(human_kb "$_free_kb") floor=$(human_kb "$MIN_FREE_KB") msg=$(q 'espaço abaixo do piso; pulando para não encher o disco do Postgres')"
    return 1
  fi
  if [ "$_need_kb" -gt 0 ] && [ "$_free_kb" -lt $(( _need_kb * 2 )) ]; then
    warn "event=dump.lowspace db=$_db free=$(human_kb "$_free_kb") last_dump=$(human_kb "$_need_kb") msg=$(q 'menos que 2x o último dump livre; reduza BACKUP_RETENTION_DAYS ou aumente o disco')"
  fi

  rm -f "$_part"
  _t0="$(date +%s)"

  # 3) the dump. -Fc is mandatory: it is the format pg_restore reads selectively and in
  # parallel (-j), and the only one restore-into-proxmox.sh/restore-drill.sh accept.
  # No --no-sync on purpose: here durability matters more than the seconds it costs.
  if ! PGPASSWORD="$_pw" pg_dump -Fc -Z "$COMPRESS" \
        -h "$_host" -U "$PG_USER" -d "$_db" -f "$_part" 2>"$_part.err"; then
    error "event=dump.fail db=$_db msg=$(q "$(tr '\n' ' ' < "$_part.err" | cut -c1-300)")"
    rm -f "$_part" "$_part.err"
    return 1
  fi
  rm -f "$_part.err"
  _t1="$(date +%s)"
  _bytes="$(file_size "$_part")"
  _bytes="${_bytes:-0}"

  # 4) VERIFICATION. Runs on the .part: the final name only exists if pg_restore managed
  # to read the whole index. So `ls *.dump` is, by construction, the list of valid backups.
  if ! pg_restore --list "$_part" > "$_part.toc" 2>"$_part.tocerr"; then
    error "event=verify.fail db=$_db bytes=$_bytes msg=$(q "pg_restore --list rejeitou o arquivo: $(tr '\n' ' ' < "$_part.tocerr" | cut -c1-200)")"
    error "event=verify.discard db=$_db file=$_part.part msg=$(q 'dump REPROVADO e descartado; backup não verificado não é backup')"
    rm -f "$_part" "$_part.toc" "$_part.tocerr"
    return 1
  fi
  rm -f "$_part.tocerr"

  _entries="$(grep -cv '^;' "$_part.toc" 2>/dev/null || true)"
  _entries="${_entries:-0}"
  _tables="$(grep -c ' TABLE DATA ' "$_part.toc" 2>/dev/null || true)"
  _tables="${_tables:-0}"
  if [ "$_entries" -eq 0 ]; then
    error "event=verify.empty db=$_db msg=$(q 'TOC sem nenhuma entrada — o dump abre mas está vazio; banco errado?')"
    rm -f "$_part" "$_part.toc"
    return 1
  fi

  # 5) atomic promotion: .part -> .dump. From here on it is a backup.
  mv -f "$_part.toc" "$_out.toc"
  mv -f "$_part" "$_out"

  # 6) checksum, so the restore detects transport corruption (restore-into-proxmox
  # checks this file before touching the database).
  if command -v sha256sum >/dev/null 2>&1; then
    ( cd "$BACKUP_DIR" && sha256sum "$(basename "$_out")" > "$(basename "$_out").sha256" )
  else
    warn "event=checksum.skip db=$_db msg=$(q 'sha256sum ausente na imagem')"
  fi

  info "event=dump.ok db=$_db label=$_label file=$(basename "$_out") bytes=$_bytes size=$(human_bytes "$_bytes") duration_s=$(( _t1 - _t0 )) toc_entries=$_entries tables=$_tables verified=true"
  return 0
}

# ---------------------------------------------------------------------------
# Pruning
# ---------------------------------------------------------------------------
# prune_one <database> — only called when the cycle produced a verified dump of this database.
prune_one() {
  _db="$1"
  [ "$RETENTION" -gt 0 ] || return 0

  _total="$(ls -1 "$BACKUP_DIR/$_db"-*.dump 2>/dev/null | wc -l | tr -d '[:space:]' || true)"
  _total="${_total:-0}"
  [ "$_total" -gt "$MIN_KEEP" ] || {
    info "event=prune.skip db=$_db total=$_total min_keep=$MIN_KEEP msg=$(q 'nada a podar: no piso de retenção')"
    return 0
  }

  # -mtime is POSIX and the mtime is trustworthy because we are the ones writing the file.
  _old="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name "$_db-*.dump" -mtime "+$RETENTION" 2>/dev/null | sort || true)"
  [ -n "$_old" ] || return 0

  _removed=0
  _freed=0
  for _f in $_old; do
    [ -f "$_f" ] || continue
    # Brake (b): never go below MIN_KEEP, however old the file is.
    if [ "$_total" -le "$MIN_KEEP" ]; then
      warn "event=prune.floor db=$_db min_keep=$MIN_KEEP msg=$(q 'retenção pararia abaixo do piso; arquivos antigos mantidos')"
      break
    fi
    _sz="$(file_size "$_f")"
    rm -f "$_f" "$_f.sha256" "$_f.toc"
    _total=$(( _total - 1 ))
    _removed=$(( _removed + 1 ))
    _freed=$(( _freed + ${_sz:-0} ))
    info "event=prune.delete db=$_db file=$(basename "$_f") age_days_gt=$RETENTION"
  done

  [ "$_removed" -gt 0 ] && \
    info "event=prune.ok db=$_db removed=$_removed freed=$(human_bytes "$_freed") remaining=$_total"
  return 0
}

# Leftovers of interrupted runs (SIGKILL mid pg_dump). They are not backups:
# they never went through verification. Only cleans up the old ones, so it does not delete
# a live .part of another process running in parallel (e.g. a manual --once during the service cycle).
prune_stale_parts() {
  _stale="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.dump.part*' -mtime +1 2>/dev/null || true)"
  for _f in $_stale; do
    [ -f "$_f" ] || continue
    rm -f "$_f"
    warn "event=prune.stale_part file=$(basename "$_f") msg=$(q 'sobra de dump interrompido, removida')"
  done
}

# ---------------------------------------------------------------------------
# One cycle
# ---------------------------------------------------------------------------
run_cycle() {
  _rc=0
  _c0="$(date +%s)"
  info "event=cycle.start"

  prune_stale_parts

  if [ "$PRUNE_ONLY" -eq 1 ]; then
    # No new dump in this mode, so brake (a) does not apply: this is a hand-requested prune.
    prune_one "$PRIMARY_DB"
    [ "$PLATFORM_MODE" != "off" ] && prune_one "$PLATFORM_DB"
    info "event=cycle.end mode=prune_only duration_s=$(( $(date +%s) - _c0 ))"
    return 0
  fi

  # ---- primary: this is the database that cannot be missing ----
  if backup_one "primario" "$PRIMARY_HOST" "$PRIMARY_DB" "$PRIMARY_PW"; then
    prune_one "$PRIMARY_DB"        # brake (a): prune only after a good dump
  else
    _rc=1
    error "event=dump.critical db=$PRIMARY_DB msg=$(q 'banco transacional NÃO foi salvo neste ciclo; retenção NÃO aplicada para não apagar os dumps bons anteriores')"
  fi

  # ---- platform: rebuildable, but the historical cost telemetry is not ----
  if [ "$PLATFORM_MODE" != "off" ]; then
    set +e
    backup_one "plataforma" "$PLATFORM_HOST" "$PLATFORM_DB" "$PLATFORM_PW"
    _prc=$?
    set -e
    case "$_prc" in
      0) prune_one "$PLATFORM_DB" ;;
      2)
        if [ "$PLATFORM_MODE" = "required" ]; then
          error "event=dump.critical db=$PLATFORM_DB msg=$(q 'BACKUP_PLATFORM_MODE=required e o servidor não respondeu')"
          _rc=1
        else
          warn "event=dump.skip db=$PLATFORM_DB msg=$(q 'postgres-platform não responde — esperado com o profile platform desligado; use BACKUP_PLATFORM_MODE=off para silenciar ou =required para reprovar')"
        fi
        ;;
      *) _rc=1 ;;
    esac
  fi

  _kb="$(free_kb)"
  info "event=cycle.end rc=$_rc duration_s=$(( $(date +%s) - _c0 )) free=$(human_kb "${_kb:-0}")"
  return "$_rc"
}

# ---------------------------------------------------------------------------
# Main loop
# ---------------------------------------------------------------------------
CYCLE_RC=0
while : ; do
  set +e
  run_cycle
  CYCLE_RC=$?
  set -e

  if [ "$ONCE" -eq 1 ]; then
    [ "$CYCLE_RC" -eq 0 ] || error "event=exit rc=$CYCLE_RC msg=$(q 'ciclo único terminou com falha')"
    exit "$CYCLE_RC"
  fi
  [ "$STOP" -eq 1 ] && break

  info "event=sleep seconds=$INTERVAL next_utc=$(date -u -d "@$(( $(date +%s) + INTERVAL ))" '+%Y-%m-%dT%H:%M:%SZ' 2>/dev/null || echo '?')"
  sleep "$INTERVAL" &
  SLEEP_PID=$!
  wait "$SLEEP_PID" 2>/dev/null || true    # interrupted by SIGTERM -> leaves the loop
  SLEEP_PID=""
  [ "$STOP" -eq 1 ] && break
done

info "event=stop msg=$(q 'serviço de backup encerrado')"
exit 0
