#!/usr/bin/env bash
# rls-cutover.sh — Etapa 2 do cutover de RLS enforce (ADR 0026 / ADR 0028) no host Proxmox.
#
# Provisiona os roles de runtime rodando o script idempotente R001 dentro do container
# `postgres` do compose `nora`. Substitui o job `provision-roles` do antigo
# .github/workflows/rls-cutover.yml, que fazia `azure/login`, abria uma regra de firewall
# temporaria pro IP do runner e falava com o FQDN do Postgres Flexible Server.
#
# POR QUE A OPERACAO SAIU DO CI (ver cabecalho de .github/workflows/rls-cutover.yml):
#   O Postgres do Proxmox nao tem endereco publico. Ele esta na bridge `data`
#   (internal: true) e a unica porta publicada e 127.0.0.1:5432 — loopback do host.
#   Nao ha firewall pra "abrir temporariamente": nao existe caminho de entrada. Um runner
#   do GitHub so alcancaria esse Postgres com tunel reverso, VPN ou runner self-hosted —
#   as tres coisas que a migracao para o modelo pull existe pra eliminar (repo publico,
#   ADR 0017). Entao o CI passou a GERAR o plano e o host passou a EXECUTAR.
#
# SAO TRES ROLES, NAO DOIS. Este e o erro que zera o painel do operador EM SILENCIO:
#   1. nora_admin (ou o owner do banco)  — DDL/Flyway. Ja existe; e quem roda ESTE script.
#      O R001 usa ALTER DEFAULT PRIVILEGES sem FOR ROLE, que e por-role-CRIADOR: rodar
#      como outro superuser faz as tabelas das PROXIMAS migrations nascerem sem grant.
#   2. nora_app        LOGIN, NOBYPASSRLS — runtime da API. E o ponto inteiro do enforce.
#   3. nora_telemetry  LOGIN, BYPASSRLS   — leitura agregada cross-tenant do painel.
#      Se este for esquecido, nada quebra com erro: a API cai no datasource primario,
#      a RLS filtra por tenant e o painel mostra ZERO. Fail-closed sem mensagem.
#      O smoke abaixo checa os TRES de proposito.
#
# Uso:
#   cd infra/proxmox
#   sops -d secrets.env.sops > /dev/shm/nora.env      # tmpfs, nunca em disco
#   set -a; . /dev/shm/nora.env; set +a
#   ./scripts/rls-cutover.sh --yes
#   shred -u /dev/shm/nora.env
#
# Variaveis de ambiente:
#   NORA_APP_PASSWORD        (obrigatoria) senha do nora_app. A MESMA que o compose
#                            injeta em DATASOURCE_PASSWORD quando NORA_RLS_ENFORCE=true.
#   NORA_TELEMETRY_PASSWORD  (obrigatoria) senha do nora_telemetry. A MESMA que vai em
#                            NORA_TELEMETRY_DATASOURCE_PASSWORD.
#                            Aceita o nome legado RLS_TELEMETRY_PASSWORD (era o GitHub
#                            Secret) pra nao quebrar quem copiou do runbook antigo.
#   POSTGRES_ADMIN_USER      (opcional) default nora_admin.
#
# NAO liga o enforce. Isso e a etapa 3: NORA_RLS_ENFORCE=true no .env + recriar a api.
# Rodar isto tem ZERO impacto no app de pe — os roles ficam ociosos ate o flip.
# E idempotente: rodar de novo so reconcilia.
#
# Flags:
#   --yes        nao pergunta confirmacao (uso em runbook automatizado)
#   --dry-run    valida pre-requisitos e imprime o plano, sem tocar no banco
#   --db NOME    banco alvo (default: nora)

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

# Compat com o nome do Secret antigo (RLS_TELEMETRY_PASSWORD).
NORA_TELEMETRY_PASSWORD="${NORA_TELEMETRY_PASSWORD:-${RLS_TELEMETRY_PASSWORD:-}}"
NORA_APP_PASSWORD="${NORA_APP_PASSWORD:-}"

while [ $# -gt 0 ]; do
  case "$1" in
    --yes)     ASSUME_YES=1; shift ;;
    --dry-run) DRY_RUN=1; shift ;;
    --db)      PG_DB="${2:?--db exige um valor}"; shift 2 ;;
    -h|--help) sed -n '2,60p' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "ERRO: flag desconhecida: $1" >&2; exit 2 ;;
  esac
done

log()  { printf '>> %s\n' "$*"; }
fail() { printf 'ERRO: %s\n' "$*" >&2; exit 1; }

