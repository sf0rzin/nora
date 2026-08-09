# NORA — Bicep Infra

Bicep IaC for deploying NORA on Azure. Compatible with Azure for Students (~R$500 of credit).

## Structure

```
infra/bicep/
├── main.bicep                 # orquestrador top-level
├── main.dev.bicepparam        # parâmetros do ambiente dev
└── modules/
    ├── log-analytics.bicep    # workspace de logs/métricas
    ├── appinsights.bicep      # telemetria (workspace-based)
    ├── storage.bicep          # blob containers
    ├── keyvault.bicep         # cofre de secrets (RBAC)
    ├── postgres.bicep         # Flexible Server B1ms
    ├── container-apps-env.bicep   # CAE compartilhado
    ├── container-app.bicep    # template reusável (api/worker/web)
    └── search.bicep           # Azure AI Search (opcional)
```

## Prerequisites

```powershell
# Az CLI
winget install -e --id Microsoft.AzureCLI

# Bicep CLI
az bicep install

# Login
az login
az account set --subscription "Azure for Students"
```

The Resource Group already exists: `rg-nora-dev` (created in `brazilsouth` before the policy was discovered).

> **NOTE — Azure for Students restrictions (2 layers)**:
>
> 1. **Region policy (`sys.regionrestriction`)** allows only: `mexicocentral`, `northcentralus`, `eastus`, `centralus`, `canadacentral`. Brazil South is **not** allowed for resources. RGs themselves can stay in brazilsouth.
> 2. **Per-service offer restriction**: `eastus` allows Storage/KV/LA/AI but REJECTS Postgres Flexible Server with `LocationIsOfferRestricted`. Tested 13/05/2026: `centralus`, `northcentralus`, `canadacentral`, `mexicocentral` accept Postgres B1ms.
>
> **Bicep deploys resources in `centralus`** (default). Critical resource providers (`Microsoft.OperationalInsights`, `Microsoft.DBforPostgreSQL`, `Microsoft.App`) need explicit registration (`az provider register --namespace <ns>`) — Students does not auto-register.

## Deploy

### 1. Configure secrets (local env vars, do NOT commit)

```powershell
$env:PG_ADMIN_PASSWORD = "<senha forte 12+ chars>"
$env:JWT_SECRET = "<random 32+ chars, ex.: openssl rand -hex 32>"
$env:OPENAI_API_KEY = "<sk-...>"          # opcional; vazio = worker em modo stub
$env:REGISTRY_PASSWORD = "<token GHCR>"   # opcional; vazio = imagens públicas
```

### 2. Validate the template

```powershell
az bicep build --file main.bicep --outdir .bicep-out
# Espera: zero warnings, zero errors
```

### 3. What-if (preview of what would be created)

```powershell
az deployment group what-if `
  --resource-group rg-nora-dev `
  --template-file main.bicep `
  --parameters main.dev.bicepparam
```

### 4. Deploy

```powershell
az deployment group create `
  --resource-group rg-nora-dev `
  --template-file main.bicep `
  --parameters main.dev.bicepparam `
  --name nora-deploy-$(Get-Date -Format "yyyyMMdd-HHmm")
```

### 5. Outputs

```powershell
az deployment group show `
  --resource-group rg-nora-dev `
  --name <nome-do-deploy> `
  --query properties.outputs
```

Includes: `webUrl`, `apiUrl`, `workerInternalFqdn`, `postgresFqdn`, `postgresJdbcUrl`, `keyVaultUri`, `storageAccountName`, `appInsightsName`, `searchEndpoint`.

## Estimated cost (MVP, without Search)

