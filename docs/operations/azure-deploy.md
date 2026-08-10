---
title: "Runbook — NORA deploy on Azure (HISTORICAL)"
owner: NORA Architect (Tech Lead)
status: historical
version: 1.0
last_reviewed: 2026-06-06
---

# Runbook — NORA deploy on Azure

> **HISTORICAL DOCUMENT — DO NOT OPERATE FROM IT.**
>
> Production moved off Azure to a self-hosted Proxmox VM
> ([ADR 0034](../adr/0034-azure-to-proxmox-migration.md)). The runbook in force is
> [`proxmox-deploy.md`](proxmox-deploy.md); the shutdown of what remained on Azure is in
> [`azure-decommission.md`](azure-decommission.md).
>
> This file is kept for two reasons: the **8 Azure for Students pitfalls** are a
> learning record cited by ADR 0034, and the description of the old stack is the
> reference for the decommission inventory. The `az` commands below point to
> resources that are being destroyed.

> **Audience (at the time):** whoever operates the NORA deploy on Azure.
>
> **Prerequisites:** active Azure subscription (currently "Azure for Students"), Az CLI 2.86+, Bicep CLI 0.43+, GitHub repo with permissions to create workflows.

> **Production environment (single-environment model).** `rg-nora-dev` is, in practice,
> NORA's **production environment**: it is what serves the public domain `nora.systems` /
> `api.nora.systems` with real traffic. There is NO second environment — we operate a single
> live environment. The physical names (`rg-nora-dev`, `nora-*-dev`, GitHub environment `dev`)
> are **historical**: renaming them would require recreating Container Apps + Postgres (downtime) and
> reconfiguring the OIDC federated credentials (subject `environment:dev`). That cosmetic
> rename is left for a post-pitch maintenance window; until then, **`dev` = production**. An
> eventual separate staging environment, if desired, would be created as `rg-nora-stg`.

## Overview

NORA is deployed on Azure via 3 GitHub Actions workflows:

1. **`build-images.yml`** — builds 3 Docker images (api/worker/web) and publishes them to `ghcr.io/sys0xff/nora-{api,worker,web}` when each service's code changes
2. **`deploy-infra.yml`** — deploys/updates Azure resources via Bicep IaC in `rg-nora-dev` when `infra/bicep/**` changes
3. **`ci.yml`** — tests, lint, type-check, validate Bicep

Provisioned stack (see `infra/bicep/main.bicep`):

