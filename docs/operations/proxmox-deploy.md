# Runbook — NORA deployment on Proxmox (self-hosted)

> **Audience:** whoever operates the NORA deployment on the Proxmox VM.
>
> **Supersedes** [`azure-deploy.md`](azure-deploy.md), which becomes a historical document.
> The decision is in [ADR 0034](../adr/0034-azure-to-proxmox-migration.md); shutting down what
> remained on Azure is covered in [`azure-decommission.md`](azure-decommission.md).
>
> **Prerequisites:** access to the "beta" Proxmox, a Cloudflare account with the `nora.systems` zone,
> `sops` + `age` on the operator's machine, and the `gh` CLI. **Nothing needs to be brought over from Azure:**
> NORA is an educational project with no production data, so the database starts empty and Flyway creates the
> schema from scratch (see `azure-decommission.md` §"What this runbook does NOT need to do").

> **A single environment, again.** As on Azure, there is **one** live environment. There is no staging. The VM is
> named `nora-prod` from the start — the cosmetic rename that stayed pending on Azure (`dev` = production)
> is not repeated here.

---

## Overview

A Debian VM on Proxmox runs the whole stack with Docker Compose (project `nora`), defined in
[`infra/proxmox/docker-compose.yml`](../../infra/proxmox/docker-compose.yml) — **that file is the
source of truth**; this runbook is how to operate it.

```
Internet ──> Cloudflare edge ──(tunnel, saída-only)──> cloudflared
                                                           │
                                                         caddy            <- retry buffer
                                                     ┌─────┼─────┐           do rolling update
                                                    web   api   admin
                                                           │
                                                        worker (interno)
                                                           │
                                           postgres  +  postgres-platform
```

**No inbound port is opened on the host.** The only published ports are on `127.0.0.1`
(`5432` → postgres, `5433` → postgres-platform) for debugging via `ssh -L`.

Replacement map, for those coming from Azure:

| Azure | Proxmox |
|---|---|
| Container Apps Environment + external ingress + managed cert | `cloudflared` (Tunnel) + `caddy` (Host-based routing) |
| 3 Container Apps (api/worker/web) + `nora-admin` | 4 compose services, same env vars (transcribed from `main.bicep:694-1540`) |
| Postgres Flexible Server B1ms | `pgvector/pgvector:pg16` (`nora`) |
| 2nd Flexible Server (ADR 0022) | `postgres-platform` (`nora_platform`), profile `platform` |
| Key Vault + 3 User-Assigned Managed Identities | SOPS + age (`secrets.env.sops` + `/etc/nora/age.key`) |
| App Insights (`-javaagent`) | `opentelemetry-javaagent.jar` → `otel-collector` → `prometheus` |
| Log Analytics (`appLogsConfiguration`) | `alloy` (Docker socket) → `loki` |
| Workbook / Metrics Explorer | `grafana` at `grafana.<dom>` |
| PITR 7 days | `backup` (hourly pg_dump, 14d retention) + VM snapshot on PBS |
| `deploy-infra.yml` (push, OIDC) | `scripts/deploy.sh` on the host (**PULL**) |

### Blockers before starting

- [ ] **API image republished with the javaagent swapped.** Only changing
      `OTEL_EXPORTER_OTLP_ENDPOINT` **does not work**: the App Insights agent exports to the
      Breeze endpoint from the connection string and **ignores** the OTLP env var. You must replace
      `applicationinsights-agent.jar` with `opentelemetry-javaagent.jar` in
      `services/api/Dockerfile` (lines 16, 22-28, 39) and publish a new tag to GHCR. Without this the
      API starts without error and **emits no telemetry at all**.
- [ ] **`PrometheusHealthSource` in place of `AppInsightsHealthSource`.** The old one does a `GET` on
      `api.applicationinsights.io/v1/apps/{id}/query` with **KQL**. The OTel Collector is write-only and
      does not speak KQL — this is a Java class rewrite, not an env var change.
- [ ] **Verified dump of both databases**, outside Azure (`azure-decommission.md` §1).
- [ ] Supporting files present in `infra/proxmox/`: `caddy/Caddyfile`, `postgres/init/*.sql`,
      `observability/{otel-collector.yaml,prometheus.yml,loki.yaml,config.alloy,grafana/provisioning/}`,
      `backup/run-backup.sh`, `scripts/deploy.sh`.

---

## The 9 self-hosting pitfalls (CATALOGUED)

Same spirit as the 8 Azure for Students pitfalls: each one cost time, and none of them produces an
obvious error. **Read before the first deployment.**

### Pitfall 1 — `?sslmode=require` in the JDBC URL brings the API down at boot

**Symptom:** the API enters a restart loop; the log shows Hikari failing before Flyway:

```
org.postgresql.util.PSQLException: The server does not support SSL.
HikariPool-1 - Exception during pool initialization.
```

**Cause:** the Azure JDBC URL carried `?sslmode=require` because the Flexible Server **requires** TLS. The
official Postgres image starts with **SSL OFF** — the parameter inherited from the Bicep makes the driver abort
the handshake.

**Fix:** the compose `DATASOURCE_URL` **does not have** `sslmode`, on purpose:

```yaml
DATASOURCE_URL: jdbc:postgresql://postgres:5432/nora
```

This is not a security relaxation: the traffic never leaves the `data` bridge, which is `internal: true`. If
someone pastes the Azure URL out of habit, the boot breaks. The same applies to
`PLATFORM_DATASOURCE_URL`, `SPRING_FLYWAY_URL` and `NORA_TELEMETRY_DATASOURCE_URL` as well.