# ---------------------------------------------------------------------------
# 1. Pre-requisitos
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || fail "docker nao encontrado no PATH."
[ -f "$R001" ] || fail "R001 nao encontrado em ${R001}. Rode a partir do repo completo."

if [ -z "$NORA_APP_PASSWORD" ]; then
  fail "NORA_APP_PASSWORD vazia. Decifre o secrets.env.sops antes (ver cabecalho)."
fi
if [ -z "$NORA_TELEMETRY_PASSWORD" ]; then
  fail "NORA_TELEMETRY_PASSWORD vazia. Sao TRES roles — sem esta senha o nora_telemetry
      nao e provisionado e o painel do operador mostra ZERO linhas EM SILENCIO."
fi
if [ "$NORA_APP_PASSWORD" = "$NORA_TELEMETRY_PASSWORD" ]; then
  fail "NORA_APP_PASSWORD e NORA_TELEMETRY_PASSWORD sao iguais. Sao roles com poderes
      OPOSTOS (NOBYPASSRLS vs BYPASSRLS): compartilhar credencial anula o isolamento."
fi

cd -- "$COMPOSE_DIR" || fail "nao consegui entrar em ${COMPOSE_DIR}"

# `docker compose ps -q` vazio = servico nao esta de pe.
PG_CID="$(docker compose ps -q "$PG_SERVICE" 2>/dev/null || true)"
[ -n "$PG_CID" ] || fail "servico '${PG_SERVICE}' nao esta rodando. Suba a stack antes: docker compose up -d ${PG_SERVICE}"

# psql via SOCKET UNIX dentro do container: o pg_hba da imagem oficial usa `local ... trust`,
# entao a senha do admin nem precisa sair do SOPS pra esta parte. O smoke, ao contrario,
# conecta por TCP (127.0.0.1 dentro do container) justamente pra EXERCITAR a autenticacao
# por senha dos roles novos — que e o que a API vai fazer.
psql_admin() {
  docker compose exec -T "$PG_SERVICE" \
    psql -v ON_ERROR_STOP=1 -U "$PG_ADMIN_USER" -d "$PG_DB" "$@"
}

log "Repo:      ${REPO_ROOT}"
log "Compose:   ${COMPOSE_DIR}"
log "Container: ${PG_SERVICE} (${PG_CID:0:12})"
log "Banco:     ${PG_DB} como ${PG_ADMIN_USER}"

# ---------------------------------------------------------------------------
# 2. Role 1 de 3 — confirma que quem roda e o OWNER do banco
# ---------------------------------------------------------------------------
# O R001 depende disso: o ALTER DEFAULT PRIVILEGES sem FOR ROLE so cobre as tabelas
# criadas por QUEM RODA o script. Se o Flyway roda como nora_admin e o R001 rodar como
# outro role, cada migration futura nasce invisivel pro nora_app.
log "Checando role 1/3 (owner/DDL)..."
DB_OWNER="$(psql_admin -tAc "SELECT pg_get_userbyid(datdba) FROM pg_database WHERE datname = current_database()" | tr -d '\r')"
CUR_USER="$(psql_admin -tAc "SELECT current_user" | tr -d '\r')"
log "  owner do banco: ${DB_OWNER} | current_user: ${CUR_USER}"
if [ "$DB_OWNER" != "$CUR_USER" ]; then
  fail "current_user (${CUR_USER}) nao e o owner do banco (${DB_OWNER}).
      Rodar o R001 assim faz as tabelas das PROXIMAS migrations nascerem sem grant pro
      nora_app. Ajuste POSTGRES_ADMIN_USER pro owner e rode de novo."
fi

if [ "$DRY_RUN" -eq 1 ]; then
  log "DRY RUN — pre-requisitos OK. Seria executado:"
  log "  psql -U ${PG_ADMIN_USER} -d ${PG_DB} -f ${R001_REL}"
  log "  criando/reconciliando: nora_app (NOBYPASSRLS) e nora_telemetry (BYPASSRLS)"
  exit 0
fi

if [ "$ASSUME_YES" -ne 1 ]; then
  printf 'Provisionar os roles em %s? Digite PROVISION: ' "$PG_DB"
  read -r answer
  [ "$answer" = "PROVISION" ] || fail "confirmacao invalida — abortado."
fi

