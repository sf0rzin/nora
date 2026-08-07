#!/usr/bin/env bash
#
# restore-into-proxmox.sh — restaura os dumps do Azure nos containers da stack local.
#
# Consome dumps `pg_dump -Fc` (os que o serviço `backup` do compose gera) e reidrata:
#   nora.dump           -> serviço `postgres`           (banco nora)
#   nora_platform.dump  -> serviço `postgres-platform`  (banco nora_platform, profile platform)
#
# ORDEM (e por que ela importa):
#
#   1. ROLES ANTES DOS DADOS. Os três roles do ADR 0026/0028 são criados vazios primeiro:
#        nora_app       LOGIN NOBYPASSRLS  -> runtime da API sob enforce
#        nora_telemetry LOGIN BYPASSRLS    -> painel do operador, leitura cross-tenant
#        (o admin/owner é o POSTGRES_USER do container, dono do schema e do Flyway)
#      Sem eles no lugar, qualquer objeto do dump que referencie um role explode o restore
#      no meio. E, mais grave: OMITIR o nora_telemetry não dá erro nenhum — o painel do
#      operador simplesmente passa a ver ZERO linhas, em silêncio (fail-closed).
#
#   2. DADOS com --no-owner --no-privileges. O owner no Azure era `nora_admin` do Flexible
#      Server; aqui o owner é o POSTGRES_USER do container. Mapear é justamente não carregar
#      owner/ACL do dump e deixar tudo nascer do role que está conectado.
#
#   3. GRANTS DEPOIS. O R001 (db/operational) só roda no fim porque ele referencia o schema
#      `nora` e a função nora.current_tenant_id(), que só existem DEPOIS que os dados/DDL
#      entraram (V016). Rodar o R001 antes falha em "schema nora does not exist".
#
# VALIDAÇÃO FINAL (o restore só é bom se provado):
#   - contagem de tabelas em public, comparada com o baseline <db>-counts.tsv, quando o backup gravou um
#   - flyway_schema_history: última versão aplicada, zero migrations com success=false,
#     e comparação com a maior V### presente no repo
#   - smoke por tenant: linhas de meetings/users/meeting_analyses por tenant
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
$SCRIPT_NAME — restaura os dumps do Azure nos containers postgres/postgres-platform

USO
  $SCRIPT_NAME --from-dir <dir-de-backup> [opções]
  $SCRIPT_NAME --dump <nora.dump> [--platform-dump <nora_platform.dump>] [opções]

OPÇÕES
  --from-dir <dir>        Diretório com os dumps (ex.: uma pasta de $BACKUP_DIR). Procura
                          nora.dump e nora_platform.dump dentro dele.
  --dump <arquivo>        Dump do banco primário (nora)
  --platform-dump <arq>   Dump do banco de plataforma (nora_platform)
  --env-file <arquivo>    Arquivo .env já decifrado (ex.: o de /dev/shm do deploy.sh)
  --sops                  Decifra $SOPS_FILE com SOPS/age para um tmpfs
  --skip-platform         Não restaura o nora_platform
  --skip-roles            Não cria/ajusta roles (assume que já existem)
  --jobs <n>              Paralelismo do pg_restore (default: $JOBS)
  --allow-count-drift     Divergência de contagem vira aviso, não erro
  --force                 Sobrescreve banco de destino que JÁ TEM tabelas
                          (usa pg_restore --clean --if-exists)
  -h, --help              Esta ajuda

SENHAS NECESSÁRIAS (env var ou --env-file/--sops)
  POSTGRES_ADMIN_USER          default: nora_admin
  POSTGRES_ADMIN_PASSWORD      obrigatória
  POSTGRES_PLATFORM_ADMIN_PASSWORD  (default: mesma do primário, como no compose)
  NORA_APP_PASSWORD            senha do role nora_app       (obrigatória, salvo --skip-roles)
  RLS_TELEMETRY_PASSWORD       senha do role nora_telemetry (obrigatória, salvo --skip-roles)

