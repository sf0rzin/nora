// main.bicep — NORA infra orchestrator
//
// Deploy order:
//   1. Log Analytics + App Insights (observability)
//   2. Storage Account
//   3. Azure Speech (Cognitive Services) — emits key1 that goes to the KV
//   4. User-Assigned Managed Identities (api, worker, web) — created BEFORE the KV
//      so that role assignment + KV references have no SystemAssigned cycle problem
//   5. Key Vault — with role assignments for the UAIs + secrets (postgres pwd, JWT, openai key, speech key)
//   6. Postgres Flexible Server
//   7. Container Apps Environment (shared)
//   8. (optional) Azure AI Search
//   9. Container Apps (worker, api, web) — use UAI + secret refs to the Key Vault
//
// Usage:
//   az deployment group create \
//     --resource-group rg-nora-dev \
//     --template-file main.bicep \
//     --parameters main.dev.bicepparam
//
// See README.md for details on turning Search on/off and costs.

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
// PARAMS — secrets (inject via bicepparam or --parameters CLI)
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

@description('DeepSeek API Key. Pro chat multi-provider (troca de modelo ao vivo, ADR 0024). Se vazio, vira "unset" no KV (secret deepseek-api-key).')
@secure()
param deepSeekApiKey string = ''

@description('Google AI (Gemini) API Key. Pro chat/multimodal. Se vazio, vira "unset" no KV (secret gemini-api-key).')
@secure()
param geminiApiKey string = ''

@description('Resend API Key. Se vazio, backend cai em LogEmailSender (dev). Vai pro KV (secret resend-api-key).')
@secure()
param resendApiKey string = ''

@description('Email From usado pelo Resend (`Nome <email@dominio>`).')
param noraEmailFrom string = 'NORA <onboarding@resend.dev>'

// ---- OAuth integrations (NORA Flows Phase 2 — ADR 0031) ----

@description('Google OAuth Client ID (Gmail/Calendar). Nao-secreto (vai como env puro). Vazio = hub mostra Google como "nao configurado" e o start retorna 422 (fail-visible).')
param googleOauthClientId string = ''

@description('Google OAuth Client Secret. Vai pro KV (google-oauth-client-secret) so quando setado.')
@secure()
param googleOauthClientSecret string = ''

@description('Slack OAuth Client ID. Mesmo contrato do Google.')
param slackOauthClientId string = ''

@description('Slack OAuth Client Secret. KV slack-oauth-client-secret so quando setado.')
@secure()
param slackOauthClientSecret string = ''

// Wave 1 of generic providers (GitHub, Notion, Todoist, Linear) — same contract as Slack:
// empty default = connector "not configured" in the hub, without breaking the deploy.

@description('GitHub OAuth Client ID. Mesmo contrato do Slack.')
param githubOauthClientId string = ''

@description('GitHub OAuth Client Secret. KV github-oauth-client-secret so quando setado.')
@secure()
param githubOauthClientSecret string = ''

@description('Notion OAuth Client ID. Mesmo contrato do Slack.')
param notionOauthClientId string = ''

@description('Notion OAuth Client Secret. KV notion-oauth-client-secret so quando setado.')
@secure()
param notionOauthClientSecret string = ''

@description('Todoist OAuth Client ID. Mesmo contrato do Slack.')
param todoistOauthClientId string = ''

@description('Todoist OAuth Client Secret. KV todoist-oauth-client-secret so quando setado.')
@secure()
param todoistOauthClientSecret string = ''

@description('Linear OAuth Client ID. Mesmo contrato do Slack.')
param linearOauthClientId string = ''

@description('Linear OAuth Client Secret. KV linear-oauth-client-secret so quando setado.')
@secure()
param linearOauthClientSecret string = ''

// Wave 2 — three different connection models:

@description('Microsoft OAuth Client ID (Outlook + Calendar via Graph; tenant common). Mesmo contrato do Slack.')
param msOauthClientId string = ''

@description('Microsoft OAuth Client Secret. Secret ms-oauth-client-secret so quando setado.')
@secure()
param msOauthClientSecret string = ''

@description('Token do bot Telegram unico do app (BotFather). SEM OAuth: cada tenant pareia por codigo e o backend guarda o chat_id. Vazio = conector "nao configurado".')
@secure()
param telegramBotToken string = ''

@description('API key do app no Trello (nao-secreta: aparece na URL de authorize que o usuario abre). SEM OAuth server-side: o usuario cola o token gerado. Vazio = "nao configurado".')
param trelloApiKey string = ''

@description('Assina o state OAuth (HMAC-SHA256, ADR 0031). Vazio = segredo efemero por boot (states nao sobrevivem a restart — ok em dev, ruim em prod).')
@secure()
param integrationsStateSecret string = ''

@description('Cifra os tokens OAuth em repouso (AES-256-GCM, 32 bytes base64, ADR 0031). Vazio = tokens armazenados sem cifra com WARN no boot.')
@secure()
param integrationsEncKey string = ''

// ============================================================
// PARAMS — images
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
// PARAMS — Azure AI Search (optional)
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
// PARAMS — Control plane (ADR 0022/0023/0024). Gated by enablePlatform.
// ============================================================

@description('Liga o control plane: 2º Postgres (plataforma), UAI admin e Container App nora-admin. Default false — mantém a infra atual intacta até o image do nora-admin e o grupo Entra existirem.')
param enablePlatform bool = false

@description('Senha admin do Postgres de plataforma. Vai pro KV (postgres-platform-password). Vazio = reusa a senha do Postgres principal.')
@secure()
param platformPostgresAdminPassword string = ''