- 1 Resource Group (`rg-nora-dev` in `brazilsouth`; resources in `centralus`)
- 1 Container Apps Environment (Consumption profile, scale-to-zero)
- 3 Container Apps (api, worker, web) with User-Assigned Managed Identities
- 1 Postgres Flexible Server B1ms (database `nora`, extensions PGCRYPTO + CITEXT)
- 1 Key Vault Standard with 4 secrets (postgres-password, jwt-secret, openai-api-key, azure-speech-key)
- 1 Storage Account LRS (Standard, blob soft-delete 7 days)
- 1 Log Analytics workspace + 1 workspace-based Application Insights
- 1 Azure Speech (Cognitive Services SpeechServices S0)
- 3 User-Assigned Managed Identities (uai-api, uai-worker, uai-web)
- (optional) 1 Azure AI Search Basic — off by default. Note: semantic search / RAG (US15) has already been delivered (PR #206) via pgvector + a provider-agnostic HTTP embedding client (Gemini/OpenAI), migration V021 `meeting_embeddings` — it does **not** depend on Azure AI Search. This resource remains optional/legacy.

Dev cost (currently provisioned): **~R$110-180/month** (see `docs/engineering/architecture.md` §12 "Stack rationale" and `docs/product/roadmap.md` Unit Economics).

---

## The 8 Azure for Students pitfalls (CATALOGUED)

These 8 pitfalls were discovered during Sub-phase 1.9 (2026-05-13). Apply the fixes before deploying, otherwise the deploy fails.

### Pitfall 1 — Federated credential needs a subject per context

**Symptom:** Deploy via `deploy-infra.yml` fails immediately at `azure/login@v2` with:

```
AADSTS700213: No matching federated identity record found for presented assertion
subject 'repo:sys0xFF/nora:environment:dev'
```

**Cause:** Service Principal created with a federated credential for `repo:sys0xFF/nora:ref:refs/heads/main`, but the job has `environment: dev` in the workflow → the subject changes to `repo:sys0xFF/nora:environment:dev`.

**Fix (one time):**

```bash
az ad app federated-credential create --id <APP_ID> --parameters '{
  "name": "github-environment-dev",
  "issuer": "https://token.actions.githubusercontent.com",
  "subject": "repo:sys0xFF/nora:environment:dev",
  "audiences": ["api://AzureADTokenExchange"]
}'
```

3 federated credentials are needed in total:
- `github-main-branch` (subject `repo:sys0xFF/nora:ref:refs/heads/main`) — for pushes to main without an environment
- `github-pull-requests` (subject `repo:sys0xFF/nora:pull_request`) — for what-if on PRs
- `github-environment-dev` (subject `repo:sys0xFF/nora:environment:dev`) — for the job with `environment: dev`

### Pitfall 2 — Region restriction policy

**Symptom:** Deploy fails with `RequestDisallowedByAzure`:

```
This policy maintains a set of best available regions where your subscription
can deploy resources.
```

**Cause:** The Azure for Students subscription has the policy `sys.regionrestriction`, which allows only:
- `mexicocentral`
- `northcentralus`
- `eastus`
- `centralus`
- `canadacentral`

Brazil South is **not** allowed for resources (RGs themselves can live in brazilsouth, but resources inside them must be in one of the 5 allowed regions).

**Fix:** Use `location = 'centralus'` in `main.dev.bicepparam` (already configured).

```powershell
$az = "az.cmd"
& $az policy assignment list --query "[?contains(displayName, 'region') || contains(displayName, 'Region')].{name:displayName, params:parameters}" -o json
```

### Pitfall 3 — Resource providers do not auto-register

**Symptom:** Deploy fails with `MissingSubscriptionRegistration`:

```
The subscription is not registered to use namespace 'Microsoft.OperationalInsights'
```

**Cause:** Normal Azure subscriptions auto-register providers on first use. Students does **not**.

**Fix (one time per provider):**

```bash
az provider register --namespace Microsoft.OperationalInsights
az provider register --namespace Microsoft.DBforPostgreSQL
az provider register --namespace Microsoft.App
az provider register --namespace Microsoft.ContainerRegistry  # preventive, in case ACR is used in the future
```

Wait for the registration to become `Registered` (a few minutes each):

```bash
az provider show --namespace Microsoft.OperationalInsights --query "registrationState"
```

### Pitfall 4 — Postgres rejects `eastus` (offer restriction)

**Symptom:** Postgres deploy fails in `eastus` with:

```
LocationIsOfferRestricted: Subscriptions are restricted from provisioning in location 'eastus'
```

**Cause:** Other Azure for Students categories accept resources in `eastus`, but Postgres Flexible Server does **not**. Per-service offer restriction.

**Regions tested (2026-05-13)** that accept Postgres B1ms:
- `centralus` — accepts
- `northcentralus` — accepts
- `canadacentral` — accepts
- `mexicocentral` — accepts
- `eastus` — rejects

**Fix:** Keep `centralus` in the bicepparam. Speech is also provisioned in `centralus` — proximity reduces cross-region latency.

### Pitfall 5 — Key Vault soft-delete reserves the global name for 7 days

**Symptom:** After `az group delete` + recreating the RG (destroy + recreate), the next deploy fails:

```
VaultAlreadyExists: The vault name 'nora-kv-dev-wgl3a3' is already in use.
... vault with the same name was recently deleted but not purged after being placed in a recoverable state.
```

**Cause:** KV has soft-delete by default (7 days). The global name stays reserved even after the RG is deleted.

**Fix:** Purge before recreating:

```bash
# List soft-deleted KVs
az keyvault list-deleted --query "[?starts_with(name, 'nora-')].{name:name, location:properties.location}" -o table

# Purge
az keyvault purge --name <kv-name> --location <region>
```

**The same applies to Cognitive Services Speech** (pitfall 5b):

```bash
az cognitiveservices account list-deleted --query "[?contains(name, 'nora')]" -o table
az cognitiveservices account purge --location <region> --resource-group <rg> --name <speech-name>
```

### Pitfall 6 — Speech kind rejects `bypass:AzureServices`

**Symptom:** Bicep deploy fails:

```
NetworkAclsBypassNotSupported: The Kind 'SpeechServices' does not support Trusted Services.
```

**Cause:** Cognitive Services has multiple kinds (SpeechServices, OpenAI, FormRecognizer, ComputerVision). Each accepts a subset of properties. **Kind `SpeechServices` does not allow `networkAcls.bypass = 'AzureServices'`** — other kinds do.

**Fix:** In `infra/bicep/modules/speech.bicep`, remove the `bypass` key from `networkAcls` (already fixed). `defaultAction: 'Allow'` is enough.

### Pitfall 7 — Contributor does not create role assignments

**Symptom:** Deploy fails:

```
The client '<sp-name>' does not have permission to perform action
'Microsoft.Authorization/roleAssignments/write' at scope '...'
```

**Cause:** An SP with the `Contributor` role provisions resources but **CANNOT create `roleAssignments`** (protection against privilege escalation). The KV refactor via UAIs needs to create the role assignment `Key Vault Secrets User` → it fails.

**Fix:** Give the SP the additional role `Role Based Access Control Administrator` (preferred — restricted to role assignments only) or `User Access Administrator` (broader):

```bash
az role assignment create \
  --assignee <SP_APP_ID> \
  --role "Role Based Access Control Administrator" \
  --scope "/subscriptions/<SUB_ID>/resourceGroups/rg-nora-dev"
```

### Pitfall 8 — Postgres blocks `CREATE EXTENSION` by default

**Symptom:** The API container app enters CrashLoopBackOff with:

```
ERROR: extension "pgcrypto" is not allow-listed for users in Azure Database for PostgreSQL
```

**Cause:** Postgres Flexible Server blocks `CREATE EXTENSION` by default. The Flyway migration fails on the first run.

**Fix (in Bicep, already configured):** Configure the `azure.extensions` parameter in the Flexible Server config:

```bicep
resource extensionsConfig 'Microsoft.DBforPostgreSQL/flexibleServers/configurations@2024-08-01' = {
  parent: server
  name: 'azure.extensions'
  properties: {
    value: 'PGCRYPTO,CITEXT'   // UPPERCASE, separado por vírgula
    source: 'user-override'
  }
}
```

To fix an existing environment (without recreating it):

```bash
az postgres flexible-server parameter set \
  --resource-group rg-nora-dev \
  --server-name nora-pg-dev-wgl3a3 \
  --name azure.extensions \
  --value 'PGCRYPTO,CITEXT'

# Depois restart o API Container App para Flyway tentar de novo:
az containerapp revision restart \
  --resource-group rg-nora-dev \
  --name nora-api-dev \
  --revision $(az containerapp show --resource-group rg-nora-dev --name nora-api-dev --query "properties.latestRevisionName" -o tsv)
```

---

## First deploy from scratch (`rg-nora-dev`)

Scenario: brand-new Azure for Students subscription, nothing provisioned.

### 1. Prepare Az CLI and log in

```bash
az login
az account set --subscription "Azure for Students"
az account show --query "{id:id, tenantId:tenantId, name:name}"
```

### 2. Resolve providers (pitfall 3)

```bash
for p in Microsoft.OperationalInsights Microsoft.DBforPostgreSQL Microsoft.App Microsoft.ContainerRegistry; do
  az provider register --namespace $p
done

# Aguardar todos ficarem Registered (~5min):
for p in Microsoft.OperationalInsights Microsoft.DBforPostgreSQL Microsoft.App; do
  echo -n "$p: "; az provider show --namespace $p --query "registrationState" -o tsv
done
```

### 3. Create the Resource Group

```bash
az group create \
  --name rg-nora-dev \
  --location brazilsouth \
  --tags project=nora env=dev managed-by=claude
```

(The RG can live in brazilsouth because RGs themselves are not blocked by the region policy. Resources go to `centralus` via the bicepparam.)

### 4. Create a Service Principal for automated deploys

```bash
# App registration
az ad app create --display-name "sp-nora-github-deploy" --query "{appId:appId, id:id}"
# Anote APP_ID

# Service Principal
az ad sp create --id <APP_ID>

# Role Contributor escopado no RG
az role assignment create \
  --assignee <APP_ID> \
  --role Contributor \
  --scope /subscriptions/<SUB_ID>/resourceGroups/rg-nora-dev

# Role RBAC Administrator (armadilha 7)
az role assignment create \
  --assignee <APP_ID> \
  --role "Role Based Access Control Administrator" \
  --scope /subscriptions/<SUB_ID>/resourceGroups/rg-nora-dev
```

### 5. Federated credentials (pitfall 1)

3 federated credentials are needed. See pitfall 1.

### 6. GitHub Secrets

Add to the repo (Settings → Secrets and variables → Actions):

```bash
gh secret set AZURE_CLIENT_ID --body "<APP_ID>" --repo sys0xFF/nora
gh secret set AZURE_TENANT_ID --body "<TENANT_ID>" --repo sys0xFF/nora
gh secret set AZURE_SUBSCRIPTION_ID --body "<SUB_ID>" --repo sys0xFF/nora

# Secrets de runtime (gerados random ou seus valores):
gh secret set PG_ADMIN_PASSWORD --body "<senha-forte-28-chars>" --repo sys0xFF/nora
gh secret set JWT_SECRET --body "<random-base64-44-chars>" --repo sys0xFF/nora
gh secret set OPENAI_API_KEY --body "<sk-...>" --repo sys0xFF/nora  # ou vazio para rodar em modo stub
```

### 7. Trigger the deploy

```bash
gh workflow run deploy-infra.yml --repo sys0xFF/nora --ref main
```

Monitor:

```bash
gh run watch
```

Total time: **~15-20min** (Postgres B1ms is the bottleneck).

### 8. Validate

```bash
az containerapp list --resource-group rg-nora-dev --query "[].{name:name, running:properties.runningStatus, fqdn:properties.configuration.ingress.fqdn}" -o table
```

Expected URLs:
- Web: `https://nora-web-dev.<env-suffix>.centralus.azurecontainerapps.io`
- API: `https://nora-api-dev.<env-suffix>.centralus.azurecontainerapps.io` (health: `/actuator/health`)

---

## Common operations

### Turning AI Search on/off (cost ~R$13-15/day while active)

Note: the product's semantic search / RAG (US15) is served by pgvector + an HTTP embedding client (PR #206, migration V021), **not** by Azure AI Search. This toggle controls only the optional/legacy AI Search resource.

Edit `infra/bicep/main.dev.bicepparam`:

```bicep
param enableSearch = true   // ligar (ex.: 29/05 para pitch 15/06)
```

Commit + merge to main → automatic deploy. **AI Search has no pause** — it is either provisioned or destroyed. Recommended strategy: turn it on ~14 days before the FIAP pitch to have time to populate the index and iterate.

### Updating GHCR images

After a push to main that touches `services/api/`, `services/nlp-worker/`, or `apps/web/`, the `build-images.yml` workflow rebuilds automatically. Published tags:

- `ghcr.io/sys0xff/nora-{api,worker,web}:latest`
- `ghcr.io/sys0xff/nora-{api,worker,web}:sha-<SHA7>`
- `ghcr.io/sys0xff/nora-{api,worker,web}:<branch>`

`main.dev.bicepparam` points to `:latest`. For rollback: switch to a specific SHA tag.

### Accessing Key Vault secrets

```bash
az keyvault secret list --vault-name nora-kv-dev-wgl3a3
az keyvault secret show --vault-name nora-kv-dev-wgl3a3 --name postgres-password --query value -o tsv
```

Requires the role `Key Vault Secrets Officer` (CRUD) or `Key Vault Secrets User` (read-only). Stratfy's Service Principal (`sp-nora-github-deploy`) already has Officer.

### Connecting to Postgres

```bash
# JDBC URL completo:
az deployment group show \
  --resource-group rg-nora-dev \
  --name <deploy-name> \
  --query properties.outputs.postgresJdbcUrl.value -o tsv

# psql direto (se firewall rule do seu IP estiver criado):
PGPASSWORD=$(az keyvault secret show --vault-name nora-kv-dev-wgl3a3 --name postgres-password --query value -o tsv)
psql "host=nora-pg-dev-wgl3a3.postgres.database.azure.com port=5432 dbname=nora user=nora_admin sslmode=require"
```

To add your IP to the firewall:

```bash
MY_IP=$(curl -s ifconfig.me)
az postgres flexible-server firewall-rule create \
  --resource-group rg-nora-dev \
  --name nora-pg-dev-wgl3a3 \
  --rule-name "dev-laptop-$(whoami)" \
  --start-ip-address $MY_IP --end-ip-address $MY_IP
```

### Full tear down (development reset)

```bash
az group delete --name rg-nora-dev --yes --no-wait
```

Takes 5-15min (the CAE is the bottleneck). **Remember pitfalls 5 (KV soft-delete) and 5b (Speech soft-delete)** — after deleting the RG, purge before recreating:

```bash
sleep 1000  # ou aguardar callback
az keyvault list-deleted --query "[?starts_with(name, 'nora-')]" -o table
az cognitiveservices account list-deleted --query "[?contains(name, 'nora')]" -o table

# Purge cada um listado:
az keyvault purge --name <kv-name> --location centralus
az cognitiveservices account purge --location centralus --resource-group rg-nora-dev --name <speech-name>
```

And **role assignments** (pitfall 7) — after recreating the RG, recreate them:

```bash
az role assignment create --assignee <APP_ID> --role Contributor --scope /subscriptions/<SUB_ID>/resourceGroups/rg-nora-dev
az role assignment create --assignee <APP_ID> --role "Role Based Access Control Administrator" --scope /subscriptions/<SUB_ID>/resourceGroups/rg-nora-dev
```

---

## Promoting dev → prod (Sub-phase 1.12)

`rg-nora-prod` does not exist yet. Plan in `docs/operations/production-readiness-gaps.md` (7 gaps) + ADR 0016.

Summary:
- Separate Bicep `main.prod.bicepparam` (prod-grade SKUs, `min replicas = 1`, `enablePurgeProtection = true`)
- Separate Service Principal `sp-nora-github-deploy-prod`
- RG `rg-nora-prod` (decide the final region)
- Monitoring + alerts wired
- Migrations safety strategy (3 options evaluated)
- Operational LGPD: **delivered** (ADR 0029) — `DELETE /privacy/meetings/{id}` (right to be forgotten) + scheduled `RetentionSweeper` + `PrivacyFlowIntegrationTest`. All that remains is validating retention enforcement in the prod environment.
- Secrets rotation policy
- Tested DR runbook (quarterly drill)

---

## History

| Date | Change |
|---|---|
| 2026-05-14 | Runbook created during Sub-phase 1.10 (Docs Refresh). Covers the 8 Azure for Students pitfalls catalogued in Sub-phase 1.9. Promotion to prod is left to `docs/operations/production-readiness-gaps.md` |
| 2026-06-06 | v1.0 (NORA Architect / Tech Lead): Doc x code reconciliation + standardisation (pre-presentation audit) |
| 2026-08-07 | Marked as **historical** (`status: historical`) by ADR 0034. Replaced by `proxmox-deploy.md`; shutdown in `azure-decommission.md`. Content preserved intact — the 8 pitfalls are a learning record and the stack inventory feeds the decommission |
