#!/bin/sh
#
# run-backup.sh — entrypoint do serviço `backup` (docker-compose.yml §backup).
#
# Substitui o PITR de 7 dias do Postgres Flexible Server por dump lógico periódico.
# Não é equivalente: o PITR recuperava qualquer instante; aqui o RPO é, no pior caso,
# BACKUP_INTERVAL_SECONDS (1h por default). Está registrado no ADR 0034 §disponibilidade.
#
# O QUE ELE FAZ, A CADA CICLO
#   1. `pg_dump -Fc` de `nora` (serviço postgres) e `nora_platform` (postgres-platform).
#   2. VERIFICA o arquivo recém-escrito com `pg_restore --list`. Só depois de passar é
#      que o `.part` vira `.dump`. Um dump que não abre nunca ganha o nome final —
#      backup não verificado não é backup, é um arquivo que dá esperança.
#   3. Grava o SHA-256 e um `.toc` (o índice do dump, útil pra inspecionar sem restaurar).
#   4. Poda por BACKUP_RETENTION_DAYS, com DOIS freios (ver §PODA).
#   5. Dorme BACKUP_INTERVAL_SECONDS e repete.
#
# POR QUE /bin/sh E NÃO BASH
#   A imagem é `postgres:16-alpine` e o compose chama `["/bin/sh", ".../run-backup.sh"]`.
#   Não há bash aqui. Tudo neste arquivo é POSIX + o que o busybox ash garante.
#
# LOGS
#   Uma linha logfmt por evento em STDOUT. O Alloy lê o socket do Docker e manda pro
#   Loki (observability/config.alloy); o campo `level=` é o que o Loki 3.x usa pra
#   derivar `detected_level`. Nada de log em arquivo: arquivo dentro de container é
#   log que ninguém lê.
#
# §PODA — por que não é um `find -mtime +N -delete` e pronto
#   Dois modos de falha reais, os dois já vistos em produção alheia:
#     (a) o pg_dump quebra (senha rotacionada, disco cheio, banco fora) e ninguém olha.
#         Passados RETENTION dias, a poda apaga o último backup bom. O incidente
#         seguinte encontra o diretório vazio. Freio: a poda de um banco SÓ roda se o
#         ciclo atual produziu um dump verificado DAQUELE banco.
#     (b) intervalo mal configurado (ex.: 24h com retenção 1 dia) deixa a janela com
#         zero arquivos. Freio: BACKUP_MIN_KEEP (default 3) sobrevive à retenção,
#         por mais velhos que sejam.
#
# BACKUP NO MESMO HOST NÃO É BACKUP
#   Estes dumps caem em ${BACKUP_DIR:-/srv/nora/backups} — o MESMO disco do Postgres.
#   Cobrem: DROP TABLE errado, migration destrutiva, corrupção lógica.
#   NÃO cobrem: perda do host. Para isso existe o snapshot do Proxmox Backup Server e
#   uma cópia externa (rclone/rsync). Ver proxmox-deploy.md §Backup manual sob demanda.
#
set -eu

# `pipefail` não é POSIX. O busybox ash do postgres:16-alpine tem, mas testamos antes
# de ligar para o script continuar válido em qualquer /bin/sh.
# shellcheck disable=SC3040
if (set -o pipefail) 2>/dev/null; then set -o pipefail; fi

SCRIPT_NAME="$(basename "$0")"

# ---------------------------------------------------------------------------
# Configuração — toda via env (é um serviço do compose), com flags para uso manual.
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

# auto      -> se o postgres-platform não responder, é o profile 'platform' desligado: avisa e segue
# required  -> falha do platform reprova o ciclo
# off       -> nem tenta
PLATFORM_MODE="${BACKUP_PLATFORM_MODE:-auto}"

# Piso absoluto de espaço livre (KiB) pra sequer tentar um dump. Encher o disco do
# /backups é pior que perder um ciclo: no layout default o /srv é o mesmo filesystem
# do volume do Postgres, e Postgres sem espaço para de aceitar escrita.
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
  # backup imediato antes de uma migration destrutiva
  docker compose -p nora exec backup /usr/local/bin/run-backup.sh --once

  # o que sobrou depois da retenção
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

