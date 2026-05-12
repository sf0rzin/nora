// main.bicep — NORA infra orquestrador
// Deploya: Log Analytics → App Insights → Storage → Key Vault → Postgres → Container Apps Env → api/worker/web → (opcional) Search
//
// Uso:
//   az deployment group create \
//     --resource-group rg-nora-dev \
//     --template-file main.bicep \
//     --parameters main.dev.bicepparam
//
// Ver README.md pra detalhes de ligar/desligar Search e custos.

targetScope = 'resourceGroup'

// ============================================================
// PARAMS — top level
// ============================================================

@description('Ambiente lógico. Usado em naming e tags. dev | staging | prod.')
@allowed([
  'dev'
  'staging'
  'prod'
])
param env string = 'dev'

@description('Região azure pra todos os recursos.')
param location string = resourceGroup().location

@description('Prefixo de naming. Mantém consistência entre recursos.')
@minLength(2)
@maxLength(6)
param namePrefix string = 'nora'

@description('Tags aplicadas a todos os recursos.')
param tags object = {
  project: 'nora'
  env: env
  'managed-by': 'bicep'
}

// ============================================================
// PARAMS — secrets (injetar via bicepparam ou --parameters CLI)
// ============================================================

@description('Usuário admin do Postgres.')
param postgresAdminLogin string = 'nora_admin'

@description('Senha admin do Postgres. Injetar via env var ou Key Vault reference.')
@secure()
@minLength(12)
param postgresAdminPassword string

@description('Secret HMAC pra assinar JWT no backend. Mínimo 32 chars.')
@secure()
@minLength(32)
param jwtSecret string

@description('OpenAI API Key. Se vazio, worker roda em modo stub.')
@secure()
param openAiApiKey string = ''

@description('Modelo OpenAI default usado pelo worker.')
param openAiModel string = 'gpt-4o-mini'

// ============================================================
// PARAMS — imagens
// ============================================================

@description('Imagem da API Spring Boot. Default = placeholder Microsoft pra deploy de skeleton.')
param apiImage string = 'mcr.microsoft.com/k8se/quickstart:latest'

@description('Imagem do worker NLP Python.')
param workerImage string = 'mcr.microsoft.com/k8se/quickstart:latest'

@description('Imagem do frontend Next.js.')
param webImage string = 'mcr.microsoft.com/k8se/quickstart:latest'

@description('Registry server (ex.: ghcr.io). Vazio = imagens públicas.')
param registryServer string = ''

@description('Username pro registry.')
param registryUsername string = ''

@description('Password pro registry.')
@secure()
param registryPassword string = ''

// ============================================================
// PARAMS — Azure AI Search (opcional)
// ============================================================

@description('Habilita Azure AI Search Basic. Cobra ~R$13-15/dia enquanto provisionado. Manter false durante dev; ligar ~14 dias antes do pitch.')
param enableSearch bool = false

@description('SKU do Search. basic = ~$73 USD/mês.')
@allowed([
  'free'
  'basic'
  'standard'
])
param searchSku string = 'basic'

// ============================================================
// PARAMS — Postgres firewall
// ============================================================

@description('Regras de firewall adicionais pro Postgres. Formato: [{ name, startIpAddress, endIpAddress }].')
param postgresFirewallRules array = []

// ============================================================
// NAMING — determinístico + único onde precisa
// ============================================================

var nameSuffix = take(uniqueString(resourceGroup().id), 6)
var nameSuffixLong = take(uniqueString(resourceGroup().id), 8)

var laName = '${namePrefix}-la-${env}'
var aiName = '${namePrefix}-ai-${env}'
var storageName = toLower(replace('${namePrefix}st${env}${nameSuffixLong}', '-', ''))
var kvName = '${namePrefix}-kv-${env}-${nameSuffix}'
var pgName = '${namePrefix}-pg-${env}-${nameSuffix}'
var caEnvName = '${namePrefix}-cae-${env}'
var apiName = '${namePrefix}-api-${env}'
var workerName = '${namePrefix}-worker-${env}'
var webName = '${namePrefix}-web-${env}'
var searchName = '${namePrefix}-search-${env}-${nameSuffix}'

