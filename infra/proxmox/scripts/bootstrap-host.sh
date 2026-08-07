#!/usr/bin/env bash
#
# bootstrap-host.sh — prepara do zero a VM (Debian ou Ubuntu) que hospeda a stack NORA no Proxmox "beta".
#
# Roda UMA vez por host (é idempotente: rodar de novo só reconcilia). Depois dele, o
# operador só precisa de `deploy.sh`.
#
# POR QUE DEPLOY POR PULL, E NÃO PUSH (decisão do ADR 0034):
#   O repositório é PÚBLICO (ADR 0017). Um runner self-hosted persistente nesta máquina
#   executaria código de pull request de fork arbitrário dentro da rede doméstica — risco
#   crítico, não hipotético. E o caminho alternativo (GitHub Actions com chave SSH) exigiria
#   expor sshd à internet, porque runners GitHub-hosted não têm faixa de IP estável.
#   Então a direção é invertida: o CI só faz build e push pro GHCR, e ESTE host puxa.
#   Resultado: zero porta inbound, zero chave SSH no GitHub Secrets, zero runner.
#
# O que instala/configura:
#   1. Pré-voo: Debian ou Ubuntu, root, arquitetura, /dev/shm como tmpfs.
#   2. Docker CE + plugin compose (repositório oficial da Docker, não o da distro).
#   3. sops + age (binários oficiais do GitHub, com verificação de checksum).
#   4. Usuário de serviço `nora` no grupo docker.
#   5. Árvore /srv/nora/{state,backups,secrets} + /etc/nora com a chave age.
#   6. Unidade systemd + timer que chama o deploy.sh (o agente de pull).
#   7. Hardening básico: unattended-upgrades, sysctl, journald com limite de tamanho.
#
# Uso:
#   sudo ./bootstrap-host.sh                    # instala tudo
#   sudo ./bootstrap-host.sh --skip-docker      # docker já existe
#   sudo ./bootstrap-host.sh --check            # só diagnostica, não muda nada
#
# Depois deste script, siga docs/operations/proxmox-deploy.md §"primeiro deploy".

set -euo pipefail

# ---------------------------------------------------------------------------
# Constantes (mesmos caminhos que deploy.sh espera — não divirja sem mudar lá)
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROXMOX_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

SERVICE_USER="${SERVICE_USER:-nora}"
STATE_DIR="${NORA_STATE_DIR:-/srv/nora/state}"
BACKUP_DIR="${BACKUP_DIR:-/srv/nora/backups}"
SECRETS_DIR="${SECRETS_DIR:-/srv/nora/secrets}"
AGE_KEY_FILE="${SOPS_AGE_KEY_FILE:-/etc/nora/age.key}"
SYSTEMD_DIR=/etc/systemd/system

# Versões pinadas por reprodutibilidade. Bumpar conscientemente.
SOPS_VERSION="${SOPS_VERSION:-3.9.4}"
AGE_VERSION="${AGE_VERSION:-1.2.1}"

# Intervalo do agente de pull. 5 min é folgado: o gargalo é o build no GitHub, não isto.
PULL_INTERVAL="${PULL_INTERVAL:-5min}"

SKIP_DOCKER=0
CHECK_ONLY=0

# ---------------------------------------------------------------------------
# Saída
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
# 1. Pré-voo
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

# /dev/shm PRECISA ser tmpfs: o deploy.sh decifra os segredos ali e se recusa a
# escrever segredo em disco. Falhar aqui é muito melhor que falhar no primeiro deploy.
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
# 2. Docker CE + plugin compose
# ---------------------------------------------------------------------------
if [ "$SKIP_DOCKER" -eq 1 ]; then
  log "Docker: pulado (--skip-docker)"
elif command -v docker >/dev/null 2>&1 && docker compose version >/dev/null 2>&1; then
  log "Docker: já instalado"
  info "$(docker --version)"
  info "$(docker compose version)"
