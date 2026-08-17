# Runbook — NORA deployment on the host (self-hosted)

> **Audience:** whoever operates the NORA deployment on the production host.
>
> **Supersedes** the historical Azure-era runbook (deleted; ADR 0036 — Azure is gone, not being
> decommissioned, so there is no shutdown procedure to link to).
> The migration decision is in [ADR 0034](../adr/0034-azure-to-proxmox-migration.md); the substrate
> correction — a single bare-metal host, not a VM on a hypervisor — is in
> [ADR 0036](../adr/0036-substrate-is-a-single-bare-metal-host.md).
>
> **Prerequisites:** access to the production host, a Cloudflare account with the `nora.systems` zone,
> `sops` + `age` on the operator's machine, and the `gh` CLI. **Nothing needs to be brought over from Azure:**
> the subscription is gone and there was never an export, so a fresh deployment starts with an empty
> database and Flyway creates the schema from scratch (ADR 0036 §"Azure is gone, not being decommissioned").

> **A single environment.** There is **one** live environment. There is no staging.

## Overview

A single bare-metal Ubuntu host, no hypervisor, runs the whole stack with Docker Compose (project
`nora`), defined in [`infra/host/docker-compose.yml`](../../infra/host/docker-compose.yml) — **that
file is the source of truth**; this runbook is how to operate it.

```
Internet ──> Cloudflare edge ──(tunnel, egress-only)──> cloudflared
                                                           │
                                                         caddy            <- retry buffer
                                                     ┌─────┼─────┐           for the rolling update
                                                    web   api   admin
                                                           │
                                                        worker (internal)
                                                           │
                                           postgres  +  postgres-platform
```

**No inbound port is opened on the host by the stack.** The only ports the compose publishes are
on `127.0.0.1` (`5432` → postgres, `5433` → postgres-platform) for debugging via `ssh -L`.

The qualifier is load-bearing and this document used to omit it: **sshd's port 22 was open to the
internet when this was measured on 2026-08-11** — `ufw` inactive, `iptables -S INPUT` policy
`ACCEPT`, no rule naming port 22 — and the firewall block further down explains why it has been
left that way. "The tunnel is the only ingress" is true of HTTP and of nothing else.

Replacement map, for those coming from Azure:

| Azure | Self-hosted |
|---|---|
| Container Apps Environment + external ingress + managed cert | `cloudflared` (Tunnel) + `caddy` (Host-based routing) |
| 3 Container Apps (api/worker/web) + `nora-admin` | 4 compose services, same env vars (transcribed from the deleted `main.bicep:694-1540`) |
| Postgres Flexible Server B1ms | `pgvector/pgvector:pg16` (`nora`) |
| 2nd Flexible Server (ADR 0022) | `postgres-platform` (`nora_platform`), profile `platform` |
| Key Vault + 3 User-Assigned Managed Identities | SOPS + age (`secrets.env.sops` + `/etc/nora/age.key`) |
| App Insights (`-javaagent`) | `opentelemetry-javaagent.jar` → `otel-collector` → `prometheus` |
| Log Analytics (`appLogsConfiguration`) | `alloy` (Docker socket) → `loki` |
| Workbook / Metrics Explorer | `grafana` at `grafana.<dom>` |
| PITR 7 days | `backup` (hourly pg_dump, 14d retention) — the only leg; there is no off-host or hypervisor copy (ADR 0036) |
| `deploy-infra.yml` (push, OIDC) | `scripts/deploy.sh` on the host (**PULL**) |

### Blockers before starting

- [ ] Supporting files present in `infra/host/`: `caddy/Caddyfile`, `postgres/init/*.sql`,
      `observability/{otel-collector.yaml,prometheus.yml,loki.yaml,config.alloy,grafana/provisioning/}`,
      `backup/run-backup.sh`, `scripts/deploy.sh`.
- [ ] `secrets.env.sops` decryptable by the age key on the host — `sops -d secrets.env.sops >/dev/null`
      is the cheapest way to find out, and the failure mode otherwise is the whole stack coming up
      with empty environment variables.
- [ ] A Cloudflare Tunnel token in the secrets file. Without it `cloudflared` starts and connects to
      nothing, and the site is unreachable while every container reports healthy.

