# Runbook — Azure shutdown (decommission)

> **Audience:** whoever executes the final Azure cut after the migration to Proxmox.
>
> **Decision:** [ADR 0034](../adr/0034-azure-to-proxmox-migration.md) ·
> **Destination:** [`proxmox-deploy.md`](proxmox-deploy.md) ·
> **Origin (historical):** [`azure-deploy.md`](azure-deploy.md)
>
> **Prerequisites:** Az CLI 2.86+ with a valid login, the `gh` CLI, access to the
> Cloudflare dashboard (zone `nora.systems`), and the `nora-prod` VM already provisioned.

> **This document is about ORDER, not about commands.** Every command here is trivial; what
> is not trivial is the sequence. Step 5 is irreversible — but, in this project, what it
> destroys is replaceable infrastructure, not irreplaceable data. See below.

---

## What this runbook does NOT need to do

**There is no data to rescue.** NORA is an educational project (FIAP Challenge 2026 × TOTVS):
`rg-nora-dev` serves a real domain and was built with production standards, but **there is
no production data and no user base**, and by the PO's decision there never will be — the product is
not going to operate commercially in this incarnation. The content of the two Postgres instances is
demonstration material, reproducible.

This eliminates the most expensive and most nerve-racking part of a decommission. Concretely:

- **No `pg_dump` on the critical path.** The database on Proxmox is born **empty**: Flyway creates
  the schema from scratch and the RLS roles come from `postgres/init/01-roles-and-db.sql`.
- **No retention clock.** If the subscription has already expired and Azure has already deleted
  everything, nothing was lost. There is no urgency to manage.
- **No copies of PII circulating.** There is no dump to encrypt, store in two places,
  track who has access to, and destroy in 90 days.
- **No reactivating the subscription.** There is no reason to pay Pay-As-You-Go just to be able
  to extract something.

> If NORA ever becomes a product with real data subjects, **this runbook no longer applies**:
> `transcripts.raw_text` holds PII at rest (ADR 0029) and the OAuth tokens (ADR 0031) are not
> recoverable without the database. In that scenario, a verified dump before anything else goes back to being
> step 1. Version 1.0 of this document, in the git history, has that procedure.

---

## The safe order

```
  1. VALIDAR o Proxmox          <- servindo tráfego real, ainda SEM DNS
  2. APONTAR o DNS              <- o cutover; reversível em minutos
  3. OBSERVAR                   <- período de carência (só se a Azure ainda estiver de pé)
  4. LIMPAR credenciais         <- GitHub Secrets/Variables + Entra
  5. DELETAR o resource group   <- ponto de não-retorno
  6. LIMPAR o repositório       <- Bicep, FQDNs hardcoded, docs
```

**The rule that remains:** nothing is deleted while the replacement has not been proven. Not because of
the data — because of the ability to compare behavior between the old and the new when something turns out
different from what was expected.

| Step | Reversible? | How to revert |
|---|---|---|
| 1 | yes | changes nothing in production |
| 2 (DNS) | **yes, in minutes** | re-point the CNAME (TTL 1 = automatic) |
| 3-4 | partially | credentials can be recreated; federated credentials, redone |
| **5 (RG delete)** | **NO** | but what is lost is infrastructure declared in `infra/bicep/`, recreatable |
| 6 | yes | it is versioned code |

---

## Step 0 — Diagnosis: which scenario are you in

This no longer defines *how much time you have* — it defines only **how much work the
decommission still involves**.

```bash
az account show --query "{nome:name, estado:state, id:id}" -o table
```

| `state` | What to do |
|---|---|
| `Enabled` | Follow the whole runbook. The RG exists and needs to be deleted. |
| `Disabled` / `Warned` | Nothing urgent. You can reactivate only to delete the RG and stop any residual charge, **or simply let it expire** — Azure removes the resources by itself at the end of the retention period. Go straight to step 4 (clean up credentials) and step 6 (clean up the repository). |
| `PastDue` | There is an open invoice. Resolve it in the portal first, otherwise `az group delete` fails. |
| login error | The subscription may already have been removed. Confirm in the portal; if it is gone, the infra decommission is done — steps 4 and 6 remain. |

