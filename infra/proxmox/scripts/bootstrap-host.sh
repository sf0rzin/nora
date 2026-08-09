#!/usr/bin/env bash
#
# bootstrap-host.sh — prepares from scratch the VM (Debian or Ubuntu) that hosts the NORA stack on the "beta" Proxmox.
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
#   and THIS host pulls. Result: zero inbound port, zero SSH key in GitHub Secrets, zero runner.
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
#
# After this script, follow docs/operations/proxmox-deploy.md §"first deployment".

set -euo pipefail

# ---------------------------------------------------------------------------
# Constants (same paths deploy.sh expects — do not diverge without changing it there)
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROXMOX_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

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
warn() { printf '%sAVISO:%s %s\n' "$C_YLW" "$C_OFF" "$*" >&2; }
err()  { printf '%sERRO:%s %s\n' "$C_RED" "$C_OFF" "$*" >&2; }
die()  { err "$*"; exit 1; }

usage() {
  sed -n '2,/^set -euo/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//; $d'
}

while [ $# -gt 0 ]; do
  case "$1" in
    --skip-docker) SKIP_DOCKER=1; shift ;;
    --check)       CHECK_ONLY=1; shift ;;
    -h|--help)     usage; exit 0 ;;
    *) err "opção desconhecida: $1"; echo >&2; usage; exit 1 ;;
  esac
done

run() {
  if [ "$CHECK_ONLY" -eq 1 ]; then
    info "[check] pularia: $*"
    return 0
  fi
  "$@"
}

# ---------------------------------------------------------------------------
# 1. Pre-flight
# ---------------------------------------------------------------------------
log "Pré-voo"

[ "$(id -u)" -eq 0 ] || die "rode como root (sudo)."

if [ -r /etc/os-release ]; then
  # shellcheck disable=SC1091
  . /etc/os-release
  info "SO: ${PRETTY_NAME:-desconhecido}"
  case "${ID:-}" in
    debian|ubuntu) : ;;
    *) warn "suportados: Debian e Ubuntu. '${ID:-?}' pode funcionar, mas você está fora do caminho trilhado." ;;
  esac
else
  warn "/etc/os-release ausente — não consigo identificar a distro."
fi

ARCH="$(dpkg --print-architecture 2>/dev/null || uname -m)"
case "$ARCH" in
  amd64|x86_64) SOPS_ARCH=amd64; AGE_ARCH=amd64 ;;
  arm64|aarch64) SOPS_ARCH=arm64; AGE_ARCH=arm64 ;;
  *) die "arquitetura não suportada: $ARCH" ;;
esac
info "Arquitetura: $ARCH"

# /dev/shm MUST be tmpfs: deploy.sh decrypts the secrets there and refuses to
# write a secret to disk. Failing here is much better than failing on the first deploy.
SHM_FS="$(findmnt -no FSTYPE /dev/shm 2>/dev/null || true)"
if [ "$SHM_FS" != "tmpfs" ]; then
  die "/dev/shm não é tmpfs (fs='${SHM_FS:-inexistente}').
       O deploy.sh decifra os segredos em tmpfs e NÃO aceita disco.
       Corrija:  mount -t tmpfs -o size=64m tmpfs /dev/shm
       e persista em /etc/fstab."
fi
info "/dev/shm: tmpfs ✓"

MEM_MB="$(awk '/MemTotal/ {print int($2/1024)}' /proc/meminfo 2>/dev/null || echo 0)"
if [ "$MEM_MB" -lt 7000 ]; then
  warn "RAM total ${MEM_MB} MB. A stack pede ~6 GB só nos limites declarados
       (api 2.5G + web 2G + worker 1.5G + postgres/observabilidade). Considere 8 GB+."
else
  info "RAM: ${MEM_MB} MB ✓"
fi