var registry = empty(registryServer) ? {} : {
  server: registryServer
  username: registryUsername
  passwordSecretRef: 'registry-password'
}

// ============================================================
// MODULES — observability
// ============================================================

module logAnalytics 'modules/log-analytics.bicep' = {
  name: 'logAnalytics'
  params: {
    name: laName
    location: location
    tags: tags
    retentionInDays: 30
  }
}

module appInsights 'modules/appinsights.bicep' = {
  name: 'appInsights'
  params: {
    name: aiName
    location: location
    tags: tags
    workspaceId: logAnalytics.outputs.id
  }
}

// ============================================================
// MODULES — storage + secrets
// ============================================================

module storage 'modules/storage.bicep' = {
  name: 'storage'
  params: {
    name: storageName
    location: location
    tags: tags
    sku: 'Standard_LRS'
  }
}

module keyVault 'modules/keyvault.bicep' = {
  name: 'keyVault'
  params: {
    name: kvName
    location: location
    tags: tags
    enablePurgeProtection: false // dev — permite teardown rápido
  }
}

// ============================================================
// MODULES — banco
// ============================================================

module postgres 'modules/postgres.bicep' = {
  name: 'postgres'
  params: {
    name: pgName
    location: location
    tags: tags
    adminLogin: postgresAdminLogin
    adminPassword: postgresAdminPassword
    databaseName: 'nora'
    skuName: 'Standard_B1ms'
    skuTier: 'Burstable'
    storageSizeGB: 32
    backupRetentionDays: 7
    allowAzureServices: true
    firewallRules: postgresFirewallRules
  }
}

// ============================================================
// MODULES — runtime (Container Apps)
// ============================================================

module containerAppsEnv 'modules/container-apps-env.bicep' = {
  name: 'containerAppsEnv'
  params: {
    name: caEnvName
    location: location
    tags: tags
    workspaceCustomerId: logAnalytics.outputs.customerId
    workspaceSharedKey: logAnalytics.outputs.sharedKey
    appInsightsConnectionString: appInsights.outputs.connectionString
  }
}

// ---- Search (condicional, antes das apps pra worker poder consumir endpoint) ----

module searchService 'modules/search.bicep' = if (enableSearch) {
  name: 'searchService'
  params: {
    name: searchName
    location: location
    tags: tags
    sku: searchSku
    replicaCount: 1
    partitionCount: 1
    enableSemanticRanker: false
  }
}

// ---- Worker NLP (internal ingress; api fala com ele) ----

var workerBaseEnv = [
  {
    name: 'USE_LLM_STUB'
    value: empty(openAiApiKey) ? 'true' : 'false'
  }
  {
    name: 'OPENAI_API_KEY'
    secretRef: 'openai-api-key'
  }
  {
    name: 'OPENAI_MODEL'
    value: openAiModel
  }
  {
    name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
    value: appInsights.outputs.connectionString
  }
  {
    name: 'NORA_ENV'
    value: env
  }
]

var workerSearchEnv = enableSearch ? [
  {
    name: 'AZURE_SEARCH_ENDPOINT'
    value: searchService.?outputs.endpoint ?? ''
  }
  {
    name: 'AZURE_SEARCH_INDEX'
    value: 'nora-context'
  }
] : []

var workerSecrets = {
  items: union(
    [
      {
        name: 'openai-api-key'
        value: empty(openAiApiKey) ? 'unset' : openAiApiKey
      }
    ],
    empty(registryServer) ? [] : [
      {
        name: 'registry-password'
        value: registryPassword
      }
    ]
  )
}

module workerApp 'modules/container-app.bicep' = {
  name: 'workerApp'
  params: {
    name: workerName
    location: location
    tags: tags
    environmentId: containerAppsEnv.outputs.id
    image: workerImage
    containerName: 'worker'
    targetPort: 8000
    ingress: 'internal'
    cpu: '0.5'
    memory: '1Gi'
    minReplicas: 0
    maxReplicas: 3
    envVars: concat(workerBaseEnv, workerSearchEnv)
    secretsObject: workerSecrets
    registry: registry
  }
}