IDEMPOTÊNCIA
  Rodar de novo sobre um banco já restaurado exige --force (senão aborta para não
  destruir dados). Com --force o resultado é o mesmo em qualquer número de execuções.
  A criação de roles e o R001 são idempotentes por natureza.

EXEMPLO
  $SCRIPT_NAME --from-dir /srv/nora/backups/20260807T031500Z --sops
EOF
}

_ts() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
log()  { printf '[%s] %s\n'        "$(_ts)" "$*" >&2; }
ok()   { printf '[%s] OK    %s\n'  "$(_ts)" "$*" >&2; }
warn() { printf '[%s] AVISO %s\n'  "$(_ts)" "$*" >&2; }
err()  { printf '[%s] ERRO  %s\n'  "$(_ts)" "$*" >&2; }
die()  { err "$*"; exit 1; }
hr()   { printf '%s\n' "------------------------------------------------------------" >&2; }

while [ $# -gt 0 ]; do
  case "$1" in
    --from-dir)         FROM_DIR="${2:?--from-dir exige um valor}"; shift 2 ;;
    --dump)             DUMP_PRIMARY="${2:?--dump exige um valor}"; shift 2 ;;
    --platform-dump)    DUMP_PLATFORM="${2:?--platform-dump exige um valor}"; shift 2 ;;
    --env-file)         ENV_FILE="${2:?--env-file exige um valor}"; shift 2 ;;
    --sops)             USE_SOPS=1; shift ;;
    --sops-file)        SOPS_FILE="${2:?--sops-file exige um valor}"; USE_SOPS=1; shift 2 ;;
    --skip-platform)    SKIP_PLATFORM=1; shift ;;
    --skip-roles)       SKIP_ROLES=1; shift ;;
    --jobs)             JOBS="${2:?--jobs exige um valor}"; shift 2 ;;
    --allow-count-drift) ALLOW_COUNT_DRIFT=1; shift ;;
    --force)            FORCE=1; shift ;;
    -h|--help)          usage; exit 0 ;;
    *) err "opção desconhecida: $1"; echo >&2; usage; exit 1 ;;
  esac
done

umask 077

# ---------------------------------------------------------------------------
# Segredos: --sops (tmpfs) > --env-file > ambiente
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
  command -v sops >/dev/null 2>&1 || die "sops não encontrado. Rode o bootstrap-host.sh ou instale manualmente."
  [ -f "$SOPS_FILE" ] || die "arquivo cifrado não existe: $SOPS_FILE"
  [ -d /dev/shm ] || die "/dev/shm não existe — não vou decifrar segredo para disco."
  TMP_ENV_DIR="$(mktemp -d /dev/shm/nora-restore.XXXXXX)"
  chmod 700 "$TMP_ENV_DIR"
  TMP_ENV="$TMP_ENV_DIR/env"
  export SOPS_AGE_KEY_FILE="${SOPS_AGE_KEY_FILE:-/etc/nora/age.key}"
  log "decifrando $SOPS_FILE (chave: $SOPS_AGE_KEY_FILE) para tmpfs..."
  sops --decrypt --input-type dotenv --output-type dotenv "$SOPS_FILE" > "$TMP_ENV" \
    || die "falha ao decifrar. A chave privada age está em $SOPS_AGE_KEY_FILE e legível?"
  ENV_FILE="$TMP_ENV"
  ok "segredos decifrados em tmpfs"
fi

if [ -n "$ENV_FILE" ]; then
  [ -f "$ENV_FILE" ] || die "--env-file não existe: $ENV_FILE"
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

[ -n "$PG_PW" ] || die "POSTGRES_ADMIN_PASSWORD não definida (use --env-file, --sops ou export)."