Also confirm whether the environment still responds:

```bash
curl -s -o /dev/null -w '%{http_code}\n' https://api.nora.systems/actuator/health
curl -s -o /dev/null -w '%{http_code}\n' \
  https://nora-api-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io/actuator/health
```

On 2026-08-07 both returned a connection error — the origin is down, not a Cloudflare
problem. If it stays that way, **skip step 3** (observation period with Azure still
standing): there is nothing standing to observe, and there is no rollback to Azure.

---

## Step 1 — Validate Proxmox serving traffic (still WITHOUT DNS)

The complete procedure is in [`proxmox-deploy.md`](proxmox-deploy.md) — what stays here are
only the **gates** that must be green before touching DNS.

The database is born **empty**. There is no data restore: Flyway applies the 26 migrations from scratch
on the API's first boot, and the three RLS roles come from
`infra/proxmox/postgres/init/01-roles-and-db.sql`, which initdb executes.

> `restore-into-proxmox.sh` exists for the path of **recovery from a backup
> of Proxmox itself** (the dumps the `backup` service generates), not to bring anything from
> Azure. It is not used in this step.

Exit gates (all mandatory):

- [ ] `flyway_schema_history` at the expected version, **zero** migrations with `success=false`
- [ ] The three correct roles: `nora_app` = `rolbypassrls f`, `nora_telemetry` = `t`
- [ ] All services `healthy` in `docker compose -p nora ps`
- [ ] The four hostnames respond **by Host header**, without DNS
      (`proxmox-deploy.md` §Verificar)
- [ ] Egress working (OpenAI/Resend reachable from the `worker`)
- [ ] Prometheus with an API metric (**proof that the javaagent was swapped**) and Loki
      receiving logs from the containers
- [ ] `CF_ACCESS_AUD` **not empty** in the `admin` container
- [ ] Real login working end to end

**If any of these fails, stop.** Nothing here has a deadline: Azure is already down, so there is
neither a service degrading nor charges accruing while you investigate.

---

## Step 2 — DNS cutover

This is the cut. It is reversible in minutes (TTL 1 = automatic in Cloudflare), and it is the step
that **resolves the 522**: today DNS resolves to a dead origin.

### Current state (Azure era)

| Name | Type | Content | Proxy |
|---|---|---|---|
| `nora.systems` (apex) | CNAME (flattening) | FQDN of `nora-web-dev` | proxied |
| `www` | CNAME | FQDN of `nora-web-dev` | proxied |
| `api` | CNAME | FQDN of `nora-api-dev` | proxied |
| `admin` | CNAME | `<tunnel-id-antigo>.cfargotunnel.com` | proxied |
| `asuid`, `asuid.www` | TXT | `customDomainVerificationId` of the CAE | n/a |

### Target state (Proxmox)

All hostnames start pointing to the **new tunnel** (`nora-prod`), and Host-based
routing is Caddy's job.

**Recommended form:** add the *public hostnames* in the tunnel (Zero Trust → Networks →
Tunnels → `nora-prod` → Public Hostname). Cloudflare **creates/updates the proxied CNAME
by itself** to `<tunnel-id>.cfargotunnel.com`.

Suggested order — **from least critical to most critical**, validating each one:

1. `grafana.nora.systems` (new, no traffic) — validates the tunnel → caddy path
2. `admin.nora.systems` — **re-points** from the old tunnel to the new one
3. `api.nora.systems` — validate `/actuator/health` externally before moving on
4. `www.nora.systems`
5. `nora.systems` (apex) — last

After each one:

```bash
curl -sS -o /dev/null -w '%{http_code}\n' https://api.nora.systems/actuator/health
dig +short api.nora.systems
```

### DNS cleanup (only after everything is green)

- [ ] Remove the `asuid` and `asuid.www` TXT records — they were the Container Apps custom domain
      ownership verification, they have no function in the tunnel
- [ ] Check that **no** record still points to `*.azurecontainerapps.io`

> **Caution inherited from ADR 0025:** the `cloudflare-setup.yml` workflow owns the Access
> App/Policy/IdP and **must run without `admin_hostname`** — with that parameter, it overwrites
> the tunnel's CNAME and takes the admin down.