Two prerequisites that ADR 0034 listed as blockers are **already done** and are recorded here so
nobody redoes them: the API image carries `opentelemetry-javaagent.jar` (not
`applicationinsights-agent.jar` — swapping only `OTEL_EXPORTER_OTLP_ENDPOINT` would have failed
silently, because the App Insights agent ignores that variable), and the control plane reads health
from `PrometheusHealthSource` rather than the App Insights KQL query API.

## One-time: moving an already-deployed host from `infra/proxmox/` to `infra/host/`

Skip this if the host was bootstrapped after ADR 0036. It applies once, to a host whose checkout
still predates the rename.

**Do not simply let the timer pull it.** The rename moves the directory the deploy runs *from*, and
three things break at once:

1. `deploy.sh` resolves `HOST_DIR`, `COMPOSE_FILE` and `SOPS_FILE` from `BASH_SOURCE` at startup and
   validates the compose file *before* `--sync` pulls. So a `--sync` run passes its preflight
   against the old tree, pulls the rename out from under itself, and then dies on a compose file
   and a secrets file that no longer exist at the paths it already resolved. Its header's advice
   ("if the change is in deploy.sh itself, run it twice") does not apply: the *directory* moved.
2. The installed systemd unit hard-codes the old absolute paths, because
   `bootstrap-host.sh` derives them from where it sits. After the pull the timer fails every
   interval with `200/CHDIR` or `203/EXEC` — and nothing in this stack alerts on a failed unit,
   so it fails quietly until someone reads the journal.
3. `secrets.env.sops` is untracked (ADR 0036 §4), so `git pull` leaves it behind in the old
   directory — which also keeps that directory alive and makes the move look like it worked.

Ordered procedure. Steps 1-3 are the ones that must not be reordered:

```bash
ssh nora-prod
cd /opt/nora

# 0. Stop the pull agent BEFORE anything moves. Running containers are untouched;
#    only the reconciliation loop stops.
sudo systemctl stop nora-deploy.timer

# 1. --ff-only refuses if a tracked file was edited by hand here. That is the point:
#    find out what diverged and put it in the repository, do not merge over it.
#    `git status --short` names it; `git diff` shows it.
git status --short
git pull --ff-only

# 2. NOW move the untracked files, once infra/host/ exists. Git does not move untracked
#    files, and this is the step whose omission makes every later deploy die on a
#    missing secrets file.
sudo mv infra/proxmox/secrets.env.sops infra/host/secrets.env.sops
[ -f infra/proxmox/env.defaults ] && sudo mv infra/proxmox/env.defaults infra/host/env.defaults

# 3. Repoint the unit at the new path, from the new path.
sudo infra/host/scripts/bootstrap-host.sh --units-only

# 4. The old directory should now be empty. If `ls -A` prints anything, something
#    untracked is still in there — look at it before removing.
ls -A infra/proxmox
sudo rmdir infra/proxmox

# 5. Deploy from the new path and watch it succeed, then restart the timer.
#    No --tag: this step proves the RELOCATED machinery works, it does not roll out code.
#    Passing --tag "sha-$(git rev-parse --short HEAD)" here is a trap — build-images.yml only
#    publishes on changes under services/ or apps/, so a docs-or-infra commit has no image at
#    its SHA and every `docker compose pull` fails. deploy.sh reads the running tag when none
#    is given, which is exactly what is wanted.
sudo env SOPS_AGE_KEY_FILE=/etc/nora/age.key infra/host/scripts/deploy.sh
sudo systemctl start nora-deploy.timer
systemctl list-timers nora-deploy.timer
```

Verify, in this order — the first two are the ones that fail silently:

```bash
systemctl cat nora-deploy.service | grep -E 'WorkingDirectory|ExecStart'   # both must say infra/host
sudo systemctl start nora-deploy.service && systemctl status nora-deploy.service --no-pager
curl -fsS https://api.nora.systems/actuator/health
```

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
docker compose -p nora exec -T postgres psql -U nora_admin -d nora < infra/host/postgres/init/01-roles-and-db.sql
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

### Pitfall 5 — an empty `CF_ACCESS_AUD` used to make the admin FAIL-OPEN

