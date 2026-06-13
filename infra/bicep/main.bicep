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

// ---- Integracoes OAuth (NORA Flows Fase 2 — ADR 0031) ----

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

// Onda 1 de provedores genericos (GitHub, Notion, Todoist, Linear) — mesmo contrato do Slack:
// default vazio = conector "nao configurado" no hub, sem quebrar o deploy.

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

@description('Assina o state OAuth (HMAC-SHA256, ADR 0031). Vazio = segredo efemero por boot (states nao sobrevivem a restart — ok em dev, ruim em prod).')
@secure()
param integrationsStateSecret string = ''

@description('Cifra os tokens OAuth em repouso (AES-256-GCM, 32 bytes base64, ADR 0031). Vazio = tokens armazenados sem cifra com WARN no boot.')
@secure()
param integrationsEncKey string = ''

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
// PARAMS — Control plane (ADR 0022/0023/0024). Gated por enablePlatform.
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

// ---- Operator identity v2: Cloudflare Tunnel + Access (ADR 0025, substitui Easy Auth do 0023) ----

@description('Connector token do Cloudflare Tunnel do nora-admin. Vai pro KV (cloudflare-tunnel-token). Vazio = "unset" (cloudflared nao conecta — tunnel off). Gerado pelo workflow cloudflare-tunnel.yml e setado no Secret CLOUDFLARE_TUNNEL_TOKEN.')
@secure()
param cloudflareTunnelToken string = ''

@description('Imagem do conector cloudflared (sidecar do nora-admin). Pinada por reprodutibilidade; bumpar conforme releases do Cloudflare. Verificado no Docker Hub em 2026-06-01.')
param cloudflaredImage string = 'docker.io/cloudflare/cloudflared:2026.5.2'

@description('Team domain do Cloudflare Access (ex.: stratfy.cloudflareaccess.com). O nora-admin valida o JWT Cf-Access-Jwt-Assertion contra o JWKS desse dominio (Tier 2, defense-in-depth). Vazio = validacao degrada pra edge-only.')
param cfAccessTeamDomain string = ''

@description('AUD tag da Access Application (admin.nora.systems). O nora-admin valida o audience do JWT do Access. Vazio = validacao de JWT degrada pra edge-only (origem ja protegida pelo tunnel + Access na borda).')
param cfAccessAud string = ''

// ---- Dominio publico customizado (Cloudflare -> custom domain, ver docs/operations/web-custom-domain.md) ----

@description('Dominio publico (apex). Vazio = usa o FQDN .azurecontainerapps.io (comportamento antigo). Ex.: nora.systems')
param publicDomain string = ''

@description('Nome do managed certificate (no env) para o apex. Ex.: mc-nora-cae-dev-nora-systems-XXXX. Criado via az hostname bind.')
param webCertName string = ''

@description('Nome do managed certificate (no env) para www.')
param wwwCertName string = ''

@description('Nome do managed certificate (no env) para api.')
param apiCertName string = ''

// ---- RLS enforce (defesa em profundidade do tenant_id, ADR 0002/0019/0026) ----

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