# ---------------------------------------------------------------------------
# 3. Roda o R001 (cria/reconcilia roles 2 e 3 + grants + default privileges)
# ---------------------------------------------------------------------------
# Senhas passadas CRUAS (sem aspas): o R001 usa :'app_password', que ja vira literal SQL
# escapado pelo psql. Passar com aspas faz a senha do role incluir as aspas.
# O SQL entra por STDIN pra nao precisar montar volume nem copiar arquivo pro container.
log "Rodando R001 (idempotente)..."
docker compose exec -T "$PG_SERVICE" \
  psql -v ON_ERROR_STOP=1 -U "$PG_ADMIN_USER" -d "$PG_DB" \
    -v app_password="$NORA_APP_PASSWORD" \
    -v telemetry_password="$NORA_TELEMETRY_PASSWORD" \
  < "$R001"
log "R001 aplicado."

# ---------------------------------------------------------------------------
# 4. Smoke dos TRES roles
# ---------------------------------------------------------------------------
log "Smoke 1/3 — flags dos roles (esperado: nora_app f, nora_telemetry t)"
psql_admin -tA -F'|' -c \
  "SELECT rolname, rolcanlogin, rolbypassrls FROM pg_roles
    WHERE rolname IN ('nora_app','nora_telemetry') ORDER BY rolname"

FLAGS_OK="$(psql_admin -tAc \
  "SELECT count(*) FROM pg_roles
    WHERE (rolname='nora_app'       AND rolcanlogin AND NOT rolbypassrls)
       OR (rolname='nora_telemetry' AND rolcanlogin AND     rolbypassrls)" | tr -d '\r')"
[ "$FLAGS_OK" = "2" ] || fail "flags dos roles incorretas (esperado 2 matches, veio ${FLAGS_OK})."

log "Smoke 2/3 — nora_app conecta por senha, le tabela EXEMPT e tabela ENFORCED"
docker compose exec -T -e PGPASSWORD="$NORA_APP_PASSWORD" "$PG_SERVICE" \
  psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U nora_app -d "$PG_DB" -tA <<'SQL'
SELECT 'users_visiveis=' || count(*) FROM users;
SET nora.current_tenant_id = '00000000-0000-0000-0000-000000000000';
SELECT 'meetings_do_tenant_random=' || count(*) FROM meetings;
SQL

# ESTE e o smoke que faltava no workflow antigo. Ele checava as flags do nora_telemetry
# em pg_roles mas NUNCA testava a conexao nem o SELECT — exatamente o caminho que falha
# em silencio. Um GRANT ausente em meeting_analyses so apareceria como painel zerado.
log "Smoke 3/3 — nora_telemetry conecta e le meeting_analyses CROSS-TENANT (BYPASSRLS)"
TEL_OUT="$(docker compose exec -T -e PGPASSWORD="$NORA_TELEMETRY_PASSWORD" "$PG_SERVICE" \
  psql -v ON_ERROR_STOP=1 -h 127.0.0.1 -U nora_telemetry -d "$PG_DB" -tAc \
  "SELECT count(*) FROM meeting_analyses" | tr -d '\r')"
log "  meeting_analyses visiveis pro nora_telemetry: ${TEL_OUT}"

# Sem SET de tenant, um role NOBYPASSRLS veria 0. O nora_telemetry tem BYPASSRLS, entao
# so validamos que a query nao levantou erro de permissao (count 0 e legitimo em base nova).
case "$TEL_OUT" in
  ''|*[!0-9]*) fail "nora_telemetry nao retornou um count valido ('${TEL_OUT}'). Verifique
      o GRANT SELECT ON meeting_analyses do R001 — sem ele o painel zera em silencio." ;;
esac

log "OK — os TRES roles conferem."
log ""
log "Proximo passo (etapa 3, SEPARADA): ligar o enforce."
log "  1. no .env:  NORA_RLS_ENFORCE=true"
log "               DATASOURCE_USERNAME=nora_app"
log "               DATASOURCE_PASSWORD=<NORA_APP_PASSWORD>"
log "               NORA_TELEMETRY_DATASOURCE_URL=jdbc:postgresql://postgres:5432/${PG_DB}"
log "               NORA_TELEMETRY_DATASOURCE_USERNAME=nora_telemetry"
log "               NORA_TELEMETRY_DATASOURCE_PASSWORD=<NORA_TELEMETRY_PASSWORD>"
log "               SPRING_FLYWAY_USER=${PG_ADMIN_USER}   (Flyway continua como admin)"
log "  2. docker compose up -d --wait api"
log "  3. conferir o painel do operador — se zerar, e o nora_telemetry."
log ""
log "Lembrete: a JDBC URL NAO leva ?sslmode=require. A imagem oficial sobe com SSL OFF"
log "e o Hikari falha no boot; o trafego nao sai da bridge 'data' (internal: true)."