**Symptom, as it was:** none. `admin.<dom>` responded normally and the ADR 0025 Tier 2 validation
simply **did not happen**. In production on Azure that was live for months: `CF_ACCESS_AUD` was
registered as a **Secret** but read as `vars.` in the workflows, so it arrived empty (see
`environment-secrets.md` §5.1).

**Symptom now:** the opposite, and loud. Since 2026-08-16 `apps/admin/src/lib/access.ts` blocks
every console route when `CF_ACCESS_TEAM_DOMAIN` or `CF_ACCESS_AUD` is empty, with a 403 page that
names both variables. It no longer degrades to "edge-only", because the Tunnel and the edge Access
Application are exactly what an attacker reaching the origin from inside the environment has
already gone around.

**Fix (already in the compose, and now the second line rather than the only one):** both variables
are mandatory with `:?`, so the `admin` container **does not start** without them — which beats
starting and answering 403 to its own operators:

```yaml
CF_ACCESS_TEAM_DOMAIN: ${CF_ACCESS_TEAM_DOMAIN:?... without it access.ts blocks every request}
CF_ACCESS_AUD:         ${CF_ACCESS_AUD:?... without it access.ts blocks every request}
```

Check after the deployment:

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
  retention_period: 720h          # 30d, aligned with Prometheus
compactor:
  working_directory: /loki/compactor
  retention_enabled: true          # <- without this line, nothing gets deleted
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

**Cause:** the App Insights agent exports to the **Breeze** endpoint of the
`APPLICATIONINSIGHTS_CONNECTION_STRING` and **does not implement** the generic OTLP exporter. With
an empty connection string it becomes a silent no-op — which is exactly what looks like "working
fine".

**Fix:** swap the JAR for `opentelemetry-javaagent.jar` and republish the image.

**Already applied.** `services/api/Dockerfile:49` sets
`-javaagent:/app/opentelemetry-javaagent.jar`. The pitfall is kept because it is invisible when
you hit it and because reverting the Dockerfile would reintroduce it silently — not because there
is anything left to do.

## First deployment from scratch

### 1. The host

**Verified by inspection on 2026-08-10 (ADR 0036):** one physical machine, Ubuntu 24.04.4 LTS,
kernel 6.8, Docker Engine with Compose v2. `systemd-detect-virt` returns `none` — there is no
hypervisor and no other guest. This runbook does not cover racking or ordering a machine; it
starts from an Ubuntu (or Debian) host that already exists and is reachable over SSH, and assumes
no inbound port other than SSH is open (the tunnel is the only ingress *for HTTP* — see Overview).

> **Consequence worth stating rather than implying** (ADR 0036): on a VM, a host that survives can
> restart the guest; here the host is the guest. There is no snapshot or clone to fall back on — see
> §Rollback and §Restore drill below, both of which had to be redesigned around that fact.

#### No hypervisor-level backup exists, and none is planned

There is no hypervisor backup server, no VM snapshot and no second machine. What exists is the
`backup` service's hourly `pg_dump` (14-day retention, on the same host) — that is the **only**
line of defense, and it covers loss of *data*, not loss of the *host*. ADR 0036 §3 records this as
a deliberate, accepted asymmetry given the database holds only reproducible demo content: **the
code, the configuration and the infrastructure definition have GitHub as their only copy.** A
change made by hand on the host and not committed does not survive a host loss.

The one exception, decided in ADR 0036 §4: `secrets.env.sops` is **not** versioned, so the secrets
are not reproducible from the repository either. A rebuild regenerates them from
`secrets.env.example`, which lists every key. That is deliberate — the age private key lives only
on this host, so a committed ciphertext would be unreadable in exactly the scenario that would
need it, while being permanently public.

### 2. Host bootstrap

Throughout this runbook `nora-prod` is a stand-in for however you reach the host — an alias in
your `~/.ssh/config`, or `user@address` spelled out. The address itself is deliberately not written
down here: this repository is public, and publishing the origin address is precisely what the
tunnel exists to make pointless.