@description('Token serviço-a-serviço (worker/BFF -> /internal/platform/**). Vai pro KV (internal-service-token).')
@secure()
param platformInternalToken string = ''

@description('Token do console (nora-admin -> /admin/platform/**). Vai pro KV (admin-bridge-token). Distinto do internal por least-privilege.')
@secure()
param platformAdminToken string = ''

@description('Imagem do app nora-admin (Next). Placeholder ate o outro arquiteto publicar a imagem real.')
param adminImage string = 'mcr.microsoft.com/k8se/quickstart:latest'

@description('Client ID da App Registration do Easy Auth (Entra) do nora-admin. Vazio = Easy Auth desligado (passo manual Entra pendente — ver runbook).')
param easyAuthClientId string = ''

@description('Client secret da App Registration do Easy Auth. Vai pro KV (easyauth-client-secret).')
@secure()
param easyAuthClientSecret string = ''

@description('Allowlist de IPs (CIDR) do ingress do nora-admin. Formato: [{ name, ipAddressRange, action: "Allow" }]. Vazio = sem restricao de rede. Com Tunnel (ADR 0025) o ingress e internal — sem FQDN publico —, entao isto fica vazio.')
param adminIpSecurityRestrictions array = []

// ---- Operator identity v2: Cloudflare Tunnel + Access (ADR 0025, replaces Easy Auth from 0023) ----

@description('Connector token do Cloudflare Tunnel do nora-admin. Vai pro KV (cloudflare-tunnel-token). Vazio = "unset" (cloudflared nao conecta — tunnel off). Gerado pelo workflow cloudflare-tunnel.yml e setado no Secret CLOUDFLARE_TUNNEL_TOKEN.')
@secure()
param cloudflareTunnelToken string = ''

@description('Imagem do conector cloudflared (sidecar do nora-admin). Pinada por reprodutibilidade; bumpar conforme releases do Cloudflare. Verificado no Docker Hub em 2026-06-01.')
param cloudflaredImage string = 'docker.io/cloudflare/cloudflared:2026.5.2'

@description('Team domain do Cloudflare Access (ex.: stratfy.cloudflareaccess.com). O nora-admin valida o JWT Cf-Access-Jwt-Assertion contra o JWKS desse dominio (Tier 2, defense-in-depth). Vazio = validacao degrada pra edge-only.')
param cfAccessTeamDomain string = ''

@description('AUD tag da Access Application (admin.nora.systems). O nora-admin valida o audience do JWT do Access. Vazio = validacao de JWT degrada pra edge-only (origem ja protegida pelo tunnel + Access na borda).')
param cfAccessAud string = ''

// ---- Custom public domain (Cloudflare -> custom domain, see docs/operations/web-custom-domain.md) ----

@description('Dominio publico (apex). Vazio = usa o FQDN .azurecontainerapps.io (comportamento antigo). Ex.: nora.systems')
param publicDomain string = ''

@description('Nome do managed certificate (no env) para o apex. Ex.: mc-nora-cae-dev-nora-systems-XXXX. Criado via az hostname bind.')
param webCertName string = ''

@description('Nome do managed certificate (no env) para www.')
param wwwCertName string = ''

@description('Nome do managed certificate (no env) para api.')
param apiCertName string = ''

// ---- RLS enforce (defense in depth for tenant_id, ADR 0002/0019/0026) ----

@description('Liga o enforce de Row Level Security no apiApp (NORA_RLS_ENFORCE). Default FALSE: o schema tem todas as policies (V016/V017/V018), mas o enforce SO vale com role NOBYPASSRLS na connection string. NAO ligar direto em prod — seguir a sequencia de cutover do ADR 0026 (provisionar nora_app/nora_telemetry via db/operational/R001 -> validar telemetria BYPASSRLS -> staging -> prod).')
param rlsEnforce bool = false

@description('URL JDBC do banco PRIMARIO conectando como o role nora_telemetry (BYPASSRLS) para a telemetria de negocio operador-only sob RLS enforce (ADR 0026). Vazio = telemetria usa o datasource primario (estado atual, pre-cutover). Setar JUNTO com rlsEnforce=true.')
param rlsTelemetryDatasourceUrl string = ''

@description('Senha do role nora_telemetry (BYPASSRLS). Vai pro KV (rls-telemetry-password). Vazio quando rlsEnforce=false.')
@secure()
param rlsTelemetryPassword string = ''

@description('Username do role de runtime da API sob RLS enforce (NOBYPASSRLS). Default nora_app; so usado quando rlsEnforce=true (senao a API conecta como postgresAdminLogin). Provisionado pelo R001 (ADR 0028).')
param appDbUsername string = 'nora_app'

@description('Senha do role nora_app (runtime sob RLS enforce). Vai pro KV (nora-app-password). Vazio quando rlsEnforce=false; provisionado no cutover (ADR 0028).')
@secure()
param appDbPassword string = ''

// ============================================================
// NAMING — deterministic + unique where needed
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
// Control plane (ADR 0022/0023)
var pgPlatformName = '${namePrefix}-pg-platform-${env}-${nameSuffix}'
var adminName = '${namePrefix}-admin-${env}'
var uaiAdminName = '${namePrefix}-uai-admin-${env}'

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
// (created before the KV to resolve the role assignment cycle)
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