if [ "$SKIP_ROLES" -eq 0 ]; then
  miss=0
  [ -n "$APP_PW" ] || { err "NORA_APP_PASSWORD não definida — sem ela o role nora_app não é criado"; miss=1; }
  [ -n "$TEL_PW" ] || {
    err "RLS_TELEMETRY_PASSWORD não definida."
    err "  ATENÇÃO: omitir o nora_telemetry NÃO gera erro em runtime — o painel do"
    err "  operador simplesmente passa a mostrar ZERO em tudo, em silêncio (ADR 0026/0028)."
    err "  Gere uma:  openssl rand -hex 24"
    miss=1
  }
  [ "$miss" -eq 0 ] || die "faltam senhas de role. Use --skip-roles se os roles já existem."
fi

# ---------------------------------------------------------------------------
# Localização dos dumps
# ---------------------------------------------------------------------------
if [ -n "$FROM_DIR" ]; then
  [ -d "$FROM_DIR" ] || die "--from-dir não é um diretório: $FROM_DIR"
  [ -n "$DUMP_PRIMARY" ]  || DUMP_PRIMARY="$FROM_DIR/nora.dump"
  [ -n "$DUMP_PLATFORM" ] || DUMP_PLATFORM="$FROM_DIR/nora_platform.dump"
fi

[ -n "$DUMP_PRIMARY" ] || { err "informe --from-dir ou --dump"; usage; exit 1; }
[ -s "$DUMP_PRIMARY" ] || die "dump primário não existe ou está vazio: $DUMP_PRIMARY"

if [ "$SKIP_PLATFORM" -eq 0 ]; then
  if [ ! -s "${DUMP_PLATFORM:-}" ]; then
    warn "dump de plataforma ausente (${DUMP_PLATFORM:-<não informado>}) — pulando."
    warn "O control plane é reconstruível: a V001 de db/platform recria o schema no boot."
    SKIP_PLATFORM=1
  fi
fi

# ---------------------------------------------------------------------------
# Compose helpers
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "docker não encontrado."
docker compose version >/dev/null 2>&1 || die "plugin 'docker compose' (v2) não encontrado. Rode o bootstrap-host.sh."
[ -f "$COMPOSE_FILE" ] || die "compose não encontrado: $COMPOSE_FILE"

COMPOSE_ARGS=(--project-name "$COMPOSE_PROJECT" --project-directory "$PROXMOX_DIR" -f "$COMPOSE_FILE")
[ "$SKIP_PLATFORM" -eq 0 ] && COMPOSE_ARGS+=(--profile platform)

dc() { docker compose "${COMPOSE_ARGS[@]}" "$@"; }

# svc_running <serviço> -> 0 se há container rodando
svc_running() {
  local cid
  cid="$(dc ps -q "$1" 2>/dev/null | head -1)"
  [ -n "$cid" ] && [ "$(docker inspect -f '{{.State.Running}}' "$cid" 2>/dev/null)" = "true" ]
}

# psql_in <serviço> <db> [args...] — psql dentro do container, como admin.
psql_in() {
  local svc="$1" db="$2"; shift 2
  local pw="$PG_PW"
  [ "$svc" = "postgres-platform" ] && pw="$PG_PLATFORM_PW"
  dc exec -T -e PGPASSWORD="$pw" "$svc" \
     psql -v ON_ERROR_STOP=1 -U "$PG_USER" -d "$db" "$@"
}