```bash
ssh nora-prod

# --- base ---
sudo apt-get update && sudo apt-get -y upgrade
sudo apt-get -y install ca-certificates curl gnupg git age unattended-upgrades \
                        postgresql-client-16 jq
sudo dpkg-reconfigure -plow unattended-upgrades   # automatic security updates

# --- Docker (official repo; the distro's docker.io is too old for compose v2) ---
# The repo path is per-distro: .../linux/ubuntu on Ubuntu, .../linux/debian on Debian.
# Getting this wrong gives a 404 on `apt-get update` that names the codename, not the
# mistake — `noble` under linux/debian looks like a missing release, not a wrong path.
# `bootstrap-host.sh` derives this from /etc/os-release; this block is the manual equivalent.
distro="$(. /etc/os-release && echo "$ID")"          # ubuntu | debian
codename="$(. /etc/os-release && echo "$VERSION_CODENAME")"
sudo install -m 0755 -d /etc/apt/keyrings
curl -fsSL "https://download.docker.com/linux/$distro/gpg" | \
  sudo gpg --dearmor -o /etc/apt/keyrings/docker.gpg
echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.gpg] \
https://download.docker.com/linux/$distro $codename stable" | \
  sudo tee /etc/apt/sources.list.d/docker.list
sudo apt-get update
sudo apt-get -y install docker-ce docker-ce-cli containerd.io \
                        docker-buildx-plugin docker-compose-plugin
sudo usermod -aG docker "$USER" && newgrp docker

# --- directories ---
sudo mkdir -p /etc/nora /srv/nora/backups /opt/nora
sudo chown "$USER":"$USER" /opt/nora
sudo chmod 700 /srv/nora/backups

# --- SOPS ---
SOPS_VER=3.9.4
curl -fsSLo /tmp/sops "https://github.com/getsops/sops/releases/download/v${SOPS_VER}/sops-v${SOPS_VER}.linux.amd64"
sudo install -m 0755 /tmp/sops /usr/local/bin/sops

# --- firewall: no inbound besides SSH ---
# READ THE SECOND BLOCKQUOTE UNDER THIS BLOCK BEFORE RUNNING THESE FOUR LINES.
sudo apt-get -y install ufw
sudo ufw default deny incoming && sudo ufw default allow outgoing
sudo ufw allow 22/tcp
sudo ufw --force enable

# --- code ---
git clone https://github.com/sf0rzin/nora.git /opt/nora
```

> `ufw` is redundant with "don't publish ports", and that is exactly why it is worth it: it protects against the
> `-p 0.0.0.0:...` that someone will add by mistake while debugging at 2 a.m.

> **This block used to read `ufw allow from 192.168.0.0/16 to any port 22`, and on this machine that
> locks you out.** The host is a provider machine on a public address (ADR 0036), not a box on a
> home LAN, and the operator reaches 22 across the internet. Enabling `deny incoming` with an
> allow rule scoped to a private range that nobody connects from cuts the session that is running
> the command. The rule above allows 22 from anywhere, which is what the machine already does —
> the value `ufw` adds here is the `-p 0.0.0.0:...` guard above, not a source restriction.
>
> **State on this host as of 2026-08-11: `ufw` is INACTIVE.** `iptables -S INPUT` is policy
> `ACCEPT` with no rule naming port 22, so 22 is open to the internet and sshd (key-only:
> `passwordauthentication no`, `permitrootlogin without-password`) is what stands in front of it.
> That is the state ADR 0037 §3 relies on when it calls 22 the recovery path. It has not been
> changed to match this block, because enabling a firewall on the only recovery path is a
> deliberate-window operation and not a documentation edit — see `ssh-over-tunnel.md` for the
> shape such a window takes.

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

Declare the public key in `infra/host/.sops.yaml` (version-controlled):

```yaml
creation_rules:
  - path_regex: secrets\.env\.sops$
    age: age1xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx
```

Assemble the secrets file from the stack's `.env.example` and encrypt it:

```bash
cd infra/host
cp secrets.env.example secrets.env      # NEVER commit this intermediate file
$EDITOR secrets.env                     # fill in the values
sops --encrypt secrets.env > secrets.env.sops
shred -u secrets.env
git add .sops.yaml secrets.env.sops && git commit -m "chore(infra): encrypted secrets (SOPS+age)"
```

**Two planes, not one.** Only what must not leak is encrypted; the rest stays in the clear and
readable in `env.defaults`. Encrypting non-secret configuration turns every tag change into an
edit of an encrypted file with an unreadable diff — and makes public values get "lost" (that is
exactly how `CF_ACCESS_AUD` disappeared on Azure). The canonical template is
`infra/host/secrets.env.example`; the complete map of the compose variables is the
`.env.example` next to it.