### Pitfall 2 — `initdb` only runs on an EMPTY VOLUME

**Symptom:** the `nora_app` / `nora_telemetry` roles do not exist, the operator panel is empty, or
the RLS flip fails. No error at Postgres boot — it starts up cleanly.

**Cause:** the image's entrypoint only executes `/docker-entrypoint-initdb.d/*` when
`PGDATA` is **empty**. If the `pgdata` volume has already been initialized (a previous `up`, a restore, a
drill), the scripts are **silently ignored**.

**Fix:** treat `postgres/init` as new-volume bootstrap, never as a migration. For an already existing
volume, apply it by hand:

```bash
docker compose -p nora exec -T postgres psql -U nora_admin -d nora < infra/proxmox/postgres/init/01-roles-and-db.sql
```

**Important corollary:** after a `pg_restore`, `DEFAULT PRIVILEGES` do **not** reach the
restored tables (they already existed in the dump). Reapplying the R001 `GRANT`s is mandatory — see
§Restoring the data, step 4.

### Pitfall 3 — `internal: true` cuts off egress for api/web/worker

**Symptom:** the stack comes up **healthy** (all healthchecks green) and, on the first real use,
everything that talks to the outside world times out: LLM analysis, embeddings, sending e-mail via Resend,
OAuth code exchange for the integrations (ADR 0031), Telegram. The log shows `UnknownHostException` or a
connection timeout — not a credential error.

**Cause:** the `internal` and `data` networks are `internal: true`. That does not only block inbound: it blocks
**egress and external DNS resolution** for any container that is **only** on them. The
compose comment ("postgres and worker do not go out on their own") describes the intent for `postgres`; the
`worker`, the `api` and the `web` **need** to go out (OpenAI, Gemini, Resend, token endpoints of the 7
OAuth providers).

**Fix:** give egress — without giving ingress — by also attaching `api`, `web` and `worker` to `edge`:

```yaml
  worker:
    networks: [internal, edge]
  api:
    networks: [internal, data, edge]
  web:
    networks: [internal, edge]
```

Being on `edge` does **not** publish any port: there is no `ports:` on those services and the only inbound
path is still `cloudflared → caddy`. `postgres`, `postgres-platform` and `backup` stay **only**
on `data` — those really do not go out.

Verification (fails before the fix, passes after):

```bash
docker compose -p nora exec worker python -c \
  "import urllib.request;print(urllib.request.urlopen('https://api.openai.com/v1/models',timeout=5).status)"
```

### Pitfall 4 — `NEXT_PUBLIC_*` is baked in at BUILD TIME

**Symptom:** you change `NEXT_PUBLIC_API_BASE_URL` in the `.env`, restart `web`, and the browser keeps
calling the old address. Nothing in the log points to the problem.

**Cause:** Next inlines `NEXT_PUBLIC_*` into the client bundle during `next build`. The runtime env
does **not** change already-compiled JavaScript. The real value is frozen into the image published by
`build-images.yml` (via `--build-arg`).

**Fix:** if the public domain changes, **rebuild the image** with the new build-arg and publish a new
tag. There is no env-var shortcut.

> Since the domain is still `nora.systems`, **the `web` image already in GHCR works without a rebuild**.
> This only becomes a problem if someone tries to validate the stack on a test domain.

### Pitfall 5 — an empty `CF_ACCESS_AUD` makes the admin FAIL-OPEN

**Symptom:** none. `admin.<dom>` responds normally. The ADR 0025 Tier 2 validation simply
**does not happen**.

**Cause:** `apps/admin/src/lib/access.ts` degrades to "edge-only" when `CF_ACCESS_TEAM_DOMAIN` or
`CF_ACCESS_AUD` are empty — **fail-OPEN, silently**. In production on Azure this has been active for
months: `CF_ACCESS_AUD` was registered as a **Secret** but is read as `vars.` in the workflows, so it
arrives empty (see `environment-secrets.md` §5.1).

**Fix (already in the compose):** both variables are mandatory with `:?` — without them the `admin` container
**does not start**:

```yaml
CF_ACCESS_TEAM_DOMAIN: ${CF_ACCESS_TEAM_DOMAIN:?... sem ele access.ts faz fail-open}
CF_ACCESS_AUD:         ${CF_ACCESS_AUD:?... sem ele access.ts faz fail-open}
```

Trading silent fail-open for noisy fail-closed is the point. Check after the deployment:

```bash
docker compose -p nora --profile platform exec admin printenv CF_ACCESS_AUD
```

### Pitfall 6 — Loki retention deletes NOTHING without a compactor

**Symptom:** the `loki_data` volume grows until it fills the VM disk. When the disk fills up, **Postgres
stops accepting writes** — log loss turns into data loss.

**Cause:** configuring `limits_config.retention_period` **is not enough**. In Loki, the thing that deletes is the
**compactor**, and it has to be explicitly turned on with `retention_enabled: true`. Without that, retention
is just a query filter: the chunks stay on disk forever.

**Fix:** in `observability/loki.yaml`:

```yaml
limits_config:
  retention_period: 720h          # 30d, alinhado ao Prometheus
compactor:
  working_directory: /loki/compactor
  retention_enabled: true          # <- sem esta linha, nada é apagado
  delete_request_store: filesystem
```

A disk alarm (Prometheus with `node_exporter` covers this) is mandatory regardless — the
compactor runs in cycles and does not protect against a spike.