# ---------------------------------------------------------------------------
# Verificação do dump ANTES de tocar no banco
# ---------------------------------------------------------------------------
verify_dump() {
  local file="$1" label="$2"
  log "$label: verificando $file"

  # checksum, quando o backup gravou um
  local sumfile="$file.sha256"
  if [ -f "$sumfile" ]; then
    if command -v sha256sum >/dev/null 2>&1; then
      (cd "$(dirname "$file")" && sha256sum -c --status "$(basename "$sumfile")") \
        || die "$label: CHECKSUM NÃO CONFERE. O arquivo corrompeu no transporte. Recopie."
      ok "$label: checksum confere"
    else
      warn "$label: sha256sum ausente — checksum não conferido"
    fi
  else
    warn "$label: sem arquivo .sha256 ao lado do dump"
  fi

  # o dump abre?
  local toc
  toc="$(mktemp)"
  if command -v pg_restore >/dev/null 2>&1; then
    pg_restore --list "$file" > "$toc" 2>/dev/null \
      || die "$label: pg_restore --list falhou — dump inutilizável."
  else
    # sem cliente local: usa o do container postgres
    dc exec -T postgres sh -c 'cat > /tmp/verify.dump && pg_restore --list /tmp/verify.dump; rc=$?; rm -f /tmp/verify.dump; exit $rc' \
      < "$file" > "$toc" 2>/dev/null \
      || die "$label: pg_restore --list falhou (via container) — dump inutilizável."
  fi
  local entries
  entries="$(grep -cv '^;' "$toc" || true)"
  rm -f "$toc"
  [ "${entries:-0}" -gt 0 ] || die "$label: TOC vazio — o dump não tem objeto nenhum."
  ok "$label: dump abre — $entries entradas no TOC"
}

# ---------------------------------------------------------------------------
# Etapa 1 — ROLES (antes dos dados)
# ---------------------------------------------------------------------------
create_roles_bare() {
  log "criando roles (nora_app NOBYPASSRLS, nora_telemetry BYPASSRLS)..."
  # Senhas passadas CRUAS via -v: o psql transforma :'var' em literal SQL escapado.
  # (mesma convenção do R001 e do rls-cutover.yml — não colocar aspas em volta)
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
  ok "roles criados/ajustados"
}

apply_r001() {
  [ -f "$R001_SQL" ] || {
    warn "R001 não encontrado em $R001_SQL — GRANTs e DEFAULT PRIVILEGES NÃO aplicados."
    warn "Sem eles, a API conectando como nora_app não enxerga tabela nenhuma."
    return 1
  }
  log "aplicando GRANTs/DEFAULT PRIVILEGES (R001)..."
  # O R001 precisa rodar como o MESMO role que é dono das tabelas (o admin do container),
  # porque ALTER DEFAULT PRIVILEGES é por-role-criador. Ver cabeçalho do R001.
  psql_in postgres nora -v app_password="$APP_PW" -v telemetry_password="$TEL_PW" -q < "$R001_SQL"
  ok "R001 aplicado"
}

# ---------------------------------------------------------------------------
# Etapa 2 — DADOS
# ---------------------------------------------------------------------------
target_table_count() {  # <serviço> <db>
  psql_in "$1" "$2" -tAc \
    "SELECT count(*) FROM information_schema.tables
      WHERE table_schema='public' AND table_type='BASE TABLE'" 2>/dev/null | tr -d '[:space:]'
}

restore_db() {  # <serviço> <db> <dump> <rótulo>
  local svc="$1" db="$2" file="$3" label="$4"
  hr
  log "RESTAURANDO $label — $file -> serviço '$svc', banco '$db'"

  svc_running "$svc" || die "$label: serviço '$svc' não está rodando. Suba com:
       docker compose ${COMPOSE_ARGS[*]} up -d $svc"

  local existing
  existing="$(target_table_count "$svc" "$db")"
  existing="${existing:-0}"
  if [ "$existing" -gt 0 ]; then
    if [ "$FORCE" -eq 0 ]; then
      err "$label: o banco '$db' JÁ TEM $existing tabelas."
      err "        Restaurar por cima sem --force destruiria dados existentes."
      err "        Se é isso mesmo que você quer:  $SCRIPT_NAME ... --force"
      err "        Se o banco deve nascer limpo:   docker compose down -v  (APAGA os volumes)"
      return 1
    fi
    warn "$label: $existing tabelas presentes — --force ativo, usando --clean --if-exists"
  fi

  # Copia o dump para dentro do container. Evita depender de cliente local e habilita -j.
  local inner="/tmp/nora-restore-$db.dump"
  log "$label: copiando dump para o container..."
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
    err "$label: pg_restore terminou com código $rc."
    err "        Causas típicas:"
    err "          - extensão faltando na imagem (pgcrypto/citext vêm no contrib do pg16: ok)"
    err "          - role referenciado inexistente -> rode SEM --skip-roles"
    err "          - objeto já existente -> use --force (adiciona --clean --if-exists)"
    return 1
  fi
  ok "$label: restaurado em $((t1 - t0))s"
  return 0
}

