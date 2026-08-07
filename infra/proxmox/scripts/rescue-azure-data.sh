#!/usr/bin/env bash
#
# rescue-azure-data.sh — EXTRAÇÃO DE EMERGÊNCIA dos dois bancos do Azure.
#
# PRIORIDADE MÁXIMA da migração (ADR 0034). A produção está fora do ar (522 no
# nora.systems / api.nora.systems desde ~2026-07) e a causa provável é a assinatura
# "Azure for Students" desativada. Assinatura desativada entra em prazo de retenção
# e depois é EXCLUÍDA — junto com os Flexible Servers e o PITR de 7 dias. Este script
# tira os dados de lá enquanto eles ainda existem.
#
# O que faz, nesta ordem:
#   1. Pré-voo: az/pg_dump/pg_restore presentes, `az account show` e estado da assinatura.
#   2. Descobre (ou aceita) o FQDN dos dois Flexible Servers e checa se estão `Ready`.
#   3. Abre regra de firewall temporária pro IP público atual — e REMOVE no trap EXIT.
#   4. `pg_dump -Fc` de `nora` (server primário) e `nora_platform` (server de plataforma).
#   5. Verifica cada dump com `pg_restore --list` (dump não verificado não é backup).
#   6. Grava SHA-256, contagem de linhas por tabela (baseline pro restore) e MANIFEST.
#
# NÃO assume que o servidor está de pé: cada etapa falha cedo com diagnóstico e o
# remédio concreto (reativar assinatura, ligar servidor parado, liberar rede, etc.).
#
# Códigos de saída:
#   0  tudo extraído e verificado
#   1  erro de uso / pré-voo (ferramenta faltando, credencial ausente)
#   2  Azure indisponível — NADA foi extraído (assinatura desativada, servidor deletado)
#   3  sucesso PARCIAL — o banco primário saiu, o de plataforma não (ou vice-versa)
#
# Uso rápido (o caminho feliz):
#   export PGPASSWORD='<senha do nora_admin>'
#   ./rescue-azure-data.sh --out-dir /srv/nora/rescue
#
set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults — todos sobrescrevíveis por flag ou env var.
# ---------------------------------------------------------------------------
SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

AZURE_RG="${AZURE_RG:-rg-nora-dev}"
# Nomes reais do ambiente dev (main.bicep:309/320 -> '<prefix>-pg[-platform]-<env>-<suffix>').
# O FQDN primário está hardcoded em 4 lugares no repo (bicepparam:140, rls-cutover.yml:40,
# rls-cutover-runbook.md:69, azure-deploy.md:398) — mantido aqui como default, não re-derivado.
PG_SERVER="${PG_SERVER:-nora-pg-dev-wgl3a3}"
PG_PLATFORM_SERVER="${PG_PLATFORM_SERVER:-nora-pg-platform-dev-wgl3a3}"
PG_FQDN="${PG_FQDN:-}"            # vazio => descobre via `az`, com fallback pro sufixo padrão
PG_PLATFORM_FQDN="${PG_PLATFORM_FQDN:-}"
PG_ADMIN_USER="${PG_ADMIN_USER:-nora_admin}"
PG_DB="${PG_DB:-nora}"
PG_PLATFORM_DB="${PG_PLATFORM_DB:-nora_platform}"
KEYVAULT_NAME="${KEYVAULT_NAME:-nora-kv-dev-wgl3a3}"
KV_SECRET_PG="${KV_SECRET_PG:-postgres-password}"

OUT_DIR="${OUT_DIR:-./azure-rescue}"
RUN_ID="${RUN_ID:-}"              # vazio => timestamp UTC; fixar torna o re-run idempotente
PG_CLIENT_IMAGE="${PG_CLIENT_IMAGE:-postgres:16-alpine}"
FIREWALL_RULE_PREFIX="nora-rescue"

CHECK_ONLY=0
FORCE=0
SKIP_PLATFORM=0
SKIP_COUNTS=0
START_IF_STOPPED=0
USE_DOCKER_CLIENT=auto            # auto | yes | no
KEEP_FIREWALL=0

