#!/usr/bin/env bash
#
# secrets-bootstrap.sh — generates and collects ALL the stack's secrets, and emits
# the encrypted `secrets.env.sops`. Runs ONCE, on the host, before the first deploy.
#
# ============================================================================
# NO VALUE IS EVER PRINTED. NEVER.
# ============================================================================
# This script was written so it can be run by an AI agent without the secrets
# entering its context/transcript — and, as a bonus, so it leaves no trace in
# `history`, in a terminal log or in a screenshot.
#
#   * Generated values go STRAIGHT to the file. They never pass through stdout.
#   * Interactive input uses `read -rs` (no echo on screen).
#   * The final report shows only the variable NAME and whether it is filled —
#     never the content. E.g.:  JWT_SECRET  [ok, 64 chars]
#   * The cleartext file is created with umask 077 and deleted in the EXIT trap.
#
# What it does:
#   1. Generates the secrets that come from NOWHERE (passwords, symmetric keys).
#   2. Collects the ones only you have (Cloudflare, LLM providers, OAuth) — from
#      a file with `--from-file` or asking one by one, without echo.
#   3. Writes `secrets.env` in tmpfs, encrypts it with SOPS+age and wipes the clear one.
#
# Usage:
#   ./secrets-bootstrap.sh                          # interactive, asks everything
#   ./secrets-bootstrap.sh --from-file ~/cf.txt     # reads what it can from the file
#   ./secrets-bootstrap.sh --regenerate JWT_SECRET  # rotates a single secret
#   ./secrets-bootstrap.sh --check                  # audits the existing .sops
#
# Format accepted in --from-file (one key per line, `=` or `:` as separator;
# lines starting with # are ignored; unknown keys are ignored silently, so you
# can point it at any notes file you happen to have):
#
#   acc_id=<cloudflare account id>
#   write_all=<api token with Tunnel:Edit + Access:Edit>
#   CLOUDFLARE_TUNNEL_TOKEN=<connector token, if you already created the tunnel>
#   OPENAI_API_KEY=...
#   GEMINI_API_KEY=...
#
# NOT used, and therefore not even read: `user`, `pass`, `read_all`. Account login
# has no function in this stack, and a read-only token does not create a tunnel. If
# they are in the file, they are ignored — but the right thing is not to leave them there.

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
warn() { printf '%sWARN:%s %s\n' "$C_YLW" "$C_OFF" "$*" >&2; }
die()  { printf '%sERROR:%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; exit 1; }

usage() { sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'; }

while [ $# -gt 0 ]; do
  case "$1" in
    --from-file)  FROM_FILE="${2:?--from-file requires a path}"; shift 2 ;;
    --check)      CHECK_ONLY=1; shift ;;
    --regenerate) REGENERATE="${2:?--regenerate requires the variable name}"; shift 2 ;;
    -h|--help)    usage; exit 0 ;;
    *) die "unknown option: $1" ;;
  esac
done

# ---------------------------------------------------------------------------
# Catalog. GERADO = created here; COLETADO = only you have it; OPCIONAL = may stay empty.
# ---------------------------------------------------------------------------
# format: NAME|class|generator-or-description
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
CLOUDFLARE_TUNNEL_TOKEN|coletado|Connector token for the nora-prod tunnel (Zero Trust > Networks > Tunnels)
CF_ACCESS_TEAM_DOMAIN|coletado|e.g.: stratfy.cloudflareaccess.com (not a secret)
CF_ACCESS_AUD|coletado|AUD tag of the admin's Access Application (not a secret)
GHCR_PULL_TOKEN|opcional|empty = assumes public packages on GHCR (the case today)
OPENAI_API_KEY|opcional|empty = worker in stub mode and chat 503
GEMINI_API_KEY|opcional|empty = embeddings/RAG off
DEEPSEEK_API_KEY|opcional|empty = provider unavailable in chat
RESEND_API_KEY|opcional|empty = backend falls back to LogEmailSender
GOOGLE_OAUTH_CLIENT_ID|opcional|empty = Google Calendar integration does not connect
GOOGLE_OAUTH_CLIENT_SECRET|opcional|empty = Google Calendar integration does not connect
NORA_PLATFORM_ENABLED|opcional|true turns on the 'platform' profile (admin + postgres-platform)
CAT
)