# ---------------------------------------------------------------------------
# Etapa 3 — VALIDAÇÃO
# ---------------------------------------------------------------------------
VALIDATION_FAILED=0
vfail() { err "VALIDAÇÃO: $*"; VALIDATION_FAILED=1; }

validate_counts() {  # <serviço> <db> <baseline.tsv|""> <rótulo>
  local svc="$1" db="$2" baseline="$3" label="$4"
  local n
  n="$(target_table_count "$svc" "$db")"
  log "$label: $n tabelas em public"
  [ "${n:-0}" -gt 0 ] || { vfail "$label: nenhuma tabela em public — o restore não trouxe nada."; return; }

  if [ -z "$baseline" ] || [ ! -s "$baseline" ]; then
    warn "$label: sem baseline de contagens (<db>-counts.tsv) — comparação pulada."
    return
  fi

  log "$label: comparando contagem de linhas com o baseline do backup..."
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
      err "  tabela AUSENTE no destino: $tbl (esperado $expected linhas)"
      drift=$((drift + 1))
    elif [ "$got" != "$expected" ]; then
      err "  divergência em $tbl: origem=$expected destino=$got"
      drift=$((drift + 1))
    fi
  done < "$baseline"
  rm -f "$live"

  if [ "$drift" -eq 0 ]; then
    ok "$label: $checked tabelas conferem linha a linha com a origem"
  elif [ "$ALLOW_COUNT_DRIFT" -eq 1 ]; then
    warn "$label: $drift divergências toleradas por --allow-count-drift"
  else
    vfail "$label: $drift tabelas divergem da origem. Use --allow-count-drift se for esperado."
  fi
}

validate_flyway() {  # <serviço> <db> <rótulo> <dir-de-migrations|"">
  local svc="$1" db="$2" label="$3" migdir="${4:-}"

  local exists
  exists="$(psql_in "$svc" "$db" -tAc "SELECT to_regclass('public.flyway_schema_history') IS NOT NULL" 2>/dev/null | tr -d '[:space:]')"
  if [ "$exists" != "t" ]; then
    vfail "$label: flyway_schema_history não existe. O dump veio de um banco não migrado?"
    return
  fi

  local failed
  failed="$(psql_in "$svc" "$db" -tAc "SELECT count(*) FROM flyway_schema_history WHERE success = false" | tr -d '[:space:]')"
  if [ "${failed:-0}" -ne 0 ]; then
    vfail "$label: $failed migrations com success=false no histórico. O banco de origem estava quebrado."
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
  log "$label: última migration aplicada = V$last_ver ($last_desc)"

  if [ -n "$migdir" ] && [ -d "$migdir" ]; then
    local repo_ver
    repo_ver="$(ls "$migdir" 2>/dev/null | sed -n 's/^V0*\([0-9][0-9]*\)__.*\.sql$/\1/p' | sort -n | tail -1)"
    local db_ver
    db_ver="$(printf '%s' "$last_ver" | sed 's/^0*//')"
    if [ -n "$repo_ver" ] && [ -n "$db_ver" ]; then
      if [ "$db_ver" -eq "$repo_ver" ]; then
        ok "$label: schema na última versão do repo (V$repo_ver)"
      elif [ "$db_ver" -lt "$repo_ver" ]; then
        warn "$label: banco em V$db_ver, repo tem até V$repo_ver."
        warn "         Esperado se o dump é anterior às últimas migrations: o Flyway da API"
        warn "         aplica as pendentes no primeiro boot. Confira antes de liberar tráfego."
      else
        vfail "$label: banco em V$db_ver, MAIOR que a V$repo_ver do repo. A imagem da API"
        vfail "         está desatualizada em relação ao dump — não suba assim."
      fi
    fi
  fi
}

