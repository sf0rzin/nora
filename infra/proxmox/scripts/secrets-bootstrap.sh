#!/usr/bin/env bash
#
# secrets-bootstrap.sh — gera e coleta TODOS os segredos da stack, e emite o
# `secrets.env.sops` cifrado. Roda UMA vez, no host, antes do primeiro deploy.
#
# ============================================================================
# NENHUM VALOR É IMPRESSO. NUNCA.
# ============================================================================
# Este script foi escrito para poder ser executado por um agente de IA sem que os
# segredos entrem no contexto/transcript dele — e, de quebra, para não deixar
# rastro em `history`, em log de terminal ou em screenshot.
#
#   * Valores gerados vão DIRETO para o arquivo. Não passam por stdout.
#   * Entrada interativa usa `read -rs` (sem eco na tela).
#   * O relatório final mostra só o NOME da variável e se está preenchida —
#     nunca o conteúdo. Ex.:  JWT_SECRET  [ok, 64 chars]
#   * O arquivo em claro é criado com umask 077 e apagado no trap EXIT.
#
# O que ele faz:
#   1. Gera os segredos que NÃO vêm de lugar nenhum (senhas, chaves simétricas).
#   2. Coleta os que só você tem (Cloudflare, provedores de LLM, OAuth) — de um
#      arquivo com `--from-file` ou perguntando um a um, sem eco.
#   3. Escreve `secrets.env` em tmpfs, cifra com SOPS+age e apaga o claro.
#
# Uso:
#   ./secrets-bootstrap.sh                          # interativo, pergunta tudo
#   ./secrets-bootstrap.sh --from-file ~/cf.txt     # lê o que puder do arquivo
#   ./secrets-bootstrap.sh --regenerate JWT_SECRET  # rotaciona um segredo só
#   ./secrets-bootstrap.sh --check                  # audita o .sops existente
#
# Formato aceito em --from-file (uma chave por linha, `=` ou `:` como separador;
# linhas iniciadas por # são ignoradas; chaves desconhecidas são ignoradas em
# silêncio, então dá pra apontar para um arquivo de anotações qualquer):
#
#   acc_id=<cloudflare account id>
#   write_all=<api token com Tunnel:Edit + Access:Edit>
#   CLOUDFLARE_TUNNEL_TOKEN=<connector token, se você já criou o túnel>
#   OPENAI_API_KEY=...
#   GEMINI_API_KEY=...
#
# NÃO são usados, e por isso nem são lidos: `user`, `pass`, `read_all`. Login de
# conta não tem função nesta stack, e um token read-only não cria túnel. Se
# estiverem no arquivo, são ignorados — mas o certo é não deixá-los lá.

set -euo pipefail
umask 077

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROXMOX_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SOPS_FILE="${SOPS_FILE:-$PROXMOX_DIR/secrets.env.sops}"
AGE_KEY_FILE="${SOPS_AGE_KEY_FILE:-/etc/nora/age.key}"

FROM_FILE=""
CHECK_ONLY=0
REGENERATE=""

if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_YLW=$'\033[33m'; C_GRN=$'\033[32m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else
  C_RED=''; C_YLW=''; C_GRN=''; C_DIM=''; C_OFF=''
fi
log()  { printf '%s==>%s %s\n' "$C_GRN" "$C_OFF" "$*"; }
info() { printf '%s    %s%s\n' "$C_DIM" "$*" "$C_OFF"; }
warn() { printf '%sAVISO:%s %s\n' "$C_YLW" "$C_OFF" "$*" >&2; }
die()  { printf '%sERRO:%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

usage() { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --from-file)  FROM_FILE="${2:?--from-file exige um caminho}"; shift 2 ;;
    --check)      CHECK_ONLY=1; shift ;;
    --regenerate) REGENERATE="${2:?--regenerate exige o nome da variavel}"; shift 2 ;;
    -h|--help)    usage; exit 0 ;;
    *) die "opção desconhecida: $1" ;;
  esac
done

