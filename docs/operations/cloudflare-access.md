---
title: "Cloudflare Access — protecting the operator console"
owner: NORA Architect (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# Cloudflare Access — protecting the operator console

Runbook for configuring and operating Cloudflare Access protecting `admin.nora.systems` (the nora-admin console). Updated on 2026-06-02 post-ADR 0025.

> **Attention — Current model (ADR 0025): Cloudflare Tunnel + Access.** `nora-admin` no longer has public ingress — the only entry path is via Cloudflare Tunnel (`cloudflared` sidecar). The CNAME `admin.nora.systems` points to `<tunnel-id>.cfargotunnel.com`, **not to Azure**. Because of that:
>
> - **This workflow (`cloudflare-setup.yml`)** owns the **Access App + Policy + IdP**.
> - **The sibling workflow (`cloudflare-tunnel.yml`)** owns the **CNAME + Tunnel + route**.
> - **NEVER** pass `admin_hostname` in `cloudflare-setup.yml` — the CNAME step has a guard that ABORTS if it detects the tunnel. The input is kept only for back-compat.
>
> For tunnel and ingress, see `docs/operations/control-plane-runbook.md` and ADR 0025.

## Why Cloudflare Access

The operator console (`nora-admin`) is the entry door of the control plane (ADR 0022/0023/0024). Easy Auth via Entra covers authentication, but:

- It depends on a manual App Registration + Entra group (a slow step, see `control-plane-runbook.md`)
- It gives no central log of access attempts
- It does not offer login with external identities (Google/GitHub) without an additional ADR

Cloudflare Access covers those three fronts at the edge, **before** the request reaches the Container App (see ADR 0025 — the operator's identity at the edge migrated from Easy Auth/Entra to Cloudflare Tunnel + Access):

- Mandatory login by email (One-Time PIN by default; Google + GitHub optional)
- Explicit allowlist (no Entra groups)
- Central log of attempts/denials in the Zero Trust panel
- Free plan up to 50 users

Easy Auth + Cloudflare Access coexist: Cloudflare protects at the network edge, Easy Auth protects at the app edge. Defense in depth.

## Current state

| Item | Value |
|---|---|
| Domain | `nora.systems` (registrar: Namecheap; nameservers: Cloudflare) |
| Account ID | `76e6b917a3ef8fcaff2b20ffb2c8afd6` |
| Protected hostname | `admin.nora.systems` |
| Default identity provider | One-Time PIN (email) |
| Initial allowlist | `axonogenesis@proton.me`, `gmaciel0204@gmail.com` |

## Initial configuration (one time)

### 1. Enable Zero Trust on the CF account

Manual step in the dashboard, **there is no API to create a team**:

1. `https://one.dash.cloudflare.com/`
2. Choose the `nora.systems` account
3. Onboarding asks for a team name (becomes `<name>.cloudflareaccess.com`) — suggestion: `stratfy`
4. Choose the **Free** plan (up to 50 users, no expiration)
5. A credit card is requested but is not charged until you go past 50 users

### 2. Create a scoped API token

`dash.cloudflare.com` → My Profile → API Tokens → Create Custom Token.

**Minimum permissions (critical path — DNS + Access App + Policy work):**

- `Account` → `Access: Apps and Policies` → **Edit**
- `Account` → `Access: Service Tokens` → **Edit**
- `Zone` → `DNS` → **Edit**
- `Zone` → `Zone` → **Read**

**Optional permission (full automation, includes creating the IdP via workflow):**

- `Account` → `Access: Organizations, Identity Providers, and Groups` → **Edit**

Without the optional permission, the workflow degrades gracefully: `team_domain` is built from the input (not verified via API) and the OTP IdP is not created — the user adds it manually in 30s from the panel (see "Operation → Adding the OTP IdP manually" below).

**Scope:**

- Account Resources: specific account
- Zone Resources: specific zone (`nora.systems`)

Paste the token as the secret `CLOUDFLARE_API_TOKEN` in the repo (`Settings → Secrets and variables → Actions`).

### 3. Run the workflow

`.github/workflows/cloudflare-setup.yml` is `workflow_dispatch` and idempotent. Inputs:

- `admin_hostname` — **LEGACY, pre-ADR 0025. LEAVE IT EMPTY.** The CNAME today is managed by `cloudflare-tunnel.yml`. The step has a guard that aborts if it detects `*.cfargotunnel.com` in the existing CNAME (protection against taking the admin down by mistake).
- `team_name` — team name (default `stratfy`); must match the team already created in step 1
- `access_emails` — CSV of authorized emails (the default already has the 2 operators)

The workflow does an upsert: ensure the Access App, ensure the allowlist policy, ensure the OTP IdP. Re-running is safe.

To configure/reconcile the **tunnel** itself (CNAME → cfargotunnel + route → sidecar), run `cloudflare-tunnel.yml` (see `control-plane-runbook.md`).

## Identity providers — adding Google and/or GitHub

Email OTP works with no additional setup. Google/GitHub OAuth need OAuth Apps created in their respective consoles.

### Google

1. `https://console.cloud.google.com/apis/credentials`
2. Create Credentials → OAuth client ID → Web application
3. Name: `Cloudflare Access — NORA admin`
4. Authorized redirect URI: `https://<team>.cloudflareaccess.com/cdn-cgi/access/callback`
5. Copy Client ID + Client Secret
6. In the Zero Trust panel → Settings → Authentication → Add new → Google
7. Paste Client ID + Client Secret + click Save

### GitHub

1. `https://github.com/settings/developers` → OAuth Apps → New OAuth App
2. Application name: `Cloudflare Access — NORA admin`
3. Homepage URL: `https://admin.nora.systems`
4. Authorization callback URL: `https://<team>.cloudflareaccess.com/cdn-cgi/access/callback`
5. Copy Client ID + generate Client Secret
6. In the Zero Trust panel → Settings → Authentication → Add new → GitHub
7. Paste Client ID + Client Secret + Save

After adding them, edit the Access App to allow the new IdPs in the `allowed_idps` field (UI: Applications → nora-admin → Edit → Identity providers).

## Operation

### Adding the OTP IdP manually (if the workflow skipped the step)

If the token does not have the optional permission, the workflow logs a warning and skips creating the OTP IdP. Add it manually (30s):

1. `https://one.dash.cloudflare.com/` → select the account
2. **Settings** → **Authentication**
3. Under **Login methods**, click **Add new**
4. Choose **One-time PIN** → Save

Done. Logging in to `admin.nora.systems` now asks for an email and Cloudflare sends a code (the workflow's allowlist still applies).

### Adding/removing an operator

Re-run the workflow with an updated `access_emails`. The workflow upserts the policy.

### Viewing access logs

`one.dash.cloudflare.com` → Logs → Access → filter by hostname `admin.nora.systems`.

It shows each login attempt (allow/deny), source IP, email, and IdP used.

### Temporarily suspending access

Zero Trust panel → Access → Applications → nora-admin → Edit → disable the policy or switch the decision to `deny`. Revert via the same path.

## Troubleshooting

### `Zone status: pending nameserver update`

The Namecheap nameservers have not yet been switched to Cloudflare's. Namecheap panel → Domain List → Manage → Nameservers → Custom DNS → paste the 2 NS given by Cloudflare (something like `xxx.ns.cloudflare.com`). Propagation usually within 10-30 min.

### The workflow logs a warning at `Resolve team domain`

This usually means the scoped token does not have `Access: Organizations, IdPs, and Groups Read` — the `/access/organizations` endpoint returns `10000 Authentication error`. The workflow degrades gracefully: it uses `team_name` from the input to build `team_domain`. Subsequent steps (Access App, Policy) validate functionally.

If Zero Trust is also not enabled (rare, a single screenshot of the panel would confirm it), the Access App steps would fail with a clear error. Solution: step 1 of the Initial configuration.

### Login is accepted but the page returns 502/timeout

In the ADR 0025 model (Tunnel), a 502 usually means the `cloudflared` sidecar did not connect to Cloudflare. Check:

```bash
# Réplicas do nora-admin (precisa ≥1 sempre — o sidecar não escala a zero):
az containerapp replica list -n nora-admin-dev -g rg-nora-dev --revision latest -o table

# Logs do sidecar cloudflared (procurar "Registered tunnel connection" e ausência de erro):
az containerapp logs show -n nora-admin-dev -g rg-nora-dev --container cloudflared --tail 100
```

Common causes:
- `CLOUDFLARE_TUNNEL_TOKEN` secret missing/wrong → the sidecar does not connect. Re-run `cloudflare-tunnel.yml`, copy the connector token from the log, update the GitHub Secret, redeploy.
- The Container App scaled to zero → switch `minReplicas` to `≥1` in the admin's Bicep.
- The tunnel was deleted/rotated but DNS still points to the old ID → re-run `cloudflare-tunnel.yml` (it recreates the DNS).

### The email does not arrive (OTP)

Cloudflare sends from the sender `noreply@notify.cloudflare.com`. Check spam, and that the email is on the workflow's allowlist.

## Revoking the token

`dash.cloudflare.com` → My Profile → API Tokens → 3 dots → Delete. Also remove the `CLOUDFLARE_API_TOKEN` secret from GitHub. Recreate it via step 2 of the Initial configuration when needed again.
