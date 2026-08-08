#!/usr/bin/env bash
#
# deploy.sh — rollout PULL-BASED, health-gated, com rollback automático.
#
# POR QUE PULL E NÃO PUSH
# -----------------------
# O repositório é PÚBLICO (ADR 0017) e o `deploy-infra.yml` tem trigger `pull_request`.
# Um runner self-hosted persistente na rede doméstica executaria, por definição, código
# de fork arbitrário dentro da LAN — qualquer pessoa abrindo um PR ganharia execução de
# comando na VM que hospeda o Postgres de produção. Por isso NÃO existe runner aqui:
# a VM PUXA em vez de receber push do CI. O CI só publica imagens no GHCR; quem decide o
# que roda é esta máquina.
#
# "Puxar" tem duas metades: `docker pull` por digest (sempre) e `git pull` do repo (só
# com `--sync`, por ser mudança de CONFIGURAÇÃO e não de artefato). Sem `--sync`, uma
# alteração no compose fica no git e não chega no host — o timer não a aplicaria nunca.
#
# COMO O ROLLOUT FUNCIONA
# -----------------------
#   1. Decifra secrets.env.sops (SOPS + age) para um .env em tmpfs (/dev/shm). Nunca em disco.
#   2. `docker compose pull` das imagens alvo.
#   3. `up -d --wait --no-deps` SERVIÇO A SERVIÇO, na ordem de dependência.
#   4. Health validado POR DENTRO (`compose exec`), nunca pela URL pública. Um problema de
#      DNS/cert/Cloudflare NÃO pode derrubar um deploy que subiu certo — e o inverso também:
#      um 200 no edge cacheado não pode mascarar um container quebrado.
#   5. Se o health falhar, ROLLBACK automático para a tag anterior (lida do container que
#      estava rodando, não de um arquivo que pode ter driftado) e re-validação.
#
# Tags imutáveis `sha-<short>` são o mecanismo de rollout (build-images.yml). `latest` é
# aceito mas desencorajado: sem tag imutável não há rollback determinístico.
#
set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROXMOX_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="${COMPOSE_FILE:-$PROXMOX_DIR/docker-compose.yml}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-nora}"
SOPS_FILE="${SOPS_FILE:-$PROXMOX_DIR/secrets.env.sops}"
AGE_KEY_FILE="${SOPS_AGE_KEY_FILE:-/etc/nora/age.key}"
STATE_DIR="${NORA_STATE_DIR:-/srv/nora/state}"
STATE_FILE="$STATE_DIR/deploy-state.env"
LOCK_FILE="$STATE_DIR/deploy.lock"

REGISTRY="${REGISTRY:-ghcr.io}"
IMAGE_PREFIX="${IMAGE_PREFIX:-sf0rzin/nora}"

WAIT_TIMEOUT="${DEPLOY_WAIT_TIMEOUT:-180}"
PROBE_RETRIES="${DEPLOY_PROBE_RETRIES:-20}"
PROBE_INTERVAL="${DEPLOY_PROBE_INTERVAL:-5}"

# Ordem de dependência (do compose): dados -> observabilidade -> worker -> api -> front -> borda.
# A borda vem por último de propósito: o Caddy é o buffer de retry do rolling update e o
# cloudflared depende dele; recriá-los antes derrubaria o tráfego durante o boot do Spring.
ALL_SERVICES=(postgres postgres-platform otel-collector prometheus loki alloy grafana
              worker api web admin caddy cloudflared backup)

# Serviços que têm imagem versionada por tag (os únicos com rollback por tag).
APP_SERVICES=(api worker web admin)
# Serviços que só existem sob o profile `platform`.
PLATFORM_SERVICES=(postgres-platform admin)

SELECTED=()
TAG=""
DO_PULL=1
DRY_RUN=0
IF_CHANGED=0
SYNC=0
REPO_MOVED=0
ROLLBACK_ONLY=0
NO_ROLLBACK=0
FORCE_PLATFORM=""

