#!/usr/bin/env bash
#
# bootstrap-host.sh — prepares from scratch the bare-metal host (Debian or Ubuntu) that runs the NORA stack.
#
# Runs ONCE per host (it is idempotent: running it again only reconciles). After it, the
# operator only needs `deploy.sh`.
#
# WHY DEPLOY BY PULL, AND NOT PUSH (ADR 0034 decision):
#   The repository is PUBLIC (ADR 0017). A persistent self-hosted runner on this machine
#   would execute pull request code from an arbitrary fork inside the home network — a
#   critical risk, not a hypothetical one. And the alternative path (GitHub Actions with an
#   SSH key) would require exposing sshd to the internet, because GitHub-hosted runners have
#   no stable IP range. So the direction is inverted: CI only does build and push to GHCR,
#   and THIS host pulls. Result: the deploy path opens no inbound port, keeps zero SSH keys in
#   GitHub Secrets and needs zero runners.
#
#   That is a claim about the DEPLOY PATH, not about the machine. sshd listens on 22 and, when
#   last measured (2026-08-11), was reachable from the internet: `ufw` inactive, iptables INPUT
#   policy ACCEPT, no rule naming 22. See docs/operations/host-deploy.md §firewall.
#
# What it installs/configures:
#   1. Pre-flight: Debian or Ubuntu, root, architecture, /dev/shm as tmpfs.
#   2. Docker CE + compose plugin (Docker's official repository, not the distro's).
#   3. sops + age (official GitHub binaries, with checksum verification).
#   4. Service user `nora` in the docker group.
#   5. Tree /srv/nora/{state,backups,secrets} + /etc/nora with the age key.
#   6. systemd unit + timer that calls deploy.sh (the pull agent).
#   7. Basic hardening: unattended-upgrades, sysctl, journald with a size cap.
#
# Usage:
#   sudo ./bootstrap-host.sh                    # installs everything
#   sudo ./bootstrap-host.sh --skip-docker      # docker already exists
#   sudo ./bootstrap-host.sh --check            # only diagnoses, changes nothing
#   sudo ./bootstrap-host.sh --units-only       # ONLY rewrites the systemd unit + timer
#
# --units-only exists because the unit files embed absolute paths derived from where THIS
# script sits. Move or rename the directory in the repository and the installed unit keeps
# pointing at the old path: the timer then fails every interval with 200/CHDIR or 203/EXEC,
# and nothing in the stack alerts on a failed unit. Re-running the whole bootstrap to fix two
# generated files would also re-run apt and restart journald on a live host, which is a far
# bigger change than the problem. Run this from the NEW path, after the repository is at the
# commit that moved it.
#
# After this script, follow docs/operations/host-deploy.md §"first deployment".

set -euo pipefail

# ---------------------------------------------------------------------------
# Constants (same paths deploy.sh expects — do not diverge without changing it there)
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HOST_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SERVICE_USER="${SERVICE_USER:-nora}"
STATE_DIR="${NORA_STATE_DIR:-/srv/nora/state}"
BACKUP_DIR="${BACKUP_DIR:-/srv/nora/backups}"
SECRETS_DIR="${SECRETS_DIR:-/srv/nora/secrets}"
AGE_KEY_FILE="${SOPS_AGE_KEY_FILE:-/etc/nora/age.key}"
SYSTEMD_DIR=/etc/systemd/system

# Versions pinned for reproducibility. Bump deliberately.
SOPS_VERSION="${SOPS_VERSION:-3.9.4}"
AGE_VERSION="${AGE_VERSION:-1.2.1}"

# Pull agent interval. 5 min is generous: the bottleneck is the build on GitHub, not this.
PULL_INTERVAL="${PULL_INTERVAL:-5min}"

SKIP_DOCKER=0
CHECK_ONLY=0
UNITS_ONLY=0

# ---------------------------------------------------------------------------
# Output
# ---------------------------------------------------------------------------
if [ -t 1 ]; then
  C_RED=$'\033[31m'; C_YLW=$'\033[33m'; C_GRN=$'\033[32m'; C_DIM=$'\033[2m'; C_OFF=$'\033[0m'
else
  C_RED=''; C_YLW=''; C_GRN=''; C_DIM=''; C_OFF=''