# ---------------------------------------------------------------------------
# Generators. They write to the SUBSHELL's stdout, captured straight into a variable —
# never to the terminal.
# ---------------------------------------------------------------------------
gerar() {
  case "$1" in
    b64:*)     openssl rand -base64 "${1#b64:}" | tr -d '\n=' | tr '+/' '-_' ;;
    hex:*)     openssl rand -hex "${1#hex:}" ;;
    # AES-256-GCM: exactly 32 bytes in STANDARD base64 (with padding). The
    # TokenCipher validates this at boot — base64url or the wrong size takes the API down.
    aes256b64) openssl rand -base64 32 ;;
    *) die "unknown generator: $1" ;;
  esac
}

# ---------------------------------------------------------------------------
# Parsing of --from-file. Accepts `=` or `:`; ignores comments, spaces and quotes.
# Maps the aliases from the notes file to the canonical names.
# ---------------------------------------------------------------------------
declare -A COLETA=()

ler_arquivo() {
  local f="$1" linha chave valor
  [ -r "$f" ] || die "cannot read $f"
  # Never echoes the content. Only counts how many keys it recognized.
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
      # Deliberately NOT read: account login is of no use to this stack, and a
      # read-only token does not create a tunnel. Keeping them would only widen the surface.
      user|username|pass|password|read_all)      ignoradas=$((ignoradas+1)) ;;
      *)
        # Any name already canonical in the catalog goes straight in.
        if printf '%s\n' "$CATALOGO" | cut -d'|' -f1 | grep -qx "$chave"; then
          COLETA["$chave"]="$valor"; reconhecidas=$((reconhecidas+1))
        else
          ignoradas=$((ignoradas+1))
        fi ;;
    esac
  done < "$f"
  info "$f: $reconhecidas key(s) recognized, $ignoradas ignored"
}

# ---------------------------------------------------------------------------
# --check: audits the existing .sops without revealing anything
# ---------------------------------------------------------------------------
if [ "$CHECK_ONLY" -eq 1 ]; then
  [ -f "$SOPS_FILE" ] || die "$SOPS_FILE does not exist — run without --check to create it."
  command -v sops >/dev/null || die "sops not found (run bootstrap-host.sh)."
  export SOPS_AGE_KEY_FILE="$AGE_KEY_FILE"
  log "Auditing $SOPS_FILE (values are NOT shown)"
  tmp="$(mktemp)"; trap 'shred -u "$tmp" 2>/dev/null || rm -f "$tmp"' EXIT
  sops --decrypt --input-type dotenv --output-type dotenv "$SOPS_FILE" > "$tmp" \
    || die "failed to decrypt. Is the age key at $AGE_KEY_FILE the right one?"
  faltando=0
  while IFS='|' read -r nome classe _; do
    v="$(sed -n "s/^$nome=//p" "$tmp" | head -1)"
    n=${#v}
    if [ "$n" -eq 0 ]; then
      case "$classe" in
        opcional) printf '  %-34s %s[empty - optional]%s\n' "$nome" "$C_DIM" "$C_OFF" ;;
        *)        printf '  %-34s %sMISSING%s\n' "$nome" "$C_RED" "$C_OFF"; faltando=$((faltando+1)) ;;
      esac
    elif [ "$v" = "unset" ]; then
      printf '  %-34s %sliteral "unset" - breaks the boot%s\n' "$nome" "$C_RED" "$C_OFF"; faltando=$((faltando+1))
    else
      printf '  %-34s %s[ok, %s chars]%s\n' "$nome" "$C_GRN" "$n" "$C_OFF"
    fi
  done <<< "$CATALOGO"
  echo
  [ "$faltando" -eq 0 ] && log "Everything mandatory is filled in." \
    || die "$faltando mandatory item(s) missing."
  exit 0