# Dumps têm PII de tenants (ADR 0029 / LGPD): nada de world-readable. Mas 077 seria
# APERTADO DEMAIS aqui, e o motivo não é óbvio: o compose sobrescreve o entrypoint
# (docker-compose.yml §backup), o que PULA o docker-entrypoint.sh da imagem oficial —
# é ele quem faria o `gosu postgres`. Sem ele, este processo roda como ROOT, e com 077
# os dumps nascem 0600 root:root. Resultado prático: o restore-drill.sh e o operador,
# rodando como usuário comum no host, levam "Permission denied" no próprio backup.
# 027 + o bit setgid no diretório (bootstrap-host.sh cria /srv/nora/backups como
# root:nora 2750) dá 0640 root:nora — legível por quem opera, invisível pro resto.
umask 027

# ---------------------------------------------------------------------------
# Log — logfmt, uma linha por evento, em stdout.
# ---------------------------------------------------------------------------
_ts() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
logf() {
  _lvl="$1"; shift
  printf 'ts=%s level=%s component=backup %s\n' "$(_ts)" "$_lvl" "$*"
}
info()  { logf info  "$@"; }
warn()  { logf warn  "$@"; }
error() { logf error "$@"; }

# Valor com espaço em logfmt precisa de aspas.
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
# Encerramento limpo.
#
# `docker stop` manda SIGTERM e espera 10s antes do SIGKILL. Um `sleep 3600` em
# primeiro plano só devolveria o controle ao shell no fim do sono — o trap ficaria
# na fila e o container morreria de SIGKILL, potencialmente no meio de um pg_dump.
# Por isso o sono é um filho + `wait`: `wait` É interrompido por sinal capturado.
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
# Pré-voo
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
# Espaço em disco
# ---------------------------------------------------------------------------
free_kb() { df -P "$BACKUP_DIR" 2>/dev/null | awk 'NR==2 {print $4}'; }

# last_dump_kb <banco> — tamanho do dump mais recente daquele banco, em KiB (0 se não há).
last_dump_kb() {
  _f="$(ls -1 "$BACKUP_DIR/$1"-*.dump 2>/dev/null | sort | tail -1 || true)"
  [ -n "$_f" ] || { printf '0'; return; }
  _b="$(file_size "$_f")"
  printf '%s' "$(( ${_b:-0} / 1024 ))"
}

# ---------------------------------------------------------------------------
# Um backup
# ---------------------------------------------------------------------------
# backup_one <rótulo> <host> <db> <senha> -> 0 ok | 1 falhou | 2 indisponível (skip)
backup_one() {
  _label="$1"; _host="$2"; _db="$3"; _pw="$4"
  _stamp="$(date -u '+%Y%m%dT%H%M%SZ')"
  _out="$BACKUP_DIR/$_db-$_stamp.dump"
  _part="$_out.part"

  # 1) o banco está de pé? pg_isready falha em 5s; pg_dump ficaria pendurado no timeout.
  if ! PGPASSWORD="$_pw" pg_isready -h "$_host" -U "$PG_USER" -d "$_db" -t 5 >/dev/null 2>&1; then
    error "event=dump.unreachable db=$_db host=$_host msg=$(q 'pg_isready falhou: banco fora, DNS interno, ou senha/rede')"
    return 2
  fi

  # 2) espaço. Estimativa = tamanho do último dump deste banco (a base só cresce).
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

  # 3) o dump. -Fc é obrigatório: é o formato que o pg_restore lê seletivamente e em
  # paralelo (-j), e o único que o restore-into-proxmox.sh/restore-drill.sh aceitam.
  # Sem --no-sync de propósito: aqui durabilidade importa mais que os segundos que ela custa.
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

  # 4) VERIFICAÇÃO. Roda no .part: o nome final só existe se o pg_restore conseguiu ler
  # o índice inteiro. Assim `ls *.dump` é, por construção, a lista de backups válidos.
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

  # 5) promoção atômica: .part -> .dump. A partir daqui é um backup.
  mv -f "$_part.toc" "$_out.toc"
  mv -f "$_part" "$_out"

  # 6) checksum, pro restore detectar corrupção de transporte (o restore-into-proxmox
  # confere este arquivo antes de tocar no banco).
  if command -v sha256sum >/dev/null 2>&1; then
    ( cd "$BACKUP_DIR" && sha256sum "$(basename "$_out")" > "$(basename "$_out").sha256" )
  else
    warn "event=checksum.skip db=$_db msg=$(q 'sha256sum ausente na imagem')"
  fi

  info "event=dump.ok db=$_db label=$_label file=$(basename "$_out") bytes=$_bytes size=$(human_bytes "$_bytes") duration_s=$(( _t1 - _t0 )) toc_entries=$_entries tables=$_tables verified=true"
  return 0
}

