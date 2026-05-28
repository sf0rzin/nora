// main.bicep — NORA infra orquestrador
//
// Ordem de deploy:
//   1. Log Analytics + App Insights (observabilidade)
//   2. Storage Account
//   3. Azure Speech (Cognitive Services) — emite key1 que vai pro KV
//   4. User-Assigned Managed Identities (api, worker, web) — criadas ANTES do KV
//      pra que role assignment + KV references nao tenham problema de ciclo de SystemAssigned
//   5. Key Vault — com role assignments pras UAIs + secrets (postgres pwd, JWT, openai key, speech key)
//   6. Postgres Flexible Server
//   7. Container Apps Environment (compartilhado)
//   8. (opcional) Azure AI Search
//   9. Container Apps (worker, api, web) — usam UAI + secret refs pro Key Vault
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

@description('Ambiente logico. Usado em naming e tags. dev | staging | prod.')
@allowed([
  'dev'
  'staging'
  'prod'
])
param env string = 'dev'

@description('Regiao azure pra todos os recursos.')
param location string = resourceGroup().location

@description('Prefixo de naming. Mantem consistencia entre recursos.')
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

@description('Usuario admin do Postgres.')
param postgresAdminLogin string = 'nora_admin'

@description('Senha admin do Postgres. Vai pro KV (secret postgres-password).')
@secure()
@minLength(12)
param postgresAdminPassword string

@description('Secret HMAC pra assinar JWT no backend. Minimo 32 chars. Vai pro KV (secret jwt-secret).')
@secure()
@minLength(32)
param jwtSecret string

@description('OpenAI API Key. Se vazio, worker roda em modo stub. Vai pro KV (secret openai-api-key).')
@secure()
param openAiApiKey string = ''

@description('Modelo OpenAI default usado pelo worker.')
param openAiModel string = 'gpt-4o-mini'

@description('Resend API Key. Se vazio, backend cai em LogEmailSender (dev). Vai pro KV (secret resend-api-key).')
@secure()
param resendApiKey string = ''

@description('Email From usado pelo Resend (`Nome <email@dominio>`).')
param noraEmailFrom string = 'NORA <onboarding@resend.dev>'

// ============================================================
// PARAMS — imagens
// ============================================================

@description('Imagem da API Spring Boot.')
param apiImage string = 'mcr.microsoft.com/k8se/quickstart:latest'

@description('Imagem do worker NLP Python.')
param workerImage string = 'mcr.microsoft.com/k8se/quickstart:latest'

@description('Imagem do frontend Next.js.')
param webImage string = 'mcr.microsoft.com/k8se/quickstart:latest'

@description('Registry server (ex.: ghcr.io). Vazio = imagens publicas.')
param registryServer string = ''

@description('Username pro registry.')
param registryUsername string = ''

@description('Password pro registry. Inline no Container App (operacional, nao vai pro KV).')
@secure()
param registryPassword string = ''

// ============================================================
// PARAMS — Azure Speech
// ============================================================

@description('SKU do Speech. S0 = paid (~$1/h de audio). F0 = free tier (limitado, nao recomendado pra demo).')
@allowed([
  'F0'
  'S0'
])
param speechSku string = 'S0'

// ============================================================
// PARAMS — Azure AI Search (opcional)
// ============================================================

@description('Habilita Azure AI Search Basic. Cobra ~R$13-15/dia enquanto provisionado. Manter false durante dev; ligar ~14 dias antes do pitch.')
param enableSearch bool = false

@description('SKU do Search.')
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
// NAMING — deterministico + unico onde precisa
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
var speechName = '${namePrefix}-speech-${env}-${nameSuffix}'
var uaiApiName = '${namePrefix}-uai-api-${env}'
var uaiWorkerName = '${namePrefix}-uai-worker-${env}'
var uaiWebName = '${namePrefix}-uai-web-${env}'

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
// MODULES — storage
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

// ============================================================
// MODULES — Azure Speech (ADR 0009)
// ============================================================

module speech 'modules/speech.bicep' = {
  name: 'speech'
  params: {
    name: speechName
    location: location
    tags: tags
    sku: speechSku
  }
}

// ============================================================
// MODULES — User-Assigned Managed Identities
// (criadas antes do KV pra resolver ciclo de role assignment)
// ============================================================

module uaiApi 'modules/user-assigned-identity.bicep' = {
  name: 'uaiApi'
  params: {
    name: uaiApiName
    location: location
    tags: tags
  }
}

module uaiWorker 'modules/user-assigned-identity.bicep' = {
  name: 'uaiWorker'
  params: {
    name: uaiWorkerName
    location: location
    tags: tags
  }
}

module uaiWeb 'modules/user-assigned-identity.bicep' = {
  name: 'uaiWeb'
  params: {
    name: uaiWebName
    location: location
    tags: tags
  }
}