// Control plane: dedicated nora-admin UAI (ADR 0023). Only when enablePlatform.
module uaiAdmin 'modules/user-assigned-identity.bicep' = if (enablePlatform) {
  name: 'uaiAdmin'
  params: {
    name: uaiAdminName
    location: location
    tags: tags
  }
}

// ============================================================
// MODULES — Key Vault (with role assignments for the UAIs + secrets)
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
      // Per-provider keys for the multi-provider chat (ADR 0024). Always in the KV ('unset' when
      // empty) — the web only references them if the provider is used.
      {
        name: 'deepseek-api-key'
        value: empty(deepSeekApiKey) ? 'unset' : deepSeekApiKey
      }
      {
        name: 'gemini-api-key'
        value: empty(geminiApiKey) ? 'unset' : geminiApiKey
      }
      {
        name: 'azure-speech-key'
        value: speech.outputs.key1
      }
      {
        name: 'resend-api-key'
        value: empty(resendApiKey) ? 'unset' : resendApiKey
      }
    ],
    // OAuth integrations (ADR 0031): secrets created ONLY when set — no 'unset' placeholder
    // (TokenCipher would reject "unset" as base64 and take down the API boot).
    empty(googleOauthClientSecret) ? [] : [
      {
        name: 'google-oauth-client-secret'
        value: googleOauthClientSecret
      }
    ],
    empty(slackOauthClientSecret) ? [] : [
      {
        name: 'slack-oauth-client-secret'
        value: slackOauthClientSecret
      }
    ],
    empty(githubOauthClientSecret) ? [] : [
      {
        name: 'github-oauth-client-secret'
        value: githubOauthClientSecret
      }
    ],
    empty(notionOauthClientSecret) ? [] : [
      {
        name: 'notion-oauth-client-secret'
        value: notionOauthClientSecret
      }
    ],
    empty(todoistOauthClientSecret) ? [] : [
      {
        name: 'todoist-oauth-client-secret'
        value: todoistOauthClientSecret
      }
    ],
    empty(linearOauthClientSecret) ? [] : [
      {
        name: 'linear-oauth-client-secret'
        value: linearOauthClientSecret
      }
    ],
    // Wave 2: Microsoft + Telegram (Trello API key is non-secret — goes as a plain env).
    empty(msOauthClientSecret) ? [] : [
      {
        name: 'ms-oauth-client-secret'
        value: msOauthClientSecret
      }
    ],
    empty(telegramBotToken) ? [] : [
      {
        name: 'telegram-bot-token'
        value: telegramBotToken
      }
    ],
    empty(integrationsStateSecret) ? [] : [
      {
        name: 'integrations-state-secret'
        value: integrationsStateSecret
      }
    ],
    empty(integrationsEncKey) ? [] : [
      {
        name: 'integrations-enc-key'
        value: integrationsEncKey
      }
    ],
    // Control plane (ADR 0022/0023): secrets only when enablePlatform.
    enablePlatform ? [
      {
        name: 'postgres-platform-password'
        value: empty(platformPostgresAdminPassword) ? postgresAdminPassword : platformPostgresAdminPassword
      }
      {
        name: 'internal-service-token'
        value: empty(platformInternalToken) ? 'unset' : platformInternalToken
      }
      {
        name: 'admin-bridge-token'
        value: empty(platformAdminToken) ? 'unset' : platformAdminToken
      }
      {
        name: 'easyauth-client-secret'
        value: empty(easyAuthClientSecret) ? 'unset' : easyAuthClientSecret
      }
      {
        name: 'cloudflare-tunnel-token'
        value: empty(cloudflareTunnelToken) ? 'unset' : cloudflareTunnelToken
      }
    ] : [],
    // BYPASSRLS telemetry RLS (ADR 0026): only when the nora_telemetry password is
    // provided (cutover step). Without it, no extra secret is created.
    empty(rlsTelemetryPassword) ? [] : [
      {
        name: 'rls-telemetry-password'
        value: rlsTelemetryPassword
      }
    ],
    // RLS enforce (ADR 0028): nora_app password created in the KV only when provided (cutover).
    empty(appDbPassword) ? [] : [
      {
        name: 'nora-app-password'
        value: appDbPassword
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
    enablePurgeProtection: false // dev — allows fast teardown
    secretsUserPrincipalIds: concat(
      [
        uaiApi.outputs.principalId
        uaiWorker.outputs.principalId
        uaiWeb.outputs.principalId
      ],
      enablePlatform ? [ uaiAdmin.?outputs.principalId ?? '' ] : []
    )
    secrets: keyVaultSecrets
  }
}

// ============================================================
// MODULES — database
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