| Resource | SKU | Cost/month BRL |
|---|---|---|
| Container Apps Environment | Consumption | free (pay per use) |
| Container App: api | 0.5 vCPU, 1Gi, min=1 | ~R$15-25 |
| Container App: worker | 0.5 vCPU, 1Gi, min=0 | ~R$0-10 |
| Container App: web | 0.25 vCPU, 0.5Gi, min=0 | ~R$0-5 |
| Postgres Flexible B1ms | Burstable | ~R$30-40 |
| Storage Standard LRS | 32GB | ~R$2 |
| Key Vault Standard | Standard | ~R$3 |
| Log Analytics | PerGB2018, 1GB/day | ~R$5-10 |
| Application Insights | Workspace-based | included in LA |
| **Total without Search** | | **~R$60-90/month** |

## Azure AI Search — turning on/off

**WARNING**: AI Search has no pause/start. It charges ~R$13-15/day while provisioned.

### Recommended strategy for the FIAP pitch 12/06

Provision ~14 days before the presentation (29/05 → 12/06), cost ~R$200, leaving ~R$300 of the 500.

### Turning on

Edit `main.dev.bicepparam`:

```bicep
param enableSearch = true
param searchSku = 'basic'
```

And redeploy:

```powershell
az deployment group create `
  --resource-group rg-nora-dev `
  --template-file main.bicep `
  --parameters main.dev.bicepparam
```

### Turning off

Set `enableSearch = false` back and redeploy. Bicep destroys the Search resource but keeps everything else.

Surgical alternative:

```powershell
az search service delete `
  --resource-group rg-nora-dev `
  --name <searchName-do-output> `
  --yes
```

## Full teardown

```powershell
az group delete --name rg-nora-dev --yes --no-wait
```

Wipes everything. Useful for starting from scratch.

## Images

Default = Microsoft placeholder (`mcr.microsoft.com/k8se/quickstart:latest`). The deploy works for testing the infra, but the Container Apps respond with the default page.

Build/push pipeline (future): replace the 3 params in `main.dev.bicepparam`:

```bicep
param apiImage = 'ghcr.io/sys0xff/nora-api:<sha-curto>'
param workerImage = 'ghcr.io/sys0xff/nora-worker:<sha-curto>'
param webImage = 'ghcr.io/sys0xff/nora-web:<sha-curto>'
```

GHCR is public by default — registry credentials are only needed if the images are private.

## CI/CD

The `.github/workflows/ci.yml` workflow has an `infra` job that runs `az bicep build` on every `.bicep` when files under `infra/bicep/**` change. It does not deploy — it only validates syntax.

For an automated deploy (future Sub-phase): create a Service Principal scoped to the RG, add federated credentials in GitHub, create a `deploy-infra.yml` workflow with `azure/login@v2` + `az deployment group create`.

## Known limitations

- **Public access on Postgres**: simplifies the MVP but exposes the database (with a firewall by IP + AllowAzureServices). Migrate to VNet integration in prod.
- **Container Apps without ACR**: use a public image (ghcr.io). For prod, create an ACR in Bicep.
- **No custom domain / certificate**: uses the Container Apps default FQDN. Current resources are in **centralus** (`*.centralus.azurecontainerapps.io`).
- **No WAF / Front Door**: for prod, add it.
- **Container Apps without zone redundancy**: acceptable for the MVP, turn it on in prod (`zoneRedundant: true`).

### Already addressed in Sub-phase 1.9+
- ✅ **Managed Identity → Key Vault wiring**: 3 UAIs (api/worker/web) with the `Key Vault Secrets User` role + Container App secrets as `keyVaultUrl + identity` (see `main.bicep:430-490`).
- ✅ **Probes (liveness/readiness/startup)**: declared in `container-app.bicep` — the Container Apps platform ignores the Dockerfile's `HEALTHCHECK`.

All of this is deliberate for the MVP. Documented to be revisited post-pitch.

## Naming convention

```
{namePrefix}-{tipo}-{env}[-{suffix}]

ex.: nora-api-dev
     nora-pg-dev-a1b2c3
     norastdevxyz12345 (storage — sem hífen)
```

`suffix` = `take(uniqueString(rg.id), 6)`. It guarantees global uniqueness where necessary (storage, kv, pg, search).
