# NORA — Infra Bicep

Bicep IaC pra deploy do NORA em Azure. Compatível com Azure for Students (~R$500 de crédito).

## Estrutura

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

## Pré-requisitos

```powershell
# Az CLI
winget install -e --id Microsoft.AzureCLI

# Bicep CLI
az bicep install

# Login
az login
az account set --subscription "Azure for Students"
```

Resource Group já existe: `rg-nora-dev` em `brazilsouth`.

## Deploy

### 1. Configurar secrets (env vars locais, NÃO commitar)

```powershell
$env:PG_ADMIN_PASSWORD = "<senha forte 12+ chars>"
$env:JWT_SECRET = "<random 32+ chars, ex.: openssl rand -hex 32>"
$env:OPENAI_API_KEY = "<sk-...>"          # opcional; vazio = worker em modo stub
$env:REGISTRY_PASSWORD = "<token GHCR>"   # opcional; vazio = imagens públicas
```

### 2. Validar template

```powershell
az bicep build --file main.bicep --outdir .bicep-out
# Espera: zero warnings, zero errors
```

### 3. What-if (preview do que seria criado)

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

Inclui: `webUrl`, `apiUrl`, `workerInternalFqdn`, `postgresFqdn`, `postgresJdbcUrl`, `keyVaultUri`, `storageAccountName`, `appInsightsName`, `searchEndpoint`.

## Custo estimado (MVP, sem Search)

| Recurso | SKU | Custo/mês BRL |
|---|---|---|
| Container Apps Environment | Consumption | grátis (paga por uso) |
| Container App: api | 0.5 vCPU, 1Gi, min=1 | ~R$15-25 |
| Container App: worker | 0.5 vCPU, 1Gi, min=0 | ~R$0-10 |
| Container App: web | 0.25 vCPU, 0.5Gi, min=0 | ~R$0-5 |
| Postgres Flexible B1ms | Burstable | ~R$30-40 |
| Storage Standard LRS | 32GB | ~R$2 |
| Key Vault Standard | Standard | ~R$3 |
| Log Analytics | PerGB2018, 1GB/dia | ~R$5-10 |
| Application Insights | Workspace-based | incluso no LA |
| **Total sem Search** | | **~R$60-90/mês** |

## Azure AI Search — ligar/desligar

**ATENÇÃO**: AI Search não tem pause/start. Cobra ~R$13-15/dia enquanto provisionado.

### Estratégia recomendada pro pitch FIAP 12/06

Provisionar ~14 dias antes da apresentação (29/05 → 12/06), custo ~R$200, sobra ~R$300 dos 500.

### Ligar

Editar `main.dev.bicepparam`:

```bicep
param enableSearch = true
param searchSku = 'basic'
```

E re-deployar:

```powershell
az deployment group create `
  --resource-group rg-nora-dev `
  --template-file main.bicep `
  --parameters main.dev.bicepparam
```

### Desligar

Voltar `enableSearch = false` e re-deployar. O Bicep destrói o recurso Search mas mantém todo o resto.

Alternativa cirúrgica:

```powershell
az search service delete `
  --resource-group rg-nora-dev `
  --name <searchName-do-output> `
  --yes
```

## Teardown completo

```powershell
az group delete --name rg-nora-dev --yes --no-wait
```

Zera tudo. Útil pra começar do zero.

## Imagens

Default = placeholder Microsoft (`mcr.microsoft.com/k8se/quickstart:latest`). Deploy funciona pra testar a infra, mas as Container Apps respondem com a página padrão.

Pipeline de build/push (futuro): substituir os 3 params em `main.dev.bicepparam`:

```bicep
param apiImage = 'ghcr.io/sys0xff/nora-api:<sha-curto>'
param workerImage = 'ghcr.io/sys0xff/nora-worker:<sha-curto>'
param webImage = 'ghcr.io/sys0xff/nora-web:<sha-curto>'
```

GHCR é público por default — só precisa de registry credentials se as imagens forem privadas.

## CI/CD

O workflow `.github/workflows/ci.yml` tem job `infra` que roda `az bicep build` em todos os `.bicep` quando arquivos em `infra/bicep/**` mudam. Não deploya — só valida sintaxe.

Pra deploy automatizado (Subfase futura): criar Service Principal escopado ao RG, adicionar federated credentials no GitHub, criar workflow `deploy-infra.yml` com `azure/login@v2` + `az deployment group create`.

## Limitações conhecidas

- **Public access no Postgres**: simplifica MVP mas expõe banco. Migrar pra VNet integration em prod.
- **Container Apps sem ACR**: usar imagem pública (ghcr.io). Pra prod, criar ACR no Bicep.
- **Sem managed identity → Key Vault wiring**: secrets vão direto como `secretsObject` no Container App. Pra prod, usar Key Vault references com role assignment.
- **Sem custom domain / certificado**: usa FQDN default do Container Apps (`*.brazilsouth.azurecontainerapps.io`).
- **Sem WAF / Front Door**: pra prod, adicionar.

Tudo isso é deliberado pro MVP. Documentado pra revisitar pós-pitch.

## Naming convention

```
{namePrefix}-{tipo}-{env}[-{suffix}]

ex.: nora-api-dev
     nora-pg-dev-a1b2c3
     norastdevxyz12345 (storage — sem hífen)
```

`suffix` = `take(uniqueString(rg.id), 6)`. Garante unicidade global onde necessário (storage, kv, pg, search).