**Inventory of `secrets.env`** — secrets only (see ADR 0034 §Secrets: the number **increases**
relative to Key Vault, because without managed identity each `secretRef` becomes a static value):

```dotenv
# Edge
CLOUDFLARE_TUNNEL_TOKEN=

# Data
POSTGRES_ADMIN_PASSWORD=            # openssl rand -base64 32
POSTGRES_PLATFORM_ADMIN_PASSWORD=   # ANOTHER value (blast radius, ADR 0022)

# RLS (the THREE roles — pitfall 8). Single source: they feed the ALTER ROLE and the API's .env.
NORA_APP_PASSWORD=                  # openssl rand -hex 24
RLS_TELEMETRY_PASSWORD=
NORA_TELEMETRY_DATASOURCE_PASSWORD= # same value as RLS_TELEMETRY_PASSWORD
SPRING_FLYWAY_PASSWORD=

# Auth
JWT_SECRET=                         # openssl rand -base64 48 (min 32 chars)

# Providers / e-mail
OPENAI_API_KEY=
DEEPSEEK_API_KEY=
GEMINI_API_KEY=
RESEND_API_KEY=

# Worker (api -> worker, ADR 0023 §3-4) — REQUIRED, `:?` on both services
NORA_WORKER_INTERNAL_TOKEN=         # openssl rand -hex 32; NOT NORA_PLATFORM_INTERNAL_TOKEN

# Integrations (ADR 0031) — only the SECRETS; the *_OAUTH_CLIENT_ID ones are public
NORA_INTEGRATIONS_ENC_KEY=          # REQUIRED. AES-256-GCM, 32 bytes BASE64 — warning below
NORA_INTEGRATIONS_STATE_SECRET=     # openssl rand -hex 32
GOOGLE_OAUTH_CLIENT_SECRET= ...     # (slack, github, notion, todoist, linear, ms)
NORA_TELEGRAM_BOT_TOKEN=
TRELLO_API_KEY=

# Control plane (ADR 0022/0023/0024) — two DISTINCT values
NORA_PLATFORM_INTERNAL_TOKEN=       # openssl rand -hex 32
NORA_PLATFORM_ADMIN_TOKEN=          # ANOTHER openssl rand -hex 32

# Observability
GRAFANA_ADMIN_PASSWORD=             # openssl rand -base64 24
```

**Stays OUTSIDE the encrypted file** (non-secret plane, `env.defaults`): `NORA_PUBLIC_DOMAIN`,
`NORA_ENV`, `POSTGRES_ADMIN_USER`, `DATASOURCE_USERNAME`, the JDBC URLs, `NORA_RLS_ENFORCE`,
`NORA_EMAIL_FROM`, the `*_OAUTH_CLIENT_ID`s, `NORA_PLATFORM_ENABLED`, **`CF_ACCESS_AUD` and
`CF_ACCESS_TEAM_DOMAIN`** (public identifiers — pitfall 5) and the rollout tags
(`API_TAG`/`WORKER_TAG`/`WEB_TAG`/`ADMIN_TAG`, which `deploy.sh --tag` overrides).

> **No value may be the string `unset`.** Inherited from the Bicep, which wrote `'unset'` into the
> Key Vault when a secret came in empty. `unset` is fatal. Empty is safe for most of these — but
> not for the ones the compose marks `${VAR:?}`, where empty stops `docker compose up` on purpose.

> **`NORA_INTEGRATIONS_ENC_KEY`:** never put the string `unset` nor a non-base64 value there. The
> `TokenCipher` validates base64 and **brings the boot down** — that was the 2026-06-11 incident with
> the KV reference. Generate it with `openssl rand -base64 32`. Empty is no longer a soft landing
> either: it used to make the cipher write every provider's OAuth access and refresh token to
> Postgres as `plain:<token>`, with one WARN line at boot as the only signal. The variable is now
> `${VAR:?}` in the compose, and the API itself refuses to start without it unless
> `NORA_INTEGRATIONS_ALLOW_PLAINTEXT=true` — a local-dev escape hatch the host compose does not
> pass through and the `prod` profile pins to false.