else
  log "Instalando Docker CE + plugin compose"
  # Repositório oficial da Docker: o docker.io das distros é antigo e não traz o
  # plugin `compose` v2, do qual o deploy.sh depende (`up -d --wait`).
  #
  # A distro NAO e fixa. O host `beta` e Proxmox VE 9.2.5 sobre Debian 13 (trixie), e a
  # unica cloud image ja presente em `local:iso` e a Ubuntu Noble 24.04 — entao a VM
  # pode nascer Ubuntu OU Debian dependendo do que for provisionado. O caminho do
  # repositorio difere (`/linux/ubuntu` vs `/linux/debian`); usar o errado da 404 ou
  # instala pacote de outra distro. Detecta em vez de assumir.
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

# Limite de log do daemon: sem isso um container barulhento enche o disco antes do
# Alloy sequer notar. O compose já define per-service, isto é a rede de segurança.
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
  # install_binary <nome> <url> <destino>
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

# Utilitários que os scripts usam
run apt-get install -y -qq postgresql-client jq curl ca-certificates findutils

# ---------------------------------------------------------------------------
# 4. Usuário de serviço
# ---------------------------------------------------------------------------
log "Usuário de serviço: $SERVICE_USER"
if id "$SERVICE_USER" >/dev/null 2>&1; then
  info "já existe"
else
  run useradd --system --create-home --home-dir "/home/$SERVICE_USER" --shell /usr/sbin/nologin "$SERVICE_USER"
fi
run usermod -aG docker "$SERVICE_USER"

# ---------------------------------------------------------------------------
# 5. Árvore de diretórios + chave age
# ---------------------------------------------------------------------------
log "Diretórios"
for d in "$STATE_DIR" "$BACKUP_DIR" "$SECRETS_DIR"; do
  run mkdir -p "$d"
  run chown "$SERVICE_USER:$SERVICE_USER" "$d"
  info "$d"
done
run chmod 0750 "$BACKUP_DIR" "$SECRETS_DIR"

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

# O deploy.sh roda como root (precisa ler a chave age 0400) mas o compose usa o
# socket do docker; o grupo já foi ajustado acima.

# ---------------------------------------------------------------------------
# 6. Agente de pull (systemd unit + timer)
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
# Roda como root: precisa ler a chave age (0400 root) para decifrar os segredos.
User=root
WorkingDirectory=${PROXMOX_DIR}
Environment=SOPS_AGE_KEY_FILE=${AGE_KEY_FILE}
Environment=NORA_STATE_DIR=${STATE_DIR}
Environment=BACKUP_DIR=${BACKUP_DIR}
ExecStart=${SCRIPT_DIR}/deploy.sh --quiet
TimeoutStartSec=900
# O deploy.sh ja faz rollback por conta propria; nao reiniciar em loop.
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
# Espalha o disparo para nao bater no GHCR no mesmo segundo que todo mundo.
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
# 7. Hardening básico
# ---------------------------------------------------------------------------
log "Hardening"

run apt-get install -y -qq unattended-upgrades
if [ "$CHECK_ONLY" -eq 0 ]; then
  # journald sem limite enche o disco tanto quanto container barulhento.
  mkdir -p /etc/systemd/journald.conf.d
  cat > /etc/systemd/journald.conf.d/nora.conf <<'CONF'
[Journal]
SystemMaxUse=2G
MaxRetentionSec=30day
CONF
  systemctl restart systemd-journald

  cat > /etc/sysctl.d/99-nora.conf <<'CONF'
# Postgres + JVM sob memória apertada: preferir OOM-killar o culpado a travar tudo.
vm.overcommit_memory = 1
vm.swappiness = 10
# Muitas conexões curtas entre containers.
net.core.somaxconn = 1024
net.ipv4.tcp_tw_reuse = 1
CONF
  sysctl --quiet --load /etc/sysctl.d/99-nora.conf || warn "sysctl parcialmente aplicado."
fi

# Nenhuma regra de firewall inbound é necessária: o cloudflared abre conexão de SAÍDA.
# Se você tiver ufw ativo, NÃO precisa liberar 80/443.
info "Sem porta inbound: o tráfego entra pelo Cloudflare Tunnel (saída-only)."

# ---------------------------------------------------------------------------
# Resumo
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
