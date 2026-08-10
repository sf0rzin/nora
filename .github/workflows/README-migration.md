# Workflows — what changed in the Azure → Proxmox migration

> Context: [ADR 0034](../../docs/adr/0034-azure-to-proxmox-migration.md).
> Operational runbook: [`docs/operations/proxmox-deploy.md`](../../docs/operations/proxmox-deploy.md).
> Azure shutdown: [`docs/operations/azure-decommission.md`](../../docs/operations/azure-decommission.md).

There is a single structural change, and everything else follows from it:

**CI no longer pushes the deploy. It now only publishes an image and a pointer; the host is what decides what runs.**

```
BEFORE                                   AFTER
────────────────────────────────         ────────────────────────────────
GitHub Actions                           GitHub Actions
  azure/login (federated OIDC)             (no deploy credential)
  az containerapp update  ──push──►        push ghcr.io/...:sha-xxxxxxx
  Container Apps                           tag git release/prod/current
                                                        │
                                           Host Proxmox │ (pull, every 5 min)
                                             nora-deploy.timer
                                             └─► deploy.sh ─► docker compose up -d --wait
```

## Why pull, and not push

It is not a matter of style preference — both push alternatives are closed off:

- **Self-hosted runner** — the repository is **public** (ADR 0017) and `deploy-infra.yml` had a `pull_request` trigger. A persistent runner on the home network would execute PR code from an arbitrary fork. Critical risk, not hypothetical.
- **SSH from the GitHub-hosted runner** — it would require exposing `sshd` to the internet, because hosted runners do not have a stable IP range to allowlist.

Pull eliminates both: **zero inbound ports, zero SSH keys in Secrets, zero runners.** The host opens an outbound connection to GHCR and to Cloudflare, and nothing else.

## Workflow by workflow

| Workflow | State | What changed |
|---|---|---|
| `ci.yml` | **edited** | The `infra` job stopped validating Bicep (`az bicep build`) and now validates `infra/proxmox/docker-compose.yml` + shellcheck on the scripts. **The job name is still `infra`** — it is a `needs` of `ci-gate`, main's only required check (ADR 0027); renaming it would break branch protection. |
| `build-images.yml` | **edited** | Build and push to GHCR **untouched** — that is the part that always worked. Removed the `deploy-apps` job (`azure/login` + `az containerapp update`) and the `permissions: id-token: write` that existed only for OIDC. In its place came `release-pointer`, which merely announces the ready `sha-<short>` tags. The `NEXT_PUBLIC_API_BASE_URL` fallback stopped pointing at the Azure FQDN. |
| `deploy-proxmox.yml` | **new** | Publishes the release pointer (git tag `release/prod/<short>` + `release/prod/current`) and, optionally, calls a webhook. It never touches the host. |
| `rls-cutover.yml` | **rewritten** | It no longer connects to any database. It validates `R001` on an ephemeral Postgres and emits the runbook; the real execution became `infra/proxmox/scripts/rls-cutover.sh`, running **on the host**. Reason: the Proxmox Postgres is on the `data` bridge (`internal: true`) publishing only `127.0.0.1:5432` — there is no network path from a runner. |
| `deploy-infra.yml` | **deleted** | It existed only for the Bicep `az deployment group create`. `infra/bicep/` **remains in the repo** as historical reference until the decommission is finished. |
| `cloudflare-setup.yml` | edited | Hostname adjustments: the tunnel now serves the whole stack, not just the admin. |
| `cloudflare-tunnel.yml` | unchanged | It still issues the connector token. It gained importance: it is now the ingress for everything. |
| `desktop-release.yml` | edited | Adjustments for the local STT (ADR 0035). **Point of attention:** `whisper-rs` compiles `whisper.cpp`, which requires a C++ toolchain on all three targets — this has not yet been validated in a real build. |

## GitHub Secrets and Variables

### Can be DELETED after the decommission

Only delete them **after** confirming that Proxmox is serving traffic and the resource group has been removed — see [`azure-decommission.md`](../../docs/operations/azure-decommission.md).

```
AZURE_CLIENT_ID              AZURE_TENANT_ID           AZURE_SUBSCRIPTION_ID
PG_ADMIN_PASSWORD            PG_PLATFORM_ADMIN_PASSWORD
EASYAUTH_CLIENT_ID           EASYAUTH_CLIENT_SECRET     (inert since ADR 0025)
NORA_APP_PASSWORD            RLS_TELEMETRY_PASSWORD     (move to secrets.env.sops)
```

Also in Entra: the `sp-nora-github-deploy` App Registration and its **3 federated credentials**
(`github-main-branch`, `github-pull-requests`, `github-environment-dev`).

> The Service Principal had **two** roles, not one: `Contributor` on `rg-nora-dev` **and**
> `Role Based Access Control Administrator` — the second one because `modules/keyvault.bicep:67`
> creates role assignments. Remove both.

### Still needed

They migrate from Key Vault to `secrets.env.sops`, but they stay in GitHub for as long as CI builds:

```
OPENAI_API_KEY   DEEPSEEK_API_KEY   GEMINI_API_KEY   RESEND_API_KEY
GOOGLE_OAUTH_*   SLACK_OAUTH_*      GH_OAUTH_*       NOTION_OAUTH_*
TODOIST_OAUTH_*  LINEAR_OAUTH_*     MS_OAUTH_*
NORA_TELEGRAM_BOT_TOKEN   TRELLO_API_KEY
NORA_INTEGRATIONS_STATE_SECRET   NORA_INTEGRATIONS_ENC_KEY
NORA_PLATFORM_INTERNAL_TOKEN     NORA_PLATFORM_ADMIN_TOKEN
JWT_SECRET       CLOUDFLARE_TUNNEL_TOKEN
```

### Need to be CREATED

| Name | Type | What for |
|---|---|---|
| `GHCR_PULL_TOKEN` | Secret | A PAT with **only** `read:packages`, used by the host for `docker login ghcr.io`. It does not go into GitHub — it goes into the host's `secrets.env.sops`. Listed here because it is generated in the GitHub UI. |
| `NORA_RELEASE_WEBHOOK` | Secret (optional) | URL that `deploy-proxmox.yml` calls to wake the pull agent before the next 5-minute tick. Without it the deploy is just slower, it does not break. |
| `CF_ACCESS_AUD` | **Secret**, not Variable | See below. |

## Pre-existing bug that the migration needs to close

`CF_ACCESS_AUD` is registered as a **Secret**, but `deploy-infra.yml:158` and `:239` were reading
`${{ vars.CF_ACCESS_AUD }}` — the *Variables* namespace, not the *Secrets* one. The value arrived
**empty** at `nora-admin`, and `apps/admin/src/lib/access.ts` **fails open** when it is empty.

Effect in production today: Cloudflare Access Tier 2 is **off**, and an Access JWT
issued for **another application in the same Cloudflare organization** is accepted by the operator console.

In the new `docker-compose.yml` this can no longer go unnoticed — `CF_ACCESS_AUD` and
`CF_ACCESS_TEAM_DOMAIN` use the `${VAR:?message}` syntax, so the container **refuses to
come up** without them. Fill both in before the first deploy with the `platform` profile.

## Verification after touching things here

```bash
gh workflow list --repo sf0rzin/nora
```

Check that `ci-gate` still lists `infra` under `needs` and that the branch protection on
`main` still points at the `ci-gate` check. If the required check disappears, PRs start merging
without green CI — a silent and expensive failure.