> **`NORA_WORKER_INTERNAL_TOKEN`:** the same value has to reach the `api` service (which sends it
> as `X-Internal-Token`) and the `worker` service (which verifies it). Different values mean every
> analysis fails with 401; a missing value stops the stack at `docker compose up`. It is a
> different secret from `NORA_PLATFORM_INTERNAL_TOKEN`, which travels worker → API.

Age key rotation (ADR 0016 Gap 7 policy, now without Key Vault):

```bash
# add the new public key to .sops.yaml, then:
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
cd /opt/nora/infra/host
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

### 6. Restore data (from a backup)

This procedure was originally written for the one-time restore of the Azure dumps during the
2026-08-07 migration. There is nothing left to restore from Azure (no subscription, no export —
ADR 0036), so today this is the general restore path: it is what the quarterly drill runs and
what a Level 2/3 rollback follows, sourced from the `backup` service's hourly `pg_dump` instead.

**Mandatory order.** If the API comes up before the restore, Flyway creates a virgin schema and
`pg_restore` collides with it.

```bash
# 1) ONLY the databases. initdb runs here (empty volume) and creates the RLS roles.
docker compose -p nora --env-file ./env.defaults --env-file /dev/shm/nora.env \
  up -d postgres postgres-platform
docker compose -p nora exec postgres pg_isready -U nora_admin -d nora

# 2) Copy the dumps (custom format, -Fc)
docker cp nora.dump          nora-postgres:/tmp/nora.dump
docker cp nora_platform.dump nora-postgres-platform:/tmp/nora_platform.dump

# 3) Restore. --no-owner/--no-acl so a dump carrying roles that do not exist on this host
#    (e.g. the historical Azure ones) does not make the restore fail on every object.
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
# 6) Now yes, the whole stack.
./scripts/deploy.sh --platform --tag sha-xxxxxxx
```

> **Only do this if you are recovering from a backup.** On the first deployment the database starts empty and
> there is nothing to restore. When it does apply, steps 1 through 5 above are automated in
> `./scripts/restore-into-host.sh --from-dir <backup-dir> --sops` (the dumps that the `backup`
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

# is the API actually emitting? (fails if the javaagent was not swapped — pitfall 9)
docker compose -p nora exec prometheus wget -qO- \
  'http://localhost:9090/api/v1/query?query=up{job="nora-api"}' | jq '.data.result'

# logs arriving in Loki?
docker compose -p nora exec loki wget -qO- \
  'http://localhost:3100/loki/api/v1/label/container/values' | jq
```

RLS (pitfall 8) and secrets:

```bash
docker compose -p nora exec postgres psql -U nora_admin -d nora -c \
  "select rolname, rolbypassrls from pg_roles where rolname like 'nora_%';"
docker compose -p nora --profile platform exec admin printenv CF_ACCESS_AUD   # must not be empty
```

**Only after that** create the public hostnames on the tunnel (§4.2) — that is the DNS cutover.
There is nothing to do on Azure before or after: the subscription is gone and there is no
decommission left to run (ADR 0036).

## Verifying it works: the end-to-end smoke

`scripts/smoke-e2e.sh` is what "it works" means here. Nine steps over the public HTTPS API:
health, signup, login refused before confirmation, confirm, login, upload, an analysis carrying
decisions and action items, a second tenant getting 404 on the first tenant's meeting, and
cleanup.

```bash
API_BASE=https://api.nora.systems \
NORA_SMOKE_CONFIRM_CMD="sudo /opt/nora/infra/host/scripts/smoke-confirm.sh" \
/opt/nora/scripts/smoke-e2e.sh
```

`API_BASE` has no default on purpose: the script creates two tenants and two root users that no
endpoint can delete, and runs a real analysis against the deployment's provider key.

`NORA_SMOKE_CONFIRM_CMD` is the one step that cannot go over the API. Confirming an address needs
the token from the e-mail, this deployment sends real mail, and the token is stored only as a
SHA-256 hash — there is nothing to read back. `smoke-confirm.sh` marks the account verified
directly, and refuses any address outside `@smoke.invalid`, which is a literal constant in the
script rather than a variable. It needs docker access, hence `sudo`.

It is worth knowing what that script can do, because "confirms an address" understates it: the
same statement also sets `status = 'ACTIVE'`, so it would move a `DISABLED` or `INVITED` account
to active. It cannot create an account, set a password or authenticate. The domain guard is the
only thing between it and a real user, which is why it is not configurable.