// Control plane: UAI dedicada do nora-admin (ADR 0023). Só quando enablePlatform.
module uaiAdmin 'modules/user-assigned-identity.bicep' = if (enablePlatform) {
  name: 'uaiAdmin'
  params: {
    name: uaiAdminName
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
      // Chaves por provider pro chat multi-provider (ADR 0024). Sempre no KV ('unset' quando
      // vazias) — o web só as referencia se o provider for usado.
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
    // Integracoes OAuth (ADR 0031): secrets criados SO quando setados — sem placeholder 'unset'
    // (o TokenCipher rejeitaria "unset" como base64 e derrubaria o boot da API).
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
    // Control plane (ADR 0022/0023): secrets só quando enablePlatform.
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
    // RLS telemetria BYPASSRLS (ADR 0026): so quando a senha do nora_telemetry e
    // fornecida (passo de cutover). Sem isso, nenhum secret extra e criado.
    empty(rlsTelemetryPassword) ? [] : [
      {
        name: 'rls-telemetry-password'
        value: rlsTelemetryPassword
      }
    ],
    // RLS enforce (ADR 0028): senha do nora_app criada no KV so quando fornecida (cutover).
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
    enablePurgeProtection: false // dev — permite teardown rapido
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

// Control plane: 2º Postgres SEPARADO (ADR 0022 — blast radius isolado). Reusa o módulo 1:1.
// Server próprio B1ms; database nora_platform. Só quando enablePlatform.
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

// ---- Domínio público customizado (Cloudflare -> custom domain no Container App) ----
// Quando publicDomain está setado (ex.: 'nora.systems'), web/api usam o domínio próprio:
// web em nora.systems chama a API em api.nora.systems (mesmo registrable domain), então os
// cookies de auth (Domain=nora.systems) são compartilhados cross-subdomínio. Os managed
// certificates são criados via `az hostname bind` (ver docs/operations/web-custom-domain.md)
// e referenciados aqui por nome. Vazio = usa só o FQDN .azurecontainerapps.io (comportamento antigo).
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

// Control plane: token + base da API pro worker ler /internal/platform/llm-config (ADR 0024).
// O outro arquiteto pluga a leitura no hot-path; aqui só provisionamos as envs.
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

// Control plane (ADR 0022/0024): 2º datasource + tokens. Só quando enablePlatform.
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

// Integracoes OAuth (ADR 0031): cada bloco so entra quando o respectivo param foi setado —
// secretRef para secret inexistente derrubaria a revision, e placeholder 'unset' quebraria o
// boot (TokenCipher valida base64). Redirect URIs derivam do dominio publico da API (PR #215):
// precisam bater EXATAMENTE com os Authorized redirect URIs cadastrados no Google/Slack.
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

// RLS enforce (ADR 0002/0019/0026). NORA_RLS_ENFORCE so liga o aspect; o enforce REAL
// exige que DATASOURCE_USERNAME/PASSWORD apontem pro role nora_app (NOBYPASSRLS) —
// passo de cutover separado e controlado (ver ADR 0026), NAO feito neste template.
// Quando rlsTelemetryDatasourceUrl esta setada, injeta o caminho BYPASSRLS dedicado da
// telemetria (role nora_telemetry) pra que o painel operador continue agregando
// cross-tenant sob enforce (senao veria 0 linhas, fail-closed).
var apiRlsEnv = concat(
  rlsEnforce ? [
    {
      name: 'NORA_RLS_ENFORCE'
      value: 'true'
    }
    // Flyway roda como ADMIN (DDL + dono das tabelas) enquanto o runtime e nora_app (ADR 0028).
    // SPRING_FLYWAY_* mapeia pra spring.flyway.* (relaxed binding) — so existe quando setado aqui.
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
      // Embeddings RAG (US15/PR #206): a API consome OPENAI_API_KEY/GEMINI_API_KEY via
      // secretRef no env — sem estas referencias o preflight do Container App falha com
      // ContainerAppSecretRefNotFound (bug latente: nenhum deploy rodou desde o merge).
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
    // Integracoes OAuth (ADR 0031): VALOR DIRETO no secret store do app (mesmo padrao do
    // registry-password), condicionais a estarem setados. NAO usar keyVaultUrl aqui: na
    // primeira ativacao com KV-reference a plataforma injetou valor corrompido (0x3F) e o
    // TokenCipher derrubou o boot (revision ActivationFailed em 2026-06-11), apesar de
    // `az containerapp secret list --show-values` exibir o valor correto. O KV continua
    // guardando as copias (bloco kvSecrets) para operacao/rotacao.
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
    // RLS telemetria BYPASSRLS (ADR 0026): referencia o secret do KV so quando setado.
    empty(rlsTelemetryPassword) ? [] : [
      {
        name: 'rls-telemetry-password'
        keyVaultUrl: '${kvUri}secrets/rls-telemetry-password'
        identity: uaiApi.outputs.id
      }
    ],
    // RLS enforce (ADR 0028): senha do role nora_app, referenciada do KV so no cutover.
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
    minReplicas: 1 // sempre pelo menos 1 — API e caminho critico
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
      // Datasource — Spring espera DATASOURCE_* (nao DATABASE_*)
      {
        name: 'DATASOURCE_URL'
        value: postgres.outputs.jdbcUrl
      }
      // RLS enforce (ADR 0028): runtime conecta como nora_app (NOBYPASSRLS); senao, admin.
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
      // Embeddings do RAG (busca semântica do chat). Provider-agnóstico (ADR 0004): o client usa
      // GEMINI_API_KEY (default) ou OPENAI_API_KEY. 'unset' (KV vazio) = embeddings desligados.
      {
        name: 'GEMINI_API_KEY'
        secretRef: 'gemini-api-key'
      }
      {
        name: 'OPENAI_API_KEY'
        secretRef: 'openai-api-key'
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
      // Forca cookie auth seguro (HTTPS-only) — prod stack so usa HTTPS
      {
        name: 'AUTH_COOKIE_SECURE'
        value: 'true'
      }
      // Domain do cookie de auth. Web e api estao em subdominios irmaos do mesmo
      // Container Apps env (ex.: salmonbeach-X.centralus.azurecontainerapps.io). Sem
      // Domain explicito, o cookie fica scoped na api e o middleware do Next no
      // dominio web nao enxerga -> redirect infinito. Setando pro registrable domain
      // do env, web e api ficam "same-site" e compartilham o cookie.
      {
        name: 'AUTH_COOKIE_DOMAIN'
        value: authCookieDomainValue
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
      // Chat IA do Core (BFF /api/chat) consome a chave LLM server-side.
      // Reusa o MESMO secret do KV que o worker usa (openai-api-key).
      {
        name: 'openai-api-key'
        keyVaultUrl: '${kvUri}secrets/openai-api-key'
        identity: uaiWeb.outputs.id
      }
      // Chat multi-provider (ADR 0024): chaves por provider lidas pelo BFF via LLM_KEY_<PROVIDER>.
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

// Control plane: token pro BFF de chat chamar /internal/platform/{llm-config,usage} (ADR 0024).
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
    // 0.25/0.5Gi + minReplicas 0 deixava o SSR do Next sufocado e com cold
    // start visivel (site "lento" na demo). Saldo Azure autorizado pelo PO.
    cpu: '1'
    memory: '2Gi'
    minReplicas: 1
    maxReplicas: 3
    envVars: concat([
      // NOTA: NEXT_PUBLIC_* sao baked in build-time no bundle Next. O Dockerfile do web
      // ja hardcoda NEXT_PUBLIC_USE_MOCKS=false. Aqui injetamos a URL em runtime apenas
      // para casos onde o build receba a variavel como build-arg ARG no Dockerfile
      // (necessario para que o bundle final aponte pra prod). Nome do contrato e
      // NEXT_PUBLIC_API_BASE_URL (alinhado com apps/web/src/lib/api/client.ts).
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
      // Chaves por provider pro chat multi-provider (ADR 0024). O BFF resolve o modelo ativo via
      // /internal/platform/llm-config e escolhe a chave por LLM_KEY_<PROVIDER em MAIÚSCULO>. Os
      // LLM_API_KEY/LLM_BASE_URL/LLM_MODEL acima seguem como default OpenAI (fallback).
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
// Identidade de operador v2 (ADR 0025): ingress INTERNAL (sem FQDN público) + conector
// cloudflared (sidecar) expõe o app via Cloudflare Tunnel atrás do Cloudflare Access.
// Easy Auth (Entra) fica inerte (tenant FIAP bloqueou App Registration). Chama o Spring
// /admin/platform/** server-side com o admin token. Só quando enablePlatform.
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
    // Next standalone escuta em 3002 (apps/admin/Dockerfile: PORT=3002 / EXPOSE 3002 / healthz).
    targetPort: 3002
    // ADR 0025: ingress INTERNAL — sem FQDN público. Acesso externo só via Cloudflare Tunnel
    // (sidecar cloudflared) atrás do Cloudflare Access. minReplicas 1: o conector fica sempre de pé.
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
      // Liga o data layer real do nora-admin (sai do mock).
      {
        name: 'NORA_ADMIN_USE_MOCKS'
        value: 'false'
      }
      // Nomes alinhados ao apps/admin/src/lib/data.ts (merged em #171). O token carrega o
      // admin-bridge-token (chamadas server-side a /admin/platform/**).
      {
        name: 'PLATFORM_API_BASE_URL'
        value: apiPublicUrl
      }
      {
        name: 'PLATFORM_INTERNAL_TOKEN'
        secretRef: 'admin-bridge-token'
      }
      // Tier 2 (ADR 0025): o nora-admin valida (server-side, lib/access.ts) o header
      // Cf-Access-Jwt-Assertion contra o JWKS do team domain, conferindo o audience. Vazios =
      // validação degrada pra edge-only (origem já protegida pelo tunnel + Access na borda).
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
    // Sidecar cloudflared (ADR 0025): conecta ao Cloudflare Tunnel e encaminha pro Next em
    // localhost:3002. Só quando o token existe — sem token, o admin sobe internal/inacessível
    // (seguro) até o cloudflare-tunnel.yml rodar e o CLOUDFLARE_TUNNEL_TOKEN ser setado.
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
    // Easy Auth (Entra) ficou inerte: tenant FIAP bloqueou App Registration (Authorization_RequestDenied).
    // Substituído por Cloudflare Tunnel + Access (ADR 0025). Mantido como no-op (clientId vazio = {}).
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

// UAI principal IDs (uteis pra debug ou role assignments extras)
output apiUaiPrincipalId string = uaiApi.outputs.principalId
output workerUaiPrincipalId string = uaiWorker.outputs.principalId
output webUaiPrincipalId string = uaiWeb.outputs.principalId

// Control plane (ADR 0022/0023) — vazios quando enablePlatform=false.
output platformPostgresFqdn string = postgresPlatform.?outputs.fqdn ?? ''
// ADR 0025: nora-admin tem ingress internal (sem FQDN público). O acesso é pelo hostname do
// Cloudflare (Tunnel + Access). O FQDN interno é nora-admin-dev.internal.<defaultDomain>.
output adminUrl string = enablePlatform ? 'https://admin.nora.systems' : ''