### Cutover rollback

Re-point the hostname to the old Container App FQDN (with Azure still standing). That is
why step 5 comes **after** an observation period — Azure is your safety
net during step 3.

---

## Step 3 — Observation period (Azure stays standing)

**Suggested minimum: 7 days** with the new stack serving 100% of the traffic and Azure still
existing (stopped, but not deleted).

What to observe:

- [ ] Errors in Grafana/Loki (`{container="nora-api"} |= "ERROR"`)
- [ ] The `backup` service generating a dump **every hour** in `/srv/nora/backups`
- [ ] A complete **restore drill** executed successfully
      ([`proxmox-deploy.md`](proxmox-deploy.md) §Restore drill) — this is the item that
      turns the RTO from a guess into a measured number
- [ ] Flows that depend on egress: sending email (Resend), the integrations' OAuth,
      LLM analysis
- [ ] No consumer complaining about `speech/token` (the old desktop receives **410 GONE +
      `SPEECH_PROVIDER_GONE`** — a terminal signal, not an infinite retry; see ADR 0035)

**To reduce cost during the observation period, without deleting anything:**

```bash
# Para os Container Apps zerando as réplicas (mantém o recurso e a configuração)
for app in nora-api-dev nora-worker-dev nora-web-dev nora-admin-dev; do
  az containerapp update -g rg-nora-dev -n "$app" --min-replicas 0 --max-replicas 0
done

# Para o Postgres (ele volta com `start`; para sozinho após 7 dias de qualquer forma)
az postgres flexible-server stop -g rg-nora-dev -n nora-pg-dev-wgl3a3
az postgres flexible-server stop -g rg-nora-dev -n nora-pg-platform-dev-wgl3a3
```

> **Stopping is not deleting.** While the RG exists, a new `pg_dump` is still possible (just
> `start` it). It is exactly that option that step 5 eliminates.

---

## Step 4 — Clean up credentials

### 4.1 GitHub Secrets

State before the migration: **15 Secrets** and **1 Variable**
(see [`environment-secrets.md`](environment-secrets.md) §3). `deploy-infra.yml` no longer
exists — it was replaced by `deploy-proxmox.yml`, which is **PULL** and consumes
no runtime secret at all.

**Confirm the value is already in `secrets.env.sops` BEFORE deleting.** GitHub does not
allow reading a Secret: deleting without having copied it means losing the value.

| Secret | Action | Reason |
|---|---|---|
| `AZURE_CLIENT_ID` | **DELETE** | OIDC for `deploy-infra.yml`, which no longer exists |
| `AZURE_TENANT_ID` | **DELETE** | same |
| `AZURE_SUBSCRIPTION_ID` | **DELETE** | same |
| `PG_ADMIN_PASSWORD` | migrate → **DELETE** | becomes `POSTGRES_ADMIN_PASSWORD` in SOPS |
| `PG_PLATFORM_ADMIN_PASSWORD` | migrate → **DELETE** | becomes `POSTGRES_PLATFORM_ADMIN_PASSWORD` |
| `JWT_SECRET` | migrate → **DELETE** | same name in SOPS. **Changing it invalidates all sessions** — migrate the value, do not generate another one, unless you want to log everyone out |
| `OPENAI_API_KEY` | migrate → **DELETE** | same name |
| `DEEPSEEK_API_KEY` | migrate → **DELETE** | same name |
| `GEMINI_API_KEY` | migrate → **DELETE** | same name |
| `RESEND_API_KEY` | migrate → **DELETE** | same name |
| `NORA_PLATFORM_INTERNAL_TOKEN` | migrate → **DELETE** | same name |
| `NORA_PLATFORM_ADMIN_TOKEN` | migrate → **DELETE** | same name |
| `CLOUDFLARE_TUNNEL_TOKEN` | **replace** → DELETE | the **new tunnel's** token goes to SOPS; the old one dies with the RG |
| `CF_ACCESS_AUD` | **DELETE** | it was registered **incorrectly** (a Secret read as `vars.` → arrived empty → fail-OPEN). The AUD is public and now lives in the host's non-secret plane. **Do not recreate it as a Secret** |
| `CLOUDFLARE_API_TOKEN` | **KEEP** | still used by `cloudflare-setup.yml` / `cloudflare-tunnel.yml` |