fi

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------
command -v openssl >/dev/null || die "openssl not found."
command -v sops    >/dev/null || die "sops not found (run bootstrap-host.sh)."
command -v age     >/dev/null || die "age not found (run bootstrap-host.sh)."
[ -r "$AGE_KEY_FILE" ] || die "age key not readable at $AGE_KEY_FILE. Run bootstrap-host.sh first."

[ -n "$FROM_FILE" ] && ler_arquivo "$FROM_FILE"

# If a .sops already exists, preserve whatever is in it (this is reconciliation, not reset).
declare -A ATUAL=()
if [ -f "$SOPS_FILE" ]; then
  log "$SOPS_FILE already exists — preserving the current values"
  export SOPS_AGE_KEY_FILE="$AGE_KEY_FILE"
  prev="$(mktemp)"
  sops --decrypt --input-type dotenv --output-type dotenv "$SOPS_FILE" > "$prev" 2>/dev/null \
    || warn "could not decrypt the existing file; treating it as new."
  # Do NOT use `IFS='=' read -r k v` here. Bash discards the line's FINAL delimiter
  # when filling the last variable, so a value ending in `=` loses that `=` --
  # which is exactly the padding of every base64. In practice: NORA_INTEGRATIONS_ENC_KEY
  # went from 44 to 43 chars (stops decoding to 32 bytes and the TokenCipher takes the
  # API down at boot) and CLOUDFLARE_TUNNEL_TOKEN from 180 to 179 (cloudflared does not
  # register the tunnel). That is: re-running this script to add ONE key silently
  # corrupted every already-encrypted secret whose value ended in `=`.
  while IFS= read -r linha; do
    case "$linha" in ''|\#*) continue ;; esac
    [ "${linha#*=}" != "$linha" ] || continue   # without `=` it is not a dotenv line
    ATUAL["${linha%%=*}"]="${linha#*=}"
  done < "$prev"
  shred -u "$prev" 2>/dev/null || rm -f "$prev"
fi

# ---------------------------------------------------------------------------
# Assembles the final set
# ---------------------------------------------------------------------------
SHM_FS="$(findmnt -no FSTYPE /dev/shm 2>/dev/null || true)"
[ "$SHM_FS" = "tmpfs" ] || die "/dev/shm is not tmpfs. Refusing to write secrets to disk."
WORK="$(mktemp -d /dev/shm/nora-secrets.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT
PLAIN="$WORK/secrets.env"
: > "$PLAIN"

if [ -e /dev/tty ] && { : < /dev/tty; } 2>/dev/null; then TEM_TTY=1; else TEM_TTY=0; fi

log "Assembling the secrets set"
if [ "$TEM_TTY" -eq 0 ]; then info "no terminal: whatever does not come from --from-file stays empty"; fi
echo

declare -a RELATORIO=()