What a pass tells you, and what it does not:

- **Does** prove the public hostname, Cloudflare's edge, the tunnel, Caddy's routing, the API, the
  worker, the provider call and the database are all working together, and that the tenant filter
  refuses a cross-tenant read.
- **Does not** prove anything about the browser: no page is rendered, no CSP is evaluated, no
  JavaScript runs. The Playwright suite in `apps/web/e2e/` does check headers, route protection
  and CSP violations, but it runs in CI against a local `next start` — never against the host —
  so the gap here is real and this script does not close it.

Two smoke tenants are left behind on every run. They are inert — addresses under a domain that
cannot receive mail — but they accumulate, and there is no tenant-delete endpoint to clean them
up with. On a demo deployment that is acceptable; it is written here so it is a known cost rather
than a surprise.

## Common operations

### Rolling out a new version

Immutable `sha-<short>` tags are the mechanism. `latest` is fine for bootstrap; it is **not** fine for
rollout, because it gives no rollback target.

```bash
cd /opt/nora && git pull
./infra/host/scripts/deploy.sh --platform --tag sha-a1b2c3d
```

The previous tag **does not need to be written down**: `deploy.sh` records `<SVC>_PREV_TAG` in
`/srv/nora/state/deploy-state.env` before switching. Just one service:

```bash
./infra/host/scripts/deploy.sh --service api --tag sha-a1b2c3d
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
        lb_try_duration 60s      # holds and retries while the origin comes back
        lb_try_interval 500ms
        fail_duration    0s      # does not mark the single origin as down
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
./infra/host/scripts/deploy.sh --service web --tag sha-a1b2c3d
```

### View logs

```bash
docker compose -p nora logs -f --tail=200 api
# history and search: Grafana → Explore → Loki
#   {container="nora-api"} |= "ERROR"
```

### Connect to Postgres

No network exposure: the ports are on `127.0.0.1`.

```bash
ssh -L 15432:127.0.0.1:5432 nora-prod          # primary
ssh -L 15433:127.0.0.1:5433 nora-prod          # platform
psql "host=127.0.0.1 port=15432 dbname=nora user=nora_admin"     # WITHOUT sslmode (pitfall 1)
```

Directly on the host: `docker compose -p nora exec postgres psql -U nora_admin -d nora`.

### On-demand manual backup

The `backup` service runs hourly (`BACKUP_INTERVAL_SECONDS`, retention
`BACKUP_RETENTION_DAYS=14`) and writes to `/srv/nora/backups`. To force it now:

```bash
docker compose -p nora exec backup /usr/local/bin/run-backup.sh --once
ls -lh /srv/nora/backups | tail
```

> **A backup on the same host is not a backup.** There is no hypervisor snapshot and no off-host
> copy of `/srv/nora/backups` (ADR 0036 §3 withdraws that leg rather than leave it on paper — there
> is no substrate for it to run on). As long as the dumps only exist on this host, the real RPO for
> a **host** loss is not the last hour, it is everything not committed to this repository. Syncing
> the dumps to an external destination (`rclone`/`rsync`) would close that gap; it is not done
> today, and ADR 0036 records the trigger for building it: the first tenant whose content is not
> reproducible.

### RLS enforce flip

Unchanged in design (ADR 0026/0028). What changes is the endpoint: where the
[`rls-cutover-runbook.md`](rls-cutover-runbook.md) says
`nora-pg-dev-wgl3a3.postgres.database.azure.com`, read `postgres` (inside the `data` network), and the
role provisioning runs through the local `psql` instead of the `rls-cutover.yml` workflow — which
depended on a runner firewall rule and OIDC, and no longer applies.

## Rollback

Three levels. Choose based on what broke, not on what is fastest.

### Level 1 — application rollback (image)

For a code bug. **Seconds to a minute.**

```bash
./infra/host/scripts/deploy.sh --service api --rollback
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
# only then: API_TAG back to the previous one + deploy
```

Everything written since the dump is lost (**up to 1 hour** — the real RPO of this stack, ADR 0034
§Availability). Every destructive migration must therefore be preceded by a manual backup.

### Level 3 — host rollback (there is no snapshot: rebuild from repo)