# ---------------------------------------------------------------------------
# Catálogo. GERADO = criado aqui; COLETADO = só você tem; OPCIONAL = pode ficar vazio.
# ---------------------------------------------------------------------------
# formato: NOME|classe|gerador-ou-descrição
CATALOGO=$(cat <<'CAT'
POSTGRES_ADMIN_PASSWORD|gerado|b64:32
POSTGRES_PLATFORM_ADMIN_PASSWORD|gerado|b64:32
JWT_SECRET|gerado|b64:48
GRAFANA_ADMIN_PASSWORD|gerado|b64:24
NORA_PLATFORM_INTERNAL_TOKEN|gerado|hex:32
NORA_PLATFORM_ADMIN_TOKEN|gerado|hex:32
NORA_INTEGRATIONS_STATE_SECRET|gerado|hex:32
NORA_INTEGRATIONS_ENC_KEY|gerado|aes256b64
NORA_APP_PASSWORD|gerado|b64:32
RLS_TELEMETRY_PASSWORD|gerado|b64:32
CLOUDFLARE_TUNNEL_TOKEN|coletado|Connector token do tunnel nora-prod (Zero Trust > Networks > Tunnels)
CF_ACCESS_TEAM_DOMAIN|coletado|ex.: stratfy.cloudflareaccess.com (nao e segredo)
CF_ACCESS_AUD|coletado|AUD tag da Access Application do admin (nao e segredo)
GHCR_PULL_TOKEN|coletado|PAT do GitHub com APENAS read:packages
OPENAI_API_KEY|opcional|vazio = worker em modo stub e chat 503
GEMINI_API_KEY|opcional|vazio = embeddings/RAG desligados
DEEPSEEK_API_KEY|opcional|vazio = provider indisponivel no chat
RESEND_API_KEY|opcional|vazio = backend cai em LogEmailSender
CAT
)

# ---------------------------------------------------------------------------
# Geradores. Escrevem em stdout do SUBSHELL, capturado direto para variável —
# nunca para o terminal.
# ---------------------------------------------------------------------------
gerar() {
  case "$1" in
    b64:*)     openssl rand -base64 "${1#b64:}" | tr -d '\n=' | tr '+/' '-_' ;;
    hex:*)     openssl rand -hex "${1#hex:}" ;;
    # AES-256-GCM: exatamente 32 bytes em base64 PADRÃO (com padding). O
    # TokenCipher valida isso no boot — base64url ou tamanho errado derruba a API.
    aes256b64) openssl rand -base64 32 ;;
    *) die "gerador desconhecido: $1" ;;
  esac
}

# ---------------------------------------------------------------------------
# Parse do --from-file. Aceita `=` ou `:`; ignora comentário, espaço e aspas.
# Mapeia os apelidos do arquivo de anotações para os nomes canônicos.
# ---------------------------------------------------------------------------
declare -A COLETA=()

ler_arquivo() {
  local f="$1" linha chave valor
  [ -r "$f" ] || die "não consigo ler $f"
  # Nunca ecoa o conteúdo. Só conta quantas chaves reconheceu.
  local reconhecidas=0 ignoradas=0
  while IFS= read -r linha || [ -n "$linha" ]; do
    case "$linha" in ''|\#*) continue ;; esac
    if [[ "$linha" == *=* ]]; then chave="${linha%%=*}"; valor="${linha#*=}"
    elif [[ "$linha" == *:* ]]; then chave="${linha%%:*}"; valor="${linha#*:}"
    else continue; fi
    chave="$(printf '%s' "$chave" | tr -d '[:space:]')"
    valor="$(printf '%s' "$valor" | sed 's/^[[:space:]]*//; s/[[:space:]]*$//; s/^"//; s/"$//')"
    [ -n "$valor" ] || continue
    case "$chave" in
      acc_id|account_id|CLOUDFLARE_ACCOUNT_ID)   COLETA[CLOUDFLARE_ACCOUNT_ID]="$valor"; reconhecidas=$((reconhecidas+1)) ;;
      write_all|CLOUDFLARE_API_TOKEN)            COLETA[CLOUDFLARE_API_TOKEN]="$valor";  reconhecidas=$((reconhecidas+1)) ;;
      # Deliberadamente NÃO lidos: login de conta não serve à stack, e um token
      # read-only não cria túnel. Guardá-los aqui só aumentaria a superfície.
      user|username|pass|password|read_all)      ignoradas=$((ignoradas+1)) ;;
      *)
        # Qualquer nome que já seja canônico do catálogo entra direto.
        if printf '%s\n' "$CATALOGO" | cut -d'|' -f1 | grep -qx "$chave"; then
          COLETA["$chave"]="$valor"; reconhecidas=$((reconhecidas+1))
        else
          ignoradas=$((ignoradas+1))
        fi ;;
    esac
  done < "$f"
  info "$f: $reconhecidas chave(s) reconhecida(s), $ignoradas ignorada(s)"
}