# ---------------------------------------------------------------------------
# Poda
# ---------------------------------------------------------------------------
# prune_one <banco> — só é chamada quando o ciclo produziu dump verificado deste banco.
prune_one() {
  _db="$1"
  [ "$RETENTION" -gt 0 ] || return 0

  _total="$(ls -1 "$BACKUP_DIR/$_db"-*.dump 2>/dev/null | wc -l | tr -d '[:space:]' || true)"
  _total="${_total:-0}"
  [ "$_total" -gt "$MIN_KEEP" ] || {
    info "event=prune.skip db=$_db total=$_total min_keep=$MIN_KEEP msg=$(q 'nada a podar: no piso de retenção')"
    return 0
  }

  # -mtime é POSIX e o mtime é confiável porque somos nós que escrevemos o arquivo.
  _old="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name "$_db-*.dump" -mtime "+$RETENTION" 2>/dev/null | sort || true)"
  [ -n "$_old" ] || return 0

  _removed=0
  _freed=0
  for _f in $_old; do
    [ -f "$_f" ] || continue
    # Freio (b): nunca descer abaixo de MIN_KEEP, por mais velho que o arquivo seja.
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

# Restos de execuções interrompidas (SIGKILL no meio de um pg_dump). Não são backups:
# nunca passaram pela verificação. Só limpa os antigos, para não apagar um .part vivo
# de outro processo rodando em paralelo (ex.: um --once manual durante o ciclo do serviço).
prune_stale_parts() {
  _stale="$(find "$BACKUP_DIR" -maxdepth 1 -type f -name '*.dump.part*' -mtime +1 2>/dev/null || true)"
  for _f in $_stale; do
    [ -f "$_f" ] || continue
    rm -f "$_f"
    warn "event=prune.stale_part file=$(basename "$_f") msg=$(q 'sobra de dump interrompido, removida')"
  done
}

# ---------------------------------------------------------------------------
# Um ciclo
# ---------------------------------------------------------------------------
run_cycle() {
  _rc=0
  _c0="$(date +%s)"
  info "event=cycle.start"

  prune_stale_parts

  if [ "$PRUNE_ONLY" -eq 1 ]; then
    # Sem dump novo neste modo, o freio (a) não se aplica: é uma poda pedida na mão.
    prune_one "$PRIMARY_DB"
    [ "$PLATFORM_MODE" != "off" ] && prune_one "$PLATFORM_DB"
    info "event=cycle.end mode=prune_only duration_s=$(( $(date +%s) - _c0 ))"
    return 0
  fi

  # ---- primário: este é o banco que não pode faltar ----
  if backup_one "primario" "$PRIMARY_HOST" "$PRIMARY_DB" "$PRIMARY_PW"; then
    prune_one "$PRIMARY_DB"        # freio (a): poda só depois de um dump bom
  else
    _rc=1
    error "event=dump.critical db=$PRIMARY_DB msg=$(q 'banco transacional NÃO foi salvo neste ciclo; retenção NÃO aplicada para não apagar os dumps bons anteriores')"
  fi

  # ---- plataforma: reconstruível, mas a telemetria de custo histórica não ----
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
# Laço principal
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
  wait "$SLEEP_PID" 2>/dev/null || true    # interrompido por SIGTERM -> sai do laço
  SLEEP_PID=""
  [ "$STOP" -eq 1 ] && break
done

info "event=stop msg=$(q 'serviço de backup encerrado')"
exit 0