Only for a **host** breakage (kernel upgrade, corrupted Docker, failed disk). There is no
hypervisor snapshot to revert to (ADR 0036 §1: "here the host is the guest"). The procedure is
*rebuild from this repository*:

1. Provision or repair the machine (OS install is outside this runbook's scope).
2. Run `infra/host/scripts/bootstrap-host.sh` to install Docker, the age key and the systemd
   pull unit.
3. `git clone` the repository, decrypt `secrets.env.sops`, and follow §First deployment from
   scratch through §5.
4. Restore the most recent `pg_dump` following §Restore data (from a backup) — if it survived the
   host loss. If it did not (it lived only on the host that failed), the recovery ceiling is
   whatever the last off-host copy is, which today is **nothing**: see the caution in §On-demand
   manual backup.

This procedure's fidelity depends entirely on the repository matching the host. A change made by
hand on the host and never committed does not survive this path — see ADR 0036 §"Recovery is
rebuild-from-repo".

## Restore drill

**Quarterly**, inherited from ADR 0016 Gap 3. What changes: previously the RTO was guaranteed by the Flexible
Server's PITR; now it is **a manual procedure**. An RTO that is never measured is a guess.

There is no hypervisor to clone (ADR 0036), so `infra/host/scripts/restore-drill.sh` measures only
the **data recovery path** — bring up a disposable Postgres (`docker run --network none`, no
compose project, no tunnel token, so it cannot reach or be reached by production), `pg_restore`
the most recent dump into it, and validate counts/Flyway/GRANTs. It does **not** measure incident
detection, host provisioning/boot or DNS/tunnel routing — those have no drill, because there is no
second host to drill them on:

```bash
./scripts/restore-drill.sh                 # drills the most recent backup, prints the RTO floor
./scripts/restore-drill.sh --list          # see what backups are available first
```

1. **Start the clock here.** Run the script; it restores into the disposable container and
   validates automatically.
2. **Record** the result: it appends one line to `/srv/nora/state/restore-drills.tsv` (dump size,
   measured RTO, pass/fail) and prints a summary. Copy that line into the table below.
3. The container is destroyed at the end unless `--keep` was passed.

| Date | Dump | Measured RTO | Findings |
|---|---|---|---|
| _(pending — first drill within 30 days after go-live)_ | | | |

> Treat the number from this drill as the RTO **floor**, never as the RTO — see the script's own
> header for what it deliberately does not measure. If the measured floor already exceeds 2h (the
> ADR 0016 Gap 3 target), either the target is wrong or the procedure
> is. **Fix one of the two the same day** — do not leave the divergence documented and alive.

## History

Rows are what happened on their date. Paths in a row are the paths as they were then; ADR 0036
later renamed the infra directory and the restore script, and that rename is the 2026-08-10 row,
not a retroactive edit of the two above it.

| Date | Change |
|---|---|
| 2026-08-07 | v1.0 — runbook created together with ADR 0034. Supersedes the historical Azure-era runbook. Covers VM provisioning, bootstrap, SOPS+age, Cloudflare Tunnel/Access, first deployment, restore coming from Azure, verification, the 9 self-hosting pitfalls, 3-level rollback and the quarterly restore drill. |
| 2026-08-07 | v1.1 — reconciliation with the actual files in the infra directory: correct names (`postgres/init/01-roles-and-db.sql`, `R001__provision_app_roles.sql`), the real `deploy.sh` flags (`--platform`, `--tag`, `--service`, `--rollback`, `--if-changed`) in place of `--profile platform` and manual editing of `API_TAG`, rollout state in `/srv/nora/state/deploy-state.env`, tmpfs on `/dev/shm`, and separation of the two configuration planes (`env.defaults` vs. `secrets.env.sops`) in the secrets inventory. Reference to the restore-into-host script. |
| 2026-08-10 | v1.2 — reconciled with ADR 0036: the substrate is a single bare-metal host, no hypervisor. Removed the fictitious VM-provisioning walkthrough (and the host details it exposed), the hypervisor-backup / VM-snapshot rollback and drill, and every link to the deleted Azure runbooks. Rewrote Level 3 rollback and the restore drill around "rebuild from repo" and the disposable-container drill that `restore-drill.sh` actually runs. |
