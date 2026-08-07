---
title: "Runbook — Deploy do NORA na Azure (HISTÓRICO)"
owner: Arquiteto NORA (Tech Lead)
status: historical
version: 1.0
last_reviewed: 2026-06-06
---

# Runbook — Deploy do NORA na Azure

> **DOCUMENTO HISTÓRICO — NÃO OPERE POR ELE.**
>
> A produção saiu da Azure para uma VM Proxmox self-hosted
> ([ADR 0034](../adr/0034-migracao-azure-para-proxmox.md)). O runbook vigente é
> [`proxmox-deploy.md`](proxmox-deploy.md); o desligamento do que ficou na Azure está em
> [`azure-decommission.md`](azure-decommission.md).
>
> Este arquivo é mantido por dois motivos: as **8 armadilhas do Azure for Students** são
> registro de aprendizado citado pelo ADR 0034, e a descrição da stack antiga é a
> referência para o inventário do decommission. Os comandos `az` abaixo apontam para
> recursos que estão sendo destruídos.

> **Audiência (à época):** quem opera o deploy do NORA em Azure.
>
> **Pré-requisitos:** subscription Azure ativa (atualmente "Azure for Students"), Az CLI 2.86+, Bicep CLI 0.43+, GitHub repo com permissões para criar workflows.

> **Ambiente de produção (modelo de ambiente único).** O `rg-nora-dev` é, na prática,
> o **ambiente de produção** do NORA: é ele que serve o domínio público `nora.systems` /
> `api.nora.systems` com tráfego real. NÃO existe um segundo ambiente — operamos um único
> ambiente vivo. Os nomes físicos (`rg-nora-dev`, `nora-*-dev`, GitHub environment `dev`)
> são **históricos**: renomeá-los exigiria recriar Container Apps + Postgres (downtime) e
> reconfigurar as federated credentials OIDC (subject `environment:dev`). Esse rename
> cosmético fica para uma janela de manutenção pós-pitch; até lá, **`dev` = produção**. Um
> eventual ambiente de staging separado, se desejado, seria criado como `rg-nora-stg`.

## Visão geral

NORA é deployado em Azure via 3 workflows GitHub Actions:

1. **`build-images.yml`** — builda 3 imagens Docker (api/worker/web) e publica em `ghcr.io/sys0xff/nora-{api,worker,web}` quando código de cada serviço muda
2. **`deploy-infra.yml`** — deploya/atualiza recursos Azure via Bicep IaC em `rg-nora-dev` quando `infra/bicep/**` muda
3. **`ci.yml`** — testes, lint, type-check, validate Bicep

Stack provisionada (ver `infra/bicep/main.bicep`):