DISK_GB="$(df -BG --output=avail /srv 2>/dev/null | tail -1 | tr -dc '0-9' || echo 0)"
if [ "${DISK_GB:-0}" -lt 40 ]; then
  warn "só ${DISK_GB:-?} GB livres em /srv. Postgres + 30d de Loki + backups pedem 40 GB+."
fi

# ---------------------------------------------------------------------------
# 2. Docker CE + compose plugin
# ---------------------------------------------------------------------------
if [ "$SKIP_DOCKER" -eq 1 ]; then
  log "Docker: pulado (--skip-docker)"
elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  log "Docker: já instalado"
  info "$(docker --version)"
  info "$(docker compose version)"
else
  log "Instalando Docker CE + plugin compose"
  # Docker's official repository: the distros' docker.io is old and does not ship the
  # `compose` v2 plugin, which deploy.sh depends on (`up -d --wait`).
  #
  # The distro is NOT fixed. The `beta` host is Proxmox VE 9.2.5 on Debian 13 (trixie), and
  # the only cloud image already present in `local:iso` is Ubuntu Noble 24.04 — so the VM
  # may be born Ubuntu OR Debian depending on what gets provisioned. The repository
  # path differs (`/linux/ubuntu` vs `/linux/debian`); using the wrong one 404s or
  # installs a package from another distro. Detect instead of assuming.
  run apt-get update -qq
  run apt-get install -y -qq ca-certificates curl gnupg
  run install -m 0755 -d /etc/apt/keyrings

  DISTRO_ID="$(. /etc/os-release && echo "${ID:-debian}")"
  CODENAME="$(. /etc/os-release && echo "${VERSION_CODENAME:-}")"
  case "$DISTRO_ID" in
    ubuntu) DOCKER_PATH=ubuntu; [ -n "$CODENAME" ] || CODENAME=noble ;;
    debian) DOCKER_PATH=debian; [ -n "$CODENAME" ] || CODENAME=bookworm ;;
    *) die "distro '$DISTRO_ID' sem repositorio Docker mapeado.
       Instale o Docker a mao e rode de novo com --skip-docker." ;;
  esac
  info "Repositorio Docker: $DOCKER_PATH/$CODENAME"

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
  log "Configurando limites de log do daemon Docker"
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
    info "$name já instalado: $("$name" --version 2>&1 | head -1)"
    return 0
  fi
  log "Instalando $name"
  if [ "$CHECK_ONLY" -eq 1 ]; then info "[check] baixaria $url"; return 0; fi
  tmp="$(mktemp -d)"
  trap 'rm -rf "$tmp"' RETURN
  curl -fsSL "$url" -o "$tmp/dl" || die "download de $name falhou: $url"
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
  log "Instalando age"
  if [ "$CHECK_ONLY" -eq 0 ]; then
    tmpd="$(mktemp -d)"
    curl -fsSL "https://github.com/FiloSottile/age/releases/download/v${AGE_VERSION}/age-v${AGE_VERSION}-linux-${AGE_ARCH}.tar.gz" -o "$tmpd/age.tgz" \
      || die "download do age falhou."
    tar -xzf "$tmpd/age.tgz" -C "$tmpd"
    install -m 0755 "$tmpd/age/age" /usr/local/bin/age
    install -m 0755 "$tmpd/age/age-keygen" /usr/local/bin/age-keygen
    rm -rf "$tmpd"
  fi
else
  info "age já instalado"
fi

# Utilities the scripts use
run apt-get install -y -qq postgresql-client jq curl ca-certificates findutils

# ---------------------------------------------------------------------------
# 4. Service user
# ---------------------------------------------------------------------------
log "Usuário de serviço: $SERVICE_USER"
if id "$SERVICE_USER" >/dev/null 2>&1; then
  info "já existe"
else
  run useradd --system --create-home --home-dir "/home/$SERVICE_USER" --shell /usr/sbin/nologin "$SERVICE_USER"
fi
run usermod -aG docker "$SERVICE_USER"

