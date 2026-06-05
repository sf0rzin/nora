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
// Chat multi-provider (ADR 0024). Vazias = só OpenAI troca ao vivo.
param deepSeekApiKey = readEnvironmentVariable('DEEPSEEK_API_KEY', '')
param geminiApiKey = readEnvironmentVariable('GEMINI_API_KEY', '')
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

// ---- Control plane (ADR 0022/0023/0024/0025) ----
// LIGADO no go-live. PRÉ-REQUISITO antes do merge: setar PG_PLATFORM_ADMIN_PASSWORD,
// NORA_PLATFORM_INTERNAL_TOKEN e NORA_PLATFORM_ADMIN_TOKEN nos GitHub Secrets — senão os tokens
// viram 'unset' no KV (admin/internal destravados).
// Auth do operador (ADR 0025): Cloudflare Access + Cloudflare Tunnel. O nora-admin sobe com
// ingress INTERNAL (sem FQDN público) e um sidecar cloudflared expõe admin.nora.systems atrás
// do Access (allowlist + OTP/SSO). Easy Auth (Entra) ficou inerte (tenant FIAP bloqueou App
// Registration) — EASYAUTH_* podem ficar vazios. Ver control-plane-runbook.md + cloudflare-access.md.
param enablePlatform = true
param platformPostgresAdminPassword = readEnvironmentVariable('PG_PLATFORM_ADMIN_PASSWORD', '')
param platformInternalToken = readEnvironmentVariable('NORA_PLATFORM_INTERNAL_TOKEN', '')
param platformAdminToken = readEnvironmentVariable('NORA_PLATFORM_ADMIN_TOKEN', '')
param adminImage = 'ghcr.io/sys0xff/nora-admin:latest'
// Easy Auth (Entra) — inerte no go-live (ADR 0025). Mantido vazio; Cloudflare substitui.
param easyAuthClientId = readEnvironmentVariable('EASYAUTH_CLIENT_ID', '')
param easyAuthClientSecret = readEnvironmentVariable('EASYAUTH_CLIENT_SECRET', '')
// Com Tunnel (ingress internal) não há FQDN público pra restringir — fica vazio.
param adminIpSecurityRestrictions = []

// ---- Cloudflare Tunnel + Access (operator identity v2, ADR 0025) ----
// CLOUDFLARE_TUNNEL_TOKEN sai do workflow cloudflare-tunnel.yml (que cria o túnel) e é setado
// como GitHub Secret. Vazio = cloudflared não sobe (admin fica internal/inacessível até setar).
// cfAccessTeamDomain casa com o team 'stratfy' (cloudflare-access.md). CF_ACCESS_AUD (não-secreto)
// vem da Access App — setar como GitHub Variable. Vazio = Tier 2 degrada pra edge-only.
param cloudflareTunnelToken = readEnvironmentVariable('CLOUDFLARE_TUNNEL_TOKEN', '')
param cfAccessTeamDomain = 'stratfy.cloudflareaccess.com'
param cfAccessAud = readEnvironmentVariable('CF_ACCESS_AUD', '')

// ---- RLS enforce (ADR 0028) — FLIP DO CUTOVER ----
// Liga o Row Level Security REAL: a API passa a conectar como nora_app (NOBYPASSRLS),
// o Flyway segue como admin (nora_admin), e o caminho BYPASSRLS da telemetria (nora_telemetry)
// mantém o painel operador agregando cross-tenant. Roles + senhas já provisionados pelo
// rls-cutover.yml (R001). As senhas vêm dos GitHub Secrets (MESMA fonte que o R001 usou) e o
// bicep as injeta no Key Vault (nora-app-password / rls-telemetry-password).
// ROLLBACK: remover este bloco (ou rlsEnforce=false) + redeploy → API volta pra nora_admin.
param rlsEnforce = true
param appDbPassword = readEnvironmentVariable('NORA_APP_PASSWORD')
param rlsTelemetryDatasourceUrl = 'jdbc:postgresql://nora-pg-dev-wgl3a3.postgres.database.azure.com:5432/nora?sslmode=require'
param rlsTelemetryPassword = readEnvironmentVariable('RLS_TELEMETRY_PASSWORD')
