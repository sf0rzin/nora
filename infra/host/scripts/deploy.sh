#!/usr/bin/env bash
#
# deploy.sh — PULL-BASED rollout, health-gated, with automatic rollback.
#
# WHY PULL AND NOT PUSH
# ---------------------
# The repository is PUBLIC (ADR 0017) and `deploy-infra.yml` has a `pull_request` trigger.
# A persistent self-hosted runner on the home network would execute, by definition, code
# from an arbitrary fork inside the LAN — anyone opening a PR would gain command execution
# on the host that runs the production Postgres. That is why there is NO runner here:
# the host PULLS instead of receiving a push from CI. CI only publishes images to GHCR; what
# runs is decided by this machine.
#
# "Pulling" has two halves: `docker pull` by digest (always) and `git pull` of the repo (only
# with `--sync`, since it is a CONFIGURATION change and not an artifact one). Without `--sync`, a
# change in the compose stays in git and never reaches the host — the timer would never apply it.
#
# HOW THE ROLLOUT WORKS
# ---------------------
#   1. Decrypts secrets.env.sops (SOPS + age) into a .env on tmpfs (/dev/shm). Never on disk.
#   2. `docker compose pull` of the target images.
#   3. `up -d --wait --no-deps` SERVICE BY SERVICE, in dependency order.
#   4. Health validated FROM THE INSIDE (`compose exec`), never via the public URL. A
#      DNS/cert/Cloudflare problem must NOT take down a deploy that came up fine — and the
#      inverse too: a cached 200 at the edge must not mask a broken container.
#   5. If health fails, automatic ROLLBACK to the previous tag (read from the container that
#      was running, not from a file that may have drifted) and re-validation.
#
# Immutable `sha-<short>` tags are the rollout mechanism (build-images.yml). `latest` is
# accepted but discouraged: without an immutable tag there is no deterministic rollback.
#
set -euo pipefail

SCRIPT_NAME="$(basename "$0")"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

COMPOSE_FILE="${COMPOSE_FILE:-$HOST_DIR/docker-compose.yml}"
COMPOSE_PROJECT="${COMPOSE_PROJECT:-nora}"
SOPS_FILE="${SOPS_FILE:-$HOST_DIR/secrets.env.sops}"
AGE_KEY_FILE="${SOPS_AGE_KEY_FILE:-/etc/nora/age.key}"
STATE_DIR="${NORA_STATE_DIR:-/srv/nora/state}"
STATE_FILE="$STATE_DIR/deploy-state.env"
LOCK_FILE="$STATE_DIR/deploy.lock"

REGISTRY="${REGISTRY:-ghcr.io}"
IMAGE_PREFIX="${IMAGE_PREFIX:-sf0rzin/nora}"

WAIT_TIMEOUT="${DEPLOY_WAIT_TIMEOUT:-180}"
PROBE_RETRIES="${DEPLOY_PROBE_RETRIES:-20}"
PROBE_INTERVAL="${DEPLOY_PROBE_INTERVAL:-5}"

# Dependency order (from the compose): data -> observability -> worker -> api -> front -> edge.
# The edge comes last on purpose: Caddy is the retry buffer of the rolling update and
# cloudflared depends on it; recreating them earlier would drop traffic during the Spring boot.
ALL_SERVICES=(postgres postgres-platform otel-collector prometheus loki alloy grafana
              worker api web admin caddy cloudflared backup)

# Services whose image is versioned by tag (the only ones with rollback by tag).
APP_SERVICES=(api worker web admin)
# Services that only exist under the `platform` profile.
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
$SCRIPT_NAME — pull-based rollout with internal health gate and automatic rollback

USAGE
  $SCRIPT_NAME [--service <name>]... [--tag sha-xxxxxxx] [options]