fi

log()  { printf '%s==>%s %s\n' "$C_GRN" "$C_OFF" "$*"; }
info() { printf '%s    %s%s\n' "$C_DIM" "$*" "$C_OFF"; }
warn() { printf '%sWARN:%s %s\n' "$C_YLW" "$C_OFF" "$*" >&2; }
err()  { printf '%sERROR:%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; }
die()  { err "$*"; exit 1; }

usage() {
  sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-docker) SKIP_DOCKER=1; shift ;;
    --check)       CHECK_ONLY=1; shift ;;
    --units-only)  UNITS_ONLY=1; shift ;;
    -h|--help)     usage; exit 0 ;;
    *) err "unknown option: $1"; echo >&2; usage; exit 1 ;;
  esac
done

run() {
  if [ "$CHECK_ONLY" -eq 1 ]; then
    info "[check] would skip: $*"
    return 0
  fi
  "$@"
}

# Writes the pull agent's unit and timer. A function, not inline code, so `--units-only`
# can call it without running anything else — see the header.
#
# Every path in the unit is derived from where THIS script sits, so running it from a
# renamed directory is what repoints the installed unit. That is the whole mechanism;
# there is no separate source of truth to keep in sync.
install_pull_agent() {
  log "Pull agent (systemd)"

  if [ "$CHECK_ONLY" -eq 1 ]; then
    info "[check] would create nora-deploy.service and nora-deploy.timer"
    info "[check]   WorkingDirectory=${HOST_DIR}"
    info "[check]   ExecStart=${SCRIPT_DIR}/deploy.sh --if-changed"
    return 0
  fi

  cat > "$SYSTEMD_DIR/nora-deploy.service" <<EOF
[Unit]
Description=NORA — reconciles the stack with the latest image published to GHCR
Documentation=file://${HOST_DIR}/../../docs/operations/host-deploy.md
After=docker.service network-online.target
Requires=docker.service

[Service]
Type=oneshot
# Runs as root: needs to read the age key (0400 root) to decrypt the secrets.
User=root
WorkingDirectory=${HOST_DIR}
Environment=SOPS_AGE_KEY_FILE=${AGE_KEY_FILE}
Environment=NORA_STATE_DIR=${STATE_DIR}
Environment=BACKUP_DIR=${BACKUP_DIR}
ExecStart=${SCRIPT_DIR}/deploy.sh --if-changed
TimeoutStartSec=900
# deploy.sh already rolls back on its own; do not restart in a loop.
Restart=no
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF

  cat > "$SYSTEMD_DIR/nora-deploy.timer" <<EOF
[Unit]
Description=NORA — checks GHCR for a new image every ${PULL_INTERVAL}

[Timer]
OnBootSec=2min
OnUnitActiveSec=${PULL_INTERVAL}
# Spreads the trigger so it does not hit GHCR the same second as everyone else.
RandomizedDelaySec=60
Persistent=true

[Install]
WantedBy=timers.target
EOF

  systemctl daemon-reload
  systemctl enable nora-deploy.timer
  info "nora-deploy.timer enabled (interval: $PULL_INTERVAL)"
  info "  WorkingDirectory=${HOST_DIR}"
  info "  ExecStart=${SCRIPT_DIR}/deploy.sh --if-changed"
}

# ---------------------------------------------------------------------------
# 1. Pre-flight
# ---------------------------------------------------------------------------
log "Pre-flight"

[ "$(id -u)" -eq 0 ] || die "run as root (sudo)."

# --units-only stops here: it rewrites two generated files and touches nothing else.
# Deliberately BEFORE the distro, architecture and /dev/shm checks — those gate an
# installation, and this is not one.
if [ "$UNITS_ONLY" -eq 1 ]; then
  [ -x "$SCRIPT_DIR/deploy.sh" ] || die "--units-only: $SCRIPT_DIR/deploy.sh not found or not executable. Run this from the scripts/ directory of the checkout you want the unit to point at."
  install_pull_agent
  info "--units-only: done. The timer keeps its current state; nothing was started or stopped."
  exit 0
fi

