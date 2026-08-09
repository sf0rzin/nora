---
title: "Runbook — Turning on the Control Plane (operator admin + telemetry)"
owner: NORA Architect (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# Runbook — Turning on the Control Plane (operator admin + telemetry)

> How to promote the control plane from **OFF** (default) to **ON** on Azure. The operator's
> identity is **Cloudflare Tunnel + Access** (ADR 0025, which replaced the Easy Auth of ADR 0023
> after the FIAP tenant blocked App Registration). ADRs 0022/0023/0024/0025. Contract:
> `docs/engineering/contracts/platform-control-plane.md`. Cloudflare edge: `cloudflare-access.md`.

## What the IaC already does vs. what is manual

| IaC (Bicep, `enablePlatform=true`) | Manual (this runbook) |
|---|---|
| 2nd Postgres `nora-pg-platform-dev`, db `nora_platform` | Provision the Cloudflare Tunnel (workflow `cloudflare-tunnel.yml`) |
| UAI `nora-uai-admin-dev` + access to the KV | Set the GitHub Secrets (3 platform tokens + `CLOUDFLARE_TUNNEL_TOKEN`) |
| Container App `nora-admin` (ingress **internal** + `cloudflared` sidecar) | Set the Variable `CF_ACCESS_AUD` |
| Secrets in the KV (tokens, pg-platform password, tunnel token) | Ensure the Access App/allowlist (Cloudflare lane, `cloudflare-setup.yml`, **without** `admin_hostname`) |
| Env of api/worker/web for the control plane | Publish the `nora-admin` image (CI `build-images.yml`) |

## Prerequisites

- Image `ghcr.io/sf0rzin/nora-admin:latest` published and **Public** on GHCR (the Container App has
  no registry creds). CI publishes on pushes to `main` that touch `apps/admin/**`.
- `CLOUDFLARE_API_TOKEN` (account `nora.systems`) with **Cloudflare Tunnel: Edit** + **DNS: Edit** +
  **Zone: Read**. It is the same token as `cloudflare-setup.yml`, with Tunnel:Edit added.
- Cloudflare Access already configured for `admin.nora.systems` (sibling lane, PRs #177/#178).

## Step 1 — Generate platform secrets

```powershell
# Rode local; NÃO cole os valores em chat/PR. São 3 secrets:
[Convert]::ToBase64String((1..24 | ForEach-Object { Get-Random -Max 256 }))  # PG_PLATFORM_ADMIN_PASSWORD
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))  # NORA_PLATFORM_INTERNAL_TOKEN
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))  # NORA_PLATFORM_ADMIN_TOKEN
```

## Step 2 — Provision the Cloudflare Tunnel

Run the workflow **`cloudflare-tunnel.yml`** (Actions → Run workflow). It is idempotent and:

1. Creates/reuses the `nora-admin` tunnel (remotely-managed).
2. Configures the route `admin.nora.systems → http://localhost:3002` (the `cloudflared` sidecar serves
   Next over the pod's localhost).
3. Upserts the DNS `admin.nora.systems` → `<tunnel-id>.cfargotunnel.com` (proxied).
4. Prints, in the log of the step **`AUD + instruções do connector token`**: the Access App's **AUD** (for
   the Variable `CF_ACCESS_AUD`, a public identifier) and the command to register the **connector
   token**. The token itself **does not appear in the log** — public repo, log readable by anyone; the command
   takes it from the API straight to `gh secret set`.

> **Why Tunnel + Access (ADR 0025):** `nora-admin` comes up with `ingress: internal` — **no public
> FQDN**. The only entry door is the tunnel, behind Cloudflare Access (allowlist + OTP/SSO).
> There is no Azure origin to bypass it. Defense in depth: network (Access) + transport (Tunnel)
> + app (validation of `Cf-Access-Jwt-Assertion` in Next, Tier 2) + service (admin token).

## Step 3 — GitHub Secrets + Variable (repo `sf0rzin/nora`)

```
# Secrets:
PG_PLATFORM_ADMIN_PASSWORD   = <Passo 1>
NORA_PLATFORM_INTERNAL_TOKEN = <Passo 1>
NORA_PLATFORM_ADMIN_TOKEN    = <Passo 1>
CLOUDFLARE_TUNNEL_TOKEN      = <connector token do Passo 2>
# Variable (Variables tab — NÃO é secret):
CF_ACCESS_AUD                = <AUD do Passo 2>
```

> Without the 3 platform tokens, they become `'unset'` in the KV (admin/internal unlocked). Without
> `CLOUDFLARE_TUNNEL_TOKEN`, the `cloudflared` sidecar does not come up and the admin stays internal/inaccessible
> (safe, but offline). Without `CF_ACCESS_AUD`, Tier 2 degrades to edge-only (Tunnel + Access still
> protect it). `EASYAUTH_*` became inert (ADR 0025) — it can be left empty.

## Step 4 — Deploy

`enablePlatform = true` is already in `main.dev.bicepparam`. Merging the PR into `main` → `deploy-infra.yml`
provisions everything (idempotent). `nora-admin` comes up internal; as soon as `CLOUDFLARE_TUNNEL_TOKEN`
is set, `cloudflared` connects and the tunnel starts serving.

## Step 5 — Provider keys (for the runtime model switch)

To switch a service's model **live** without a deploy, the provider's key must already be
provisioned. OpenAI already is (`openai-api-key`). For DeepSeek and Gemini, add `DEEPSEEK_API_KEY`
and `GEMINI_API_KEY` to the GitHub Secrets (ADR 0024 / decision #C). Optional — ask the PO whether it is worth doing
now.

## Step 6 — Coordination with the Cloudflare lane

Ensure the Access App + allowlist + OTP via `cloudflare-setup.yml` — **running with `admin_hostname`
EMPTY**. The DNS of `admin.nora.systems` now belongs to the tunnel (Step 2); passing `admin_hostname` would make
`cloudflare-setup.yml` overwrite the tunnel's CNAME with the direct FQDN. Attention: **never pass
`admin_hostname` after turning on the tunnel.**

## Post-deploy verification

```bash
# 1. API subiu com o módulo platform (procurar no log): "módulo HEALTHY"
# 2. llm-config (precisa do internal token):
curl -H "X-Internal-Token: $internal" \
  "https://nora-api-dev.<domain>/internal/platform/llm-config?service=chat"

# 3. Conector cloudflared conectado (réplica do nora-admin de pé):
az containerapp replica list -n nora-admin-dev -g rg-nora-dev -o table

# 4. Console: abrir https://admin.nora.systems -> redireciona para o Cloudflare Access (OTP/SSO,
#    só allowlist). Após login, o Next valida o Cf-Access-Jwt-Assertion (Tier 2) e renderiza.
#    O FQDN interno (nora-admin-dev.internal.<domain>) NÃO é acessível de fora.
```

## Rollback

`param enablePlatform = false` + deploy. The 2nd Postgres, `nora-admin` and `cloudflared` disappear; the API
goes back to `NORA_PLATFORM_ENABLED` absent (inert module). The client path never depended on this
(fail-soft, ADR 0022). The tunnel/DNS on Cloudflare remain (inert without a connector); to remove them, delete
the `nora-admin` tunnel in the panel/API and the CNAME.

## Security notes

- `/admin/platform/**` on the public `nora-api` is protected by the admin token (the network/identity
  isolation is at the `nora-admin` edge via Cloudflare). Keep the admin token strong and
  distinct from the internal one.
- `nora-admin` has no public FQDN (internal ingress). Entry is only through the Cloudflare Tunnel,
  behind Access — there is no exposed Azure origin to bypass it.
- Tier 2 (validation of `Cf-Access-Jwt-Assertion` in Next) runs in a server component because the
  edge middleware would inline `CF_ACCESS_*` at build-time. `/healthz` is a route handler (outside the gate),
  so the Container App probe works without a JWT.
- `cloudflared` needs ≥1 replica always up (the admin does not scale to zero) — a small compute
  cost, accepted in ADR 0025.