- 1 Resource Group (`rg-nora-dev` em `brazilsouth`; recursos em `centralus`)
- 1 Container Apps Environment (Consumption profile, scale-to-zero)
- 3 Container Apps (api, worker, web) com User-Assigned Managed Identities
- 1 Postgres Flexible Server B1ms (banco `nora`, extensions PGCRYPTO + CITEXT)
- 1 Key Vault Standard com 4 secrets (postgres-password, jwt-secret, openai-api-key, azure-speech-key)
- 1 Storage Account LRS (Standard, blob soft-delete 7 dias)
- 1 Log Analytics workspace + 1 Application Insights workspace-based
- 1 Azure Speech (Cognitive Services SpeechServices S0)
- 3 User-Assigned Managed Identities (uai-api, uai-worker, uai-web)
- (opcional) 1 Azure AI Search Basic — desligado por padrão. Atenção: a busca semântica / RAG (US15) já foi entregue (PR #206) via pgvector + HTTP embedding client provider-agnóstico (Gemini/OpenAI), migration V021 `meeting_embeddings` — **não** depende de Azure AI Search. Este recurso permanece opcional/legado.

Custo dev (atualmente provisionado): **~R$110-180/mês** (ver `docs/engineering/architecture.md` §12 "Stack rationale" e `docs/product/roadmap.md` Unit Economics).

---

## As 8 armadilhas do Azure for Students (CATALOGADAS)

Estas 8 armadilhas foram descobertas durante Sub-fase 1.9 (2026-05-13). Aplique fixes antes de deploy senão deploy falha.

### Armadilha 1 — Federated credential precisa subject por contexto

**Sintoma:** Deploy via `deploy-infra.yml` falha imediatamente em `azure/login@v2` com:

```
AADSTS700213: No matching federated identity record found for presented assertion
subject 'repo:sys0xFF/nora:environment:dev'
```

**Causa:** Service Principal criado com federated credential para `repo:sys0xFF/nora:ref:refs/heads/main`, mas job tem `environment: dev` no workflow → subject muda para `repo:sys0xFF/nora:environment:dev`.

**Fix (uma vez):**

```bash
az ad app federated-credential create --id <APP_ID> --parameters '{
  "name": "github-environment-dev",
  "issuer": "https://token.actions.githubusercontent.com",
  "subject": "repo:sys0xFF/nora:environment:dev",
  "audiences": ["api://AzureADTokenExchange"]
}'
```

3 federated credentials totais necessários:
- `github-main-branch` (subject `repo:sys0xFF/nora:ref:refs/heads/main`) — para push em main sem environment
- `github-pull-requests` (subject `repo:sys0xFF/nora:pull_request`) — para what-if em PR
- `github-environment-dev` (subject `repo:sys0xFF/nora:environment:dev`) — para job com `environment: dev`

### Armadilha 2 — Region restriction policy

**Sintoma:** Deploy falha com `RequestDisallowedByAzure`:

```
This policy maintains a set of best available regions where your subscription
can deploy resources.
```

**Causa:** Subscription Azure for Students tem policy `sys.regionrestriction` que permite só:
- `mexicocentral`
- `northcentralus`
- `eastus`
- `centralus`
- `canadacentral`

Brazil South **não** está permitido para recursos (RGs em si podem ficar em brazilsouth, mas recursos dentro precisam estar em uma das 5 regiões permitidas).

**Fix:** Usar `location = 'centralus'` no `main.dev.bicepparam` (já configurado).

```powershell
$az = "az.cmd"
& $az policy assignment list --query "[?contains(displayName, 'region') || contains(displayName, 'Region')].{name:displayName, params:parameters}" -o json
```

### Armadilha 3 — Resource providers não auto-registram

**Sintoma:** Deploy falha com `MissingSubscriptionRegistration`:

```
The subscription is not registered to use namespace 'Microsoft.OperationalInsights'
```

**Causa:** Subscriptions Azure normais auto-registram providers no primeiro uso. Students **não**.

**Fix (uma vez por provider):**

```bash
az provider register --namespace Microsoft.OperationalInsights
az provider register --namespace Microsoft.DBforPostgreSQL
az provider register --namespace Microsoft.App
az provider register --namespace Microsoft.ContainerRegistry  # preventivo se for usar ACR no futuro
```

Aguardar registration ficar `Registered` (alguns minutos cada):

```bash
az provider show --namespace Microsoft.OperationalInsights --query "registrationState"
```

### Armadilha 4 — Postgres rejeita `eastus` (offer restriction)

**Sintoma:** Deploy de Postgres falha em `eastus` com:

```
LocationIsOfferRestricted: Subscriptions are restricted from provisioning in location 'eastus'
```

**Causa:** Outras categorias de Azure for Students aceitam recursos em `eastus`, mas Postgres Flexible Server **não**. Restriction de offer por serviço.

**Regiões testadas (2026-05-13)** que aceitam Postgres B1ms:
- `centralus` — aceita
- `northcentralus` — aceita
- `canadacentral` — aceita
- `mexicocentral` — aceita
- `eastus` — rejeita

**Fix:** Manter `centralus` no bicepparam. Speech também provisionado em `centralus` — proximidade reduz latência cross-region.

### Armadilha 5 — Key Vault soft-delete reserva nome global por 7 dias

**Sintoma:** Após `az group delete` + recreate do RG (destruir + recriar), próximo deploy falha:

```
VaultAlreadyExists: The vault name 'nora-kv-dev-wgl3a3' is already in use.
... vault with the same name was recently deleted but not purged after being placed in a recoverable state.
```

**Causa:** KV tem soft-delete por padrão (7 dias). Nome global fica reservado mesmo após delete do RG.

**Fix:** Purge antes de recreate:

```bash
# Lista KVs soft-deleted
az keyvault list-deleted --query "[?starts_with(name, 'nora-')].{name:name, location:properties.location}" -o table

# Purge
az keyvault purge --name <kv-name> --location <region>
```

**O mesmo vale para Cognitive Services Speech** (armadilha 5b):

```bash
az cognitiveservices account list-deleted --query "[?contains(name, 'nora')]" -o table
az cognitiveservices account purge --location <region> --resource-group <rg> --name <speech-name>
```

### Armadilha 6 — Speech kind rejeita `bypass:AzureServices`

**Sintoma:** Bicep deploy falha:

```
NetworkAclsBypassNotSupported: The Kind 'SpeechServices' does not support Trusted Services.
```

**Causa:** Cognitive Services tem múltiplos kinds (SpeechServices, OpenAI, FormRecognizer, ComputerVision). Cada um aceita um subset de propriedades. **Kind `SpeechServices` não permite `networkAcls.bypass = 'AzureServices'`** — outros kinds aceitam.

**Fix:** No `infra/bicep/modules/speech.bicep`, remover chave `bypass` do `networkAcls` (já corrigido). `defaultAction: 'Allow'` basta.

### Armadilha 7 — Contributor não cria role assignments

**Sintoma:** Deploy falha:

```
The client '<sp-name>' does not have permission to perform action
'Microsoft.Authorization/roleAssignments/write' at scope '...'
```

**Causa:** SP com role `Contributor` provisiona recursos mas **NÃO consegue criar `roleAssignments`** (proteção contra privilege escalation). KV refactor via UAIs precisa criar role assignment `Key Vault Secrets User` → falha.

**Fix:** Dar ao SP role adicional `Role Based Access Control Administrator` (preferida — restrita só a role assignments) ou `User Access Administrator` (mais ampla):

```bash
az role assignment create \
  --assignee <SP_APP_ID> \
  --role "Role Based Access Control Administrator" \
  --scope "/subscriptions/<SUB_ID>/resourceGroups/rg-nora-dev"
```

### Armadilha 8 — Postgres bloqueia `CREATE EXTENSION` por default

**Sintoma:** API container app entra em CrashLoopBackOff com:

```
ERROR: extension "pgcrypto" is not allow-listed for users in Azure Database for PostgreSQL
```

**Causa:** Postgres Flexible Server bloqueia `CREATE EXTENSION` por padrão. Flyway migration falha na primeira execução.

**Fix (no Bicep, já configurado):** Configurar parameter `azure.extensions` na Flexible Server config:

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

Pra fix em ambiente existente (sem recriar):

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

## Primeiro deploy do zero (`rg-nora-dev`)

Cenário: subscription Azure for Students nova, sem nada provisionado.

### 1. Preparar Az CLI e login

```bash
az login
az account set --subscription "Azure for Students"
az account show --query "{id:id, tenantId:tenantId, name:name}"
```

### 2. Resolver providers (armadilha 3)

```bash
for p in Microsoft.OperationalInsights Microsoft.DBforPostgreSQL Microsoft.App Microsoft.ContainerRegistry; do
  az provider register --namespace $p
done

# Aguardar todos ficarem Registered (~5min):
for p in Microsoft.OperationalInsights Microsoft.DBforPostgreSQL Microsoft.App; do
  echo -n "$p: "; az provider show --namespace $p --query "registrationState" -o tsv
done
```

### 3. Criar Resource Group

```bash
az group create \
  --name rg-nora-dev \
  --location brazilsouth \
  --tags project=nora env=dev managed-by=claude
```

(RG pode ficar em brazilsouth pois RGs em si não são bloqueados pela region policy. Recursos vão para `centralus` via bicepparam.)

### 4. Criar Service Principal para deploy automatizado

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

### 5. Federated credentials (armadilha 1)

3 federated credentials necessárias. Veja armadilha 1.

### 6. GitHub Secrets

Adicionar ao repo (Settings → Secrets and variables → Actions):

```bash
gh secret set AZURE_CLIENT_ID --body "<APP_ID>" --repo sys0xFF/nora
gh secret set AZURE_TENANT_ID --body "<TENANT_ID>" --repo sys0xFF/nora
gh secret set AZURE_SUBSCRIPTION_ID --body "<SUB_ID>" --repo sys0xFF/nora

# Secrets de runtime (gerados random ou seus valores):
gh secret set PG_ADMIN_PASSWORD --body "<senha-forte-28-chars>" --repo sys0xFF/nora
gh secret set JWT_SECRET --body "<random-base64-44-chars>" --repo sys0xFF/nora
gh secret set OPENAI_API_KEY --body "<sk-...>" --repo sys0xFF/nora  # ou vazio para rodar em modo stub
```

### 7. Disparar deploy

```bash
gh workflow run deploy-infra.yml --repo sys0xFF/nora --ref main
```

Monitorar:

```bash
gh run watch
```

Tempo total: **~15-20min** (Postgres B1ms é o gargalo).

### 8. Validar

```bash
az containerapp list --resource-group rg-nora-dev --query "[].{name:name, running:properties.runningStatus, fqdn:properties.configuration.ingress.fqdn}" -o table
```

URLs esperadas:
- Web: `https://nora-web-dev.<env-suffix>.centralus.azurecontainerapps.io`
- API: `https://nora-api-dev.<env-suffix>.centralus.azurecontainerapps.io` (health: `/actuator/health`)

---

## Operações comuns

### Ligar/desligar AI Search (custo ~R$13-15/dia ativo)

Atenção: a busca semântica / RAG do produto (US15) é servida por pgvector + HTTP embedding client (PR #206, migration V021), **não** por Azure AI Search. Este toggle controla apenas o recurso opcional/legado de AI Search.

Editar `infra/bicep/main.dev.bicepparam`:

```bicep
param enableSearch = true   // ligar (ex.: 29/05 para pitch 15/06)
```

Commit + merge em main → deploy automático. **AI Search não tem pause** — só provisiona ou destrói. Estratégia recomendada: ligar ~14 dias antes do pitch FIAP para ter tempo de popular índice e iterar.

### Atualizar imagens GHCR

Após push em main que toque `services/api/`, `services/nlp-worker/`, ou `apps/web/`, workflow `build-images.yml` rebuilda automaticamente. Tags publicadas:

- `ghcr.io/sys0xff/nora-{api,worker,web}:latest`
- `ghcr.io/sys0xff/nora-{api,worker,web}:sha-<SHA7>`
- `ghcr.io/sys0xff/nora-{api,worker,web}:<branch>`

`main.dev.bicepparam` aponta para `:latest`. Para rollback: trocar para tag SHA específica.

### Acessar secrets do Key Vault

```bash
az keyvault secret list --vault-name nora-kv-dev-wgl3a3
az keyvault secret show --vault-name nora-kv-dev-wgl3a3 --name postgres-password --query value -o tsv
```

Requer role `Key Vault Secrets Officer` (CRUD) ou `Key Vault Secrets User` (read-only). O Service Principal da Stratfy (`sp-nora-github-deploy`) já tem Officer.

### Conectar Postgres

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

Pra adicionar seu IP no firewall:

```bash
MY_IP=$(curl -s ifconfig.me)
az postgres flexible-server firewall-rule create \
  --resource-group rg-nora-dev \
  --name nora-pg-dev-wgl3a3 \
  --rule-name "dev-laptop-$(whoami)" \
  --start-ip-address $MY_IP --end-ip-address $MY_IP
```

### Tear down completo (development reset)

```bash
az group delete --name rg-nora-dev --yes --no-wait
```

Demora 5-15min (CAE é o gargalo). **Lembre das armadilhas 5 (KV soft-delete) e 5b (Speech soft-delete)** — após delete do RG, faça purge antes de recreate:

```bash
sleep 1000  # ou aguardar callback
az keyvault list-deleted --query "[?starts_with(name, 'nora-')]" -o table
az cognitiveservices account list-deleted --query "[?contains(name, 'nora')]" -o table

# Purge cada um listado:
az keyvault purge --name <kv-name> --location centralus
az cognitiveservices account purge --location centralus --resource-group rg-nora-dev --name <speech-name>
```

E **role assignments** (armadilha 7) — após RG recreate, recriar:

```bash
az role assignment create --assignee <APP_ID> --role Contributor --scope /subscriptions/<SUB_ID>/resourceGroups/rg-nora-dev
az role assignment create --assignee <APP_ID> --role "Role Based Access Control Administrator" --scope /subscriptions/<SUB_ID>/resourceGroups/rg-nora-dev
```

---

## Promover dev → prod (Sub-fase 1.12)

`rg-nora-prod` ainda não existe. Plano em `docs/operations/production-readiness-gaps.md` (7 gaps) + ADR 0016.

Resumo:
- Bicep `main.prod.bicepparam` separado (SKUs prod-grade, `min replicas = 1`, `enablePurgeProtection = true`)
- Service Principal separado `sp-nora-github-deploy-prod`
- RG `rg-nora-prod` (decidir region final)
- Monitoring + alerts wired
- Migrations safety strategy (3 opções avaliadas)
- LGPD operacional: **entregue** (ADR 0029) — `DELETE /privacy/meetings/{id}` (direito ao esquecimento) + `RetentionSweeper` agendado + `PrivacyFlowIntegrationTest`. Resta apenas validar enforcement de retenção no ambiente prod.
- Secrets rotation policy
- DR runbook testado (drill quarterly)

---

## Histórico

| Data | Mudança |
|---|---|
| 2026-05-14 | Runbook criado durante Sub-fase 1.10 (Docs Refresh). Cobre 8 armadilhas do Azure for Students catalogadas na Sub-fase 1.9. Promoção para prod fica como `docs/operations/production-readiness-gaps.md` |
| 2026-06-06 | v1.0 (Arquiteto NORA / Tech Lead): Reconciliação doc x código + padronização (auditoria pré-apresentação) |
| 2026-08-07 | Marcado como **histórico** (`status: historical`) pelo ADR 0034. Substituído por `proxmox-deploy.md`; desligamento em `azure-decommission.md`. Conteúdo preservado intacto — as 8 armadilhas são registro de aprendizado e o inventário da stack alimenta o decommission |