Secrets that **come into existence** (new, from the PULL model — both optional):

| Secret | Use |
|---|---|
| `NORA_DEPLOY_WEBHOOK_URL` | wakes up the pull agent. Absent = polling |
| `NORA_DEPLOY_WEBHOOK_TOKEN` | bearer that the agent validates |

Kept by other workflows: `TAURI_SIGNING_PRIVATE_KEY`,
`TAURI_SIGNING_PRIVATE_KEY_PASSWORD` (`desktop-release.yml`), `GITHUB_TOKEN` (automatic).

```bash
for s in AZURE_CLIENT_ID AZURE_TENANT_ID AZURE_SUBSCRIPTION_ID \
         PG_ADMIN_PASSWORD PG_PLATFORM_ADMIN_PASSWORD JWT_SECRET \
         OPENAI_API_KEY DEEPSEEK_API_KEY GEMINI_API_KEY RESEND_API_KEY \
         NORA_PLATFORM_INTERNAL_TOKEN NORA_PLATFORM_ADMIN_TOKEN \
         CLOUDFLARE_TUNNEL_TOKEN CF_ACCESS_AUD; do
  gh secret delete "$s" --repo sf0rzin/nora
done
gh secret list --repo sf0rzin/nora
```

### 4.2 GitHub Variables

| Variable | Action | Reason |
|---|---|---|
| `NORA_EMAIL_FROM` | **DELETE** | no workflow reads it anymore; the value lives in the host's non-secret plane |
| `NEXT_PUBLIC_API_BASE_URL` | **KEEP and check** | build-arg of `build-images.yml`. **Baked at build time** — if it is wrong, the `web` bundle calls the wrong address and no runtime env fixes it (pitfall 4 of `proxmox-deploy.md`). It must be `https://api.nora.systems` |
| `NORA_API_BASE_URL` | **CREATE if it does not exist** | used in the desktop bundle. Today it **does not exist**, and the app falls back to a hardcoded value pointing to the Azure FQDN — which is dead. Leaving it as is ships a desktop that does not connect |

```bash
gh variable list --repo sf0rzin/nora
gh variable set NORA_API_BASE_URL --body "https://api.nora.systems" --repo sf0rzin/nora
```

### 4.3 Entra ID / App Registrations

**Deleting the resource group does NOT delete App Registrations.** They live in Entra (tenant
`fiap.com.br`), not in the subscription. The *role assignments*, on the other hand, do die with the RG.

| Object | Action |
|---|---|
| App Registration `sp-nora-github-deploy` | **DELETE** (together with the Service Principal) |
| 3 federated credentials (`ref:refs/heads/main`, `pull_request`, `environment:dev`) | they disappear with the app; if you cannot delete the app, **remove at least these** |
| Role assignments (`Contributor`, `Role Based Access Control Administrator` on `rg-nora-dev`) | they disappear with the RG; check afterwards |
| Easy Auth App Registration (ADR 0023) | **check whether it exists.** It was probably **never created** — the `fiap.com.br` tenant denied `az ad app create` with `Authorization_RequestDenied`, which is the blocker that produced ADR 0025. `EASYAUTH_CLIENT_ID`/`EASYAUTH_CLIENT_SECRET` were **orphaned** references in the deleted workflow |

```bash
# Inventário
az ad app list --display-name sp-nora-github-deploy \
  --query "[].{appId:appId, id:id, name:displayName}" -o table
az ad app federated-credential list --id <APP_ID> -o table
az role assignment list --assignee <APP_ID> --all -o table

# Remoção
az ad app delete --id <APP_ID>
```

> **If the tenant denies the delete** (the same institutional restriction as in ADR 0025): remove the
> federated credentials one by one and the role assignments. With no federated credential and no
> role, the app is inert even if it remains listed. Record the pending item.

### 4.4 Cloudflare

- [ ] **Delete the old tunnel** of `nora-admin` (the connector ran as a sidecar in the
      Container App and dies with the RG; the record stays orphaned in the dashboard and is confusing)