usage() {
  cat <<EOF
$SCRIPT_NAME — extração de emergência dos bancos do Azure (ADR 0034)

USO
  $SCRIPT_NAME [opções]

OPÇÕES
  --out-dir <dir>            Diretório de saída (default: $OUT_DIR)
  --run-id <id>              ID do run (subdiretório). Default: timestamp UTC.
                             Fixe um valor para re-rodar de forma idempotente:
                             dumps já verificados são pulados (a menos de --force).
  --resource-group <rg>      Resource group (default: $AZURE_RG)
  --server <nome>            Nome do Flexible Server primário (default: $PG_SERVER)
  --platform-server <nome>   Nome do Flexible Server de plataforma (default: $PG_PLATFORM_SERVER)
  --fqdn <host>              FQDN do primário (pula a descoberta via az)
  --platform-fqdn <host>     FQDN do de plataforma
  --user <login>             Login admin do Postgres (default: $PG_ADMIN_USER)
  --keyvault <nome>          Key Vault para buscar a senha (default: $KEYVAULT_NAME)
  --skip-platform            Não tenta extrair o banco nora_platform
  --skip-counts              Não coleta a contagem de linhas por tabela (mais rápido)
  --start-if-stopped         Se o servidor estiver 'Stopped', liga antes de extrair
  --check-only               Só faz o pré-voo e o diagnóstico. Não extrai nada.
  --force                    Refaz dumps já existentes no run-id
  --keep-firewall            NÃO remove a regra de firewall no final (debug; use com cuidado)
  --docker-client <auto|yes|no>
                             Roda pg_dump/pg_restore dentro de $PG_CLIENT_IMAGE.
                             'auto' (default) cai pro Docker se o pg_dump local for
                             de major version menor que a do servidor.
  -h, --help                 Esta ajuda

CREDENCIAL
  A senha do admin vem, nesta ordem:
    1. \$PGPASSWORD
    2. \$PG_ADMIN_PASSWORD
    3. Azure Key Vault ($KEYVAULT_NAME / $KV_SECRET_PG) via \`az keyvault secret show\`
  A senha NUNCA é impressa nem gravada em disco.

SAÍDA (dentro de <out-dir>/<run-id>/)
  nora.dump                  pg_dump -Fc do banco transacional
  nora.dump.sha256           checksum
  nora.dump.toc              saída de \`pg_restore --list\` (prova de que o dump abre)
  nora-counts.tsv            contagem de linhas por tabela (baseline pro restore)
  nora_platform.dump[...]    idem para o control plane
  globals.sql                roles/memberships (best-effort, sem hashes de senha)
  MANIFEST.txt               resumo: FQDNs, versões, tamanhos, checksums

EXEMPLOS
  # Diagnóstico rápido: a assinatura ainda está viva?
  $SCRIPT_NAME --check-only

  # Extração completa
  export PGPASSWORD='...' && $SCRIPT_NAME --out-dir /srv/nora/rescue

  # Servidor parado por inatividade (Flexible Server para sozinho após 7 dias)
  $SCRIPT_NAME --start-if-stopped
EOF
}

# ---------------------------------------------------------------------------
# Logging — tudo em stderr para não poluir eventuais capturas de stdout.
# ---------------------------------------------------------------------------
_ts() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
log()  { printf '[%s] %s\n'        "$(_ts)" "$*" >&2; }
ok()   { printf '[%s] OK    %s\n'  "$(_ts)" "$*" >&2; }
warn() { printf '[%s] AVISO %s\n'  "$(_ts)" "$*" >&2; }
err()  { printf '[%s] ERRO  %s\n'  "$(_ts)" "$*" >&2; }
die()  { err "$*"; exit "${2:-1}"; }

hr() { printf '%s\n' "------------------------------------------------------------" >&2; }

human_bytes() {
  awk -v b="${1:-0}" 'BEGIN{
    split("B KiB MiB GiB TiB", u, " "); i=1
    while (b >= 1024 && i < 5) { b /= 1024; i++ }
    printf (i==1 ? "%d %s" : "%.1f %s"), b, u[i]
  }'
}

file_size() { wc -c < "$1" | tr -d '[:space:]'; }

# ---------------------------------------------------------------------------
# Parsing de argumentos
# ---------------------------------------------------------------------------
while [ $# -gt 0 ]; do
  case "$1" in
    --out-dir)          OUT_DIR="${2:?--out-dir exige um valor}"; shift 2 ;;
    --run-id)           RUN_ID="${2:?--run-id exige um valor}"; shift 2 ;;
    --resource-group|-g) AZURE_RG="${2:?--resource-group exige um valor}"; shift 2 ;;
    --server)           PG_SERVER="${2:?--server exige um valor}"; shift 2 ;;
    --platform-server)  PG_PLATFORM_SERVER="${2:?--platform-server exige um valor}"; shift 2 ;;
    --fqdn)             PG_FQDN="${2:?--fqdn exige um valor}"; shift 2 ;;
    --platform-fqdn)    PG_PLATFORM_FQDN="${2:?--platform-fqdn exige um valor}"; shift 2 ;;
    --user|-U)          PG_ADMIN_USER="${2:?--user exige um valor}"; shift 2 ;;
    --keyvault)         KEYVAULT_NAME="${2:?--keyvault exige um valor}"; shift 2 ;;
    --skip-platform)    SKIP_PLATFORM=1; shift ;;
    --skip-counts)      SKIP_COUNTS=1; shift ;;
    --start-if-stopped) START_IF_STOPPED=1; shift ;;
    --check-only)       CHECK_ONLY=1; shift ;;
    --force)            FORCE=1; shift ;;
    --keep-firewall)    KEEP_FIREWALL=1; shift ;;
    --docker-client)    USE_DOCKER_CLIENT="${2:?--docker-client exige auto|yes|no}"; shift 2 ;;
    -h|--help)          usage; exit 0 ;;
    *) err "opção desconhecida: $1"; echo >&2; usage; exit 1 ;;
  esac
done

umask 077   # dumps contêm PII de tenants — nunca world-readable (ADR 0029 / LGPD)

# ---------------------------------------------------------------------------
# Cleanup — remove a regra de firewall SEMPRE, preservando o exit code original.
# ---------------------------------------------------------------------------
FW_RULE=""
FW_SERVERS=()

cleanup() {
  local rc=$?
  set +e
  if [ -n "$FW_RULE" ] && [ "$KEEP_FIREWALL" -eq 0 ] && [ "${#FW_SERVERS[@]}" -gt 0 ]; then
    local srv
    for srv in "${FW_SERVERS[@]}"; do
      log "removendo regra de firewall '$FW_RULE' de $srv..."
      az postgres flexible-server firewall-rule delete \
        -g "$AZURE_RG" -n "$srv" --rule-name "$FW_RULE" --yes -o none 2>/dev/null \
        && ok "regra removida de $srv" \
        || warn "não consegui remover a regra '$FW_RULE' de $srv — REMOVA MANUALMENTE:
         az postgres flexible-server firewall-rule delete -g $AZURE_RG -n $srv --rule-name $FW_RULE --yes"
    done
  elif [ -n "$FW_RULE" ] && [ "$KEEP_FIREWALL" -eq 1 ]; then
    warn "--keep-firewall: a regra '$FW_RULE' FICOU ABERTA. Remova quando terminar."
  fi
  exit "$rc"
}
trap cleanup EXIT INT TERM

# ---------------------------------------------------------------------------
# Pré-voo 1 — ferramentas
# ---------------------------------------------------------------------------
need() {
  command -v "$1" >/dev/null 2>&1 || die "'$1' não encontrado no PATH. $2"
}

hr
log "PRÉ-VOO 1/4 — ferramentas"
need az       "Instale o Azure CLI: https://aka.ms/azure-cli"
need curl     "Instale curl (necessário para descobrir o IP público)."
command -v jq >/dev/null 2>&1 || warn "jq ausente — alguns diagnósticos ficam menos legíveis."

HAVE_LOCAL_PG=0
if command -v pg_dump >/dev/null 2>&1 && command -v pg_restore >/dev/null 2>&1; then
  HAVE_LOCAL_PG=1
  LOCAL_PG_MAJOR="$(pg_dump --version | sed -E 's/[^0-9]*([0-9]+).*/\1/')"
  log "pg_dump local: major $LOCAL_PG_MAJOR"
else
  LOCAL_PG_MAJOR=0
  warn "pg_dump/pg_restore não encontrados localmente."
fi

HAVE_DOCKER=0
command -v docker >/dev/null 2>&1 && docker info >/dev/null 2>&1 && HAVE_DOCKER=1

if [ "$HAVE_LOCAL_PG" -eq 0 ] && [ "$HAVE_DOCKER" -eq 0 ]; then
  die "sem cliente Postgres e sem Docker utilizável. Instale um dos dois:
       Debian/Ubuntu: sudo apt-get install -y postgresql-client-16
       ou             use Docker ($PG_CLIENT_IMAGE) e rode com --docker-client yes"
fi
ok "ferramentas presentes"

# ---------------------------------------------------------------------------
# Pré-voo 2 — login e ESTADO DA ASSINATURA (a causa provável do apagão)
# ---------------------------------------------------------------------------
hr
log "PRÉ-VOO 2/4 — login Azure e estado da assinatura"

if ! ACCOUNT_JSON="$(az account show -o json 2>&1)"; then
  err "não há login ativo no Azure CLI."
  err "Saída do az: ${ACCOUNT_JSON}"
  die "Rode:  az login   (ou: az login --use-device-code numa sessão SSH)" 2
fi

SUB_ID="$(az account show --query id -o tsv 2>/dev/null || echo '?')"
SUB_NAME="$(az account show --query name -o tsv 2>/dev/null || echo '?')"
SUB_STATE="$(az account show --query state -o tsv 2>/dev/null || echo 'Unknown')"
SUB_USER="$(az account show --query user.name -o tsv 2>/dev/null || echo '?')"

log "assinatura: $SUB_NAME ($SUB_ID)"
log "identidade: $SUB_USER"
log "estado:     $SUB_STATE"

case "$SUB_STATE" in
  Enabled)
    ok "assinatura ativa"
    ;;
  Disabled|Warned|PastDue|Expired)
    hr
    err "A ASSINATURA ESTÁ EM '$SUB_STATE' — os recursos estão suspensos."
    err ""
    err "Isso bate com o sintoma observado (522 no nora.systems, FQDN do Container App"
    err "sem conexão). Enquanto a assinatura não voltar pra 'Enabled', o Postgres NÃO"
    err "aceita conexão e este script NÃO consegue extrair nada."
    err ""
    err "O QUE FAZER, NESTA ORDEM (relógio correndo — depois do prazo de retenção os"
    err "recursos são EXCLUÍDOS e os dados vão junto):"
    err "  1. Portal -> Subscriptions -> '$SUB_NAME' -> Overview."
    err "     Se for 'Azure for Students' com crédito zerado/expirado, a reativação é"
    err "     via 'Upgrade' (troca pra Pay-As-You-Go com cartão). Crédito novo não volta."
    err "  2. Confirme o retorno:  az account show --query state -o tsv   (espera 'Enabled')"
    err "  3. O Flexible Server pode subir 'Stopped'. Rode este script de novo com"
    err "     --start-if-stopped."
    err "  4. Só então prossiga com a migração (restore-into-proxmox.sh)."
    err ""
    err "Se a reativação não for possível, tente ainda assim um --check-only: recursos em"
    err "período de retenção às vezes respondem ao control plane mesmo suspensos."
    hr
    die "abortando: assinatura não está Enabled" 2
    ;;
  *)
    warn "estado da assinatura desconhecido ('$SUB_STATE') — seguindo mesmo assim."
    ;;
esac

# ---------------------------------------------------------------------------
# Pré-voo 3 — os servidores existem e estão Ready?
# ---------------------------------------------------------------------------
hr
log "PRÉ-VOO 3/4 — Flexible Servers"

# describe_server <nome> -> ecoa "fqdn|state|version|publicNetworkAccess" ou vazio
describe_server() {
  local name="$1" out
  if ! out="$(az postgres flexible-server show -g "$AZURE_RG" -n "$name" \
        --query '[fullyQualifiedDomainName, state, version, network.publicNetworkAccess]' \
        -o tsv 2>&1)"; then
    printf ''
    return 1
  fi
  printf '%s' "$out" | tr '\t' '|'
}

# check_server <nome> <fqdn_override> <rotulo> -> ecoa o FQDN utilizável, ou falha.
# Efeito colateral: LAST_SERVER_MANAGED=1 quando o `az` enxerga o recurso (e portanto
# faz sentido tentar abrir regra de firewall nele).
LAST_SERVER_MANAGED=0
check_server() {
  local name="$1" fqdn_override="$2" label="$3"
  local info fqdn state version pubaccess
  LAST_SERVER_MANAGED=0

  if info="$(describe_server "$name")" && [ -n "$info" ]; then
    LAST_SERVER_MANAGED=1
    fqdn="$(printf '%s' "$info" | cut -d'|' -f1)"
    state="$(printf '%s' "$info" | cut -d'|' -f2)"
    version="$(printf '%s' "$info" | cut -d'|' -f3)"
    pubaccess="$(printf '%s' "$info" | cut -d'|' -f4)"
    log "$label: $name  state=$state  pg=$version  publicAccess=$pubaccess"

    if [ "$state" = "Stopped" ]; then
      if [ "$START_IF_STOPPED" -eq 1 ]; then
        log "$label: servidor parado — ligando (leva ~2-5 min)..."
        az postgres flexible-server start -g "$AZURE_RG" -n "$name" -o none \
          || die "falha ao ligar '$name'. Ligue pelo Portal e rode de novo."
        ok "$label: servidor iniciado"
        state="Ready"
      else
        err "$label: servidor está STOPPED. O Flexible Server para sozinho após 7 dias"
        err "        de inatividade (e ao suspender a assinatura). Rode com --start-if-stopped"
        err "        ou:  az postgres flexible-server start -g $AZURE_RG -n $name"
        return 1
      fi
    elif [ "$state" != "Ready" ]; then
      err "$label: servidor em estado '$state' (esperado 'Ready'). Aguarde e repita;"
      err "        se persistir, veja o Activity Log:"
      err "        az monitor activity-log list -g $AZURE_RG --max-events 20 -o table"
      return 1
    fi

    if [ "$pubaccess" = "Disabled" ]; then
      err "$label: publicNetworkAccess=Disabled (servidor VNet-injected). Regra de firewall"
      err "        não resolve — é preciso rodar a extração de DENTRO da VNet (uma VM/Bastion)"
      err "        ou habilitar acesso público temporariamente:"
      err "        az postgres flexible-server update -g $AZURE_RG -n $name --public-access Enabled"
      return 1
    fi

    [ -n "$fqdn_override" ] && fqdn="$fqdn_override"
    printf '%s' "$fqdn"
    return 0
  fi

  # Não conseguiu descrever: pode ser servidor deletado, RG errado, ou control plane suspenso.
  LAST_SERVER_MANAGED=0
  err "$label: não consegui ler '$name' no resource group '$AZURE_RG'."
  if [ -n "$fqdn_override" ]; then
    warn "$label: seguindo com o FQDN informado ($fqdn_override) — tentativa às cegas."
    printf '%s' "$fqdn_override"
    return 0
  fi
  err "        Diagnóstico:"
  err "          az group show -n $AZURE_RG -o table"
  err "          az postgres flexible-server list -o table"
  err "        Se o servidor sumiu da lista, procure em recursos deletados/retenção:"
  err "          az resource list --resource-type Microsoft.DBforPostgreSQL/flexibleServers -o table"
  err "        Se você já sabe o FQDN, force com --fqdn/--platform-fqdn (o firewall será"
  err "        pulado e a conexão tentada direto)."
  return 1
}

PRIMARY_OK=1
PRIMARY_FQDN=""
if PRIMARY_FQDN="$(check_server "$PG_SERVER" "$PG_FQDN" "primário")"; then
  ok "primário alcançável no control plane: $PRIMARY_FQDN"
  # Só faz sentido abrir firewall em servidor que o `az` enxerga.
  if [ "$LAST_SERVER_MANAGED" -eq 1 ]; then FW_SERVERS+=("$PG_SERVER"); fi
else
  PRIMARY_OK=0
fi

PLATFORM_OK=1
PLATFORM_FQDN_RESOLVED=""
if [ "$SKIP_PLATFORM" -eq 1 ]; then
  PLATFORM_OK=0
  log "plataforma: pulada por --skip-platform"
elif PLATFORM_FQDN_RESOLVED="$(check_server "$PG_PLATFORM_SERVER" "$PG_PLATFORM_FQDN" "plataforma")"; then
  ok "plataforma alcançável no control plane: $PLATFORM_FQDN_RESOLVED"
  describe_server "$PG_PLATFORM_SERVER" >/dev/null 2>&1 && FW_SERVERS+=("$PG_PLATFORM_SERVER")
else
  PLATFORM_OK=0
  warn "plataforma indisponível — a extração seguirá só com o banco primário."
fi

if [ "$PRIMARY_OK" -eq 0 ] && [ "$PLATFORM_OK" -eq 0 ]; then
  die "nenhum servidor alcançável — nada a extrair. Veja o diagnóstico acima." 2
fi

# ---------------------------------------------------------------------------
# Pré-voo 4 — credencial
# ---------------------------------------------------------------------------
hr
log "PRÉ-VOO 4/4 — credencial do Postgres"

PG_PASSWORD="${PGPASSWORD:-${PG_ADMIN_PASSWORD:-}}"
PG_PASSWORD_SOURCE="env"
if [ -z "$PG_PASSWORD" ]; then
  log "PGPASSWORD/PG_ADMIN_PASSWORD vazios — tentando o Key Vault '$KEYVAULT_NAME'..."
  if PG_PASSWORD="$(az keyvault secret show --vault-name "$KEYVAULT_NAME" \
        --name "$KV_SECRET_PG" --query value -o tsv 2>/dev/null)" && [ -n "$PG_PASSWORD" ]; then
    PG_PASSWORD_SOURCE="keyvault:$KEYVAULT_NAME/$KV_SECRET_PG"
    ok "senha obtida do Key Vault"
  else
    err "não consegui a senha do admin."
    err "  1) Se você tem a senha:  export PGPASSWORD='...'  e rode de novo."
    err "  2) Do Key Vault (precisa do papel 'Key Vault Secrets User'):"
    err "     az keyvault secret show --vault-name $KEYVAULT_NAME --name $KV_SECRET_PG --query value -o tsv"
    err "  3) Se o KV também está suspenso/purgado, a senha pode estar nos GitHub Secrets"
    err "     (PG_ADMIN_PASSWORD) — mas o GitHub não permite LER um secret; use uma cópia"
    err "     local ou resete a senha do admin:"
    err "     az postgres flexible-server update -g $AZURE_RG -n $PG_SERVER --admin-password '<nova>'"
    die "sem credencial — abortando" 1
  fi
fi
ok "credencial disponível (origem: $PG_PASSWORD_SOURCE)"
export PGPASSWORD="$PG_PASSWORD"
export PGCONNECT_TIMEOUT="${PGCONNECT_TIMEOUT:-20}"

if [ "$CHECK_ONLY" -eq 1 ]; then
  hr
  ok "--check-only: pré-voo concluído. Servidores e credencial parecem utilizáveis."
  log "Rode sem --check-only para extrair de fato."
  exit 0
fi

# ---------------------------------------------------------------------------
# Preparação do diretório de saída
# ---------------------------------------------------------------------------
[ -n "$RUN_ID" ] || RUN_ID="$(date -u '+%Y%m%dT%H%M%SZ')"
RUN_DIR="$OUT_DIR/$RUN_ID"
mkdir -p "$RUN_DIR"
RUN_DIR="$(cd "$RUN_DIR" && pwd)"
hr
log "diretório do run: $RUN_DIR"

# ---------------------------------------------------------------------------
# Cliente Postgres: local ou containerizado.
#
# pg_dump recusa servidor com major version MAIOR que a do cliente. Debian 12 traz
# client 15 por default e o Flexible Server é 16 — cenário de falha muito comum na
# hora errada. Se houver Docker, caímos pro cliente containerizado automaticamente.
# ---------------------------------------------------------------------------
SERVER_MAJOR=""
if [ "$PRIMARY_OK" -eq 1 ]; then
  SERVER_MAJOR="$(az postgres flexible-server show -g "$AZURE_RG" -n "$PG_SERVER" \
                    --query version -o tsv 2>/dev/null | cut -d. -f1 || true)"
fi
[ -n "$SERVER_MAJOR" ] || SERVER_MAJOR=16

case "$USE_DOCKER_CLIENT" in
  yes) DOCKERIZED=1 ;;
  no)  DOCKERIZED=0 ;;
  auto)
    if [ "$HAVE_LOCAL_PG" -eq 1 ] && [ "$LOCAL_PG_MAJOR" -ge "$SERVER_MAJOR" ]; then
      DOCKERIZED=0
    elif [ "$HAVE_DOCKER" -eq 1 ]; then
      DOCKERIZED=1
      warn "pg_dump local é major $LOCAL_PG_MAJOR e o servidor é $SERVER_MAJOR —"
      warn "usando o cliente containerizado ($PG_CLIENT_IMAGE). Force com --docker-client no."
    else
      DOCKERIZED=0
      warn "pg_dump local ($LOCAL_PG_MAJOR) < servidor ($SERVER_MAJOR) e sem Docker."
      warn "O dump provavelmente vai falhar com 'server version mismatch'."
      warn "Instale:  sudo apt-get install -y postgresql-client-$SERVER_MAJOR"
    fi
    ;;
  *) die "--docker-client aceita auto|yes|no (recebido: '$USE_DOCKER_CLIENT')" ;;
esac

if [ "$DOCKERIZED" -eq 1 ]; then
  OUT_MOUNT="/out"
  log "cliente Postgres: container $PG_CLIENT_IMAGE"
else
  OUT_MOUNT="$RUN_DIR"
  log "cliente Postgres: binários locais (major $LOCAL_PG_MAJOR)"
fi

# pg <ferramenta> [args...] — executa pg_dump/pg_restore/psql/pg_dumpall no cliente escolhido.
# Caminhos de arquivo devem sempre usar $OUT_MOUNT.
pg() {
  if [ "$DOCKERIZED" -eq 1 ]; then
    docker run --rm -i \
      -e PGPASSWORD -e PGCONNECT_TIMEOUT \
      -u "$(id -u):$(id -g)" \
      -v "$RUN_DIR:$OUT_MOUNT" \
      "$PG_CLIENT_IMAGE" "$@"
  else
    "$@"
  fi
}

conn_str() {  # conn_str <fqdn> <db>
  printf 'host=%s port=5432 dbname=%s user=%s sslmode=require' "$1" "$2" "$PG_ADMIN_USER"
}

# ---------------------------------------------------------------------------
# Extração de um banco
# ---------------------------------------------------------------------------
# dump_db <fqdn> <db> <rotulo> -> 0 sucesso, 1 falha
dump_db() {
  local fqdn="$1" db="$2" label="$3"
  local base="$db"
  local local_dump="$RUN_DIR/$base.dump"
  local inner_dump="$OUT_MOUNT/$base.dump"
  local inner_part="$OUT_MOUNT/$base.dump.part"
  local local_part="$RUN_DIR/$base.dump.part"

  hr
  log "EXTRAINDO $label — $db @ $fqdn"

  if [ -s "$local_dump" ] && [ "$FORCE" -eq 0 ]; then
    if pg pg_restore --list "$inner_dump" >/dev/null 2>&1; then
      ok "$label: dump já existe e abre corretamente — pulando (use --force para refazer)"
      return 0
    fi
    warn "$label: dump existente está corrompido — refazendo"
  fi
  rm -f "$local_part"

  # 1) conectividade: falha aqui é MUITO mais legível que um pg_dump abortando no meio.
  log "$label: testando conexão..."
  local probe
  if ! probe="$(pg psql "$(conn_str "$fqdn" "$db")" -tAc "SELECT version()" 2>&1)"; then
    err "$label: não conectou em $fqdn/$db."
    err "        Resposta: $(printf '%s' "$probe" | head -3 | tr '\n' ' ')"
    case "$probe" in
      *"no pg_hba"*|*"pg_hba.conf"*)
        err "        -> regra de firewall não pegou (IP mudou?) ou o servidor exige AAD auth." ;;
      *"password authentication failed"*)
        err "        -> senha errada. Confira a origem ($PG_PASSWORD_SOURCE) ou resete:"
        err "           az postgres flexible-server update -g $AZURE_RG -n $PG_SERVER --admin-password '<nova>'" ;;
      *"does not exist"*)
        err "        -> o banco '$db' não existe nesse servidor. Liste com:"
        err "           az postgres flexible-server db list -g $AZURE_RG -s <server> -o table" ;;
      *"timeout expired"*|*"could not connect"*|*"Connection timed out"*)
        err "        -> rede/firewall. Confirme seu IP público e a regra criada acima." ;;
      *"SSL"*|*"ssl"*)
        err "        -> problema de TLS. O Flexible Server exige sslmode=require." ;;
    esac
    return 1
  fi
  log "$label: conectado — $(printf '%s' "$probe" | cut -c1-60)"

  # 2) dump para .part e rename atômico: um .dump só existe se o pg_dump terminou.
  log "$label: pg_dump -Fc em andamento (pode demorar)..."
  local t0 t1
  t0="$(date +%s)"
  if ! pg pg_dump -Fc -Z6 --no-sync \
        --file="$inner_part" \
        "$(conn_str "$fqdn" "$db")" 2>"$RUN_DIR/$base.dump.err"; then
    err "$label: pg_dump FALHOU. Últimas linhas:"
    tail -5 "$RUN_DIR/$base.dump.err" >&2 || true
    if grep -qi "server version" "$RUN_DIR/$base.dump.err" 2>/dev/null; then
      err "        -> incompatibilidade de versão do cliente. Rode com --docker-client yes."
    fi
    rm -f "$local_part"
    return 1
  fi
  t1="$(date +%s)"
  mv -f "$local_part" "$local_dump"
  rm -f "$RUN_DIR/$base.dump.err"

  local size
  size="$(file_size "$local_dump")"
  ok "$label: dump gravado — $(human_bytes "$size") em $((t1 - t0))s -> $local_dump"

  if [ "$size" -lt 1024 ]; then
    warn "$label: dump com menos de 1 KiB. Isso é suspeito para um banco em produção."
  fi

  # 3) verificação: um dump que não abre não é backup.
  log "$label: verificando com pg_restore --list..."
  if ! pg pg_restore --list "$inner_dump" > "$RUN_DIR/$base.dump.toc" 2>"$RUN_DIR/$base.toc.err"; then
    err "$label: pg_restore --list FALHOU — o dump está inutilizável."
    tail -5 "$RUN_DIR/$base.toc.err" >&2 || true
    return 1
  fi
  rm -f "$RUN_DIR/$base.toc.err"
  local entries tables
  entries="$(grep -cv '^;' "$RUN_DIR/$base.dump.toc" || true)"
  tables="$(grep -c ' TABLE DATA ' "$RUN_DIR/$base.dump.toc" || true)"
  if [ "${entries:-0}" -eq 0 ]; then
    err "$label: TOC vazio — o dump não tem objeto nenhum."
    return 1
  fi
  ok "$label: dump verificado — $entries entradas no TOC, $tables tabelas com dados"

  # 4) checksum
  if command -v sha256sum >/dev/null 2>&1; then
    (cd "$RUN_DIR" && sha256sum "$base.dump" > "$base.dump.sha256")
  elif command -v shasum >/dev/null 2>&1; then
    (cd "$RUN_DIR" && shasum -a 256 "$base.dump" > "$base.dump.sha256")
  else
    warn "$label: sem sha256sum/shasum — checksum NÃO gravado."
  fi
  [ -f "$RUN_DIR/$base.dump.sha256" ] && ok "$label: checksum -> $base.dump.sha256"

  # 5) baseline de contagens (o restore-into-proxmox compara contra isto)
  if [ "$SKIP_COUNTS" -eq 0 ]; then
    log "$label: coletando contagem de linhas por tabela..."
    if pg psql "$(conn_str "$fqdn" "$db")" -tA -F$'\t' --set ON_ERROR_STOP=1 \
         -c "SELECT c.relname,
                    (xpath('/row/c/text()',
                           query_to_xml(format('SELECT count(*) AS c FROM %I.%I', n.nspname, c.relname),
                                        false, true, '')))[1]::text::bigint
             FROM pg_class c
             JOIN pg_namespace n ON n.oid = c.relnamespace
             WHERE c.relkind = 'r' AND n.nspname = 'public'
             ORDER BY 1" > "$RUN_DIR/$base-counts.tsv" 2>/dev/null; then
      ok "$label: $(wc -l < "$RUN_DIR/$base-counts.tsv" | tr -d ' ') tabelas contadas -> $base-counts.tsv"
    else
      warn "$label: não consegui coletar contagens (segue sem baseline)."
      rm -f "$RUN_DIR/$base-counts.tsv"
    fi
  fi

  return 0
}

# ---------------------------------------------------------------------------
# Firewall temporário
# ---------------------------------------------------------------------------
if [ "${#FW_SERVERS[@]}" -gt 0 ]; then
  hr
  log "abrindo firewall temporário para o IP público atual"
  MY_IP=""
  for svc in "https://ifconfig.me" "https://api.ipify.org" "https://icanhazip.com"; do
    MY_IP="$(curl -fsS --max-time 10 "$svc" 2>/dev/null | tr -d '[:space:]' || true)"
    case "$MY_IP" in
      *[0-9].[0-9]*) break ;;
      *) MY_IP="" ;;
    esac
  done
  if [ -z "$MY_IP" ]; then
    warn "não consegui descobrir o IP público (sem saída HTTPS?). Pulando o firewall."
    warn "Se a conexão falhar, crie a regra manualmente:"
    warn "  az postgres flexible-server firewall-rule create -g $AZURE_RG -n $PG_SERVER \\"
    warn "     --rule-name manual --start-ip-address <IP> --end-ip-address <IP>"
  else
    # Nome determinístico -> re-rodar o script sobrescreve a mesma regra (idempotente).
    HOSTTAG="$(hostname 2>/dev/null | tr -cd 'a-zA-Z0-9' | cut -c1-16)"
    [ -n "$HOSTTAG" ] || HOSTTAG="host"
    FW_RULE="${FIREWALL_RULE_PREFIX}-${HOSTTAG}"
    log "IP público: $MY_IP  |  regra: $FW_RULE"
    for srv in "${FW_SERVERS[@]}"; do
      if az postgres flexible-server firewall-rule create \
           -g "$AZURE_RG" -n "$srv" --rule-name "$FW_RULE" \
           --start-ip-address "$MY_IP" --end-ip-address "$MY_IP" -o none 2>/dev/null; then
        ok "firewall aberto em $srv"
      else
        warn "não consegui criar a regra em $srv — vou tentar conectar mesmo assim"
      fi
    done
    # O Flexible Server leva alguns segundos para propagar a regra.
    sleep 5
  fi
fi

# ---------------------------------------------------------------------------
# Execução
# ---------------------------------------------------------------------------
RC_PRIMARY=1
RC_PLATFORM=1

if [ "$PRIMARY_OK" -eq 1 ]; then
  if dump_db "$PRIMARY_FQDN" "$PG_DB" "PRIMÁRIO"; then RC_PRIMARY=0; fi
fi

if [ "$PLATFORM_OK" -eq 1 ]; then
  if dump_db "$PLATFORM_FQDN_RESOLVED" "$PG_PLATFORM_DB" "PLATAFORMA"; then RC_PLATFORM=0; fi
fi

# Globals (roles + memberships). Best-effort: no Flexible Server o admin NÃO é superuser,
# então hashes de senha não saem — daí o --no-role-passwords. Serve como inventário: quais
# roles existiam (nora_app, nora_telemetry, o owner) e quem era membro de quem.
# As senhas dos roles são recriadas do zero pelo R001 no restore.
if [ "$PRIMARY_OK" -eq 1 ] && [ "$RC_PRIMARY" -eq 0 ]; then
  hr
  log "coletando globals (roles/memberships, sem hashes)..."
  if pg pg_dumpall --globals-only --no-role-passwords \
       -d "$(conn_str "$PRIMARY_FQDN" "$PG_DB")" > "$RUN_DIR/globals.sql" 2>/dev/null \
       && [ -s "$RUN_DIR/globals.sql" ]; then
    ok "globals -> globals.sql ($(human_bytes "$(file_size "$RUN_DIR/globals.sql")"))"
  else
    rm -f "$RUN_DIR/globals.sql"
    warn "pg_dumpall --globals-only falhou (esperado sem superuser). Sem impacto:"
    warn "o restore recria os roles via db/operational/R001__provision_app_roles.sql."
  fi
fi

# ---------------------------------------------------------------------------
# MANIFEST + resumo
# ---------------------------------------------------------------------------
{
  echo "NORA — resgate de dados do Azure"
  echo "run_id:          $RUN_ID"
  echo "gerado_em_utc:   $(_ts)"
  echo "executado_por:   $(whoami 2>/dev/null || echo '?')@$(hostname 2>/dev/null || echo '?')"
  echo "subscription:    $SUB_NAME ($SUB_ID) state=$SUB_STATE"
  echo "resource_group:  $AZURE_RG"
  echo "server_primario: ${PRIMARY_FQDN:-<indisponível>} db=$PG_DB"
  echo "server_platform: ${PLATFORM_FQDN_RESOLVED:-<indisponível>} db=$PG_PLATFORM_DB"
  echo "cliente_pg:      $([ "$DOCKERIZED" -eq 1 ] && echo "docker/$PG_CLIENT_IMAGE" || echo "local major $LOCAL_PG_MAJOR")"
  echo ""
  echo "arquivos:"
  for f in "$RUN_DIR"/*; do
    [ -f "$f" ] || continue
    printf '  %-28s %12s bytes\n' "$(basename "$f")" "$(file_size "$f")"
  done
  echo ""
  echo "checksums:"
  cat "$RUN_DIR"/*.sha256 2>/dev/null | sed 's/^/  /' || echo "  (nenhum)"
  echo ""
  echo "proximo passo:"
  echo "  infra/proxmox/scripts/restore-into-proxmox.sh --from-dir $RUN_DIR"
} > "$RUN_DIR/MANIFEST.txt"

hr
log "RESUMO"
log "  diretório: $RUN_DIR"
for db in "$PG_DB" "$PG_PLATFORM_DB"; do
  f="$RUN_DIR/$db.dump"
  if [ -s "$f" ]; then
    log "  $(printf '%-16s' "$db") $(human_bytes "$(file_size "$f")")  [verificado]"
  else
    log "  $(printf '%-16s' "$db") <não extraído>"
  fi
done
hr

if [ "$RC_PRIMARY" -ne 0 ]; then
  err "O BANCO PRIMÁRIO NÃO FOI EXTRAÍDO. Este é o dado que não pode ser perdido."
  err "Releia os erros acima, corrija e rode de novo (o run-id '$RUN_ID' pode ser"
  err "reaproveitado com --run-id $RUN_ID — o que já saiu não é refeito)."
  exit 2
fi

if [ "$SKIP_PLATFORM" -eq 0 ] && [ "$RC_PLATFORM" -ne 0 ]; then
  warn "SUCESSO PARCIAL: '$PG_DB' extraído e verificado; '$PG_PLATFORM_DB' NÃO."
  warn "O control plane (ADR 0022) é reconstruível — o catálogo de modelos LLM e as"
  warn "feature flags são recriados pela V001 de db/platform no primeiro boot. Perde-se"
  warn "só a telemetria histórica de custo (usage_events). Decida se vale insistir."
  exit 3
fi

ok "RESGATE COMPLETO. Guarde $RUN_DIR fora desta máquina antes de seguir."
ok "Próximo passo: restore-into-proxmox.sh --from-dir $RUN_DIR"
exit 0