// Control plane: SEPARATE 2nd Postgres (ADR 0022 — isolated blast radius). Reuses the module 1:1.
// Its own B1ms server; database nora_platform. Only when enablePlatform.
module postgresPlatform 'modules/postgres.bicep' = if (enablePlatform) {
  name: 'postgresPlatform'
  params: {
    name: pgPlatformName
    location: location
    tags: tags
    adminLogin: postgresAdminLogin
    adminPassword: empty(platformPostgresAdminPassword) ? postgresAdminPassword : platformPostgresAdminPassword
    databaseName: 'nora_platform'
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

// ---- Search (conditional, before the apps so the worker can consume the endpoint) ----

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

// Helper: KV reference for a secret. Container App references a KV secret via
// keyVaultUrl + identity (UAI resource ID). ARM does the fetch on revision create.
var kvUri = keyVault.outputs.uri

// Deterministic FQDNs of the Container Apps Environment.
// Built before the apps to avoid a cycle (apiApp -> webApp.fqdn and webApp -> apiApp.fqdn).
// Pattern: {appName}.{envDefaultDomain}
var apiPublicFqdn = '${apiName}.${containerAppsEnv.outputs.defaultDomain}'
var webPublicFqdn = '${webName}.${containerAppsEnv.outputs.defaultDomain}'
var apiPublicUrl = 'https://${apiPublicFqdn}'
var webPublicUrl = 'https://${webPublicFqdn}'

// ---- Custom public domain (Cloudflare -> custom domain on the Container App) ----
// When publicDomain is set (e.g.: 'nora.systems'), web/api use their own domain:
// web on nora.systems calls the API on api.nora.systems (same registrable domain), so the
// auth cookies (Domain=nora.systems) are shared cross-subdomain. The managed
// certificates are created via `az hostname bind` (see docs/operations/web-custom-domain.md)
// and referenced here by name. Empty = uses only the .azurecontainerapps.io FQDN (old behavior).
var hasPublicDomain = !empty(publicDomain)
var certBaseId = '${containerAppsEnv.outputs.id}/managedCertificates'
var corsAllowedOrigins = hasPublicDomain ? 'https://${publicDomain},https://www.${publicDomain}' : webPublicUrl
var authCookieDomainValue = hasPublicDomain ? publicDomain : containerAppsEnv.outputs.defaultDomain
var frontendBaseUrl = hasPublicDomain ? 'https://${publicDomain}' : webPublicUrl
var apiBaseUrl = hasPublicDomain ? 'https://api.${publicDomain}' : apiPublicUrl
var webCustomDomains = hasPublicDomain ? [
  { name: publicDomain, bindingType: 'SniEnabled', certificateId: '${certBaseId}/${webCertName}' }
  { name: 'www.${publicDomain}', bindingType: 'SniEnabled', certificateId: '${certBaseId}/${wwwCertName}' }
] : []
var apiCustomDomains = hasPublicDomain ? [
  { name: 'api.${publicDomain}', bindingType: 'SniEnabled', certificateId: '${certBaseId}/${apiCertName}' }
] : []

// ---- Worker NLP (internal ingress; the api talks to it) ----

// Worker NLP consumes LLM_* (ADR 0004). Bicep keeps the secretRef named openai-api-key
// so as not to break the existing KV, but the env exposed to the Python process is LLM_API_KEY.
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
  // Role name distinguishes api/worker/web in the Application Map.
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

// Control plane: token + API base so the worker can read /internal/platform/llm-config (ADR 0024).
// The other architect wires the read into the hot-path; here we only provision the envs.
var workerPlatformEnv = enablePlatform ? [
  {
    name: 'NORA_PLATFORM_INTERNAL_TOKEN'
    secretRef: 'internal-service-token'
  }
  {
    name: 'NORA_API_BASE_URL'
    value: apiPublicUrl
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
    ],
    enablePlatform ? [
      {
        name: 'internal-service-token'
        keyVaultUrl: '${kvUri}secrets/internal-service-token'
        identity: uaiWorker.outputs.id
      }
    ] : []
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
    envVars: concat(workerBaseEnv, workerSearchEnv, workerPlatformEnv)
    secretsObject: workerSecrets
    userAssignedIdentityId: uaiWorker.outputs.id
    registry: registry
  }
}

// ---- API Spring Boot (external ingress) ----

// Control plane (ADR 0022/0024): 2nd datasource + tokens. Only when enablePlatform.
var apiPlatformSecrets = enablePlatform ? [
  {
    name: 'postgres-platform-password'
    keyVaultUrl: '${kvUri}secrets/postgres-platform-password'
    identity: uaiApi.outputs.id
  }
  {
    name: 'internal-service-token'
    keyVaultUrl: '${kvUri}secrets/internal-service-token'
    identity: uaiApi.outputs.id
  }
  {
    name: 'admin-bridge-token'
    keyVaultUrl: '${kvUri}secrets/admin-bridge-token'
    identity: uaiApi.outputs.id
  }
] : []

// OAuth integrations (ADR 0031): each block only enters when the respective param was set —
// a secretRef to a nonexistent secret would take down the revision, and an 'unset' placeholder would
// break boot (TokenCipher validates base64). Redirect URIs derive from the API public domain (PR #215):
// they must match EXACTLY the Authorized redirect URIs registered in Google/Slack.
var apiIntegrationsEnv = union(
  empty(googleOauthClientId) || empty(googleOauthClientSecret) ? [] : [
    {
      name: 'GOOGLE_OAUTH_CLIENT_ID'
      value: googleOauthClientId
    }
    {
      name: 'GOOGLE_OAUTH_CLIENT_SECRET'
      secretRef: 'google-oauth-client-secret'
    }
    {
      name: 'GOOGLE_OAUTH_REDIRECT_URI'
      value: '${apiBaseUrl}/integrations/google/oauth/callback'
    }
  ],
  empty(slackOauthClientId) || empty(slackOauthClientSecret) ? [] : [
    {
      name: 'SLACK_OAUTH_CLIENT_ID'
      value: slackOauthClientId
    }
    {
      name: 'SLACK_OAUTH_CLIENT_SECRET'
      secretRef: 'slack-oauth-client-secret'
    }
    {
      name: 'SLACK_OAUTH_REDIRECT_URI'
      value: '${apiBaseUrl}/integrations/slack/oauth/callback'
    }
  ],
  empty(githubOauthClientId) || empty(githubOauthClientSecret) ? [] : [
    {
      name: 'GITHUB_OAUTH_CLIENT_ID'
      value: githubOauthClientId
    }
    {
      name: 'GITHUB_OAUTH_CLIENT_SECRET'
      secretRef: 'github-oauth-client-secret'
    }
    {
      name: 'GITHUB_OAUTH_REDIRECT_URI'
      value: '${apiBaseUrl}/integrations/github/oauth/callback'
    }
  ],
  empty(notionOauthClientId) || empty(notionOauthClientSecret) ? [] : [
    {
      name: 'NOTION_OAUTH_CLIENT_ID'
      value: notionOauthClientId
    }
    {
      name: 'NOTION_OAUTH_CLIENT_SECRET'
      secretRef: 'notion-oauth-client-secret'
    }
    {
      name: 'NOTION_OAUTH_REDIRECT_URI'
      value: '${apiBaseUrl}/integrations/notion/oauth/callback'
    }
  ],
  empty(todoistOauthClientId) || empty(todoistOauthClientSecret) ? [] : [
    {
      name: 'TODOIST_OAUTH_CLIENT_ID'
      value: todoistOauthClientId
    }
    {
      name: 'TODOIST_OAUTH_CLIENT_SECRET'
      secretRef: 'todoist-oauth-client-secret'
    }
    {
      name: 'TODOIST_OAUTH_REDIRECT_URI'
      value: '${apiBaseUrl}/integrations/todoist/oauth/callback'
    }
  ],
  empty(linearOauthClientId) || empty(linearOauthClientSecret) ? [] : [
    {
      name: 'LINEAR_OAUTH_CLIENT_ID'
      value: linearOauthClientId
    }
    {
      name: 'LINEAR_OAUTH_CLIENT_SECRET'
      secretRef: 'linear-oauth-client-secret'
    }
    {
      name: 'LINEAR_OAUTH_REDIRECT_URI'
      value: '${apiBaseUrl}/integrations/linear/oauth/callback'
    }
  ],
  // Wave 2 — Microsoft follows the OAuth contract; Telegram and Trello are a single env each.
  empty(msOauthClientId) || empty(msOauthClientSecret) ? [] : [
    {
      name: 'MS_OAUTH_CLIENT_ID'
      value: msOauthClientId
    }
    {
      name: 'MS_OAUTH_CLIENT_SECRET'
      secretRef: 'ms-oauth-client-secret'
    }
    {
      name: 'MS_OAUTH_REDIRECT_URI'
      value: '${apiBaseUrl}/integrations/microsoft/oauth/callback'
    }
  ],
  empty(telegramBotToken) ? [] : [
    {
      name: 'NORA_TELEGRAM_BOT_TOKEN'
      secretRef: 'telegram-bot-token'
    }
  ],
  empty(trelloApiKey) ? [] : [
    {
      name: 'TRELLO_API_KEY'
      value: trelloApiKey
    }
  ],
  empty(integrationsStateSecret) ? [] : [
    {
      name: 'NORA_INTEGRATIONS_STATE_SECRET'
      secretRef: 'integrations-state-secret'
    }
  ],
  empty(integrationsEncKey) ? [] : [
    {
      name: 'NORA_INTEGRATIONS_ENC_KEY'
      secretRef: 'integrations-enc-key'
    }
  ]
)

var apiPlatformEnv = enablePlatform ? [
  {
    name: 'NORA_PLATFORM_ENABLED'
    value: 'true'
  }
  {
    name: 'PLATFORM_DATASOURCE_URL'
    value: postgresPlatform.?outputs.jdbcUrl ?? ''
  }
  {
    name: 'PLATFORM_DATASOURCE_USERNAME'
    value: postgresAdminLogin
  }
  {
    name: 'PLATFORM_DATASOURCE_PASSWORD'
    secretRef: 'postgres-platform-password'
  }
  {
    name: 'NORA_PLATFORM_INTERNAL_TOKEN'
    secretRef: 'internal-service-token'
  }
  {
    name: 'NORA_PLATFORM_ADMIN_TOKEN'
    secretRef: 'admin-bridge-token'
  }
] : []

// RLS enforce (ADR 0002/0019/0026). NORA_RLS_ENFORCE only turns on the aspect; the REAL
// enforce requires DATASOURCE_USERNAME/PASSWORD to point at the nora_app role (NOBYPASSRLS) —
// a separate, controlled cutover step (see ADR 0026), NOT done in this template.
// When rlsTelemetryDatasourceUrl is set, injects the dedicated BYPASSRLS path of the
// telemetry (role nora_telemetry) so the operator panel keeps aggregating
// cross-tenant under enforce (otherwise it would see 0 rows, fail-closed).
var apiRlsEnv = concat(
  rlsEnforce ? [
    {
      name: 'NORA_RLS_ENFORCE'
      value: 'true'
    }
    // Flyway runs as ADMIN (DDL + owner of the tables) while the runtime is nora_app (ADR 0028).
    // SPRING_FLYWAY_* maps to spring.flyway.* (relaxed binding) — only exists when set here.
    {
      name: 'SPRING_FLYWAY_URL'
      value: postgres.outputs.jdbcUrl
    }
    {
      name: 'SPRING_FLYWAY_USER'
      value: postgresAdminLogin
    }
    {
      name: 'SPRING_FLYWAY_PASSWORD'
      secretRef: 'postgres-password'
    }
  ] : [],
  empty(rlsTelemetryDatasourceUrl) ? [] : [
    {
      name: 'NORA_TELEMETRY_DATASOURCE_URL'
      value: rlsTelemetryDatasourceUrl
    }
    {
      name: 'NORA_TELEMETRY_DATASOURCE_USERNAME'
      value: 'nora_telemetry'
    }
    {
      name: 'NORA_TELEMETRY_DATASOURCE_PASSWORD'
      secretRef: 'rls-telemetry-password'
    }
  ]
)

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
      // RAG embeddings (US15/PR #206): the API consumes OPENAI_API_KEY/GEMINI_API_KEY via
      // secretRef in the env — without these references the Container App preflight fails with
      // ContainerAppSecretRefNotFound (latent bug: no deploy has run since the merge).
      {
        name: 'openai-api-key'
        keyVaultUrl: '${kvUri}secrets/openai-api-key'
        identity: uaiApi.outputs.id
      }
      {
        name: 'gemini-api-key'
        keyVaultUrl: '${kvUri}secrets/gemini-api-key'
        identity: uaiApi.outputs.id
      }
    ],
    // OAuth integrations (ADR 0031): DIRECT VALUE in the app secret store (same pattern as
    // registry-password), conditional on being set. Do NOT use keyVaultUrl here: on the
    // first activation with KV-reference the platform injected a corrupted value (0x3F) and
    // TokenCipher took down the boot (revision ActivationFailed on 2026-06-11), even though
    // `az containerapp secret list --show-values` showed the correct value. The KV keeps
    // holding the copies (kvSecrets block) for operation/rotation.
    empty(googleOauthClientSecret) ? [] : [
      {
        name: 'google-oauth-client-secret'
        value: googleOauthClientSecret
      }
    ],
    empty(slackOauthClientSecret) ? [] : [
      {
        name: 'slack-oauth-client-secret'
        value: slackOauthClientSecret
      }
    ],
    empty(githubOauthClientSecret) ? [] : [
      {
        name: 'github-oauth-client-secret'
        value: githubOauthClientSecret
      }
    ],
    empty(notionOauthClientSecret) ? [] : [
      {
        name: 'notion-oauth-client-secret'
        value: notionOauthClientSecret
      }
    ],
    empty(todoistOauthClientSecret) ? [] : [
      {
        name: 'todoist-oauth-client-secret'
        value: todoistOauthClientSecret
      }
    ],
    empty(linearOauthClientSecret) ? [] : [
      {
        name: 'linear-oauth-client-secret'
        value: linearOauthClientSecret
      }
    ],
    // Wave 2 (same direct-value pattern): Microsoft + Telegram.
    empty(msOauthClientSecret) ? [] : [
      {
        name: 'ms-oauth-client-secret'
        value: msOauthClientSecret
      }
    ],
    empty(telegramBotToken) ? [] : [
      {
        name: 'telegram-bot-token'
        value: telegramBotToken
      }
    ],
    empty(integrationsStateSecret) ? [] : [
      {
        name: 'integrations-state-secret'
        value: integrationsStateSecret
      }
    ],
    empty(integrationsEncKey) ? [] : [
      {
        name: 'integrations-enc-key'
        value: integrationsEncKey
      }
    ],
    empty(registryServer) ? [] : [
      {
        name: 'registry-password'
        value: registryPassword
      }
    ],
    apiPlatformSecrets,
    // BYPASSRLS telemetry RLS (ADR 0026): references the KV secret only when set.
    empty(rlsTelemetryPassword) ? [] : [
      {
        name: 'rls-telemetry-password'
        keyVaultUrl: '${kvUri}secrets/rls-telemetry-password'
        identity: uaiApi.outputs.id
      }
    ],
    // RLS enforce (ADR 0028): nora_app role password, referenced from the KV only at cutover.
    empty(appDbPassword) ? [] : [
      {
        name: 'nora-app-password'
        keyVaultUrl: '${kvUri}secrets/nora-app-password'
        identity: uaiApi.outputs.id
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
    customDomains: apiCustomDomains
    cpu: '1'
    memory: '2Gi'
    minReplicas: 1 // always at least 1 — the API is a critical path
    maxReplicas: 3
    envVars: concat([
      {
        name: 'SPRING_PROFILES_ACTIVE'
        value: env
      }
      {
        name: 'NORA_ENV'
        value: env
      }
      // Datasource — Spring expects DATASOURCE_* (not DATABASE_*)
      {
        name: 'DATASOURCE_URL'
        value: postgres.outputs.jdbcUrl
      }
      // RLS enforce (ADR 0028): runtime connects as nora_app (NOBYPASSRLS); otherwise, admin.
      {
        name: 'DATASOURCE_USERNAME'
        value: rlsEnforce ? appDbUsername : postgresAdminLogin
      }
      {
        name: 'DATASOURCE_PASSWORD'
        secretRef: rlsEnforce ? 'nora-app-password' : 'postgres-password'
      }
      {
        name: 'JWT_SECRET'
        secretRef: 'jwt-secret'
      }
      // RAG embeddings (chat semantic search). Provider-agnostic (ADR 0004): the client uses
      // GEMINI_API_KEY (default) or OPENAI_API_KEY. 'unset' (empty KV) = embeddings turned off.
      {
        name: 'GEMINI_API_KEY'
        secretRef: 'gemini-api-key'
      }
      {
        name: 'OPENAI_API_KEY'
        secretRef: 'openai-api-key'
      }
      // Worker NLP base URL — Spring expects NLP_WORKER_BASE_URL
      {
        name: 'NLP_WORKER_BASE_URL'
        value: 'https://${workerApp.outputs.fqdn}'
      }
      {
        name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
        value: appInsights.outputs.connectionString
      }
      // Role name distinguishes api/worker/web in the Application Map.
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
      // CORS + public URLs (point to the web Container App)
      // FQDNs built via env.defaultDomain to avoid the apiApp <-> webApp cycle
      {
        name: 'CORS_ALLOWED_ORIGINS'
        value: corsAllowedOrigins
      }
      {
        name: 'NORA_APP_PUBLIC_BASE_URL'
        value: frontendBaseUrl
      }
      {
        name: 'NORA_FRONTEND_BASE_URL'
        value: frontendBaseUrl
      }
      // Forces secure auth cookie (HTTPS-only) — the prod stack only uses HTTPS
      {
        name: 'AUTH_COOKIE_SECURE'
        value: 'true'
      }
      // Auth cookie Domain. Web and api are on sibling subdomains of the same
      // Container Apps env (e.g.: salmonbeach-X.centralus.azurecontainerapps.io). Without
      // an explicit Domain, the cookie stays scoped to the api and the Next middleware on
      // the web domain does not see it -> infinite redirect. Setting it to the env's
      // registrable domain, web and api become "same-site" and share the cookie.
      {
        name: 'AUTH_COOKIE_DOMAIN'
        value: authCookieDomainValue
      }
      // Blocks signup/reset from returning raw tokens in prod
      {
        name: 'EXPOSE_DEV_TOKENS'
        value: 'false'
      }
      // Resend email — if RESEND_API_KEY is empty, the app falls back to LogEmailSender.
      // In prod we want the real sender; the KV holds the secret when set via bicepparam.
      {
        name: 'RESEND_API_KEY'
        secretRef: 'resend-api-key'
      }
      {
        name: 'NORA_EMAIL_FROM'
        value: noraEmailFrom
      }
    ], apiIntegrationsEnv, apiPlatformEnv, apiRlsEnv)
    secretsObject: apiSecrets
    userAssignedIdentityId: uaiApi.outputs.id
    registry: registry
  }
}