- [ ] **Keep** the Access Application for `admin.nora.systems` and the **same AUD** — ADR
      0034 reuses it (if you recreate the App, the AUD changes and `CF_ACCESS_AUD` needs to be updated)
- [ ] **Create** the Access Application for `grafana.nora.systems` (new public route)
- [ ] Review `CLOUDFLARE_API_TOKEN`: the permissions are still correct for the new tunnel

---

## Step 5 — Delete the resource group (POINT OF NO RETURN)

What is lost here is **infrastructure declared in `infra/bicep/`** — recreatable from
the repository — and the disposable content of the two databases. There is no irreplaceable data at
stake (see §"What this runbook does NOT need to do"). Even so, check the items off: the value
of Azure standing at this point is not backup, it is **being able to compare behavior** when the
Proxmox behaves differently from what was expected.

- [ ] Proxmox validated serving real traffic by a test hostname (step 1)
- [ ] Proxmox serving 100% of the traffic without an incident (step 3) — 7 days is ideal; for an
      academic demo with a fixed date, the real criterion is *not doing this on the eve of the pitch*
- [ ] **Restore drill executed successfully** from the Proxmox backup
      (`scripts/restore-drill.sh`). Not because Azure is a safety net — it is not,
      it is already down — but because a restore that has never been tested is a procedure that does
      not exist, and `production-readiness-gaps.md:67` already admitted that gap
- [ ] Credentials migrated and checked (step 4)

> If the subscription is already deactivated and the resources already removed, this step is a no-op.
> Confirm with `az group show --name rg-nora-dev` and move on to step 6.

```bash
az group delete --name rg-nora-dev --yes --no-wait
```

It takes 5-15 min (the Container Apps Environment is the bottleneck).

### Soft-delete: what survives the RG delete

Two pitfalls inherited from `azure-deploy.md` (5 and 5b) — **now in your favor**: they are your
last window for regret, of **7 days**.

```bash
az keyvault list-deleted --query "[?starts_with(name, 'nora-')].{name:name}" -o table
az cognitiveservices account list-deleted --query "[?contains(name, 'nora')]" -o table
```

The soft-deleted Key Vault still contains `postgres-password`, `jwt-secret`, etc. If some
secret was not migrated, **this is where you recover it** — and that is why the purge comes
afterwards, not together.

**Purge (irreversible, only when you are sure):**

```bash
az keyvault purge --name nora-kv-dev-wgl3a3 --location centralus
az cognitiveservices account purge --location centralus \
  --resource-group rg-nora-dev --name <speech-name>
```

> If you intend to **never again** use this subscription, the purge is optional — letting the
> soft-delete expire on its own has the same effect and keeps the rescue window open for the
> full period. Purge only if you need to free the **global name** to recreate something.

### After the RG: the subscription

- **If it was upgraded to Pay-As-You-Go at some point:** cancel the subscription
  now, otherwise it keeps charging (even when empty, there are residual costs).
  Portal → Subscriptions → Cancel subscription.
- **If the subscription was already deactivated:** do nothing. It expires by itself.
- Confirm that nothing was left in **other** resource groups:

```bash
az resource list --query "[?contains(name, 'nora')].{name:name, rg:resourceGroup}" -o table
```

---

## Step 6 — Clean up the repository

After the RG is deleted, code that references Azure becomes a trap for whoever comes
later: commands that look valid and point at nothing.

### 6.1 The FQDN hardcoded in four places

`nora-pg-dev-wgl3a3.postgres.database.azure.com` (ADR 0034):

| File | Action |
|---|---|
| `infra/bicep/main.dev.bicepparam:140` | goes away with the Bicep (§6.2) |
| `.github/workflows/rls-cutover.yml:40` | the whole workflow goes away — it depended on a runner firewall rule and on OIDC. The RLS flip becomes a local `psql` (`proxmox-deploy.md` §Flip do RLS enforce) |
| `docs/operations/rls-cutover-runbook.md:69` | change the host to `postgres` and **remove `?sslmode=require`** (pitfall 1 — it takes Hikari down at boot) |
| `docs/operations/azure-deploy.md:398` | do not edit: it becomes a historical document (§6.3) |