# ---------------------------------------------------------------------------
# --check: audita o .sops existente sem revelar nada
# ---------------------------------------------------------------------------
if [ "$CHECK_ONLY" -eq 1 ]; then
  [ -f "$SOPS_FILE" ] || die "não existe $SOPS_FILE — rode sem --check para criar."
  command -v sops >/dev/null || die "sops não encontrado (rode o bootstrap-host.sh)."
  export SOPS_AGE_KEY_FILE="$AGE_KEY_FILE"
  log "Auditando $SOPS_FILE (valores NÃO são exibidos)"
  tmp="$(mktemp)"; trap 'shred -u "$tmp" 2>/dev/null || rm -f "$tmp"' EXIT
  sops --decrypt --input-type dotenv --output-type dotenv "$SOPS_FILE" > "$tmp" \
    || die "falha ao decifrar. A chave age em $AGE_KEY_FILE é a certa?"
  faltando=0
  while IFS='|' read -r nome classe _; do
    v="$(sed -n "s/^$nome=//p" "$tmp" | head -1)"
    n=${#v}
    if [ "$n" -eq 0 ]; then
      case "$classe" in
        opcional) printf '  %-34s %s[vazio - opcional]%s\n' "$nome" "$C_DIM" "$C_OFF" ;;
        *)        printf '  %-34s %sFALTANDO%s\n' "$nome" "$C_RED" "$C_OFF"; faltando=$((faltando+1)) ;;
      esac
    elif [ "$v" = "unset" ]; then
      printf '  %-34s %sliteral "unset" - quebra o boot%s\n' "$nome" "$C_RED" "$C_OFF"; faltando=$((faltando+1))
    else
      printf '  %-34s %s[ok, %s chars]%s\n' "$nome" "$C_GRN" "$n" "$C_OFF"
    fi
  done <<< "$CATALOGO"
  echo
  [ "$faltando" -eq 0 ] && log "Tudo que é obrigatório está preenchido." \
    || die "$faltando item(ns) obrigatório(s) faltando."
  exit 0
fi

# ---------------------------------------------------------------------------
# Pré-voo
# ---------------------------------------------------------------------------
command -v openssl >/dev/null || die "openssl não encontrado."
command -v sops    >/dev/null || die "sops não encontrado (rode o bootstrap-host.sh)."
command -v age     >/dev/null || die "age não encontrado (rode o bootstrap-host.sh)."
[ -r "$AGE_KEY_FILE" ] || die "chave age não legível em $AGE_KEY_FILE. Rode o bootstrap-host.sh primeiro."

[ -n "$FROM_FILE" ] && ler_arquivo "$FROM_FILE"

# Se já existe um .sops, preserva o que estiver lá (isto é reconciliação, não reset).
declare -A ATUAL=()
if [ -f "$SOPS_FILE" ]; then
  log "Já existe $SOPS_FILE — preservando os valores atuais"
  export SOPS_AGE_KEY_FILE="$AGE_KEY_FILE"
  prev="$(mktemp)"
  sops --decrypt --input-type dotenv --output-type dotenv "$SOPS_FILE" > "$prev" 2>/dev/null \
    || warn "não consegui decifrar o existente; vou tratar como novo."
  while IFS='=' read -r k v; do [ -n "${k:-}" ] && ATUAL["$k"]="$v"; done < "$prev"
  shred -u "$prev" 2>/dev/null || rm -f "$prev"