validate_tenants_smoke() {  # <serviço> <db> <rótulo>
  local svc="$1" db="$2" label="$3"
  local has_tenants
  has_tenants="$(psql_in "$svc" "$db" -tAc "SELECT to_regclass('public.tenants') IS NOT NULL" 2>/dev/null | tr -d '[:space:]')"
  if [ "$has_tenants" != "t" ]; then
    vfail "$label: tabela 'tenants' não existe — restore incompleto."
    return
  fi

  local n
  n="$(psql_in "$svc" "$db" -tAc "SELECT count(*) FROM tenants" | tr -d '[:space:]')"
  if [ "${n:-0}" -eq 0 ]; then
    vfail "$label: ZERO tenants. Ou o banco de origem estava vazio, ou o restore falhou em silêncio."
    return
  fi

  log "$label: smoke por tenant ($n tenants)"
  # Conectado como owner/admin: RLS não se aplica (as policies são fail-closed só para
  # roles não-owner sem a GUC nora.current_tenant_id). Aqui queremos justamente o total real.
  psql_in "$svc" "$db" -P pager=off -c \
    "SELECT t.slug,
            t.status,
            (SELECT count(*) FROM users u            WHERE u.tenant_id = t.id) AS usuarios,
            (SELECT count(*) FROM meetings m         WHERE m.tenant_id = t.id) AS reunioes,
            (SELECT count(*) FROM meeting_analyses a WHERE a.tenant_id = t.id) AS analises
       FROM tenants t
      ORDER BY t.slug" >&2
  ok "$label: smoke por tenant executado"

  # Um tenant sem NENHUM usuário é sinal de restore parcial (FK users->tenants é RESTRICT).
  local orfaos
  orfaos="$(psql_in "$svc" "$db" -tAc \
    "SELECT count(*) FROM tenants t WHERE NOT EXISTS (SELECT 1 FROM users u WHERE u.tenant_id = t.id)" | tr -d '[:space:]')"
  [ "${orfaos:-0}" -eq 0 ] || warn "$label: $orfaos tenant(s) sem nenhum usuário — confira se é esperado."
}

validate_roles() {
  log "conferindo flags dos roles (o ponto do ADR 0028)..."
  local out
  out="$(psql_in postgres nora -tA -F'|' -c \
    "SELECT rolname, rolcanlogin, rolbypassrls FROM pg_roles
      WHERE rolname IN ('nora_app','nora_telemetry') ORDER BY rolname")"
  printf '%s\n' "$out" | sed 's/^/    /' >&2

  printf '%s' "$out" | grep -q '^nora_app|t|f$' \
    || vfail "nora_app precisa ser LOGIN e NOBYPASSRLS (senão o RLS não vale nada)."
  printf '%s' "$out" | grep -q '^nora_telemetry|t|t$' \
    || vfail "nora_telemetry precisa ser LOGIN e BYPASSRLS (senão o painel do operador zera EM SILÊNCIO)."

  # nora_app enxerga as tabelas? (prova de que os GRANTs do R001 pegaram)
  local visiveis
  visiveis="$(dc exec -T -e PGPASSWORD="$APP_PW" postgres \
      psql -tAc "SELECT count(*) FROM information_schema.table_privileges
                  WHERE grantee='nora_app' AND privilege_type='SELECT'" \
      -U nora_app -d nora 2>/dev/null | tr -d '[:space:]' || echo 0)"
  if [ "${visiveis:-0}" -gt 0 ]; then
    ok "nora_app conecta e tem SELECT em $visiveis tabelas"
  else
    vfail "nora_app não conecta ou não tem GRANT nenhum — a API não vai subir."
  fi
}