# ---------------------------------------------------------------------------
# 5. Directory tree + age key
# ---------------------------------------------------------------------------
log "Diretórios"
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
  info "chave age já existe em $AGE_KEY_FILE (não sobrescrevo)"
elif [ "$CHECK_ONLY" -eq 1 ]; then
  info "[check] geraria chave age em $AGE_KEY_FILE"
else
  log "Gerando chave age"
  age-keygen -o "$AGE_KEY_FILE" 2>/dev/null
  chmod 0400 "$AGE_KEY_FILE"
  chown root:root "$AGE_KEY_FILE"
  PUBKEY="$(age-keygen -y "$AGE_KEY_FILE")"
  cat <<EOF

  ${C_YLW}AÇÃO NECESSÁRIA${C_OFF} — chave pública age deste host:

      $PUBKEY

  1. Cole em ${PROXMOX_DIR}/.sops.yaml como recipient.
  2. Cifre os segredos:  sops --encrypt --input-type dotenv --output-type dotenv \\
                              secrets.env > secrets.env.sops
  3. Commite o .sops (é seguro) e APAGUE o secrets.env em claro.

  A chave PRIVADA fica só aqui, em $AGE_KEY_FILE (0400 root). Se este host morrer
  sem backup dela, os segredos cifrados no repo viram lixo — guarde uma cópia
  offline (ver docs/operations/proxmox-deploy.md §chave-age).

EOF
fi

# deploy.sh runs as root (it needs to read the 0400 age key) but the compose uses the
# docker socket; the group was already adjusted above.

# ---------------------------------------------------------------------------
# 6. Pull agent (systemd unit + timer)
# ---------------------------------------------------------------------------
log "Agente de pull (systemd)"

if [ "$CHECK_ONLY" -eq 0 ]; then
  cat > "$SYSTEMD_DIR/nora-deploy.service" <<EOF
[Unit]
Description=NORA — reconcilia a stack com a ultima imagem publicada no GHCR
Documentation=file://${PROXMOX_DIR}/../../docs/operations/proxmox-deploy.md
After=docker.service network-online.target
Requires=docker.service

[Service]
Type=oneshot
# Runs as root: needs to read the age key (0400 root) to decrypt the secrets.
User=root
WorkingDirectory=${PROXMOX_DIR}
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
Description=NORA — checa o GHCR por imagem nova a cada ${PULL_INTERVAL}

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
  info "nora-deploy.timer habilitado (intervalo: $PULL_INTERVAL)"
  info "NÃO iniciei o timer — faça o primeiro deploy à mão e valide antes:"
  info "  ${SCRIPT_DIR}/deploy.sh   &&   systemctl start nora-deploy.timer"
else
  info "[check] criaria nora-deploy.service e nora-deploy.timer"
fi

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
  sysctl --quiet --load /etc/sysctl.d/99-nora.conf || warn "sysctl parcialmente aplicado."
fi

# No inbound firewall rule is necessary: cloudflared opens an OUTBOUND connection.
# If you have ufw active, you do NOT need to open 80/443.
info "Sem porta inbound: o tráfego entra pelo Cloudflare Tunnel (saída-only)."

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
echo
log "Bootstrap concluído"
cat <<EOF

  Próximos passos (docs/operations/proxmox-deploy.md):

    1. Cifrar os segredos com a chave age acima  ->  secrets.env.sops
    2. Criar o Cloudflare Tunnel e pegar o TUNNEL_TOKEN
    3. (banco nasce VAZIO — o Flyway cria o schema; nada a resgatar do Azure)
    4. Primeiro deploy                           ->  scripts/deploy.sh
    5. Restaurar de um backup, se precisar          ->  scripts/restore-into-proxmox.sh
    6. Ensaiar o restore (nunca foi testado)     ->  scripts/restore-drill.sh
    7. Só então:  systemctl start nora-deploy.timer

EOF