// ---- Web Next.js (external ingress) ----

var webSecrets = {
  items: union(
    [
      // Core AI chat (BFF /api/chat) consumes the LLM key server-side.
      // Reuses the SAME KV secret the worker uses (openai-api-key).
      {
        name: 'openai-api-key'
        keyVaultUrl: '${kvUri}secrets/openai-api-key'
        identity: uaiWeb.outputs.id
      }
      // Multi-provider chat (ADR 0024): per-provider keys read by the BFF via LLM_KEY_<PROVIDER>.
      {
        name: 'deepseek-api-key'
        keyVaultUrl: '${kvUri}secrets/deepseek-api-key'
        identity: uaiWeb.outputs.id
      }
      {
        name: 'gemini-api-key'
        keyVaultUrl: '${kvUri}secrets/gemini-api-key'
        identity: uaiWeb.outputs.id
      }
    ],
    empty(registryServer) ? [] : [
      {
        name: 'registry-password'
        value: registryPassword
      }
    ],
    enablePlatform ? [
      {
        name: 'internal-service-token'
        keyVaultUrl: '${kvUri}secrets/internal-service-token'
        identity: uaiWeb.outputs.id
      }
    ] : []
  )
}

// Control plane: token so the chat BFF can call /internal/platform/{llm-config,usage} (ADR 0024).
var webPlatformEnv = enablePlatform ? [
  {
    name: 'NORA_PLATFORM_INTERNAL_TOKEN'
    secretRef: 'internal-service-token'
  }
] : []

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
    customDomains: webCustomDomains
    // 0.25/0.5Gi + minReplicas 0 left the Next SSR choked and with visible cold
    // start (site "slow" in the demo). Azure balance authorized by the PO.
    cpu: '1'
    memory: '2Gi'
    minReplicas: 1
    maxReplicas: 3
    envVars: concat([
      // NOTE: NEXT_PUBLIC_* are baked in at build-time into the Next bundle. The web Dockerfile
      // already hardcodes NEXT_PUBLIC_USE_MOCKS=false. Here we inject the URL at runtime only
      // for cases where the build receives the variable as a build-arg ARG in the Dockerfile
      // (needed so the final bundle points to prod). The contract name is
      // NEXT_PUBLIC_API_BASE_URL (aligned with apps/web/src/lib/api/client.ts).
      {
        name: 'NEXT_PUBLIC_API_BASE_URL'
        value: apiBaseUrl
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
      // Core AI chat (BFF /api/chat) — provider-agnostic (ADR 0004). The key
      // stays server-side (secretRef), never in the browser bundle. If openAiApiKey
      // is empty at deploy time, the secret is 'unset' and the chat returns 503.
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
      // Per-provider keys for the multi-provider chat (ADR 0024). The BFF resolves the active model via
      // /internal/platform/llm-config and picks the key by LLM_KEY_<PROVIDER in UPPERCASE>. The
      // LLM_API_KEY/LLM_BASE_URL/LLM_MODEL above remain the OpenAI default (fallback).
      {
        name: 'LLM_KEY_OPENAI'
        secretRef: 'openai-api-key'
      }
      {
        name: 'LLM_KEY_DEEPSEEK'
        secretRef: 'deepseek-api-key'
      }
      {
        name: 'LLM_KEY_GOOGLE'
        secretRef: 'gemini-api-key'
      }
    ], webPlatformEnv)
    secretsObject: webSecrets
    userAssignedIdentityId: uaiWeb.outputs.id
    registry: registry
  }
}