### 6.2 Infra and workflows

- [ ] `infra/bicep/` — remove. It is the most dangerous reference: it describes an infra that no
      longer exists and still "compiles".
- [ ] `.github/workflows/rls-cutover.yml` — remove
- [ ] Check that no remaining workflow references `azure/login`, `id-token: write` or
      `secrets.AZURE_*`

```bash
grep -rn "azure/login\|AZURE_CLIENT_ID\|azurecontainerapps.io" .github/ infra/ || echo "limpo"
```

### 6.3 Documentation

- [ ] `docs/operations/azure-deploy.md` → mark it as **historical** in the header
      (`status: historical`) and point to `proxmox-deploy.md`. **Do not delete it:** the 8
      Azure for Students pitfalls are a learning record, and ADR 0034 cites them.
- [ ] `docs/engineering/architecture.md:437` — the Azure resources table (which includes
      `nora-pg-dev-wgl3a3`) needs to become the Proxmox stack table
- [ ] `docs/operations/production-readiness-gaps.md` — the Azure-anchored gaps were
      partially superseded by ADR 0034; reconcile
- [ ] `docs/operations/environment-secrets.md` — the entire cartography assumes Key Vault +
      Managed Identity. Rewrite it for SOPS+age (the `CF_ACCESS_AUD` §5.1 stays as a
      historical record of the bug)

---

## Final checklist

```
DIAGNÓSTICO  (sem resgate: não há dado a preservar — ver §"O que este runbook NÃO precisa fazer")
  [ ] az account show --query state          -> anotado; define quanto trabalho resta

PROXMOX
  [ ] stack sobe com banco VAZIO             -> Flyway cria o schema do zero
  [ ] flyway_schema_history                  -> versão esperada, 0 falhas
  [ ] 3 roles                                -> nora_app=f, nora_telemetry=t
  [ ] todos os serviços healthy
  [ ] 4 hostnames respondendo por Host header
  [ ] métrica da API no Prometheus (javaagent trocado)
  [ ] CF_ACCESS_AUD não vazio

DNS
  [ ] grafana -> admin -> api -> www -> apex, um a um, verificando
  [ ] TXT asuid/asuid.www removidos
  [ ] nenhum registro para *.azurecontainerapps.io

OBSERVAÇÃO (pular se a Azure já estiver fora do ar — não há o que observar)
  [ ] sem erro relevante no Loki
  [ ] backup horário gerando dump
  [ ] RESTORE DRILL executado com sucesso   <- fecha o gap do production-readiness-gaps.md:67
  [ ] Container Apps zerados / Postgres parado (economia)

CREDENCIAIS
  [ ] 14 Secrets deletados, CLOUDFLARE_API_TOKEN mantido
  [ ] NORA_EMAIL_FROM deletada; NEXT_PUBLIC_API_BASE_URL conferida
  [ ] NORA_API_BASE_URL criada (desktop apontava pro Azure morto)
  [ ] sp-nora-github-deploy deletado (ou federated credentials removidas)
  [ ] túnel antigo deletado; Access App do grafana criada

PONTO DE NÃO-RETORNO
  [ ] az group delete --name rg-nora-dev
  [ ] soft-delete do KV conferido antes do purge
  [ ] assinatura cancelada (se houve upgrade para PAYG)

REPOSITÓRIO
  [ ] infra/bicep removido
  [ ] rls-cutover.yml removido
  [ ] FQDN hardcoded resolvido nos 4 lugares
  [ ] azure-deploy.md marcado como histórico
```

---

## History

| Date | Change |
|---|---|
| 2026-08-07 | v1.0 — created together with ADR 0034. Safe shutdown order in 8 steps, starting with a verified rescue of the data. |
| 2026-08-07 | v2.0 — premise correction. The PO clarified that NORA is educational, with no production data and no user base, and that it will not operate commercially. The rescue steps and the dump-custody steps were removed (along with `rescue-azure-data.sh`); the runbook drops from 8 to 6 steps and the database on Proxmox is now born empty. The rescue procedure, should it ever be needed again, is in v1.0 in the git history. |