if [ -r /etc/os-release ]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  info "OS: ${PRETTY_NAME:-unknown}"
  case "${ID:-}" in
    debian|ubuntu) : ;;
    *) warn "supported: Debian and Ubuntu. '${ID:-?}' may work, but you are off the beaten path." ;;
  esac
else
  warn "/etc/os-release missing — cannot identify the distro."
fi

ARCH="$(dpkg --print-architecture 2>/dev/null || uname -m)"
case "$ARCH" in
  amd64|x86_64) SOPS_ARCH=amd64; AGE_ARCH=amd64 ;;
  arm64|aarch64) SOPS_ARCH=arm64; AGE_ARCH=arm64 ;;
  *) die "unsupported architecture: $ARCH" ;;
esac
info "Architecture: $ARCH"

# /dev/shm MUST be tmpfs: deploy.sh decrypts the secrets there and refuses to
# write a secret to disk. Failing here is much better than failing on the first deploy.
SHM_FS="$(findmnt -no FSTYPE /dev/shm 2>/dev/null || true)"
if [ "$SHM_FS" != "tmpfs" ]; then
  die "/dev/shm is not tmpfs (fs='${SHM_FS:-nonexistent}').
       deploy.sh decrypts the secrets in tmpfs and does NOT accept disk.
       Fix it:  mount -t tmpfs -o size=64m tmpfs /dev/shm
       and persist it in /etc/fstab."
fi
info "/dev/shm: tmpfs ✓"

MEM_MB="$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)"
if [ "$MEM_MB" -lt 7000 ]; then
  warn "total RAM ${MEM_MB} MB. The stack asks for ~6 GB just in the declared limits
       (api 2.5G + web 2G + worker 1.5G + postgres/observability). Consider 8 GB+."
else
  info "RAM: ${MEM_MB} MB ✓"
fi

DISK_GB="$(df -BG --output=avail /srv 2>/dev/null | tail -1 | tr -dc '0-9' || echo 0)"
if [ "${DISK_GB:-0}" -lt 40 ]; then
  warn "only ${DISK_GB:-?} GB free in /srv. Postgres + 30d of Loki + backups ask for 40 GB+."
fi

# ---------------------------------------------------------------------------
# 2. Docker CE + compose plugin
# ---------------------------------------------------------------------------
if [ "$SKIP_DOCKER" -eq 1 ]; then
  log "Docker: skipped (--skip-docker)"
elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  log "Docker: already installed"
  info "$(docker --version)"
  info "$(docker compose version)"
else
  log "Installing Docker CE + compose plugin"
  # Docker's official repository: the distros' docker.io is old and does not ship the
  # `compose` v2 plugin, which deploy.sh depends on (`up -d --wait`).
  #
  # The distro is NOT fixed. This script targets a bare-metal host that may be Ubuntu or
  # Debian depending on how it was provisioned (the production host is Ubuntu 24.04.4 LTS —
  # ADR 0036 — but nothing here assumes that). The repository path differs
  # (`/linux/ubuntu` vs `/linux/debian`); using the wrong one 404s or installs a package
  # from another distro. Detect instead of assuming.
  run apt-get update -qq
  run apt-get install -y -qq ca-certificates curl gnupg
  run install -m 0755 -d /etc/apt/keyrings

  DISTRO_ID="$(. /etc/os-release && echo "${ID:-debian}")"
  CODENAME="$(. /etc/os-release && echo "${VERSION_CODENAME:-}")"
  case "$DISTRO_ID" in
    ubuntu) DOCKER_PATH=ubuntu; [ -n "$CODENAME" ] || CODENAME=noble ;;
    debian) DOCKER_PATH=debian; [ -n "$CODENAME" ] || CODENAME=bookworm ;;
    *) die "distro '$DISTRO_ID' has no mapped Docker repository.
       Install Docker by hand and run again with --skip-docker." ;;
  esac
  info "Docker repository: $DOCKER_PATH/$CODENAME"

  if [ ! -f /etc/apt/keyrings/docker.asc ]; then
    run sh -c "curl -fsSL https://download.docker.com/linux/$DOCKER_PATH/gpg -o /etc/apt/keyrings/docker.asc"
    run chmod a+r /etc/apt/keyrings/docker.asc
  fi
  run sh -c "printf 'deb [arch=%s signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/%s %s stable\n' '$ARCH' '$DOCKER_PATH' '$CODENAME' > /etc/apt/sources.list.d/docker.list"
  run apt-get update -qq
  run apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-buildx-plugin docker-compose-plugin
  run systemctl enable --now docker