// ============================================================
// MODULES — Control plane: nora-admin (Next shell, ADR 0023 + ADR 0025)
// Operator identity v2 (ADR 0025): INTERNAL ingress (no public FQDN) + cloudflared
// connector (sidecar) exposes the app via Cloudflare Tunnel behind Cloudflare Access.
// Easy Auth (Entra) stays inert (FIAP tenant blocked App Registration). Calls Spring
// /admin/platform/** server-side with the admin token. Only when enablePlatform.
// ============================================================

var tenantIssuer = '${environment().authentication.loginEndpoint}${subscription().tenantId}/v2.0'

var adminSecrets = {
  items: union(
    [
      {
        name: 'admin-bridge-token'
        keyVaultUrl: '${kvUri}secrets/admin-bridge-token'
        identity: uaiAdmin.?outputs.id ?? ''
      }
      {
        name: 'easyauth-client-secret'
        keyVaultUrl: '${kvUri}secrets/easyauth-client-secret'
        identity: uaiAdmin.?outputs.id ?? ''
      }
      {
        name: 'cloudflare-tunnel-token'
        keyVaultUrl: '${kvUri}secrets/cloudflare-tunnel-token'
        identity: uaiAdmin.?outputs.id ?? ''
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

module adminApp 'modules/container-app.bicep' = if (enablePlatform) {
  name: 'adminApp'
  params: {
    name: adminName
    location: location
    tags: tags
    environmentId: containerAppsEnv.outputs.id
    image: adminImage
    containerName: 'admin'
    // Next standalone listens on 3002 (apps/admin/Dockerfile: PORT=3002 / EXPOSE 3002 / healthz).
    targetPort: 3002
    // ADR 0025: INTERNAL ingress — no public FQDN. External access only via Cloudflare Tunnel
    // (cloudflared sidecar) behind Cloudflare Access. minReplicas 1: the connector stays always up.
    ingress: 'internal'
    cpu: '0.25'
    memory: '0.5Gi'
    minReplicas: 1
    maxReplicas: 2
    envVars: [
      {
        name: 'NORA_ENV'
        value: env
      }
      {
        name: 'APPLICATIONINSIGHTS_CONNECTION_STRING'
        value: appInsights.outputs.connectionString
      }
      // Turns on the real nora-admin data layer (out of the mock).
      {
        name: 'NORA_ADMIN_USE_MOCKS'
        value: 'false'
      }
      // Names aligned with apps/admin/src/lib/data.ts (merged in #171). The token carries the
      // admin-bridge-token (server-side calls to /admin/platform/**).
      {
        name: 'PLATFORM_API_BASE_URL'
        value: apiPublicUrl
      }
      {
        name: 'PLATFORM_INTERNAL_TOKEN'
        secretRef: 'admin-bridge-token'
      }
      // Tier 2 (ADR 0025): nora-admin validates (server-side, lib/access.ts) the header
      // Cf-Access-Jwt-Assertion against the team domain JWKS, checking the audience. Empty =
      // validation degrades to edge-only (origin already protected by the tunnel + Access at the edge).
      {
        name: 'CF_ACCESS_TEAM_DOMAIN'
        value: cfAccessTeamDomain
      }
      {
        name: 'CF_ACCESS_AUD'
        value: cfAccessAud
      }
    ]
    secretsObject: adminSecrets
    userAssignedIdentityId: uaiAdmin.?outputs.id ?? ''
    registry: registry
    ipSecurityRestrictions: adminIpSecurityRestrictions
    // cloudflared sidecar (ADR 0025): connects to the Cloudflare Tunnel and forwards to Next on
    // localhost:3002. Only when the token exists — without a token, the admin comes up internal/unreachable
    // (safe) until cloudflare-tunnel.yml runs and CLOUDFLARE_TUNNEL_TOKEN is set.
    sidecars: empty(cloudflareTunnelToken) ? [] : [
      {
        name: 'cloudflared'
        image: cloudflaredImage
        args: [ 'tunnel', '--no-autoupdate', 'run' ]
        resources: {
          cpu: json('0.25')
          memory: '0.5Gi'
        }
        env: [
          {
            name: 'TUNNEL_TOKEN'
            secretRef: 'cloudflare-tunnel-token'
          }
        ]
      }
    ]
    // Easy Auth (Entra) went inert: FIAP tenant blocked App Registration (Authorization_RequestDenied).
    // Replaced by Cloudflare Tunnel + Access (ADR 0025). Kept as a no-op (empty clientId = {}).
    easyAuth: empty(easyAuthClientId) ? {} : {
      enabled: true
      clientId: easyAuthClientId
      openIdIssuer: tenantIssuer
      clientSecretSettingName: 'easyauth-client-secret'
      unauthenticatedClientAction: 'RedirectToLoginPage'
    }
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

// UAI principal IDs (useful for debug or extra role assignments)
output apiUaiPrincipalId string = uaiApi.outputs.principalId
output workerUaiPrincipalId string = uaiWorker.outputs.principalId
output webUaiPrincipalId string = uaiWeb.outputs.principalId

// Control plane (ADR 0022/0023) — empty when enablePlatform=false.
output platformPostgresFqdn string = postgresPlatform.?outputs.fqdn ?? ''
// ADR 0025: nora-admin has internal ingress (no public FQDN). Access is via the Cloudflare
// hostname (Tunnel + Access). The internal FQDN is nora-admin-dev.internal.<defaultDomain>.
output adminUrl string = enablePlatform ? 'https://admin.nora.systems' : ''