### Pitfall 7 — the ~45s window of 502s during a rolling update

See §[Rolling update](#rolling-update). It is the most visible consequence of the migration and is recorded
as a loss in ADR 0034 §Availability — **it is not a bug, it is the cost of trading Container Apps for
Compose.**

### Pitfall 8 — the THREE RLS roles (omitting `nora_telemetry` zeroes out the panel SILENTLY)

**Symptom:** the operator's telemetry panel shows **0 rows**. No error, no 500, no log.

**Cause:** the RLS from ADRs 0026/0028 depends on **three** roles, not two:

| Role | Flag | Use |
|---|---|---|
| `nora_app` | `NOBYPASSRLS` | application runtime — sees only the GUC's tenant |
| `nora_telemetry` | **`BYPASSRLS`** | operator panel — needs to cross tenants |
| admin / owner | schema owner | Flyway / DDL |

If `NORA_TELEMETRY_DATASOURCE_*` is not filled in, the API falls back to the runtime datasource
(`nora_app`), which is `NOBYPASSRLS`: cross-tenant queries return **zero rows with no error**.
Silent fail-closed — the most expensive failure mode to diagnose.

**Fix:** provision all three at bootstrap (pitfall 2) and fill in all three variables. Check:

```bash
docker compose -p nora exec postgres psql -U nora_admin -d nora -c \
  "select rolname, rolbypassrls from pg_roles where rolname in ('nora_app','nora_telemetry','nora_admin');"
```

Expected: `nora_app` = `f`, `nora_telemetry` = `t`.

### Pitfall 9 — the App Insights `-javaagent` ignores the OTLP env var

**Symptom:** you point `OTEL_EXPORTER_OTLP_ENDPOINT` at the local collector, the API starts, the
healthcheck passes — and Prometheus/Grafana end up **with no data at all from the API**.

**Cause:** `JAVA_TOOL_OPTIONS` loads `-javaagent:/app/applicationinsights-agent.jar`
(`services/api/Dockerfile:39`). That agent exports to the **Breeze** endpoint of the
`APPLICATIONINSIGHTS_CONNECTION_STRING` and **does not implement** the generic OTLP exporter. With an empty
connection string it becomes a silent no-op — which is exactly what looks like "working
fine".

**Fix:** swap the JAR for `opentelemetry-javaagent.jar` and **republish the image**. That is why it is the first
item in the blockers.

---

## First deployment from scratch

### 1. Provision the VM on Proxmox

#### The `beta` host, as it really is

Surveyed over SSH on 2026-08-07 (`ssh beta`, entry in `~/.ssh/config` → `142.132.199.184`,
`root`, key `hetzner-admin-ed25519`). These are not assumptions:

| | |
|---|---|
| Hypervisor | Proxmox VE **9.2.5**, kernel 7.0.14-8-pve, on **Debian 13 (trixie)** |
| CPU | AMD Ryzen 9 5950X — 16 cores / **32 threads** |
| RAM | **125 GB** total, ~49 GB available (the `windows11-beta` VM alone reserves 64 GB) |
| Storage | `local-lvm` (LVM-thin) with **6.8 TB free** (2.6% used); `local` (dir) with 48 GB |
| VM bridge | `vmbr1` → `10.10.1.0/24`, gateway `10.10.1.1` |
| Egress | `MASQUERADE` from `10.10.1.0/24` to `enp7s0`, `ip_forward=1` |
| Inbound | **none** — the VMs have no public IP |
| Existing VMs | 100 `windows11-beta`, 101 `ayla`, 102 `yara`, 103 `anglis`, 104 `edge`, 105 `passabola` |
| Next free VMID | **106** |
| Image already available | `local:iso/noble-server-cloudimg-amd64.img` (Ubuntu 24.04) |

> **The topology validates the design, not the other way around.** The VMs only have NAT egress and zero inbound
> from the internet. The Cloudflare Tunnel was not chosen for convenience — it is the **only** way to publish
> from `vmbr1` without touching the host firewall or port forwarding. If someone one day proposes
> "just open 443 directly", that means changing the NAT of the hypervisor that serves five other VMs.

#### Profile of the new VM

Sized from the compose limits (api 2 vCPU/2.5 Gi, web 2/2 Gi, worker 1/1.5 Gi, admin
0.5/0.5 Gi, plus the two Postgres instances and the observability stack):

| Item | Value | Note |
|---|---|---|
| VMID / Name | `106` / `nora-prod` | no `-dev`; the Azure naming mistake is not repeated |
| OS | Ubuntu 24.04 (cloudimg already in `local:iso`) or Debian 13 | `bootstrap-host.sh` detects the distro and picks the right Docker repo |
| vCPU | 6, type `host` | plenty of headroom: the host has 32 threads |
| RAM | 16 GB, **no ballooning** | fits in the ~49 GB free; ballooning + Postgres is unpredictable OOM |
| Disk | 100 GB on `local-lvm`, `virtio-scsi-single`, **Discard** + **SSD emulation** | the thin pool has 6.8 TB; discard keeps it honest |
| Network | `virtio`, bridge **`vmbr1`**, static IP **`10.10.1.30/24`**, gw `10.10.1.1` | follow the pattern of the existing VMs (`.21` yara, `.22` anglis, `.23` passabola) |
| DNS | `1.1.1.1 8.8.8.8` | same as the other VMs |
| Boot | QEMU Guest Agent **enabled** | required for a consistent snapshot |
| Protection | `Start at boot: yes`, `Protection: yes` | prevents accidental destruction |

```bash
# no host beta, como root
qm create 106 --name nora-prod --ostype l26 \
  --cores 6 --cpu host --memory 16384 --balloon 0 \
  --net0 virtio,bridge=vmbr1 --agent enabled=1 \
  --scsihw virtio-scsi-single --onboot 1 --protection 1
qm importdisk 106 /var/lib/vz/template/iso/noble-server-cloudimg-amd64.img local-lvm
qm set 106 --scsi0 local-lvm:vm-106-disk-0,discard=on,iothread=1,ssd=1
qm resize 106 scsi0 100G
qm set 106 --ide2 local-lvm:cloudinit --boot order=scsi0 --serial0 socket --vga serial0
qm set 106 --ipconfig0 ip=10.10.1.30/24,gw=10.10.1.1 --nameserver "1.1.1.1 8.8.8.8"
qm set 106 --ciuser nora --sshkeys ~/.ssh/authorized_keys
qm start 106
```

Then add it to your local `~/.ssh/config`, following the pattern of the other VMs — note that they are
reachable from the host, not from the internet:

```
Host nora-prod
  HostName 10.10.1.30
  User nora
  ProxyJump beta
  IdentityFile ~/.ssh/hetzner-admin-ed25519
```

#### Hypervisor backup — does not exist yet, and needs to be created

**There is no Proxmox Backup Server on this host.** What exists is a local `vzdump` job called
`ayla-daily`, and it covers **only VM 101**:

```
vzdump: ayla-daily
  storage local · mode snapshot · schedule 03:30
  prune-backups keep-daily=7,keep-weekly=4
  vmid 101
```

A new VM **does not join that job automatically**. Without creating an equivalent, `nora-prod` is left
with no hypervisor backup at all — and then the hourly `pg_dump` of the `backup` service becomes the only line
of defense, which covers loss of *data* but not loss of the *VM*.

Create the job (Datacenter → Backup → Add, or by editing `/etc/pve/jobs.cfg`), with the same policy:

```
vzdump: nora-daily
  storage local
  mode snapshot
  schedule 03:30
  prune-backups keep-daily=7,keep-weekly=4
  vmid 106
  notes-template NORA prod - backup automatico {{guestname}}
```

> Mind the space: the `local` storage has **48 GB free** and already holds ~5 GB per `ayla` backup.
> A 100 GB snapshot of `nora-prod` will not fit there. Either point this job at `local-lvm`/a dedicated
> storage, or shrink the VM's disk, or add a Proxmox Backup Server. **Decide this before
> go-live, not after the first backup fails silently.**

### 2. Host bootstrap

```bash
ssh nora-prod

# --- base ---
sudo apt-get update && sudo apt-get -y upgrade
sudo apt-get -y install ca-certificates curl gnupg git age unattended-upgrades \
                        postgresql-client-16 jq
sudo dpkg-reconfigure -plow unattended-upgrades   # security updates automáticos

# --- Docker (repo oficial; o docker.io do Debian é velho demais pro compose v2) ---
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL https://download.docker.com/linux/debian/gpg | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/debian $(. /etc/os-release && echo $VERSION_CODENAME) stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
sudo apt-get -y install docker-ce docker-ce-cli containerd.io \
                        docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER" && newgrp docker

# --- diretórios ---
sudo mkdir -p /etc/nora /srv/nora/backups /opt/nora
sudo chown "$USER":"$USER" /opt/nora
sudo chmod 700 /srv/nora/backups

# --- SOPS ---
SOPS_VER=3.9.4
curl -fsSLo /tmp/sops "https://github.com/getsops/sops/releases/download/v${SOPS_VER}/sops-v${SOPS_VER}.linux.amd64"
sudo install -m 0755 /tmp/sops /usr/local/bin/sops

# --- firewall: nada de inbound além de SSH da LAN ---
sudo apt-get -y install ufw
sudo ufw default deny incoming && sudo ufw default allow outgoing
sudo ufw allow from 192.168.0.0/16 to any port 22 proto tcp
sudo ufw --force enable

# --- código ---
git clone https://github.com/sf0rzin/nora.git /opt/nora
```

> `ufw` is redundant with "don't publish ports", and that is exactly why it is worth it: it protects against the
> `-p 0.0.0.0:...` that someone will add by mistake while debugging at 2 a.m.

Limit journald and the Docker log (the compose already sets `max-size: 20m` / `max-file: 5` per
container, but the daemon needs the default too):

```bash
echo '{ "log-driver": "json-file", "log-opts": { "max-size": "20m", "max-file": "5" } }' | \
  sudo tee /etc/docker/daemon.json
sudo systemctl restart docker
```

### 3. Generate the age key and encrypt the secrets

**On the operator's machine** (not on the host — the private key is generated once and copied):

```bash
age-keygen -o age.key
# Public key: age1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Keep the **private key** in two offline places (password manager + physical media). It is
the only material that decrypts everything — losing it means recreating **all** the secrets.

Install it on the host:

```bash
scp age.key nora-prod:/tmp/age.key
ssh nora-prod 'sudo mv /tmp/age.key /etc/nora/age.key && \
               sudo chown root:root /etc/nora/age.key && \
               sudo chmod 400 /etc/nora/age.key'
```

Declare the public key in `infra/proxmox/.sops.yaml` (version-controlled):

```yaml
creation_rules:
  - path_regex: secrets\.env\.sops$
    age: age1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Assemble the secrets file from the stack's `.env.example` and encrypt it:

```bash
cd infra/proxmox
cp secrets.env.example secrets.env      # NUNCA commitar este intermediário
$EDITOR secrets.env                     # preencher os valores
sops --encrypt secrets.env > secrets.env.sops
shred -u secrets.env
git add .sops.yaml secrets.env.sops && git commit -m "chore(infra): segredos cifrados (SOPS+age)"
```

**Two planes, not one.** Only what must not leak is encrypted; the rest stays in the clear and
readable in `env.defaults`. Encrypting non-secret configuration turns every tag change into an
edit of an encrypted file with an unreadable diff — and makes public values get "lost" (that is
exactly how `CF_ACCESS_AUD` disappeared on Azure). The canonical template is
`infra/proxmox/secrets.env.example`; the complete map of the compose variables is the
`.env.example` next to it.

**Inventory of `secrets.env`** — secrets only (see ADR 0034 §Secrets: the number **increases**
relative to Key Vault, because without managed identity each `secretRef` becomes a static value):

```dotenv
# Borda
CLOUDFLARE_TUNNEL_TOKEN=

# Dados
POSTGRES_ADMIN_PASSWORD=            # openssl rand -base64 32
POSTGRES_PLATFORM_ADMIN_PASSWORD=   # OUTRO valor (blast radius, ADR 0022)

# RLS (os TRÊS roles — armadilha 8). Fonte única: alimentam o ALTER ROLE e o .env da API.
NORA_APP_PASSWORD=                  # openssl rand -hex 24
RLS_TELEMETRY_PASSWORD=
NORA_TELEMETRY_DATASOURCE_PASSWORD= # mesmo valor de RLS_TELEMETRY_PASSWORD
SPRING_FLYWAY_PASSWORD=

# Auth
JWT_SECRET=                         # openssl rand -base64 48 (min 32 chars)

# Providers / e-mail
OPENAI_API_KEY=
DEEPSEEK_API_KEY=
GEMINI_API_KEY=
RESEND_API_KEY=

# Integrações (ADR 0031) — só os SECRETS; os *_OAUTH_CLIENT_ID são públicos
NORA_INTEGRATIONS_ENC_KEY=          # AES-256-GCM, 32 bytes BASE64 — ver aviso abaixo
NORA_INTEGRATIONS_STATE_SECRET=     # openssl rand -hex 32
GOOGLE_OAUTH_CLIENT_SECRET= ...     # (slack, github, notion, todoist, linear, ms)
NORA_TELEGRAM_BOT_TOKEN=
TRELLO_API_KEY=

# Control plane (ADR 0022/0023/0024) — dois valores DISTINTOS
NORA_PLATFORM_INTERNAL_TOKEN=       # openssl rand -hex 32
NORA_PLATFORM_ADMIN_TOKEN=          # OUTRO openssl rand -hex 32

# Observabilidade
GRAFANA_ADMIN_PASSWORD=             # openssl rand -base64 24
```

**Stays OUTSIDE the encrypted file** (non-secret plane, `env.defaults`): `NORA_PUBLIC_DOMAIN`,
`NORA_ENV`, `POSTGRES_ADMIN_USER`, `DATASOURCE_USERNAME`, the JDBC URLs, `NORA_RLS_ENFORCE`,
`NORA_EMAIL_FROM`, the `*_OAUTH_CLIENT_ID`s, `NORA_PLATFORM_ENABLED`, **`CF_ACCESS_AUD` and
`CF_ACCESS_TEAM_DOMAIN`** (public identifiers — pitfall 5) and the rollout tags
(`API_TAG`/`WORKER_TAG`/`WEB_TAG`/`ADMIN_TAG`, which `deploy.sh --tag` overrides).

> **No value may be the string `unset`.** Inherited from the Bicep, which wrote `'unset'` into the
> Key Vault when a secret came in empty. Empty is safe; `unset` is fatal.

> **`NORA_INTEGRATIONS_ENC_KEY`:** never put the string `unset` nor a non-base64 value there. The
> `TokenCipher` validates base64 and **brings the boot down** — that was the 2026-06-11 incident with
> the KV reference. Generate it with `openssl rand -base64 32`.

Age key rotation (ADR 0016 Gap 7 policy, now without Key Vault):

```bash
# adiciona a nova pública em .sops.yaml, depois:
sops updatekeys secrets.env.sops
```

### 4. Cloudflare Tunnel + Access Applications

The compose's `TUNNEL_TOKEN` implies a **remotely managed tunnel**: the hostname configuration
lives in the Cloudflare dashboard/API, not in a local `config.yml`.

1. **Create the tunnel** (Zero Trust → Networks → Tunnels → Create a tunnel → *Cloudflared*), named
   `nora-prod`. Copy the **connector token** into `CLOUDFLARE_TUNNEL_TOKEN`.

2. **Public hostnames** — all four point to the **same** service, because the one doing Host-based
   routing is Caddy:

   | Hostname | Service |
   |---|---|
   | `nora.systems` | `http://caddy:80` |
   | `www.nora.systems` | `http://caddy:80` |
   | `api.nora.systems` | `http://caddy:80` |
   | `admin.nora.systems` | `http://caddy:80` |
   | `grafana.nora.systems` | `http://caddy:80` |

   Adding a hostname automatically creates the **proxied** CNAME to
   `<tunnel-id>.cfargotunnel.com`. **Do not create the public hostnames yet** if you want to verify
   before the DNS cutover — see §Verify.

3. **Access Applications** (Zero Trust → Access → Applications):

   | Application | Policy | Note |
   |---|---|---|
   | `admin.nora.systems` | e-mail allowlist + OTP/SSO | already exists (ADR 0025); reuse the **AUD** |
   | `grafana.nora.systems` | e-mail allowlist + OTP/SSO | **NEW** — Grafana now has a public route; without Access it is exposed behind a single password |

   The apex, `www` and `api` stay **public** (they are the product).

4. **`CF_ACCESS_AUD`** = the AUD tag of the admin Access App. If you are reusing the one from Azure, the
   value does not change. Register it in `secrets.env` (pitfall 5).

> **Caveat inherited from ADR 0025:** the `cloudflare-setup.yml` workflow owns the Access App/Policy/IdP
> and **must run without `admin_hostname`** — with that parameter, it overwrites the tunnel's CNAME.

### 5. First deployment

```bash
cd /opt/nora/infra/proxmox
./scripts/deploy.sh --platform --tag sha-xxxxxxx
```

`deploy.sh` does, in order: decrypts `secrets.env.sops` (SOPS + age) into a `.env` on
**tmpfs** (`/dev/shm` — it **refuses** to run if `/dev/shm` is not tmpfs, so as not to let a
secret touch the disk) → `docker compose pull` → `up -d --wait --no-deps` **service by service**,
in dependency order, honoring the healthcheck → on a health failure, **automatic rollback**
to the previous tag. The `.env` is overwritten and deleted in the EXIT trap.

Flags that matter:

| Flag | Use |
|---|---|
| `--tag sha-xxxxxxx` | tag of the app images to bring up |
| `--service api,web` | only these services (repeatable or a list) |
| `--if-changed` | only deploys if the remote digest differs — this is what the systemd timer calls |
| `--rollback` | returns the selected services to the previous tag recorded in the state |
| `--no-rollback` | on a health failure, leaves it broken for debugging |
| `--dry-run` | shows what it would do |

The rollout state (current tag, previous tag, digest, timestamp) lives in
`/srv/nora/state/deploy-state.env` — it is what makes rollback possible without your having
written anything down.

Manually, when you need to debug the script:

```bash
sops --decrypt --input-type dotenv --output-type dotenv secrets.env.sops > /dev/shm/nora.env
chmod 600 /dev/shm/nora.env
docker compose -p nora --env-file ./env.defaults --env-file /dev/shm/nora.env \
  --profile platform up -d --wait
shred -u /dev/shm/nora.env
```

> **Two configuration planes, on purpose.** `env.defaults` (non-secret: domain, image
> tags, toggles, retention) and `secrets.env.sops` (only what must not leak). Compose
> accepts a repeated `--env-file` and the **last one wins**. Encrypting the image tags would make every
> rollout an edit of an encrypted file with an unreadable diff — see the header of
> `secrets.env.example`.

> **Do not** bring everything up at once if you are going to restore data. See the next step.

### 6. Restore the data coming from Azure

**Mandatory order.** If the API comes up before the restore, Flyway creates a virgin schema and
`pg_restore` collides with it.

```bash
# 1) SÓ os bancos. O initdb roda aqui (volume vazio) e cria os roles do RLS.
docker compose -p nora --env-file ./env.defaults --env-file /dev/shm/nora.env \
  up -d postgres postgres-platform
docker compose -p nora exec postgres pg_isready -U nora_admin -d nora

# 2) Copiar os dumps (formato custom, -Fc — ver azure-decommission.md §1)
docker cp nora.dump          nora-postgres:/tmp/nora.dump
docker cp nora_platform.dump nora-postgres-platform:/tmp/nora_platform.dump

# 3) Restaurar. --no-owner/--no-acl porque os roles do Azure (azure_pg_admin,
#    azuresu) não existem aqui e fariam o restore cuspir erro em cada objeto.
docker compose -p nora exec postgres \
  pg_restore -U nora_admin -d nora --no-owner --no-acl --exit-on-error -v /tmp/nora.dump
docker compose -p nora exec postgres-platform \
  pg_restore -U nora_admin -d nora_platform --no-owner --no-acl --exit-on-error -v /tmp/nora_platform.dump
```

**4) Reapply the GRANTs (mandatory — pitfall 2).** `ALTER DEFAULT PRIVILEGES` only applies to
objects created **afterwards**; the restored tables already existed in the dump and arrive **without** the
`nora_app` / `nora_telemetry` permissions:

```bash
docker compose -p nora exec -T postgres psql -U nora_admin -d nora \
  < ../../services/api/src/main/resources/db/operational/R001__provision_app_roles.sql
```

**5) Check before bringing up the rest:**

```bash
docker compose -p nora exec postgres psql -U nora_admin -d nora -c "
  select count(*) as tenants from tenants;
  select count(*) as meetings from meetings;
  select count(*) as transcripts from transcripts;
  select max(installed_rank) as flyway_rank, max(version) as flyway_version
    from flyway_schema_history where success;"
```

The `flyway_schema_history` **comes in the dump**. If the version matches the repo's, the Flyway run at boot will not
reapply anything — that is how you know the restore is intact. If it is **behind**, Flyway will
migrate on the API's first boot (expected); if it is **ahead**, stop: the dump is from code newer
than the image.

```bash
# 6) Agora sim, a stack inteira.
./scripts/deploy.sh --platform --tag sha-xxxxxxx
```

> **Only do this if you are recovering from a backup.** On the first deployment the database starts empty and
> there is nothing to restore. When it does apply, steps 1 through 5 above are automated in
> `./scripts/restore-into-proxmox.sh --from-dir <dir-de-backup> --sops` (the dumps that the `backup`
> service generates in `$BACKUP_DIR`), which creates the roles before the data, restores with `--no-owner
> --no-privileges` and applies R001 **afterwards**. Use the script; the manual sequence above is what it
> does, for when something fails midway.

