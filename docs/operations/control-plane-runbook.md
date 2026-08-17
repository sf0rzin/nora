# Runbook — Turning on the Control Plane (operator admin + telemetry)

> **Historical.** Written for the Azure deployment (Bicep, Container Apps), which is gone — no
> subscription, no export (ADR 0036). On the self-hosted stack the control plane is the `platform`
> compose profile in `infra/host/docker-compose.yml` (2nd Postgres `postgres-platform`, the
> `admin` service, `cloudflared`/`caddy` for ingress) — see `docs/operations/host-deploy.md` §4
> (Cloudflare Tunnel + Access Applications) and §5 (`deploy.sh --platform`). Kept here for the
> identity/security reasoning (ADR 0022/0023/0024/0025), which is unchanged.
>
> How to promote the control plane from **OFF** (default) to **ON**, as originally written. The
> operator's identity is **Cloudflare Tunnel + Access** (ADR 0025, which replaced the Easy Auth of
> ADR 0023 after the FIAP tenant blocked App Registration). ADRs 0022/0023/0024/0025. Contract:
> `docs/engineering/contracts/platform-control-plane.md`. Cloudflare edge: `cloudflare-access.md`.

## What the IaC already does vs. what is manual (as written, on Azure)

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
# Run locally; do NOT paste the values in chat/PR. There are 3 secrets:
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
4. Prints, in the log of the step **`AUD + connector token instructions`**: the Access App's **AUD** (for
   the Variable `CF_ACCESS_AUD`, a public identifier) and the command to register the **connector
   token**. The token itself **does not appear in the log** — public repo, log readable by anyone; the command
   takes it from the API straight to `gh secret set`.

> **Why Tunnel + Access (ADR 0025):** `nora-admin` has no published port on the self-hosted stack —
> **no public origin at all**. The only entry door is the tunnel, behind Cloudflare Access
> (allowlist + OTP/SSO), so there is nothing to bypass it. Defense in depth: network (Access) +
> transport (Tunnel) + app (validation of `Cf-Access-Jwt-Assertion` in Next, Tier 2) + service
> (admin token).

## Step 3 — GitHub Secrets + Variable (repo `sf0rzin/nora`)

```
# Secrets:
PG_PLATFORM_ADMIN_PASSWORD   = <Step 1>
NORA_PLATFORM_INTERNAL_TOKEN = <Step 1>
NORA_PLATFORM_ADMIN_TOKEN    = <Step 1>
CLOUDFLARE_TUNNEL_TOKEN      = <connector token from Step 2>
# Variable (Variables tab — NOT a secret):
CF_ACCESS_AUD                = <AUD from Step 2>
```

> Without the 3 platform tokens, they become `'unset'` in the KV (admin/internal unlocked). Without
> `CLOUDFLARE_TUNNEL_TOKEN`, the `cloudflared` sidecar does not come up and the admin stays internal/inaccessible
> (safe, but offline). Without `CF_ACCESS_AUD`, Tier 2 used to degrade to edge-only; since 2026-08-16
> `apps/admin/src/lib/access.ts` blocks every console route instead, so an empty AUD is an outage
> rather than a silent downgrade. `EASYAUTH_*` became inert (ADR 0025) — it can be left empty.

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
# 1. API came up with the platform module (look in the log for): "module HEALTHY"
# 2. llm-config (needs the internal token):
curl -H "X-Internal-Token: $internal" \
  "https://nora-api-dev.<domain>/internal/platform/llm-config?service=chat"

# 3. cloudflared connector connected (nora-admin replica up):
az containerapp replica list -n nora-admin-dev -g rg-nora-dev -o table

# 4. Console: open https://admin.nora.systems -> redirects to Cloudflare Access (OTP/SSO,
#    allowlist only). After login, Next validates the Cf-Access-Jwt-Assertion (Tier 2) and renders.
#    The internal FQDN (nora-admin-dev.internal.<domain>) is NOT accessible from outside.
```

## Procedure — backfilling the RAG index (ADR 0042)

Run this after configuring an embedding credential for the first time, and after changing
`NORA_EMBEDDING_PROVIDER` or `NORA_EMBEDDING_MODEL`. Indexing only happens at the end of an
analysis, so meetings analysed before either event have no usable vector and are invisible to
`GET /meetings/search` until this is run. It does not need the platform database.

```bash
# 1. Preview. Free — SQL only, no provider call. Tells you which tenants are behind and why.
curl -sH "X-Internal-Token: $NORA_PLATFORM_ADMIN_TOKEN" \
  https://api.nora.systems/admin/platform/embeddings/backfill

# 2. Run, one tenant at a time. `limit` defaults to 25 and is clamped to 100.
curl -sX POST -H "X-Internal-Token: $NORA_PLATFORM_ADMIN_TOKEN" \
  -H "X-Operator-Email: $YOUR_EMAIL" -H 'Content-Type: application/json' \
  -d '{"tenantId":"<uuid>","limit":100}' \
  https://api.nora.systems/admin/platform/embeddings/backfill

# 3. Repeat step 2 while `remaining` > 0.
```

Reading the output:

- `enabled: false` — no embedding credential; step 2 returns `409`. Fix the credential first.
- `source: primary` with every counter at zero, **under RLS enforce** — that is not necessarily an
  empty backlog. The primary role is NOBYPASSRLS and this read carries no tenant GUC, so it can be
  fail-closed. Configure `NORA_TELEMETRY_DATASOURCE_URL` (role `nora_telemetry`) and re-read.
- `stoppedReason` non-null — the run ended early on its 60s budget or on three consecutive provider
  failures. In the second case, check the provider before running again.
- Cost lands in `telemetry/cost` under `service=embedding-backfill`, separate from the ordinary
  `embedding` traffic. On Gemini `promptTokens` is 0 because the provider reports none; it means
  unknown, not free.

## Rollback

`param enablePlatform = false` + deploy. The 2nd Postgres, `nora-admin` and `cloudflared` disappear; the API
goes back to `NORA_PLATFORM_ENABLED` absent (inert module). The client path never depended on this
(fail-soft, ADR 0022). The tunnel/DNS on Cloudflare remain (inert without a connector); to remove them, delete
the `nora-admin` tunnel in the panel/API and the CNAME.

## Security notes

- `/admin/platform/**` on the public `nora-api` is protected by the admin token (the network/identity
  isolation is at the `nora-admin` edge via Cloudflare). Keep the admin token strong and
  distinct from the internal one.
- `nora-admin` has no public FQDN and no published port. Entry is only through the Cloudflare
  Tunnel, behind Access — there is no exposed origin to bypass it.
- Tier 2 (validation of `Cf-Access-Jwt-Assertion` in Next) runs in a server component because the
  edge middleware would inline `CF_ACCESS_*` at build-time. `/healthz` is a route handler (outside the gate),
  so the Container App probe works without a JWT.
- `cloudflared` needs ≥1 replica always up (the admin does not scale to zero) — a small compute
  cost, accepted in ADR 0025.