// ============================================================
// MODULES — Key Vault (com role assignments pras UAIs + secrets)
// ============================================================

var keyVaultSecrets = {
  items: concat(
    [
      {
        name: 'postgres-password'
        value: postgresAdminPassword
      }
      {
        name: 'jwt-secret'
        value: jwtSecret
      }
      {
        name: 'openai-api-key'
        value: empty(openAiApiKey) ? 'unset' : openAiApiKey
      }
      {
        name: 'azure-speech-key'
        value: speech.outputs.key1
      }
      {
        name: 'resend-api-key'
        value: empty(resendApiKey) ? 'unset' : resendApiKey
      }
    ]
  )
}

module keyVault 'modules/keyvault.bicep' = {
  name: 'keyVault'
  params: {
    name: kvName
    location: location
    tags: tags
    enablePurgeProtection: false // dev — permite teardown rapido
    secretsUserPrincipalIds: [
      uaiApi.outputs.principalId
      uaiWorker.outputs.principalId
      uaiWeb.outputs.principalId
    ]
    secrets: keyVaultSecrets
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

// Helper: KV reference pra um secret. Container App referencia secret do KV via
// keyVaultUrl + identity (UAI resource ID). ARM faz fetch on revision create.
var kvUri = keyVault.outputs.uri

// FQDNs deterministicos do Container Apps Environment.
// Construidos antes das apps pra evitar ciclo (apiApp -> webApp.fqdn e webApp -> apiApp.fqdn).
// Pattern: {appName}.{envDefaultDomain}
var apiPublicFqdn = '${apiName}.${containerAppsEnv.outputs.defaultDomain}'
var webPublicFqdn = '${webName}.${containerAppsEnv.outputs.defaultDomain}'
var apiPublicUrl = 'https://${apiPublicFqdn}'
var webPublicUrl = 'https://${webPublicFqdn}'

// ---- Worker NLP (internal ingress; api fala com ele) ----

// Worker NLP consome LLM_* (ADR 0004). Bicep mantem secretRef chamado openai-api-key
// para nao quebrar KV existente, mas a env exposta ao processo Python e LLM_API_KEY.
var workerBaseEnv = [
  {
    name: 'USE_LLM_STUB'
    value: empty(openAiApiKey) ? 'true' : 'false'
  }
  {
    name: 'LLM_PROVIDER'
    value: 'openai'
  }
  {
    name: 'LLM_API_KEY'
    secretRef: 'openai-api-key'
  }
  {
    name: 'LLM_MODEL'
    value: openAiModel
  }
  {
    name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
    value: appInsights.outputs.connectionString
  }
  // Role name diferencia api/worker/web no Application Map.
  {
    name: 'OTEL_SERVICE_NAME'
    value: 'nora-worker'
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
        keyVaultUrl: '${kvUri}secrets/openai-api-key'
        identity: uaiWorker.outputs.id
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
    targetPort: 8001
    ingress: 'internal'
    cpu: '0.5'
    memory: '1Gi'
    minReplicas: 0
    maxReplicas: 3
    envVars: concat(workerBaseEnv, workerSearchEnv)
    secretsObject: workerSecrets
    userAssignedIdentityId: uaiWorker.outputs.id
    registry: registry
  }
}

// ---- API Spring Boot (external ingress) ----

var apiSecrets = {
  items: union(
    [
      {
        name: 'postgres-password'
        keyVaultUrl: '${kvUri}secrets/postgres-password'
        identity: uaiApi.outputs.id
      }
      {
        name: 'jwt-secret'
        keyVaultUrl: '${kvUri}secrets/jwt-secret'
        identity: uaiApi.outputs.id
      }
      {
        name: 'azure-speech-key'
        keyVaultUrl: '${kvUri}secrets/azure-speech-key'
        identity: uaiApi.outputs.id
      }
      {
        name: 'resend-api-key'
        keyVaultUrl: '${kvUri}secrets/resend-api-key'
        identity: uaiApi.outputs.id
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
    minReplicas: 1 // sempre pelo menos 1 — API e caminho critico
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
      // Datasource — Spring espera DATASOURCE_* (nao DATABASE_*)
      {
        name: 'DATASOURCE_URL'
        value: postgres.outputs.jdbcUrl
      }
      {
        name: 'DATASOURCE_USERNAME'
        value: postgresAdminLogin
      }
      {
        name: 'DATASOURCE_PASSWORD'
        secretRef: 'postgres-password'
      }
      {
        name: 'JWT_SECRET'
        secretRef: 'jwt-secret'
      }
      // Worker NLP base URL — Spring espera NLP_WORKER_BASE_URL
      {
        name: 'NLP_WORKER_BASE_URL'
        value: 'https://${workerApp.outputs.fqdn}'
      }
      {
        name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
        value: appInsights.outputs.connectionString
      }
      // Role name diferencia api/worker/web no Application Map.
      {
        name: 'APPLICATIONINSIGHTS_ROLE_NAME'
        value: 'nora-api'
      }
      {
        name: 'STORAGE_ACCOUNT_NAME'
        value: storage.outputs.name
      }
      {
        name: 'KEY_VAULT_URI'
        value: keyVault.outputs.uri
      }
      {
        name: 'AZURE_SPEECH_KEY'
        secretRef: 'azure-speech-key'
      }
      {
        name: 'AZURE_SPEECH_REGION'
        value: speech.outputs.region
      }
      {
        name: 'AZURE_SPEECH_ENDPOINT'
        value: speech.outputs.endpoint
      }
      // CORS + URLs publicas (apontam pro web Container App)
      // FQDNs construidos via env.defaultDomain pra evitar ciclo apiApp <-> webApp
      {
        name: 'CORS_ALLOWED_ORIGINS'
        value: webPublicUrl
      }
      {
        name: 'NORA_APP_PUBLIC_BASE_URL'
        value: webPublicUrl
      }
      {
        name: 'NORA_FRONTEND_BASE_URL'
        value: webPublicUrl
      }
      // Forca cookie auth seguro (HTTPS-only) — prod stack so usa HTTPS
      {
        name: 'AUTH_COOKIE_SECURE'
        value: 'true'
      }
      // Bloqueia signup/reset retornar tokens crus em prod
      {
        name: 'EXPOSE_DEV_TOKENS'
        value: 'false'
      }
      // Email Resend — se RESEND_API_KEY estiver vazio, app cai em LogEmailSender.
      // Em prod queremos o sender real; KV mantem o secret quando setado via bicepparam.
      {
        name: 'RESEND_API_KEY'
        secretRef: 'resend-api-key'
      }
      {
        name: 'NORA_EMAIL_FROM'
        value: noraEmailFrom
      }
    ]
    secretsObject: apiSecrets
    userAssignedIdentityId: uaiApi.outputs.id
    registry: registry
  }
}

// ---- Web Next.js (external ingress) ----

var webSecrets = {
  items: union(
    [
      // Chat IA do Core (BFF /api/chat) consome a chave LLM server-side.
      // Reusa o MESMO secret do KV que o worker usa (openai-api-key).
      {
        name: 'openai-api-key'
        keyVaultUrl: '${kvUri}secrets/openai-api-key'
        identity: uaiWeb.outputs.id
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
      // NOTA: NEXT_PUBLIC_* sao baked in build-time no bundle Next. O Dockerfile do web
      // ja hardcoda NEXT_PUBLIC_USE_MOCKS=false. Aqui injetamos a URL em runtime apenas
      // para casos onde o build receba a variavel como build-arg ARG no Dockerfile
      // (necessario para que o bundle final aponte pra prod). Nome do contrato e
      // NEXT_PUBLIC_API_BASE_URL (alinhado com apps/web/src/lib/api/client.ts).
      {
        name: 'NEXT_PUBLIC_API_BASE_URL'
        value: apiPublicUrl
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
      // Chat IA do Core (BFF /api/chat) — provider-agnostic (ADR 0004). A chave
      // fica server-side (secretRef), nunca no bundle do browser. Se openAiApiKey
      // estiver vazio no deploy, o secret vale 'unset' e o chat retorna 503.
      {
        name: 'LLM_API_KEY'
        secretRef: 'openai-api-key'
      }
      {
        name: 'LLM_BASE_URL'
        value: 'https://api.openai.com/v1'
      }
      {
        name: 'LLM_MODEL'
        value: openAiModel
      }
    ]
    secretsObject: webSecrets
    userAssignedIdentityId: uaiWeb.outputs.id
    registry: registry
  }
}

// ============================================================
// OUTPUTS
// ============================================================

output webUrl string = webPublicUrl
output apiUrl string = apiPublicUrl
output workerInternalFqdn string = workerApp.outputs.fqdn
output postgresFqdn string = postgres.outputs.fqdn
output postgresJdbcUrl string = postgres.outputs.jdbcUrl
output keyVaultUri string = keyVault.outputs.uri
output storageAccountName string = storage.outputs.name
output appInsightsName string = appInsights.outputs.name
output logAnalyticsWorkspaceId string = logAnalytics.outputs.id
output searchEndpoint string = enableSearch ? (searchService.?outputs.endpoint ?? '') : ''
output speechEndpoint string = speech.outputs.endpoint
output speechRegion string = speech.outputs.region

// UAI principal IDs (uteis pra debug ou role assignments extras)
output apiUaiPrincipalId string = uaiApi.outputs.principalId
output workerUaiPrincipalId string = uaiWorker.outputs.principalId
output webUaiPrincipalId string = uaiWeb.outputs.principalId
