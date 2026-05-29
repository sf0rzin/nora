// main.dev.bicepparam — parâmetros do ambiente DEV
//
// Secrets vêm de env vars locais (NÃO commitar valores no arquivo):
//   $env:PG_ADMIN_PASSWORD = "..."
//   $env:JWT_SECRET = "..."
//   $env:OPENAI_API_KEY = "..."      # opcional
//   $env:REGISTRY_PASSWORD = "..."   # opcional
//
// Deploy:
//   az deployment group create --resource-group rg-nora-dev \
//     --template-file main.bicep \
//     --parameters main.dev.bicepparam

using './main.bicep'

param env = 'dev'
// Azure for Students restringe regiões via 2 camadas:
//   1. Policy "sys.regionrestriction" na subscription — permite só:
//      mexicocentral, northcentralus, eastus, centralus, canadacentral.
//   2. Offer restriction por serviço — eastus permite Storage/KV/LA/AI mas
//      REJEITA Postgres Flexible Server com `LocationIsOfferRestricted`.
//      Testado em 13/05: centralus, northcentralus, canadacentral e
//      mexicocentral aceitam Postgres B1ms. Eastus rejeita.
//
// Escolha: centralus — alinha com nora-speech existente, ~110ms do Brasil,
// maturidade alta de SKUs. nora-speech do Anthony pode coexistir (RG novo).
param location = 'centralus'
param namePrefix = 'nora'
param tags = {
  project: 'nora'
  env: 'dev'
  'managed-by': 'bicep'
}

// ---- Secrets via env vars ----
param postgresAdminLogin = 'nora_admin'
param postgresAdminPassword = readEnvironmentVariable('PG_ADMIN_PASSWORD')
param jwtSecret = readEnvironmentVariable('JWT_SECRET')
param openAiApiKey = readEnvironmentVariable('OPENAI_API_KEY', '')
// Se vazio, backend usa LogEmailSender em dev (logs em vez de email real).
param resendApiKey = readEnvironmentVariable('RESEND_API_KEY', '')
param noraEmailFrom = readEnvironmentVariable('NORA_EMAIL_FROM', 'NORA <onboarding@resend.dev>')

// ---- Imagens ----
// Imagens reais publicadas pelo workflow `build-images.yml` no GHCR público.
// Packages marcados como Public em github.com/users/sys0xff/packages.
// Tag `latest` é atualizada a cada push em main que toca services/{api,worker,nlp-worker} ou apps/web.
param apiImage = 'ghcr.io/sys0xff/nora-api:latest'
param workerImage = 'ghcr.io/sys0xff/nora-worker:latest'
param webImage = 'ghcr.io/sys0xff/nora-web:latest'

// ---- Registry (vazio = imagens públicas) ----
param registryServer = ''
param registryUsername = ''
param registryPassword = readEnvironmentVariable('REGISTRY_PASSWORD', '')

// ---- AI Search ----
// Off por padrão em dev. Ligar ~14 dias antes do pitch (~29/05 → 12/06).
param enableSearch = false
param searchSku = 'basic'

// ---- Postgres firewall ----
// Em dev, adicionar IP do desenvolvedor pra acessar via psql/DBeaver direto.
// Pegar IP com: curl ifconfig.me
param postgresFirewallRules = []

// ---- Control plane (ADR 0022/0023/0024) ----
// OFF por padrão: mantém a infra atual intacta. O outro arquiteto liga (enablePlatform=true)
// quando: (1) imagem do nora-admin publicada no GHCR; (2) grupo Entra + App Registration criados
// (passo MANUAL — ver docs/operations/control-plane-runbook.md); (3) secrets abaixo setados.
param enablePlatform = false
param platformPostgresAdminPassword = readEnvironmentVariable('PG_PLATFORM_ADMIN_PASSWORD', '')
param platformInternalToken = readEnvironmentVariable('NORA_PLATFORM_INTERNAL_TOKEN', '')
param platformAdminToken = readEnvironmentVariable('NORA_PLATFORM_ADMIN_TOKEN', '')
param adminImage = 'ghcr.io/sys0xff/nora-admin:latest'
param easyAuthClientId = readEnvironmentVariable('EASYAUTH_CLIENT_ID', '')
param easyAuthClientSecret = readEnvironmentVariable('EASYAUTH_CLIENT_SECRET', '')
// Allowlist de IP do operador no go-live, ex.:
// [ { name: 'escritorio', ipAddressRange: '203.0.113.0/24', action: 'Allow' } ]
param adminIpSecurityRestrictions = []