usage() {
  cat <<EOF
$SCRIPT_NAME — rollout pull-based com health gate interno e rollback automático

USO
  $SCRIPT_NAME [--service <nome>]... [--tag sha-xxxxxxx] [opções]

OPÇÕES
  --service <nome>     Só este serviço (repetível, ou lista separada por vírgula).
                       Default: todos, na ordem de dependência.
  --tag <tag>          Tag da imagem a subir (ex.: sha-a1b2c3d). Aplica-se aos
                       serviços de aplicação selecionados: ${APP_SERVICES[*]}.
  --if-changed         Só faz deploy se o digest remoto da tag no GHCR diferir do
                       último digest registrado no estado. É o modo usado pelo timer
                       systemd (nora-deploy.timer) — sai 0 sem fazer nada quando não mudou.
  --sync               Faz \`git pull --ff-only\` no repo do host ANTES de qualquer coisa.
                       Sem isto o deploy só atualiza IMAGENS: mudança no compose, no
                       Caddyfile ou nos scripts fica no git e nunca chega na máquina.
                       Combinado com --if-changed, um HEAD que andou já é motivo de
                       deploy — senão a config nova ficaria parada até a próxima imagem.
  --rollback           Volta os serviços selecionados para a tag anterior do estado
                       e sai (não faz pull de tag nova).
  --no-pull            Não roda \`docker compose pull\` (usa a imagem local).
  --no-rollback        Em falha de health, NÃO faz rollback (deixa quebrado para debug).
  --platform / --no-platform
                       Força ligar/desligar o profile 'platform'. Default: auto,
                       lendo NORA_PLATFORM_ENABLED do arquivo de segredos.
  --wait-timeout <s>   Timeout do \`up --wait\` por serviço (default: $WAIT_TIMEOUT)
  --dry-run            Mostra o que faria, não executa
  -h, --help           Esta ajuda

ESTADO
  $STATE_FILE
  Guarda, por serviço: tag corrente, digest resolvido, TAG ANTERIOR (usada no rollback)
  e o timestamp do último deploy bem-sucedido.

SEGREDOS
  $SOPS_FILE  (versionado, cifrado)
  Chave privada age: $AGE_KEY_FILE (só no host, 0400 root).
  Decifrado para um .env em /dev/shm (tmpfs), apagado no trap EXIT. Nunca toca o disco.

EXEMPLOS
  $SCRIPT_NAME --tag sha-a1b2c3d                 # rollout completo numa tag
  $SCRIPT_NAME --service api --tag sha-a1b2c3d   # só a API
  $SCRIPT_NAME --service api,web --tag sha-a1b2c3d
  $SCRIPT_NAME --if-changed                      # o que o timer systemd chama
  $SCRIPT_NAME --service api --rollback          # volta a API pra tag anterior
EOF
}

_ts() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
log()  { printf '[%s] %s\n'        "$(_ts)" "$*" >&2; }
ok()   { printf '[%s] OK    %s\n'  "$(_ts)" "$*" >&2; }
warn() { printf '[%s] AVISO %s\n'  "$(_ts)" "$*" >&2; }
err()  { printf '[%s] ERRO  %s\n'  "$(_ts)" "$*" >&2; }
die()  { err "$*"; exit 1; }
hr()   { printf '%s\n' "------------------------------------------------------------" >&2; }

contains() { local n="$1"; shift; local x; for x in "$@"; do [ "$x" = "$n" ] && return 0; done; return 1; }

while [ $# -gt 0 ]; do
  case "$1" in
    --service|-s)
      IFS=',' read -r -a _svcs <<< "${2:?--service exige um valor}"
      SELECTED+=("${_svcs[@]}"); shift 2 ;;
    --tag|-t)        TAG="${2:?--tag exige um valor}"; shift 2 ;;
    --if-changed)    IF_CHANGED=1; shift ;;
    --sync)          SYNC=1; shift ;;
    --rollback)      ROLLBACK_ONLY=1; shift ;;
    --no-pull)       DO_PULL=0; shift ;;
    --no-rollback)   NO_ROLLBACK=1; shift ;;
    --platform)      FORCE_PLATFORM=1; shift ;;
    --no-platform)   FORCE_PLATFORM=0; shift ;;
    --wait-timeout)  WAIT_TIMEOUT="${2:?--wait-timeout exige um valor}"; shift 2 ;;
    --dry-run)       DRY_RUN=1; shift ;;
    -h|--help)       usage; exit 0 ;;
    *) err "opção desconhecida: $1"; echo >&2; usage; exit 1 ;;
  esac
done

umask 077

# Validação dos serviços pedidos
if [ "${#SELECTED[@]}" -eq 0 ]; then
  SELECTED=("${ALL_SERVICES[@]}")
else
  for s in "${SELECTED[@]}"; do
    contains "$s" "${ALL_SERVICES[@]}" || die "serviço desconhecido: '$s'. Válidos: ${ALL_SERVICES[*]}"
  done
fi

if [ -n "$TAG" ]; then
  case "$TAG" in
    sha-*|latest|v*) : ;;
    *) warn "tag '$TAG' não parece uma tag imutável 'sha-<short>' — rollback fica impreciso." ;;
  esac
  _apps_selected=0
  for s in "${SELECTED[@]}"; do contains "$s" "${APP_SERVICES[@]}" && _apps_selected=1; done
  [ "$_apps_selected" -eq 1 ] || die "--tag só se aplica a ${APP_SERVICES[*]}; nenhum deles foi selecionado."
fi

# ---------------------------------------------------------------------------
# Pré-voo
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "docker não encontrado. Rode o bootstrap-host.sh."
docker compose version >/dev/null 2>&1 || die "plugin 'docker compose' v2 ausente. Rode o bootstrap-host.sh."
docker info >/dev/null 2>&1 || die "o daemon do Docker não responde (permissão? o usuário está no grupo 'docker'?)."
[ -f "$COMPOSE_FILE" ] || die "compose não encontrado: $COMPOSE_FILE"

mkdir -p "$STATE_DIR" 2>/dev/null || die "não consegui criar $STATE_DIR (permissão?)."
touch "$STATE_FILE" 2>/dev/null || die "não consegui escrever em $STATE_FILE."

# Lock: dois deploys concorrentes (o timer + um operador) recriariam containers ao mesmo tempo.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCK_FILE"
  flock -n 9 || die "outro deploy já está rodando (lock: $LOCK_FILE). Aguarde ou remova se for órfão."
fi

# ---------------------------------------------------------------------------
# Segredos -> tmpfs
# ---------------------------------------------------------------------------
ENV_FILE=""
ENV_DIR=""
cleanup() {
  local rc=$?
  set +e
  if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
    # Sobrescreve antes de remover: tmpfs não persiste, mas a página pode ser reaproveitada.
    command -v shred >/dev/null 2>&1 && shred -u "$ENV_FILE" 2>/dev/null || rm -f "$ENV_FILE"
  fi
  [ -n "$ENV_DIR" ] && [ -d "$ENV_DIR" ] && rmdir "$ENV_DIR" 2>/dev/null
  exit "$rc"
}
trap cleanup EXIT INT TERM

prepare_env() {
  [ -f "$SOPS_FILE" ] || die "arquivo de segredos não encontrado: $SOPS_FILE"
  command -v sops >/dev/null 2>&1 || die "sops não encontrado. Rode o bootstrap-host.sh."

  # Exigimos tmpfs de verdade. Sem isso, o .env decifrado encostaria no disco.
  local shm_fs=""
  if command -v findmnt >/dev/null 2>&1; then
    shm_fs="$(findmnt -no FSTYPE /dev/shm 2>/dev/null || true)"
  fi
  if [ ! -d /dev/shm ] || { [ -n "$shm_fs" ] && [ "$shm_fs" != "tmpfs" ]; }; then
    die "/dev/shm não é tmpfs (fs='${shm_fs:-inexistente}'). Recuso decifrar segredo para disco.
       Corrija a montagem antes de deployar:  mount -t tmpfs -o size=64m tmpfs /dev/shm"
  fi

  ENV_DIR="$(mktemp -d /dev/shm/nora-deploy.XXXXXX)"
  chmod 700 "$ENV_DIR"
  ENV_FILE="$ENV_DIR/.env"
  ( umask 077; : > "$ENV_FILE" )

  [ -r "$AGE_KEY_FILE" ] || warn "chave age não legível em $AGE_KEY_FILE — o sops pode falhar."
  export SOPS_AGE_KEY_FILE="$AGE_KEY_FILE"

  log "decifrando segredos para tmpfs ($ENV_DIR)..."
  if ! sops --decrypt --input-type dotenv --output-type dotenv "$SOPS_FILE" > "$ENV_FILE" 2>"$ENV_DIR/sops.err"; then
    err "falha ao decifrar $SOPS_FILE:"
    sed 's/^/       /' "$ENV_DIR/sops.err" >&2 || true
    err "Verifique: a chave privada age em $AGE_KEY_FILE corresponde a um dos recipients"
    err "do arquivo?  sops --decrypt $SOPS_FILE | head -1"
    rm -f "$ENV_DIR/sops.err"
    die "sem segredos, não há deploy"
  fi
  rm -f "$ENV_DIR/sops.err"
  [ -s "$ENV_FILE" ] || die "o .env decifrado saiu VAZIO — o secrets.env.sops está correto?"
  ok "segredos em tmpfs ($(grep -c '=' "$ENV_FILE" || echo 0) variáveis)"
}

# envget <CHAVE> — lê um valor do .env decifrado sem despejar tudo no ambiente do shell.
envget() {
  local v
  v="$(sed -n "s/^[[:space:]]*$1=//p" "$ENV_FILE" 2>/dev/null | tail -1)"
  v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
  printf '%s' "$v"
}

# ---------------------------------------------------------------------------
# Estado (tag anterior / digest)
# ---------------------------------------------------------------------------
state_get() {
  local v
  v="$(sed -n "s/^$1=//p" "$STATE_FILE" 2>/dev/null | tail -1)"
  printf '%s' "$v"
}
state_set() {
  local key="$1" val="$2" tmp
  tmp="$(mktemp "$STATE_DIR/.state.XXXXXX")"
  grep -v "^$key=" "$STATE_FILE" 2>/dev/null > "$tmp" || true
  printf '%s=%s\n' "$key" "$val" >> "$tmp"
  chmod 600 "$tmp"
  mv -f "$tmp" "$STATE_FILE"
}

svc_key() { printf '%s' "$(printf '%s' "$1" | tr '[:lower:]-' '[:upper:]_')"; }

# tag_var_for <serviço> -> nome da env var de tag do compose
tag_var_for() {
  case "$1" in
    api)    printf 'API_TAG' ;;
    worker) printf 'WORKER_TAG' ;;
    web)    printf 'WEB_TAG' ;;
    admin)  printf 'ADMIN_TAG' ;;
    *)      printf '' ;;
  esac
}

image_ref_for() {  # <serviço> <tag>
  printf '%s/%s-%s:%s' "$REGISTRY" "$IMAGE_PREFIX" "$1" "$2"
}

# running_tag <serviço> -> tag da imagem do container que está de pé (fonte da verdade)
running_tag() {
  local cid img
  cid="$(dc ps -q "$1" 2>/dev/null | head -1)"
  [ -n "$cid" ] || { printf ''; return; }
  img="$(docker inspect -f '{{.Config.Image}}' "$cid" 2>/dev/null || true)"
  case "$img" in
    *:*) printf '%s' "${img##*:}" ;;
    *)   printf '' ;;
  esac
}

# remote_digest <repo-sem-registry> <tag> — digest do manifesto no GHCR, SEM baixar a imagem.
# Usado pelo --if-changed: é o que transforma o timer num deploy por pull de verdade
# (checa o digest da tag) em vez de um `docker pull` cego a cada 5 minutos.
remote_digest() {
  local repo="$1" tag="$2" token digest
  command -v curl >/dev/null 2>&1 || { printf ''; return; }
  command -v jq   >/dev/null 2>&1 || { printf ''; return; }
  token="$(curl -fsSL --max-time 15 \
            "https://ghcr.io/token?scope=repository:${repo}:pull&service=ghcr.io" 2>/dev/null \
           | jq -r '.token // empty' 2>/dev/null || true)"
  [ -n "$token" ] || { printf ''; return; }
  digest="$(curl -fsSL --max-time 20 -o /dev/null -D - -X GET \
              -H "Authorization: Bearer $token" \
              -H "Accept: application/vnd.oci.image.index.v1+json" \
              -H "Accept: application/vnd.docker.distribution.manifest.list.v2+json" \
              -H "Accept: application/vnd.docker.distribution.manifest.v2+json" \
              "https://ghcr.io/v2/${repo}/manifests/${tag}" 2>/dev/null \
            | awk 'BEGIN{IGNORECASE=1} /^docker-content-digest:/{print $2}' | tr -d '\r' | tail -1)"
  printf '%s' "$digest"
}

local_digest() {  # <serviço>
  local cid img
  cid="$(dc ps -q "$1" 2>/dev/null | head -1)"
  [ -n "$cid" ] || { printf ''; return; }
  img="$(docker inspect -f '{{.Image}}' "$cid" 2>/dev/null || true)"
  printf '%s' "$img"
}

# ---------------------------------------------------------------------------
# Compose wrapper
# ---------------------------------------------------------------------------
COMPOSE_ARGS=()
dc() { docker compose "${COMPOSE_ARGS[@]}" "$@"; }

run() {  # executa (ou só mostra, em --dry-run)
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '[dry-run] %s\n' "$*" >&2
    return 0
  fi
  "$@"
}

# ---------------------------------------------------------------------------
# HEALTH POR DENTRO
#
# Regra: nunca usar https://<dominio> para decidir se o deploy deu certo. O ingress é
# cloudflared -> caddy; um problema de DNS, de certificado ou do próprio túnel produz
# 522/526 mesmo com todos os containers saudáveis. Validar por fora transformaria uma
# falha de borda num rollback desnecessário — exatamente o oposto do que queremos.
#
# probe_cmd devolve o comando a rodar DENTRO do container. Quando o binário não existe
# na imagem (distroless), o exec sai 126/127 e caímos para o healthcheck do próprio
# Docker, que é igualmente interno.
# ---------------------------------------------------------------------------
PG_USER_CACHE=""
probe_cmd() {
  case "$1" in
    postgres)          printf 'pg_isready\t-U\t%s\t-d\tnora' "$PG_USER_CACHE" ;;
    postgres-platform) printf 'pg_isready\t-U\t%s\t-d\tnora_platform' "$PG_USER_CACHE" ;;
    worker)            printf 'python\t-c\timport urllib.request; urllib.request.urlopen("http://localhost:8001/healthz", timeout=5)' ;;
    api)               printf 'wget\t-q\t-O\t-\thttp://localhost:8080/actuator/health' ;;
    web)               printf 'wget\t-q\t--spider\thttp://localhost:3000' ;;
    # admin: 127.0.0.1 e nao `localhost`. O Next standalone faz bind IPv4-only, e o
    # /etc/hosts do container resolve `localhost` tambem para ::1 -- o wget do BusyBox
    # tenta o IPv6 primeiro e leva connection refused com o servidor no ar. Este probe e
    # INDEPENDENTE do healthcheck do compose: corrigir la (docker-compose.yml) e deixar
    # aqui faz o container ficar `healthy` e o deploy reprovar assim mesmo.
    admin)             printf 'wget\t-q\t--spider\thttp://127.0.0.1:3002/healthz' ;;
    # caddy: /healthz do bloco :80, NAO a admin API em :2019 — o handler de /config/ so
    # trata GET/POST/PUT/..., e o --spider do busybox emite HEAD, levando 405 (que o
    # probe_once trata como falha real). Mesmo alvo do healthcheck do compose.
    # otel-collector: imagem distroless, sem wget nem shell. return 2 = 'sem probe'.
    caddy)             printf 'wget\t-q\t--spider\thttp://localhost/healthz' ;;
    otel-collector)    return 2 ;;
    prometheus)        printf 'wget\t-q\t--spider\thttp://localhost:9090/-/healthy' ;;
    loki)              printf 'wget\t-q\t--spider\thttp://localhost:3100/ready' ;;
    grafana)           printf 'wget\t-q\t--spider\thttp://localhost:3000/api/health' ;;
    cloudflared)       printf 'cloudflared\t--version' ;;
    alloy|backup)      printf '' ;;   # sem probe próprio: valida por estado do container
    *)                 printf '' ;;
  esac
}

# docker_health <serviço> -> healthy | unhealthy | starting | none | absent
docker_health() {
  local cid st hs
  cid="$(dc ps -q "$1" 2>/dev/null | head -1)"
  [ -n "$cid" ] || { printf 'absent'; return; }
  st="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || echo unknown)"
  [ "$st" = "running" ] || { printf 'absent'; return; }
  hs="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo none)"
  printf '%s' "$hs"
}

# probe_once <serviço> -> 0 saudável, 1 não saudável, 2 probe indisponível
probe_once() {
  local svc="$1" cmd out rc
  cmd="$(probe_cmd "$svc")"

  if [ -n "$cmd" ]; then
    local -a argv=()
    IFS=$'\t' read -r -a argv <<< "$cmd"
    set +e
    out="$(dc exec -T "$svc" "${argv[@]}" 2>&1)"
    rc=$?
    set -e
    if [ "$rc" -eq 0 ]; then
      # A API responde 200 mesmo em DOWN parcial em algumas configs — confere o status.
      if [ "$svc" = "api" ] && ! printf '%s' "$out" | grep -q '"status":"UP"'; then
        err "  api: /actuator/health respondeu sem status UP: $(printf '%s' "$out" | head -c 200)"
        return 1
      fi
      return 0
    fi
    case "$rc" in
      126|127) return 2 ;;   # binário do probe não existe na imagem
    esac
    case "$out" in
      *"executable file not found"*|*"no such file or directory"*) return 2 ;;
    esac
    return 1
  fi
  return 2
}

# healthy <serviço> -> 0/1, com retry
healthy() {
  local svc="$1" i rc hs
  for i in $(seq 1 "$PROBE_RETRIES"); do
    hs="$(docker_health "$svc")"
    if [ "$hs" = "absent" ]; then
      err "  $svc: container não está rodando"
      return 1
    fi
    if [ "$hs" = "unhealthy" ]; then
      err "  $svc: healthcheck do Docker reporta UNHEALTHY"
      return 1
    fi

    set +e
    probe_once "$svc"
    rc=$?
    set -e
    case "$rc" in
      0) ok "  $svc: health interno OK (tentativa $i)"; return 0 ;;
      2)
        # Sem probe utilizável: cai para o healthcheck do container (também interno).
        if [ "$hs" = "healthy" ]; then
          ok "  $svc: sem probe próprio na imagem; healthcheck do Docker = healthy"
          return 0
        fi
        if [ "$hs" = "none" ]; then
          warn "  $svc: sem healthcheck declarado e sem probe — validado só por 'running'"
          return 0
        fi
        ;;
    esac
    [ "$i" -lt "$PROBE_RETRIES" ] && sleep "$PROBE_INTERVAL"
  done
  err "  $svc: não ficou saudável após $((PROBE_RETRIES * PROBE_INTERVAL))s"
  err "  Últimas linhas do log:"
  dc logs --tail 30 "$svc" 2>&1 | sed 's/^/      /' >&2 || true
  return 1
}

# Checagem extra de alcançabilidade serviço-a-serviço, de dentro da rede `internal`.
# Prova DNS + rota + app juntos, ainda sem tocar a URL pública.
reachable_from_caddy() {  # <host> <porta> <caminho>
  local host="$1" port="$2" path="$3"
  [ "$(docker_health caddy)" != "absent" ] || return 0
  set +e
  dc exec -T caddy wget -q --spider "http://$host:$port$path" >/dev/null 2>&1
  local rc=$?
  set -e
  if [ "$rc" -eq 0 ]; then
    ok "  caddy -> $host:$port$path alcançável"
  else
    warn "  caddy NÃO alcança $host:$port$path (DNS interno ou rede do compose)"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Deploy de um serviço
# ---------------------------------------------------------------------------
bring_up() {  # <serviço>
  local svc="$1" rc=0
  set +e
  run dc up -d --wait --no-deps --wait-timeout "$WAIT_TIMEOUT" "$svc"
  rc=$?
  set -e
  return $rc
}

deploy_service() {  # <serviço> -> 0 ok, 1 falhou (após tentativa de rollback)
  local svc="$1"
  local tagvar prev_tag new_tag key
  tagvar="$(tag_var_for "$svc")"
  key="$(svc_key "$svc")"

  hr
  log "SERVIÇO: $svc"

  if [ -n "$tagvar" ]; then
    prev_tag="$(running_tag "$svc")"
    [ -n "$prev_tag" ] || prev_tag="$(state_get "${key}_TAG")"
    new_tag="${TAG:-${prev_tag:-latest}}"
    export "$tagvar=$new_tag"
    log "  tag: ${prev_tag:-<nenhuma>} -> $new_tag"
  else
    prev_tag=""
    new_tag=""
    log "  imagem fixada no compose (sem tag gerenciada)"
  fi

  if [ "$DO_PULL" -eq 1 ]; then
    log "  docker compose pull..."
    if ! run dc pull --quiet "$svc"; then
      err "  pull falhou para $svc."
      if [ -n "$tagvar" ]; then
        err "  A tag '$new_tag' existe no GHCR? Confira:"
        err "    docker manifest inspect $(image_ref_for "$svc" "$new_tag") >/dev/null && echo existe"
        err "  Imagem privada? Faça login:  echo \$GHCR_TOKEN | docker login ghcr.io -u <user> --password-stdin"
      fi
      return 1
    fi
  fi

  log "  up -d --wait --no-deps (timeout ${WAIT_TIMEOUT}s)..."
  local up_rc=0
  bring_up "$svc" || up_rc=$?

  if [ "$DRY_RUN" -eq 1 ]; then
    log "  [dry-run] health e estado não são avaliados"
    return 0
  fi

  local health_rc=0
  if [ "$up_rc" -eq 0 ]; then
    healthy "$svc" || health_rc=1
  else
    err "  'up --wait' falhou (código $up_rc) — container não ficou pronto no prazo"
    health_rc=1
  fi

  # Checagem extra de rede interna para os serviços que o Caddy roteia.
  if [ "$health_rc" -eq 0 ]; then
    case "$svc" in
      api)   reachable_from_caddy api 8080 /actuator/health ;;
      web)   reachable_from_caddy web 3000 / ;;
      admin) reachable_from_caddy admin 3002 /healthz ;;
    esac
  fi

  if [ "$health_rc" -eq 0 ]; then
    if [ -n "$tagvar" ]; then
      [ -n "$prev_tag" ] && [ "$prev_tag" != "$new_tag" ] && state_set "${key}_PREV_TAG" "$prev_tag"
      state_set "${key}_TAG" "$new_tag"
      state_set "${key}_DIGEST" "$(local_digest "$svc")"
    fi
    state_set "${key}_DEPLOYED_AT" "$(_ts)"
    ok "  $svc no ar e saudável"
    return 0
  fi

  # -------------------------------------------------------------------------
  # ROLLBACK
  # -------------------------------------------------------------------------
  err "  $svc FALHOU no health gate"
  if [ "$NO_ROLLBACK" -eq 1 ]; then
    warn "  --no-rollback: deixando o serviço no estado atual para debug"
    return 1
  fi
  if [ -z "$tagvar" ]; then
    err "  $svc não tem tag gerenciada — rollback por tag é impossível."
    err "  A imagem está fixada no docker-compose.yml; reverta o arquivo e rode de novo."
    return 1
  fi
  if [ -z "$prev_tag" ] || [ "$prev_tag" = "$new_tag" ]; then
    err "  não há tag anterior distinta para voltar (anterior='${prev_tag:-<nenhuma>}')."
    err "  Este é provavelmente o primeiro deploy deste serviço. Investigue com:"
    err "    docker compose ${COMPOSE_ARGS[*]} logs --tail 200 $svc"
    return 1
  fi

  hr
  warn "  ROLLBACK: $svc voltando de '$new_tag' para '$prev_tag'"
  export "$tagvar=$prev_tag"
  local rb_rc=0
  [ "$DO_PULL" -eq 1 ] && { dc pull --quiet "$svc" >/dev/null 2>&1 || true; }
  bring_up "$svc" || rb_rc=$?
  if [ "$rb_rc" -eq 0 ] && healthy "$svc"; then
    state_set "${key}_TAG" "$prev_tag"
    state_set "${key}_DIGEST" "$(local_digest "$svc")"
    state_set "${key}_ROLLED_BACK_AT" "$(_ts)"
    state_set "${key}_FAILED_TAG" "$new_tag"
    ok "  ROLLBACK CONCLUÍDO: $svc rodando '$prev_tag' e saudável"
    err "  A tag '$new_tag' NÃO subiu. Investigue antes de tentar de novo:"
    err "    docker compose ${COMPOSE_ARGS[*]} logs --tail 200 $svc"
  else
    err "  ROLLBACK TAMBÉM FALHOU. O serviço '$svc' está FORA."
    err "  Intervenção manual necessária:"
    err "    docker compose ${COMPOSE_ARGS[*]} logs --tail 200 $svc"
    err "    docker compose ${COMPOSE_ARGS[*]} ps"
  fi
  return 1
}

rollback_service() {  # <serviço> — rollback explícito, via --rollback
  local svc="$1" tagvar key prev
  tagvar="$(tag_var_for "$svc")"
  key="$(svc_key "$svc")"
  [ -n "$tagvar" ] || { warn "$svc não tem tag gerenciada — nada a reverter."; return 0; }
  prev="$(state_get "${key}_PREV_TAG")"
  [ -n "$prev" ] || { err "$svc: não há ${key}_PREV_TAG no estado ($STATE_FILE)."; return 1; }

  hr
  log "ROLLBACK EXPLÍCITO: $svc -> $prev"
  local cur; cur="$(running_tag "$svc")"
  export "$tagvar=$prev"
  [ "$DO_PULL" -eq 1 ] && { run dc pull --quiet "$svc" || true; }
  local rc=0
  bring_up "$svc" || rc=$?
  if [ "$DRY_RUN" -eq 1 ]; then return 0; fi
  if [ "$rc" -eq 0 ] && healthy "$svc"; then
    [ -n "$cur" ] && state_set "${key}_PREV_TAG" "$cur"
    state_set "${key}_TAG" "$prev"
    state_set "${key}_DEPLOYED_AT" "$(_ts)"
    ok "$svc revertido para $prev"
    return 0
  fi
  err "$svc: rollback falhou."
  return 1
}

# ---------------------------------------------------------------------------
# Login no GHCR
# ---------------------------------------------------------------------------
# Os 4 pacotes em ghcr.io/sf0rzin/nora-* estão PÚBLICOS hoje (verificado com pull
# anônimo da VM 106 em 2026-08-08), então o caminho normal é GHCR_PULL_TOKEN vazio e
# esta função vira no-op. Ela existe para o dia em que algum pacote voltar a ser
# privado: sem login o `compose pull` leva 401/denied e o rollout morre no primeiro
# serviço, com uma mensagem que não diz "faça login".
ghcr_login() {
  local tok user
  tok="$(envget GHCR_PULL_TOKEN)"
  if [ -z "$tok" ]; then
    log "GHCR_PULL_TOKEN vazio — assumindo pacotes públicos em $REGISTRY"
    return 0
  fi
  # Sem GHCR_USER explícito, o owner do IMAGE_PREFIX serve (ex.: sf0rzin/nora -> sf0rzin).
  user="$(envget GHCR_USER)"
  [ -n "$user" ] || user="${IMAGE_PREFIX%%/*}"
  if printf '%s' "$tok" | run docker login "$REGISTRY" -u "$user" --password-stdin >/dev/null 2>&1; then
    ok "autenticado em $REGISTRY como $user"
  else
    warn "docker login em $REGISTRY falhou (usuário: $user).
       Se os pacotes forem privados, o pull vai falhar logo abaixo.
       Confira o escopo do PAT: precisa de read:packages."
  fi
}

# ---------------------------------------------------------------------------
# Sincronização do repo do host
# ---------------------------------------------------------------------------
# O rollout é PULL, mas até aqui só as IMAGENS eram puxadas. Mudança no compose, no
# Caddyfile, no prometheus.yml ou nos próprios scripts ficava no git e nunca chegava
# na máquina — o cabeçalho deste arquivo dizia "git pull + docker pull" e metade disso
# não acontecia. `--sync` fecha essa metade.
#
# Continua sendo OPT-IN, e o timer NÃO usa: com ele ligado, um merge na main passaria a
# reconfigurar produção sozinho. O rollback automático cobre tag de imagem, não compose
# quebrado — então essa é uma decisão de operação, não um default.
#
# Sutileza: o pull pode substituir ESTE arquivo no meio da execução. O git escreve num
# temporário e renomeia, então o bash continua lendo o inode antigo e a rodada atual usa
# a versão ANTIGA do script — a nova só vale a partir da próxima. Se a mudança for no
# próprio deploy.sh, rode duas vezes.
REPO_ROOT="$(cd "$PROXMOX_DIR/../.." && pwd)"

sync_repo() {
  [ "$SYNC" -eq 1 ] || return 0
  [ -d "$REPO_ROOT/.git" ] || { warn "--sync: $REPO_ROOT não é um repo git — pulando"; return 0; }

  local owner antes depois
  owner="$(stat -c %U "$REPO_ROOT")"
  # Rodando sob sudo, um `git` de root num diretório de outro dono para em
  # "detected dubious ownership". Puxar como o dono evita isso sem precisar mexer em
  # safe.directory global.
  local -a git_cmd=(git -C "$REPO_ROOT")
  [ "$(id -un)" = "$owner" ] || git_cmd=(sudo -u "$owner" git -C "$REPO_ROOT")

  antes="$("${git_cmd[@]}" rev-parse HEAD 2>/dev/null || echo desconhecido)"
  log "--sync: git pull --ff-only em $REPO_ROOT (como $owner)"
  # --ff-only de propósito: se houver mudança local, é para PARAR e o operador ver, não
  # para mesclar sozinho em cima de um host de produção.
  if ! run "${git_cmd[@]}" pull --ff-only; then
    die "--sync: git pull falhou. Há mudança local em $REPO_ROOT? \`git -C $REPO_ROOT status\`"
  fi
  depois="$("${git_cmd[@]}" rev-parse HEAD 2>/dev/null || echo desconhecido)"

  if [ "$antes" != "$depois" ]; then
    REPO_MOVED=1
    ok "repo atualizado: ${antes:0:7} -> ${depois:0:7}"
  else
    log "repo já estava em ${depois:0:7}"
  fi
}

# ---------------------------------------------------------------------------
# Execução
# ---------------------------------------------------------------------------
sync_repo
prepare_env
ghcr_login

PG_USER_CACHE="$(envget POSTGRES_ADMIN_USER)"
[ -n "$PG_USER_CACHE" ] || PG_USER_CACHE="nora_admin"

# Profile 'platform': auto pelo segredo, sobrescrevível por flag.
PLATFORM_ON=0
if [ -n "$FORCE_PLATFORM" ]; then
  PLATFORM_ON="$FORCE_PLATFORM"
else
  case "$(envget NORA_PLATFORM_ENABLED)" in
    true|TRUE|1|yes) PLATFORM_ON=1 ;;
    *)               PLATFORM_ON=0 ;;
  esac
fi

COMPOSE_ARGS=(--project-name "$COMPOSE_PROJECT" --project-directory "$PROXMOX_DIR"
              -f "$COMPOSE_FILE" --env-file "$ENV_FILE")
[ "$PLATFORM_ON" -eq 1 ] && COMPOSE_ARGS+=(--profile platform)

# Remove serviços de plataforma quando o profile está desligado.
FINAL=()
for s in "${ALL_SERVICES[@]}"; do
  contains "$s" "${SELECTED[@]}" || continue
  if contains "$s" "${PLATFORM_SERVICES[@]}" && [ "$PLATFORM_ON" -eq 0 ]; then
    log "pulando '$s' (profile 'platform' desligado)"
    continue
  fi
  FINAL+=("$s")
done
[ "${#FINAL[@]}" -gt 0 ] || die "nenhum serviço a processar."

hr
log "projeto:   $COMPOSE_PROJECT"
log "compose:   $COMPOSE_FILE"
log "platform:  $([ "$PLATFORM_ON" -eq 1 ] && echo ligado || echo desligado)"
log "serviços:  ${FINAL[*]}"
[ -n "$TAG" ] && log "tag alvo:  $TAG"
[ "$DRY_RUN" -eq 1 ] && log "MODO DRY-RUN — nada será alterado"

# --rollback: só reverte e sai.
if [ "$ROLLBACK_ONLY" -eq 1 ]; then
  rc=0
  for s in "${FINAL[@]}"; do rollback_service "$s" || rc=1; done
  exit "$rc"
fi

# --if-changed: compara o digest remoto com o registrado. É o que o timer usa.
if [ "$IF_CHANGED" -eq 1 ] && [ "$REPO_MOVED" -eq 1 ]; then
  hr
  log "--if-changed: o repo andou (--sync), então a config pode ter mudado — deployando tudo"
  log "  comparar digest de imagem não veria uma alteração no compose ou no Caddyfile."
elif [ "$IF_CHANGED" -eq 1 ]; then
  hr
  log "--if-changed: comparando digests no GHCR"
  CHANGED=()
  for s in "${FINAL[@]}"; do
    tagvar="$(tag_var_for "$s")"
    [ -n "$tagvar" ] || continue
    key="$(svc_key "$s")"
    want_tag="${TAG:-$(running_tag "$s")}"
    [ -n "$want_tag" ] || want_tag="$(state_get "${key}_TAG")"
    [ -n "$want_tag" ] || want_tag="latest"
    rd="$(remote_digest "${IMAGE_PREFIX}-${s}" "$want_tag")"
    sd="$(state_get "${key}_REMOTE_DIGEST")"
    if [ -z "$rd" ]; then
      warn "  $s: não consegui resolver o digest remoto de '$want_tag' — vou deployar por precaução"
      CHANGED+=("$s")
    elif [ "$rd" != "$sd" ]; then
      log "  $s: digest mudou ($want_tag)"
      log "      antes: ${sd:-<nenhum>}"
      log "      agora: $rd"
      CHANGED+=("$s")
    else
      log "  $s: digest inalterado ($want_tag) — pulando"
    fi
  done
  if [ "${#CHANGED[@]}" -eq 0 ]; then
    ok "nada mudou no GHCR. Nenhum deploy necessário."
    exit 0
  fi
  FINAL=("${CHANGED[@]}")
  log "serviços com mudança: ${FINAL[*]}"
fi

FAILED=()
for s in "${FINAL[@]}"; do
  if deploy_service "$s"; then
    # Registra o digest remoto só depois do sucesso: assim uma falha é retentada
    # no próximo ciclo do timer em vez de ficar marcada como "já feita".
    tagvar="$(tag_var_for "$s")"
    if [ -n "$tagvar" ] && [ "$DRY_RUN" -eq 0 ]; then
      key="$(svc_key "$s")"
      cur_tag="$(running_tag "$s")"
      [ -n "$cur_tag" ] && {
        rd="$(remote_digest "${IMAGE_PREFIX}-${s}" "$cur_tag")"
        [ -n "$rd" ] && state_set "${key}_REMOTE_DIGEST" "$rd"
      }
    fi
  else
    FAILED+=("$s")
  fi
done

hr
if [ "${#FAILED[@]}" -eq 0 ]; then
  ok "DEPLOY CONCLUÍDO — ${#FINAL[@]} serviço(s), todos saudáveis por dentro."
  [ "$DRY_RUN" -eq 0 ] && log "estado: $STATE_FILE"
  exit 0
fi

err "DEPLOY COM FALHAS: ${FAILED[*]}"
err "Serviços que subiram continuam no ar; os que falharam foram revertidos quando possível."
err "Diagnóstico:  docker compose ${COMPOSE_ARGS[*]} ps"
exit 1