fi

# ---------------------------------------------------------------------------
# Monta o conjunto final
# ---------------------------------------------------------------------------
SHM_FS="$(findmnt -no FSTYPE /dev/shm 2>/dev/null || true)"
[ "$SHM_FS" = "tmpfs" ] || die "/dev/shm não é tmpfs. Recuso escrever segredo em disco."
WORK="$(mktemp -d /dev/shm/nora-secrets.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
PLAIN="$WORK/secrets.env"
: > "$PLAIN"

log "Montando o conjunto de segredos"
echo

declare -a RELATORIO=()

while IFS='|' read -r nome classe spec; do
  valor=""
  origem=""

  if [ -n "$REGENERATE" ] && [ "$nome" = "$REGENERATE" ] && [ "$classe" = "gerado" ]; then
    valor="$(gerar "$spec")"; origem="regenerado"
  elif [ -n "${COLETA[$nome]:-}" ]; then
    valor="${COLETA[$nome]}"; origem="do arquivo"
  elif [ -n "${ATUAL[$nome]:-}" ]; then
    valor="${ATUAL[$nome]}"; origem="preservado"
  elif [ "$classe" = "gerado" ]; then
    valor="$(gerar "$spec")"; origem="gerado"
  else
    # Coletado ou opcional e ainda sem valor: pergunta SEM ECO.
    printf '  %s%s%s\n' "$C_YLW" "$nome" "$C_OFF"
    printf '    %s%s%s\n' "$C_DIM" "$spec" "$C_OFF"
    if [ "$classe" = "opcional" ]; then
      printf '    valor (ENTER deixa vazio): '
    else
      printf '    valor: '
    fi
    read -rs valor < /dev/tty || valor=""
    echo
    origem=$([ -n "$valor" ] && echo "digitado" || echo "vazio")
  fi

  printf '%s=%s\n' "$nome" "$valor" >> "$PLAIN"
  RELATORIO+=("$(printf '  %-34s %-12s %s' "$nome" "$origem" \
    "$([ -n "$valor" ] && echo "[${#valor} chars]" || echo "[vazio]")")")
done <<< "$CATALOGO"

# Sanidade que só se descobre em produção, tarde:
enc="$(sed -n 's/^NORA_INTEGRATIONS_ENC_KEY=//p' "$PLAIN" | head -1)"
if [ -n "$enc" ]; then
  bytes="$(printf '%s' "$enc" | base64 -d 2>/dev/null | wc -c || echo 0)"
  [ "$bytes" -eq 32 ] || die "NORA_INTEGRATIONS_ENC_KEY não decodifica para 32 bytes ($bytes).
       O TokenCipher valida isso no boot e derruba a API. Regere com --regenerate."
fi
grep -q '=unset$' "$PLAIN" && die 'algum valor ficou como a string literal "unset" — isso quebra o boot.'

# ---------------------------------------------------------------------------
# Cifra
# ---------------------------------------------------------------------------
export SOPS_AGE_KEY_FILE="$AGE_KEY_FILE"
sops --encrypt --input-type dotenv --output-type dotenv "$PLAIN" > "$SOPS_FILE.tmp" \
  || die "sops falhou ao cifrar. O .sops.yaml tem o recipient age deste host?"
mv -f "$SOPS_FILE.tmp" "$SOPS_FILE"
chmod 0644 "$SOPS_FILE"   # cifrado: pode ser versionado

echo
log "Resultado (nomes e tamanhos — nenhum valor)"
printf '%s\n' "${RELATORIO[@]}"
echo
log "Escrito e cifrado: $SOPS_FILE"
cat <<EOF

  O arquivo em claro viveu só em tmpfs e já foi apagado.

  Próximos passos:
    1. git add $SOPS_FILE && git commit    (é seguro: está cifrado)
    2. ./scripts/secrets-bootstrap.sh --check     (confere sem revelar)
    3. ./scripts/deploy.sh

  A chave PRIVADA age em $AGE_KEY_FILE é a única coisa que decifra isto.
  Sem backup dela, o arquivo versionado vira lixo. Guarde uma cópia offline.

EOF