### 7. Verify

**Before touching DNS.** Caddy does not publish a port, so verification is from inside the `edge`
network, with the right Host header:

```bash
for h in nora.systems api.nora.systems admin.nora.systems grafana.nora.systems; do
  printf '%-26s ' "$h"
  docker run --rm --network nora_edge curlimages/curl:8.11.1 \
    -s -o /dev/null -w '%{http_code}\n' -H "Host: $h" http://caddy/
done
```

Expected: `200` on the apex, `200` on the api (or `401`/`404` depending on the root route), `302`/`403` on the admin
(Access is not in the path here — the gate is at the edge), `200`/`302` on grafana.

Health of each service:

```bash
docker compose -p nora ps --format 'table {{.Service}}\t{{.Status}}'
docker compose -p nora exec api    wget -qO- http://localhost:8080/actuator/health
docker compose -p nora exec worker python -c \
  "import urllib.request;print(urllib.request.urlopen('http://localhost:8001/healthz').read())"
```

Egress (pitfall 3) and observability:

```bash
docker compose -p nora exec worker python -c \
  "import urllib.request;print(urllib.request.urlopen('https://api.openai.com/v1/models',timeout=5).status)"

# a API está mesmo emitindo? (falha se o javaagent não foi trocado — armadilha 9)
docker compose -p nora exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=up{job="nora-api"}' | jq '.data.result'

# logs chegando no Loki?
docker compose -p nora exec loki wget -qO- \
  'http://localhost:3100/loki/api/v1/label/container/values' | jq
```