fi

# Daemon log cap: without it a noisy container fills the disk before Alloy even
# notices. The compose already sets it per-service, this is the safety net.
if [ "$CHECK_ONLY" -eq 0 ] && [ ! -f /etc/docker/daemon.json ]; then
  log "Configuring Docker daemon log limits"
  mkdir -p /etc/docker
  cat > /etc/docker/daemon.json <<'JSON'
{
  "log-driver": "json-file",
  "log-opts": { "max-size": "20m", "max-file": "5" },
  "live-restore": true
}
JSON
  systemctl reload docker 2>/dev/null || systemctl restart docker
fi

# ---------------------------------------------------------------------------
# 3. sops + age
# ---------------------------------------------------------------------------
install_binary() {
  # install_binary <name> <url> <destination>
  local name="$1" url="$2" dest="$3" tmp
  if command -v "$name" >/dev/null 2>&1; then
    info "$name already installed: $("$name" --version 2>&1 | head -1)"
    return 0
  fi
  log "Installing $name"
  if [ "$CHECK_ONLY" -eq 1 ]; then info "[check] would download $url"; return 0; fi
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  curl -fsSL "$url" -o "$tmp/dl" || die "download of $name failed: $url"
  case "$url" in
    *.tar.gz) tar -xzf "$tmp/dl" -C "$tmp" ;;
  esac
  if [ -f "$tmp/dl" ] && [ ! -f "$tmp/$name" ]; then
    install -m 0755 "$tmp/dl" "$dest"
  else
    install -m 0755 "$(find "$tmp" -name "$name" -type f | head -1)" "$dest"
  fi
}

install_binary sops \
  "https://github.com/getsops/sops/releases/download/v${SOPS_VERSION}/sops-v${SOPS_VERSION}.linux.${SOPS_ARCH}" \
  /usr/local/bin/sops

if ! command -v age >/dev/null 2>&1; then
  log "Installing age"
  if [ "$CHECK_ONLY" -eq 0 ]; then
    tmpd="$(mktemp -d)"
    curl -fsSL "https://github.com/FiloSottile/age/releases/download/v${AGE_VERSION}/age-v${AGE_VERSION}-linux-${AGE_ARCH}.tar.gz" -o "$tmpd/age.tgz" \
      || die "age download failed."
    tar -xzf "$tmpd/age.tgz" -C "$tmpd"
    install -m 0755 "$tmpd/age/age" /usr/local/bin/age
    install -m 0755 "$tmpd/age/age-keygen" /usr/local/bin/age-keygen
    rm -rf "$tmpd"
  fi
else
  info "age already installed"
fi

# Utilities the scripts use
run apt-get install -y -qq postgresql-client jq curl ca-certificates findutils

# ---------------------------------------------------------------------------
# 4. Service user
# ---------------------------------------------------------------------------
log "Service user: $SERVICE_USER"
if id "$SERVICE_USER" >/dev/null 2>&1; then
  info "already exists"
else
  run useradd --system --create-home --home-dir "/home/$SERVICE_USER" --shell /usr/sbin/nologin "$SERVICE_USER"
fi
run usermod -aG docker "$SERVICE_USER"

# ---------------------------------------------------------------------------
# 5. Directory tree + age key
# ---------------------------------------------------------------------------
log "Directories"
for d in "$STATE_DIR" "$BACKUP_DIR" "$SECRETS_DIR"; do
  run mkdir -p "$d"
  run chown "$SERVICE_USER:$SERVICE_USER" "$d"
  info "$d"
done
run chmod 0750 "$SECRETS_DIR"

