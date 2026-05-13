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
// Azure for Students restringe regiões via policy "sys.regionrestriction".
// Permitidas: mexicocentral, northcentralus, eastus, centralus, canadacentral.
// Brazil South NÃO está permitido. Usamos eastus pra melhor maturidade de serviços
// (~110ms do Brasil — aceitável pra dev/pitch).
param location = 'eastus'
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

// ---- Imagens ----
// Default = placeholder Microsoft. Substituir pelas imagens reais quando o pipeline de build/push estiver no ar.
// Sugestão: ghcr.io/sys0xff/nora-{api,worker,web}:<sha-curto>
param apiImage = 'mcr.microsoft.com/k8se/quickstart:latest'
param workerImage = 'mcr.microsoft.com/k8se/quickstart:latest'
param webImage = 'mcr.microsoft.com/k8se/quickstart:latest'

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