# ---------------------------------------------------------------------------
# Execução
# ---------------------------------------------------------------------------
hr
log "restore-into-proxmox — projeto compose '$COMPOSE_PROJECT'"
log "  primário:   $DUMP_PRIMARY"
[ "$SKIP_PLATFORM" -eq 0 ] && log "  plataforma: $DUMP_PLATFORM"

verify_dump "$DUMP_PRIMARY" "PRIMÁRIO"
[ "$SKIP_PLATFORM" -eq 0 ] && verify_dump "$DUMP_PLATFORM" "PLATAFORMA"

# 1) roles antes dos dados
if [ "$SKIP_ROLES" -eq 0 ]; then
  hr
  svc_running postgres || die "serviço 'postgres' não está rodando — suba a stack primeiro."
  create_roles_bare
else
  log "--skip-roles: criação de roles pulada"
fi

# 2) dados
restore_db postgres nora "$DUMP_PRIMARY" "PRIMÁRIO" || die "restore do banco primário falhou."

if [ "$SKIP_PLATFORM" -eq 0 ]; then
  if svc_running postgres-platform; then
    restore_db postgres-platform nora_platform "$DUMP_PLATFORM" "PLATAFORMA" \
      || warn "restore do nora_platform falhou — o control plane sobe DEGRADADO (admin -> 503)."
  else
    warn "serviço 'postgres-platform' não está rodando (profile 'platform' desligado?)."
    warn "Suba com:  docker compose --profile platform up -d postgres-platform"
    SKIP_PLATFORM=1
  fi
fi

# 3) grants depois (o R001 depende do schema nora, criado pela V016 que veio no dump)
if [ "$SKIP_ROLES" -eq 0 ]; then
  hr
  apply_r001 || VALIDATION_FAILED=1
fi

# 4) validação
hr
log "VALIDAÇÃO"
BASELINE_PRIMARY=""
BASELINE_PLATFORM=""
if [ -n "$FROM_DIR" ]; then
  [ -f "$FROM_DIR/nora-counts.tsv" ]          && BASELINE_PRIMARY="$FROM_DIR/nora-counts.tsv"
  [ -f "$FROM_DIR/nora_platform-counts.tsv" ] && BASELINE_PLATFORM="$FROM_DIR/nora_platform-counts.tsv"
fi

validate_counts  postgres nora "$BASELINE_PRIMARY" "PRIMÁRIO"
validate_flyway  postgres nora "PRIMÁRIO" "$MIGRATION_DIR"
validate_tenants_smoke postgres nora "PRIMÁRIO"
[ "$SKIP_ROLES" -eq 0 ] && validate_roles

if [ "$SKIP_PLATFORM" -eq 0 ]; then
  validate_counts postgres-platform nora_platform "$BASELINE_PLATFORM" "PLATAFORMA"
  # O Flyway de plataforma tem history table própria (locations classpath:db/platform).
  validate_flyway postgres-platform nora_platform "PLATAFORMA" ""
fi

hr
if [ "$VALIDATION_FAILED" -ne 0 ]; then
  err "RESTORE CONCLUÍDO COM FALHAS DE VALIDAÇÃO. NÃO libere tráfego ainda."
  err "Revise os itens marcados como VALIDAÇÃO acima."
  exit 1
fi

ok "RESTORE VALIDADO."
log "Próximos passos:"
log "  1. ./deploy.sh --tag <sha-xxxxxxx>            (sobe a stack apontando pro banco local)"
log "  2. ./restore-drill.sh                          (mede o RTO real — fecha o gap 3 do ADR 0016)"
exit 0