while IFS='|' read -r nome classe spec; do
  valor=""
  origem=""

  if [ -n "$REGENERATE" ] && [ "$nome" = "$REGENERATE" ] && [ "$classe" = "gerado" ]; then
    valor="$(gerar "$spec")"; origem="regenerated"
  elif [ -n "${COLETA[$nome]:-}" ]; then
    valor="${COLETA[$nome]}"; origem="from file"
  elif [ -n "${ATUAL[$nome]:-}" ]; then
    valor="${ATUAL[$nome]}"; origem="preserved"
  elif [ "$classe" = "gerado" ]; then
    valor="$(gerar "$spec")"; origem="generated"
  elif [ "$TEM_TTY" -eq 0 ]; then
    # No terminal (ssh without -t, cron, agent): there is no way to ask. Leaves
    # it empty and moves on -- the final report shows the hole, and `--check`
    # demands whatever is mandatory. Asking here would only produce a repeated
    # /dev/tty error, one line per variable.
    valor=""; origem="no tty"
  else
    # Collected or optional and still without a value: ask WITHOUT ECHO.
    printf '  %s%s%s\n' "$C_YLW" "$nome" "$C_OFF"
    printf '    %s%s%s\n' "$C_DIM" "$spec" "$C_OFF"
    if [ "$classe" = "opcional" ]; then
      printf '    value (ENTER leaves it empty): '
    else
      printf '    value: '
    fi
    read -rs valor < /dev/tty || valor=""
    echo
    origem=$([ -n "$valor" ] && echo "typed" || echo "empty")
  fi

  printf '%s=%s\n' "$nome" "$valor" >> "$PLAIN"
  RELATORIO+=("$(printf '  %-34s %-12s %s' "$nome" "$origem" \
    "$([ -n "$valor" ] && echo "[${#valor} chars]" || echo "[empty]")")")
done <<< "$CATALOGO"

# Sanity check you only discover in production, late:
enc="$(sed -n 's/^NORA_INTEGRATIONS_ENC_KEY=//p' "$PLAIN" | head -1)"
if [ -n "$enc" ]; then
  # `... | wc -c || echo 0` does not substitute, it CONCATENATES: under `pipefail` a
  # failure at any stage makes `echo 0` run AFTER `wc` has already printed, and `bytes`
  # becomes "32\n0" -- `[ -eq ]` then dies with "integer expression expected" instead
  # of giving the diagnosis. Decode to a file and count separately.
  base64 -d < <(printf '%s' "$enc") > "$WORK/enc.bin" 2>/dev/null || true
  bytes="$(wc -c < "$WORK/enc.bin")"
  rm -f "$WORK/enc.bin"
  [ "$bytes" -eq 32 ] || die "NORA_INTEGRATIONS_ENC_KEY does not decode to 32 bytes ($bytes).
       The TokenCipher validates this at boot and takes the API down. Regenerate with --regenerate."
fi
grep -q '=unset$' "$PLAIN" && die 'some value ended up as the literal string "unset" — this breaks the boot.'

# ---------------------------------------------------------------------------
# Encrypt
# ---------------------------------------------------------------------------
export SOPS_AGE_KEY_FILE="$AGE_KEY_FILE"
sops --encrypt --input-type dotenv --output-type dotenv "$PLAIN" > "$SOPS_FILE.tmp" \
  || die "sops failed to encrypt. Does .sops.yaml have this host's age recipient?"
mv -f "$SOPS_FILE.tmp" "$SOPS_FILE"
chmod 0644 "$SOPS_FILE"   # encrypted: can be versioned

# Round-trip proof. A secret corrupted while assembling is invisible in here --
# it only shows up as a container in CrashLoop, or worse, as wrong behaviour in
# silence. Comparing byte by byte costs nothing and closes the whole bug class.
verif="$WORK/verify.env"
sops --decrypt --input-type dotenv --output-type dotenv "$SOPS_FILE" > "$verif" 2>/dev/null \
  || die "the newly encrypted file does not decrypt with the key at $AGE_KEY_FILE."
cmp -s "$PLAIN" "$verif" \
  || die "the encrypt/decrypt round trip did not return the same content. Do NOT use this file."
rm -f "$verif"

echo
log "Result (names and sizes — no values)"
printf '%s\n' "${RELATORIO[@]}"
echo
log "Written and encrypted: $SOPS_FILE"
cat <<EOF

  The cleartext file only lived in tmpfs and has already been deleted.

  Next steps:
    1. git add $SOPS_FILE && git commit    (safe: it is encrypted)
    2. ./scripts/secrets-bootstrap.sh --check     (verifies without revealing)
    3. ./scripts/deploy.sh

  The PRIVATE age key at $AGE_KEY_FILE is the only thing that decrypts this.
  Without a backup of it, the versioned file turns into garbage. Keep an offline copy.

EOF