OPTIONS
  --service <name>     Only this service (repeatable, or comma-separated list).
                       Default: all, in dependency order.
  --tag <tag>          Image tag to roll out (e.g.: sha-a1b2c3d). Applies to the
                       selected application services: ${APP_SERVICES[*]}.
  --if-changed         Only deploys if the tag's remote digest on GHCR differs from the
                       last digest recorded in the state. This is the mode used by the
                       systemd timer (nora-deploy.timer) — exits 0 doing nothing when unchanged.
  --sync               Runs \`git pull --ff-only\` on the host repo BEFORE anything else.
                       Without this the deploy only updates IMAGES: a change in the compose, in
                       the Caddyfile or in the scripts stays in git and never reaches the machine.
                       Combined with --if-changed, a HEAD that moved is already reason for a
                       deploy — otherwise the new config would stay stuck until the next image.
  --rollback           Reverts the selected services to the previous tag from the state
                       and exits (does not pull a new tag).
  --no-pull            Does not run \`docker compose pull\` (uses the local image).
  --no-rollback        On health failure, does NOT roll back (leaves it broken for debugging).
  --platform / --no-platform
                       Forces the 'platform' profile on/off. Default: auto,
                       reading NORA_PLATFORM_ENABLED from the secrets file.
  --wait-timeout <s>   Timeout of \`up --wait\` per service (default: $WAIT_TIMEOUT)
  --dry-run            Shows what it would do, does not execute
  -h, --help           This help

STATE
  $STATE_FILE
  Stores, per service: current tag, resolved digest, PREVIOUS TAG (used in the rollback)
  and the timestamp of the last successful deploy.

SECRETS
  $SOPS_FILE  (versioned, encrypted)
  age private key: $AGE_KEY_FILE (host only, 0400 root).
  Decrypted to a .env in /dev/shm (tmpfs), erased on the EXIT trap. Never touches disk.

EXAMPLES
  $SCRIPT_NAME --tag sha-a1b2c3d                 # full rollout on one tag
  $SCRIPT_NAME --service api --tag sha-a1b2c3d   # API only
  $SCRIPT_NAME --service api,web --tag sha-a1b2c3d
  $SCRIPT_NAME --if-changed                      # what the systemd timer calls
  $SCRIPT_NAME --service api --rollback          # reverts the API to the previous tag
EOF
}

_ts() { date -u '+%Y-%m-%dT%H:%M:%SZ'; }
log()  { printf '[%s] %s\n'        "$(_ts)" "$*" >&2; }
ok()   { printf '[%s] OK    %s\n'  "$(_ts)" "$*" >&2; }
warn() { printf '[%s] WARN  %s\n'  "$(_ts)" "$*" >&2; }
err()  { printf '[%s] ERROR %s\n'  "$(_ts)" "$*" >&2; }
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
    *) err "unknown option: $1"; echo >&2; usage; exit 1 ;;
  esac
done

umask 077

# Validation of the requested services
if [ "${#SELECTED[@]}" -eq 0 ]; then
  SELECTED=("${ALL_SERVICES[@]}")
else
  for s in "${SELECTED[@]}"; do
    contains "$s" "${ALL_SERVICES[@]}" || die "unknown service: '$s'. Valid: ${ALL_SERVICES[*]}"
  done
fi

if [ -n "$TAG" ]; then
  case "$TAG" in
    sha-*|latest|v*) : ;;
    *) warn "tag '$TAG' does not look like an immutable 'sha-<short>' tag — rollback becomes imprecise." ;;
  esac
  _apps_selected=0
  for s in "${SELECTED[@]}"; do contains "$s" "${APP_SERVICES[@]}" && _apps_selected=1; done
  [ "$_apps_selected" -eq 1 ] || die "--tag only applies to ${APP_SERVICES[*]}; none of them were selected."
fi

# ---------------------------------------------------------------------------
# Pre-flight
# ---------------------------------------------------------------------------
command -v docker >/dev/null 2>&1 || die "docker not found. Run bootstrap-host.sh."
docker compose version >/dev/null 2>&1 || die "'docker compose' v2 plugin missing. Run bootstrap-host.sh."
docker info >/dev/null 2>&1 || die "the Docker daemon is not responding (permission? is the user in the 'docker' group?)."
[ -f "$COMPOSE_FILE" ] || die "compose not found: $COMPOSE_FILE"

mkdir -p "$STATE_DIR" 2>/dev/null || die "could not create $STATE_DIR (permission?)."
touch "$STATE_FILE" 2>/dev/null || die "could not write to $STATE_FILE."

# Lock: two concurrent deploys (the timer + an operator) would recreate containers at the same time.
if command -v flock >/dev/null 2>&1; then
  exec 9>"$LOCK_FILE"
  flock -n 9 || die "another deploy is already running (lock: $LOCK_FILE). Wait or remove it if orphaned."
fi

# ---------------------------------------------------------------------------
# Secrets -> tmpfs
# ---------------------------------------------------------------------------
ENV_FILE=""
ENV_DIR=""
cleanup() {
  local rc=$?
  set +e
  if [ -n "$ENV_FILE" ] && [ -f "$ENV_FILE" ]; then
    # Overwrite before removing: tmpfs does not persist, but the page can be reused.
    command -v shred >/dev/null 2>&1 && shred -u "$ENV_FILE" 2>/dev/null || rm -f "$ENV_FILE"
  fi
  [ -n "$ENV_DIR" ] && [ -d "$ENV_DIR" ] && rmdir "$ENV_DIR" 2>/dev/null
  exit "$rc"
}
trap cleanup EXIT INT TERM

prepare_env() {
  # This file is untracked on purpose (ADR 0036 §4), so a `git pull` that renames the
  # directory around it leaves it behind rather than moving it. That is the likeliest
  # reason to be reading this message.
  if [ ! -f "$SOPS_FILE" ]; then
    die "secrets file not found: $SOPS_FILE
    It is not versioned, so nothing moves it for you. If the infra directory was just renamed,
    see docs/operations/host-deploy.md, section 'moving an already-deployed host'."
  fi
  command -v sops >/dev/null 2>&1 || die "sops not found. Run bootstrap-host.sh."

  # We demand a real tmpfs. Without it, the decrypted .env would touch the disk.
  local shm_fs=""
  if command -v findmnt >/dev/null 2>&1; then
    shm_fs="$(findmnt -no FSTYPE /dev/shm 2>/dev/null || true)"
  fi
  if [ ! -d /dev/shm ] || { [ -n "$shm_fs" ] && [ "$shm_fs" != "tmpfs" ]; }; then
    die "/dev/shm is not tmpfs (fs='${shm_fs:-nonexistent}'). Refusing to decrypt secrets to disk.
       Fix the mount before deploying:  mount -t tmpfs -o size=64m tmpfs /dev/shm"
  fi

  ENV_DIR="$(mktemp -d /dev/shm/nora-deploy.XXXXXX)"
  chmod 700 "$ENV_DIR"
  ENV_FILE="$ENV_DIR/.env"
  ( umask 077; : > "$ENV_FILE" )

  [ -r "$AGE_KEY_FILE" ] || warn "age key not readable at $AGE_KEY_FILE — sops may fail."
  export SOPS_AGE_KEY_FILE="$AGE_KEY_FILE"

  log "decrypting secrets to tmpfs ($ENV_DIR)..."
  if ! sops --decrypt --input-type dotenv --output-type dotenv "$SOPS_FILE" > "$ENV_FILE" 2>"$ENV_DIR/sops.err"; then
    err "failed to decrypt $SOPS_FILE:"
    sed 's/^/       /' "$ENV_DIR/sops.err" >&2 || true
    err "Check: does the age private key at $AGE_KEY_FILE match one of the recipients"
    err "of the file?  sops --decrypt $SOPS_FILE | head -1"
    rm -f "$ENV_DIR/sops.err"
    die "no secrets, no deploy"
  fi
  rm -f "$ENV_DIR/sops.err"
  [ -s "$ENV_FILE" ] || die "the decrypted .env came out EMPTY — is secrets.env.sops correct?"
  ok "secrets in tmpfs ($(grep -c '=' "$ENV_FILE" || echo 0) variables)"
}

# envget <KEY> — reads a value from the decrypted .env without dumping everything into the shell environment.
envget() {
  local v
  v="$(sed -n "s/^[[:space:]]*$1=//p" "$ENV_FILE" 2>/dev/null | tail -1)"
  v="${v%\"}"; v="${v#\"}"; v="${v%\'}"; v="${v#\'}"
  printf '%s' "$v"
}

# ---------------------------------------------------------------------------
# State (previous tag / digest)
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

# tag_var_for <service> -> name of the compose tag env var
tag_var_for() {
  case "$1" in
    api)    printf 'API_TAG' ;;
    worker) printf 'WORKER_TAG' ;;
    web)    printf 'WEB_TAG' ;;
    admin)  printf 'ADMIN_TAG' ;;
    *)      printf '' ;;
  esac
}

image_ref_for() {  # <service> <tag>
  printf '%s/%s-%s:%s' "$REGISTRY" "$IMAGE_PREFIX" "$1" "$2"
}

# running_tag <service> -> image tag of the container that is up (source of truth)
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

# remote_digest <repo-without-registry> <tag> — manifest digest on GHCR, WITHOUT downloading the image.
# Used by --if-changed: it is what turns the timer into a real pull-based deploy
# (checks the tag's digest) instead of a blind `docker pull` every 5 minutes.
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

local_digest() {  # <service>
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

run() {  # executes (or only shows, under --dry-run)
  if [ "$DRY_RUN" -eq 1 ]; then
    printf '[dry-run] %s\n' "$*" >&2
    return 0
  fi
  "$@"
}

# ---------------------------------------------------------------------------
# HEALTH FROM THE INSIDE
#
# Rule: never use https://<domain> to decide whether the deploy worked. The ingress is
# cloudflared -> caddy; a DNS problem, a certificate problem or one in the tunnel itself
# produces 522/526 even with all containers healthy. Validating from the outside would turn
# an edge failure into an unnecessary rollback — exactly the opposite of what we want.
#
# probe_cmd returns the command to run INSIDE the container. When the binary does not exist
# in the image (distroless), the exec exits 126/127 and we fall back to Docker's own
# healthcheck, which is equally internal.
# ---------------------------------------------------------------------------
PG_USER_CACHE=""
probe_cmd() {
  case "$1" in
    postgres)          printf 'pg_isready\t-U\t%s\t-d\tnora' "$PG_USER_CACHE" ;;
    postgres-platform) printf 'pg_isready\t-U\t%s\t-d\tnora_platform' "$PG_USER_CACHE" ;;
    worker)            printf 'python\t-c\timport urllib.request; urllib.request.urlopen("http://localhost:8001/healthz", timeout=5)' ;;
    api)               printf 'wget\t-q\t-O\t-\thttp://localhost:8080/actuator/health' ;;
    web)               printf 'wget\t-q\t--spider\thttp://localhost:3000' ;;
    # admin: 127.0.0.1 and not `localhost`. Next standalone binds IPv4-only, and the
    # container's /etc/hosts resolves `localhost` to ::1 as well -- BusyBox's wget
    # tries IPv6 first and gets connection refused with the server up. This probe is
    # INDEPENDENT of the compose healthcheck: fixing it there (docker-compose.yml) and leaving
    # it here makes the container go `healthy` and the deploy fail all the same.
    admin)             printf 'wget\t-q\t--spider\thttp://127.0.0.1:3002/healthz' ;;
    # caddy: /healthz of the :80 block, NOT the admin API on :2019 — the /config/ handler only
    # handles GET/POST/PUT/..., and busybox's --spider issues HEAD, getting 405 (which
    # probe_once treats as a real failure). Same target as the compose healthcheck.
    # otel-collector: distroless image, no wget and no shell. return 2 = 'no probe'.
    caddy)             printf 'wget\t-q\t--spider\thttp://localhost/healthz' ;;
    otel-collector)    return 2 ;;
    prometheus)        printf 'wget\t-q\t--spider\thttp://localhost:9090/-/healthy' ;;
    loki)              printf 'wget\t-q\t--spider\thttp://localhost:3100/ready' ;;
    grafana)           printf 'wget\t-q\t--spider\thttp://localhost:3000/api/health' ;;
    cloudflared)       printf 'cloudflared\t--version' ;;
    alloy|backup)      printf '' ;;   # no own probe: validates by container state
    *)                 printf '' ;;
  esac
}

# docker_health <service> -> healthy | unhealthy | starting | none | absent
docker_health() {
  local cid st hs
  cid="$(dc ps -q "$1" 2>/dev/null | head -1)"
  [ -n "$cid" ] || { printf 'absent'; return; }
  st="$(docker inspect -f '{{.State.Status}}' "$cid" 2>/dev/null || echo unknown)"
  [ "$st" = "running" ] || { printf 'absent'; return; }
  hs="$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$cid" 2>/dev/null || echo none)"
  printf '%s' "$hs"
}

# probe_once <service> -> 0 healthy, 1 not healthy, 2 probe unavailable
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
      # The API answers 200 even on partial DOWN in some configs — check the status.
      if [ "$svc" = "api" ] && ! printf '%s' "$out" | grep -q '"status":"UP"'; then
        err "  api: /actuator/health responded without status UP: $(printf '%s' "$out" | head -c 200)"
        return 1
      fi
      return 0
    fi
    case "$rc" in
      126|127) return 2 ;;   # the probe binary does not exist in the image
    esac
    case "$out" in
      *"executable file not found"*|*"no such file or directory"*) return 2 ;;
    esac
    return 1
  fi
  return 2
}

# healthy <service> -> 0/1, with retry
healthy() {
  local svc="$1" i rc hs
  for i in $(seq 1 "$PROBE_RETRIES"); do
    hs="$(docker_health "$svc")"
    if [ "$hs" = "absent" ]; then
      err "  $svc: container is not running"
      return 1
    fi
    if [ "$hs" = "unhealthy" ]; then
      err "  $svc: Docker healthcheck reports UNHEALTHY"
      return 1
    fi

    set +e
    probe_once "$svc"
    rc=$?
    set -e
    case "$rc" in
      0) ok "  $svc: internal health OK (attempt $i)"; return 0 ;;
      2)
        # No usable probe: falls back to the container healthcheck (also internal).
        if [ "$hs" = "healthy" ]; then
          ok "  $svc: no own probe in the image; Docker healthcheck = healthy"
          return 0
        fi
        if [ "$hs" = "none" ]; then
          warn "  $svc: no declared healthcheck and no probe — validated only by 'running'"
          return 0
        fi
        ;;
    esac
    [ "$i" -lt "$PROBE_RETRIES" ] && sleep "$PROBE_INTERVAL"
  done
  err "  $svc: did not become healthy after $((PROBE_RETRIES * PROBE_INTERVAL))s"
  err "  Last lines of the log:"
  dc logs --tail 30 "$svc" 2>&1 | sed 's/^/      /' >&2 || true
  return 1
}

# Extra service-to-service reachability check, from inside the `internal` network.
# Proves DNS + route + app together, still without touching the public URL.
reachable_from_caddy() {  # <host> <port> <path>
  local host="$1" port="$2" path="$3"
  [ "$(docker_health caddy)" != "absent" ] || return 0
  set +e
  dc exec -T caddy wget -q --spider "http://$host:$port$path" >/dev/null 2>&1
  local rc=$?
  set -e
  if [ "$rc" -eq 0 ]; then
    ok "  caddy -> $host:$port$path reachable"
  else
    warn "  caddy CANNOT reach $host:$port$path (internal DNS or compose network)"
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Deploy of one service
# ---------------------------------------------------------------------------
bring_up() {  # <service>
  local svc="$1" rc=0
  set +e
  run dc up -d --wait --no-deps --wait-timeout "$WAIT_TIMEOUT" "$svc"
  rc=$?
  set -e
  return $rc
}

deploy_service() {  # <service> -> 0 ok, 1 failed (after a rollback attempt)
  local svc="$1"
  local tagvar prev_tag new_tag key
  tagvar="$(tag_var_for "$svc")"
  key="$(svc_key "$svc")"

  hr
  log "SERVICE: $svc"

  if [ -n "$tagvar" ]; then
    prev_tag="$(running_tag "$svc")"
    [ -n "$prev_tag" ] || prev_tag="$(state_get "${key}_TAG")"
    new_tag="${TAG:-${prev_tag:-latest}}"
    export "$tagvar=$new_tag"
    log "  tag: ${prev_tag:-<none>} -> $new_tag"
  else
    prev_tag=""
    new_tag=""
    log "  image pinned in compose (no managed tag)"
  fi

  if [ "$DO_PULL" -eq 1 ]; then
    log "  docker compose pull..."
    if ! run dc pull --quiet "$svc"; then
      err "  pull failed for $svc."
      if [ -n "$tagvar" ]; then
        err "  Does the tag '$new_tag' exist on GHCR? Check:"
        err "    docker manifest inspect $(image_ref_for "$svc" "$new_tag") >/dev/null && echo exists"
        err "  Private image? Log in:  echo \$GHCR_TOKEN | docker login ghcr.io -u <user> --password-stdin"
      fi
      return 1
    fi
  fi

  log "  up -d --wait --no-deps (timeout ${WAIT_TIMEOUT}s)..."
  local up_rc=0
  bring_up "$svc" || up_rc=$?

  if [ "$DRY_RUN" -eq 1 ]; then
    log "  [dry-run] health and state are not evaluated"
    return 0
  fi

  local health_rc=0
  if [ "$up_rc" -eq 0 ]; then
    healthy "$svc" || health_rc=1
  else
    err "  'up --wait' failed (code $up_rc) — container did not become ready in time"
    health_rc=1
  fi

  # Extra internal network check for the services Caddy routes.
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
    ok "  $svc up and healthy"
    return 0
  fi

  # -------------------------------------------------------------------------
  # ROLLBACK
  # -------------------------------------------------------------------------
  err "  $svc FAILED the health gate"
  if [ "$NO_ROLLBACK" -eq 1 ]; then
    warn "  --no-rollback: leaving the service in its current state for debugging"
    return 1
  fi
  if [ -z "$tagvar" ]; then
    err "  $svc has no managed tag — rollback by tag is impossible."
    err "  The image is pinned in docker-compose.yml; revert the file and run again."
    return 1
  fi
  if [ -z "$prev_tag" ] || [ "$prev_tag" = "$new_tag" ]; then
    err "  there is no distinct previous tag to revert to (previous='${prev_tag:-<none>}')."
    err "  This is probably the first deploy of this service. Investigate with:"
    err "    docker compose ${COMPOSE_ARGS[*]} logs --tail 200 $svc"
    return 1
  fi

  hr
  warn "  ROLLBACK: $svc reverting from '$new_tag' to '$prev_tag'"
  export "$tagvar=$prev_tag"
  local rb_rc=0
  [ "$DO_PULL" -eq 1 ] && { dc pull --quiet "$svc" >/dev/null 2>&1 || true; }
  bring_up "$svc" || rb_rc=$?
  if [ "$rb_rc" -eq 0 ] && healthy "$svc"; then
    state_set "${key}_TAG" "$prev_tag"
    state_set "${key}_DIGEST" "$(local_digest "$svc")"
    state_set "${key}_ROLLED_BACK_AT" "$(_ts)"
    state_set "${key}_FAILED_TAG" "$new_tag"
    ok "  ROLLBACK COMPLETE: $svc running '$prev_tag' and healthy"
    err "  The tag '$new_tag' did NOT go live. Investigate before trying again:"
    err "    docker compose ${COMPOSE_ARGS[*]} logs --tail 200 $svc"
  else
    err "  ROLLBACK ALSO FAILED. The service '$svc' is DOWN."
    err "  Manual intervention required:"
    err "    docker compose ${COMPOSE_ARGS[*]} logs --tail 200 $svc"
    err "    docker compose ${COMPOSE_ARGS[*]} ps"
  fi
  return 1
}

rollback_service() {  # <service> — explicit rollback, via --rollback
  local svc="$1" tagvar key prev
  tagvar="$(tag_var_for "$svc")"
  key="$(svc_key "$svc")"
  [ -n "$tagvar" ] || { warn "$svc has no managed tag — nothing to revert."; return 0; }
  prev="$(state_get "${key}_PREV_TAG")"
  [ -n "$prev" ] || { err "$svc: there is no ${key}_PREV_TAG in the state ($STATE_FILE)."; return 1; }

  hr
  log "EXPLICIT ROLLBACK: $svc -> $prev"
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
    ok "$svc reverted to $prev"
    return 0
  fi
  err "$svc: rollback failed."
  return 1
}

# ---------------------------------------------------------------------------
# GHCR login
# ---------------------------------------------------------------------------
# The 4 packages under ghcr.io/sf0rzin/nora-* are PUBLIC today (verified with an
# anonymous pull from the host), so the normal path is an empty GHCR_PULL_TOKEN
# and this function becomes a no-op. It exists for the day some package goes back to
# private: without login `compose pull` gets 401/denied and the rollout dies on the first
# service, with a message that does not say "log in".
ghcr_login() {
  local tok user
  tok="$(envget GHCR_PULL_TOKEN)"
  if [ -z "$tok" ]; then
    log "GHCR_PULL_TOKEN empty — assuming public packages on $REGISTRY"
    return 0
  fi
  # Without an explicit GHCR_USER, the IMAGE_PREFIX owner does (e.g.: sf0rzin/nora -> sf0rzin).
  user="$(envget GHCR_USER)"
  [ -n "$user" ] || user="${IMAGE_PREFIX%%/*}"
  if printf '%s' "$tok" | run docker login "$REGISTRY" -u "$user" --password-stdin >/dev/null 2>&1; then
    ok "authenticated to $REGISTRY as $user"
  else
    warn "docker login to $REGISTRY failed (user: $user).
       If the packages are private, the pull will fail right below.
       Check the PAT scope: needs read:packages."
  fi
}

# ---------------------------------------------------------------------------
# Host repo synchronization
# ---------------------------------------------------------------------------
# The rollout is PULL, but until here only the IMAGES were pulled. A change in the compose,
# in the Caddyfile, in prometheus.yml or in the scripts themselves stayed in git and never
# reached the machine — this file's header said "git pull + docker pull" and half of that
# did not happen. `--sync` closes that half.
#
# It stays OPT-IN, and the timer does NOT use it: with it on, a merge to main would start
# reconfiguring production by itself. The automatic rollback covers image tags, not a broken
# compose — so this is an operations decision, not a default.
#
# Subtlety: the pull can replace THIS file mid-execution. Git writes to a temporary file
# and renames, so bash keeps reading the old inode and the current run uses the OLD version
# of the script — the new one only takes effect from the next one. If the change is in
# deploy.sh itself, run it twice.
REPO_ROOT="$(cd "$HOST_DIR/../.." && pwd)"

sync_repo() {
  [ "$SYNC" -eq 1 ] || return 0
  [ -d "$REPO_ROOT/.git" ] || { warn "--sync: $REPO_ROOT is not a git repo — skipping"; return 0; }

  local owner before after
  owner="$(stat -c %U "$REPO_ROOT")"
  # Running under sudo, a root `git` in a directory owned by someone else stops at
  # "detected dubious ownership". Pulling as the owner avoids that without having to touch
  # a global safe.directory.
  local -a git_cmd=(git -C "$REPO_ROOT")
  [ "$(id -un)" = "$owner" ] || git_cmd=(sudo -u "$owner" git -C "$REPO_ROOT")

  before="$("${git_cmd[@]}" rev-parse HEAD 2>/dev/null || echo unknown)"
  log "--sync: git pull --ff-only on $REPO_ROOT (as $owner)"
  # --ff-only on purpose: if there is a local change, it is to STOP and let the operator see,
  # not to merge by itself on top of a production host.
  if ! run "${git_cmd[@]}" pull --ff-only; then
    die "--sync: git pull failed. Is there a local change in $REPO_ROOT? \`git -C $REPO_ROOT status\`"
  fi
  after="$("${git_cmd[@]}" rev-parse HEAD 2>/dev/null || echo unknown)"

  if [ "$before" != "$after" ]; then
    REPO_MOVED=1
    ok "repo updated: ${before:0:7} -> ${after:0:7}"
  else
    log "repo was already at ${after:0:7}"
  fi
}

# ---------------------------------------------------------------------------
# Execution
# ---------------------------------------------------------------------------
sync_repo
prepare_env
ghcr_login

PG_USER_CACHE="$(envget POSTGRES_ADMIN_USER)"
[ -n "$PG_USER_CACHE" ] || PG_USER_CACHE="nora_admin"

# Profile 'platform': auto from the secret, overridable by flag.
PLATFORM_ON=0
if [ -n "$FORCE_PLATFORM" ]; then
  PLATFORM_ON="$FORCE_PLATFORM"
else
  case "$(envget NORA_PLATFORM_ENABLED)" in
    true|TRUE|1|yes) PLATFORM_ON=1 ;;
    *)               PLATFORM_ON=0 ;;
  esac
fi

COMPOSE_ARGS=(--project-name "$COMPOSE_PROJECT" --project-directory "$HOST_DIR"
              -f "$COMPOSE_FILE" --env-file "$ENV_FILE")
[ "$PLATFORM_ON" -eq 1 ] && COMPOSE_ARGS+=(--profile platform)

# Drops platform services when the profile is off.
FINAL=()
for s in "${ALL_SERVICES[@]}"; do
  contains "$s" "${SELECTED[@]}" || continue
  if contains "$s" "${PLATFORM_SERVICES[@]}" && [ "$PLATFORM_ON" -eq 0 ]; then
    log "skipping '$s' (profile 'platform' off)"
    continue
  fi
  FINAL+=("$s")
done
[ "${#FINAL[@]}" -gt 0 ] || die "no service to process."

hr
log "project:   $COMPOSE_PROJECT"
log "compose:   $COMPOSE_FILE"
log "platform:  $([ "$PLATFORM_ON" -eq 1 ] && echo on || echo off)"
log "services:  ${FINAL[*]}"
[ -n "$TAG" ] && log "target tag:  $TAG"
[ "$DRY_RUN" -eq 1 ] && log "DRY-RUN MODE — nothing will be changed"

# --rollback: only reverts and exits.
if [ "$ROLLBACK_ONLY" -eq 1 ]; then
  rc=0
  for s in "${FINAL[@]}"; do rollback_service "$s" || rc=1; done
  exit "$rc"
fi

# --if-changed: compares the remote digest with the recorded one. It is what the timer uses.
if [ "$IF_CHANGED" -eq 1 ] && [ "$REPO_MOVED" -eq 1 ]; then
  hr
  log "--if-changed: the repo moved (--sync), so the config may have changed — deploying everything"
  log "  comparing image digest would not catch a change in the compose or the Caddyfile."
elif [ "$IF_CHANGED" -eq 1 ]; then
  hr
  log "--if-changed: comparing digests on GHCR"
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
      warn "  $s: could not resolve the remote digest of '$want_tag' — deploying as a precaution"
      CHANGED+=("$s")
    elif [ "$rd" != "$sd" ]; then
      log "  $s: digest changed ($want_tag)"
      log "      before: ${sd:-<none>}"
      log "      now: $rd"
      CHANGED+=("$s")
    else
      log "  $s: digest unchanged ($want_tag) — skipping"
    fi
  done
  if [ "${#CHANGED[@]}" -eq 0 ]; then
    ok "nothing changed on GHCR. No deploy necessary."
    exit 0
  fi
  FINAL=("${CHANGED[@]}")
  log "services with changes: ${FINAL[*]}"
fi

FAILED=()
for s in "${FINAL[@]}"; do
  if deploy_service "$s"; then
    # Record the remote digest only after success: this way a failure is retried
    # on the next timer cycle instead of being marked as "already done".
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
  ok "DEPLOY COMPLETE — ${#FINAL[@]} service(s), all healthy internally."
  [ "$DRY_RUN" -eq 0 ] && log "state: $STATE_FILE"
  exit 0
fi

err "DEPLOY WITH FAILURES: ${FAILED[*]}"
err "Services that came up remain running; the ones that failed were reverted when possible."
err "Diagnosis:  docker compose ${COMPOSE_ARGS[*]} ps"
exit 1