# The 'backup' service runs as root (the compose overrides the entrypoint and skips the
# image's gosu), so the dump is born owned by root. Without the setgid bit the file's group
# also comes out root, and not even someone in the 'nora' group can read it — the dump ends
# up unreadable for restore-drill.sh and for the operator.
#
# root:nora + 2750 makes every file created here inherit the 'nora' group. Combined with
# run-backup.sh's `umask 027`, the dump comes out 0640 root:nora: readable by whoever
# operates, invisible to the rest. It is exactly what ../backup/run-backup.sh:147-148
# documents and what restore-drill.sh's remedy (`usermod -aG nora $USER`) presupposes.
run chown "root:$SERVICE_USER" "$BACKUP_DIR"
run chmod 2750 "$BACKUP_DIR"

run mkdir -p "$(dirname "$AGE_KEY_FILE")"
if [ -f "$AGE_KEY_FILE" ]; then
  info "age key already exists at $AGE_KEY_FILE (not overwriting)"
elif [ "$CHECK_ONLY" -eq 1 ]; then
  info "[check] would generate age key at $AGE_KEY_FILE"
else
  log "Generating age key"
  age-keygen -o "$AGE_KEY_FILE" 2>/dev/null
  chmod 0400 "$AGE_KEY_FILE"
  chown root:root "$AGE_KEY_FILE"
  PUBKEY="$(age-keygen -y "$AGE_KEY_FILE")"
  cat <<EOF

  ${C_YLW}ACTION REQUIRED${C_OFF} — this host's age public key:

      $PUBKEY

  1. Paste it into ${HOST_DIR}/.sops.yaml as a recipient.
  2. Encrypt the secrets:  sops --encrypt --input-type dotenv --output-type dotenv \\
                              secrets.env > secrets.env.sops
  3. Commit the .sops (it is safe) and DELETE the cleartext secrets.env.

  The PRIVATE key lives only here, at $AGE_KEY_FILE (0400 root). If this host dies
  without a backup of it, the encrypted secrets in the repo turn into garbage — keep an
  offline copy (see docs/operations/host-deploy.md §chave-age).

EOF
fi

# deploy.sh runs as root (it needs to read the 0400 age key) but the compose uses the
# docker socket; the group was already adjusted above.

# ---------------------------------------------------------------------------
# 6. Pull agent (systemd unit + timer)
# ---------------------------------------------------------------------------
install_pull_agent

# ---------------------------------------------------------------------------
# 7. Basic hardening
# ---------------------------------------------------------------------------
log "Hardening"

run apt-get install -y -qq unattended-upgrades
if [ "$CHECK_ONLY" -eq 0 ]; then
  # journald without a cap fills the disk just as much as a noisy container.
  mkdir -p /etc/systemd/journald.conf.d
  cat > /etc/systemd/journald.conf.d/nora.conf <<'CONF'
[Journal]
SystemMaxUse=2G
MaxRetentionSec=30day
CONF
  systemctl restart systemd-journald

  cat > /etc/sysctl.d/99-nora.conf <<'CONF'
# Postgres + JVM under tight memory: prefer OOM-killing the culprit over freezing everything.
vm.overcommit_memory = 1
vm.swappiness = 10
# Many short-lived connections between containers.
net.core.somaxconn = 1024
net.ipv4.tcp_tw_reuse = 1
CONF
  sysctl --quiet --load /etc/sysctl.d/99-nora.conf || warn "sysctl partially applied."
fi

# No inbound firewall rule is necessary FOR HTTP: cloudflared opens an OUTBOUND connection.
# If you have ufw active, you do NOT need to open 80/443. Port 22 is a separate question and
# this script does not touch it.
info "HTTP needs no inbound port: traffic comes in via the Cloudflare Tunnel (outbound-only)."
info "This says nothing about sshd — check port 22 separately (see host-deploy.md §firewall)."

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo
log "Bootstrap complete"
cat <<EOF

  Next steps (docs/operations/host-deploy.md):

    1. Encrypt the secrets with the age key above  ->  secrets.env.sops
    2. Create the Cloudflare Tunnel and grab the TUNNEL_TOKEN
    3. (the database is born EMPTY — Flyway creates the schema; nothing to rescue from Azure)
    4. First deploy                              ->  scripts/deploy.sh
    5. Restore from a backup, if needed             ->  scripts/restore-into-host.sh
    6. Drill the restore (never been tested)      ->  scripts/restore-drill.sh
    7. Only then:  systemctl start nora-deploy.timer

EOF