// ---- API Spring Boot (external ingress) ----

var apiSecrets = {
  items: union(
    [
      {
        name: 'postgres-password'
        value: postgresAdminPassword
      }
      {
        name: 'jwt-secret'
        value: jwtSecret
      }
    ],
    empty(registryServer) ? [] : [
      {
        name: 'registry-password'
        value: registryPassword
      }
    ]
  )
}

module apiApp 'modules/container-app.bicep' = {
  name: 'apiApp'
  params: {
    name: apiName
    location: location
    tags: tags
    environmentId: containerAppsEnv.outputs.id
    image: apiImage
    containerName: 'api'
    targetPort: 8080
    ingress: 'external'
    cpu: '0.5'
    memory: '1Gi'
    minReplicas: 1 // sempre pelo menos 1 — API é caminho crítico
    maxReplicas: 3
    envVars: [
      {
        name: 'SPRING_PROFILES_ACTIVE'
        value: env
      }
      {
        name: 'NORA_ENV'
        value: env
      }
      {
        name: 'DATABASE_URL'
        value: postgres.outputs.jdbcUrl
      }
      {
        name: 'DATABASE_USER'
        value: postgresAdminLogin
      }
      {
        name: 'DATABASE_PASSWORD'
        secretRef: 'postgres-password'
      }
      {
        name: 'JWT_SECRET'
        secretRef: 'jwt-secret'
      }
      {
        name: 'WORKER_URL'
        value: 'https://${workerApp.outputs.fqdn}'
      }
      {
        name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
        value: appInsights.outputs.connectionString
      }
      {
        name: 'STORAGE_ACCOUNT_NAME'
        value: storage.outputs.name
      }
      {
        name: 'KEY_VAULT_URI'
        value: keyVault.outputs.uri
      }
    ]
    secretsObject: apiSecrets
    registry: registry
  }
}

// ---- Web Next.js (external ingress) ----

var webSecrets = {
  items: empty(registryServer) ? [] : [
    {
      name: 'registry-password'
      value: registryPassword
    }
  ]
}

module webApp 'modules/container-app.bicep' = {
  name: 'webApp'
  params: {
    name: webName
    location: location
    tags: tags
    environmentId: containerAppsEnv.outputs.id
    image: webImage
    containerName: 'web'
    targetPort: 3000
    ingress: 'external'
    cpu: '0.25'
    memory: '0.5Gi'
    minReplicas: 0
    maxReplicas: 3
    envVars: [
      {
        name: 'NEXT_PUBLIC_API_URL'
        value: 'https://${apiApp.outputs.fqdn}'
      }
      {
        name: 'NEXT_PUBLIC_USE_MOCKS'
        value: 'false'
      }
      {
        name: 'NORA_ENV'
        value: env
      }
      {
        name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
        value: appInsights.outputs.connectionString
      }
    ]
    secretsObject: webSecrets
    registry: registry
  }
}

// ============================================================
// OUTPUTS
// ============================================================

output webUrl string = 'https://${webApp.outputs.fqdn}'
output apiUrl string = 'https://${apiApp.outputs.fqdn}'
output workerInternalFqdn string = workerApp.outputs.fqdn
output postgresFqdn string = postgres.outputs.fqdn
output postgresJdbcUrl string = postgres.outputs.jdbcUrl
output keyVaultUri string = keyVault.outputs.uri
output storageAccountName string = storage.outputs.name
output appInsightsName string = appInsights.outputs.name // connection string fica no recurso; consultar via `az monitor app-insights component show`
output logAnalyticsWorkspaceId string = logAnalytics.outputs.id
output searchEndpoint string = enableSearch ? (searchService.?outputs.endpoint ?? '') : ''

// IDs pra futuros role assignments (managed identities das Container Apps)
output apiPrincipalId string = apiApp.outputs.principalId
output workerPrincipalId string = workerApp.outputs.principalId
output webPrincipalId string = webApp.outputs.principalId