RLS (pitfall 8) and secrets:

```bash
docker compose -p nora exec postgres psql -U nora_admin -d nora -c \
  "select rolname, rolbypassrls from pg_roles where rolname like 'nora_%';"
docker compose -p nora --profile platform exec admin printenv CF_ACCESS_AUD   # não pode ser vazio
```

**Only after that** create the public hostnames on the tunnel (§4.2) — that is the DNS cutover. The
complete cutover order, including what to do on Azure before and after, is in
[`azure-decommission.md`](azure-decommission.md).

---

## Common operations

### Rolling out a new version

Immutable `sha-<short>` tags are the mechanism. `latest` is fine for bootstrap; it is **not** fine for
rollout, because it gives no rollback target.

```bash
cd /opt/nora && git pull
./infra/proxmox/scripts/deploy.sh --platform --tag sha-a1b2c3d
```

The previous tag **does not need to be written down**: `deploy.sh` records `<SVC>_PREV_TAG` in
`/srv/nora/state/deploy-state.env` before switching. Just one service:

```bash
./infra/proxmox/scripts/deploy.sh --service api --tag sha-a1b2c3d
```

<a id="rolling-update"></a>

### Rolling update — the ~45s window

**There is no real rolling update in this stack.** `docker compose up -d` **tears down the old
container before bringing up the new one** — there is no readiness gate like the one
`activeRevisionsMode: Single` gave in Container Apps. The Spring boot with Flyway takes ~30s (the `api`
healthcheck uses `start_period: 45s` and `retries: 12`, inherited from the Bicep's `failureThreshold: 12`).

Practical result: **~45s per API deployment during which the origin does not respond.**

Mitigation — not elimination — in `caddy/Caddyfile`:

```caddyfile
api.{$NORA_PUBLIC_DOMAIN} {
    reverse_proxy api:8080 {
        lb_try_duration 60s      # segura e reenvia enquanto a origem volta
        lb_try_interval 500ms
        fail_duration    0s      # não marca a única origem como down
        health_uri       /actuator/health
        health_interval  5s
    }
}
```

What this does **not** solve, and you need to know:

- requests that exceed `lb_try_duration` **fail** (504 instead of 502 — still an error);
- connections **in flight** at the moment of the `stop` are cut;
- **SSE / chat streaming** breaks: the retry buffer does not rebuild an already-started stream;
- long uploads are lost and have to be redone.

**Recommended practice:** deploy the API in a low-traffic window; deploy `web`/`admin` (short boot)
at any time. A `worker`-only deployment does not affect the user — the API degrades with a
controlled error.

Deploying a single service:

```bash
./infra/proxmox/scripts/deploy.sh --service web --tag sha-a1b2c3d
```

### View logs

```bash
docker compose -p nora logs -f --tail=200 api
# histórico e busca: Grafana → Explore → Loki
#   {container="nora-api"} |= "ERROR"
```

### Connect to Postgres

No network exposure: the ports are on `127.0.0.1`.

```bash
ssh -L 15432:127.0.0.1:5432 nora-prod          # primário
ssh -L 15433:127.0.0.1:5433 nora-prod          # plataforma
psql "host=127.0.0.1 port=15432 dbname=nora user=nora_admin"     # SEM sslmode (armadilha 1)
```

Directly on the host: `docker compose -p nora exec postgres psql -U nora_admin -d nora`.

### On-demand manual backup

The `backup` service runs hourly (`BACKUP_INTERVAL_SECONDS`, retention
`BACKUP_RETENTION_DAYS=14`) and writes to `/srv/nora/backups`. To force it now:

```bash
docker compose -p nora exec backup /usr/local/bin/run-backup.sh --once
ls -lh /srv/nora/backups | tail
```

> **A backup on the same host is not a backup.** Sync `/srv/nora/backups` off the VM (the Proxmox
> Backup Server job covers the whole disk; an `rclone`/`rsync` to an external destination covers the
> "Proxmox caught fire" case). As long as the dumps only exist on the VM, the real RPO for a host loss is
> **the last PBS snapshot**, not the last hour.

### RLS enforce flip

Unchanged in design (ADR 0026/0028). What changes is the endpoint: where the
[`rls-cutover-runbook.md`](rls-cutover-runbook.md) says
`nora-pg-dev-wgl3a3.postgres.database.azure.com`, read `postgres` (inside the `data` network), and the
role provisioning runs through the local `psql` instead of the `rls-cutover.yml` workflow — which
depended on a runner firewall rule and OIDC, and no longer applies.

---

## Rollback

Three levels. Choose based on what broke, not on what is fastest.

### Level 1 — application rollback (image)

For a code bug. **Seconds to a minute.**

```bash
./infra/proxmox/scripts/deploy.sh --service api --rollback
```

`--rollback` reads `API_PREV_TAG` from `/srv/nora/state/deploy-state.env` — written
automatically during the previous rollout. That is why `latest` is **forbidden** in a rollout: without an
immutable tag there is no rollback target, and the state would have nothing to record.

If `up --wait` itself fails on health, `deploy.sh` **already does this rollback by itself**
(unless `--no-rollback`).

### Level 2 — schema rollback (there is NO automatic one)

> **Warning.** Flyway is **forward-only** (`standards.md` §6: "a migration is never edited after being
> applied"). Rolling the image back **does not roll the schema back**. If the new migration is destructive
> (`DROP COLUMN`, a lossy type change), the old image will find a database it does not
> understand — and the Level 1 rollback **does not fix it**; it may make things worse.

In that case, the rollback is a **data restore**:

```bash
docker compose -p nora stop api web admin worker
docker compose -p nora exec postgres \
  psql -U nora_admin -d postgres -c 'drop database nora; create database nora;'
docker compose -p nora exec postgres \
  pg_restore -U nora_admin -d nora --no-owner --no-acl --exit-on-error /backups/nora-<TS>.dump
docker compose -p nora exec -T postgres psql -U nora_admin -d nora \
  < services/api/src/main/resources/db/operational/R001__provision_app_roles.sql
# só então: API_TAG de volta para a anterior + deploy
```

Everything written since the dump is lost (**up to 1 hour** — the real RPO of this stack, ADR 0034
§Availability). Every destructive migration must therefore be preceded by a manual backup.

### Level 3 — host rollback (Proxmox snapshot)

Only for a **host** breakage (kernel upgrade, corrupted Docker, disk). Reverting the snapshot
**discards all data written since it was taken** — including the dumps in `/srv/nora/backups`.

```
Proxmox → nora-prod → Snapshots → selecionar → Rollback
```

**Before reverting, copy `/srv/nora/backups` off the VM.** Without that you trade a host problem
for data loss.

---

## Restore drill

**Quarterly**, inherited from ADR 0016 Gap 3. What changes: previously the RTO was guaranteed by the Flexible
Server's PITR; now it is **a manual procedure**. An RTO that is never measured is a guess.

1. **Clone** `nora-prod` on Proxmox (Full Clone) as `nora-drill`.
2. **Isolate before powering on** — two precautions, in this order:
   - move the NIC to a bridge with no uplink (or an isolated vlan);
   - **remove `CLOUDFLARE_TUNNEL_TOKEN` from the clone's `.env`.** If the clone comes up with the token, it
     registers a **second connector on the same tunnel** and Cloudflare starts balancing production
     traffic between the real VM and the drill. This is the most dangerous mistake in the procedure.
3. **Start the clock here.** Delete the volumes and restore from the most recent backup following
   §Restore the data (steps 1 through 5).
4. **Smoke test** with the §Verify checklist: `tenants`/`meetings`/`transcripts` counts matching
   production, `flyway_schema_history` at the expected version, the three roles correct, login
   working.
5. **Stop the clock. Record** in the table below: date, dump size, measured RTO, and what
   went wrong (there is always something).
6. **Destroy** the clone.

| Date | Dump | Measured RTO | Findings |
|---|---|---|---|
| _(pending — first drill within 30 days after go-live)_ | | | |

> If the measured RTO exceeds 2h (the ADR 0016 Gap 3 target), either the target is wrong or the procedure
> is. **Fix one of the two the same day** — do not leave the divergence documented and alive.

---

## History

| Date | Change |
|---|---|
| 2026-08-07 | v1.0 — runbook created together with ADR 0034. Supersedes `azure-deploy.md`. Covers VM provisioning, bootstrap, SOPS+age, Cloudflare Tunnel/Access, first deployment, restore coming from Azure, verification, the 9 self-hosting pitfalls, 3-level rollback and the quarterly restore drill. |
| 2026-08-07 | v1.1 — reconciliation with the actual files in `infra/proxmox/`: correct names (`postgres/init/01-roles-and-db.sql`, `R001__provision_app_roles.sql`), the real `deploy.sh` flags (`--platform`, `--tag`, `--service`, `--rollback`, `--if-changed`) in place of `--profile platform` and manual editing of `API_TAG`, rollout state in `/srv/nora/state/deploy-state.env`, tmpfs on `/dev/shm`, and separation of the two configuration planes (`env.defaults` vs. `secrets.env.sops`) in the secrets inventory. Reference to `restore-into-proxmox.sh`. |
