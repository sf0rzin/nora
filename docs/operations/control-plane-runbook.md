# Runbook — Ligar o Control Plane (admin de operador + telemetria)

> Como promover o control plane de **OFF** (default) para **ON** no Azure. Cobre o **passo MANUAL do
> Entra** (Bicep não cria grupo/App Registration de forma confiável). ADRs 0022/0023/0024. Contrato:
> `docs/engineering/contracts/platform-control-plane.md`.

## O que o IaC já faz vs. o que é manual

| IaC (Bicep, `enablePlatform=true`) | Manual (este runbook) |
|---|---|
| 2º Postgres `nora-pg-platform-dev`, db `nora_platform` | Grupo Entra "NORA Platform Admins" |
| UAI `nora-uai-admin-dev` + acesso ao KV | App Registration do Easy Auth (clientId/secret) |
| Container App `nora-admin` (Easy Auth + IP allowlist) | Atribuir o grupo à App Registration |
| Secrets no KV (tokens, senha pg-platform, easyauth) | Setar os GitHub Secrets |
| Env do api/worker/web pro control plane | Publicar a imagem `nora-admin` (outro arquiteto) |

## Pré-requisitos

- Imagem `ghcr.io/sys0xff/nora-admin:latest` publicada (app Next do outro arquiteto; precisa expor
  `/healthz` e ler `X-MS-CLIENT-PRINCIPAL-NAME`/`-ID` do Easy Auth).
- `az` logado no tenant `fiap.com.br`, subscription Azure for Students, RG `rg-nora-dev`.

## Passo 1 — Gerar secrets

```powershell
# Tokens internos (distintos por least-privilege) e senha do 2º Postgres
$internal = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
$admin    = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))
$pgpwd    = [Convert]::ToBase64String((1..24 | ForEach-Object { Get-Random -Max 256 }))
```

## Passo 2 — Entra (MANUAL): grupo + App Registration

```bash
# 2.1 Grupo de segurança dos operadores
GROUP_ID=$(az ad group create --display-name "NORA Platform Admins" \
  --mail-nickname "nora-platform-admins" --query id -o tsv)

# 2.2 App Registration do Easy Auth (web)
ADMIN_FQDN="nora-admin-dev.<envDefaultDomain>"   # pegar de: az containerapp env show ... defaultDomain
APP_ID=$(az ad app create --display-name "NORA Admin (Easy Auth)" \
  --web-redirect-uris "https://$ADMIN_FQDN/.auth/login/aad/callback" \
  --query appId -o tsv)

# 2.3 Client secret
CLIENT_SECRET=$(az ad app credential reset --id "$APP_ID" --query password -o tsv)

# 2.4 Service principal + "assignment required" + atribuir SÓ o grupo
az ad sp create --id "$APP_ID"
# No portal: Enterprise Applications > NORA Admin > Properties > "Assignment required?" = Yes
# Users and groups > Add > selecionar "NORA Platform Admins"

# 2.5 Adicionar os donos ao grupo
az ad group member add --group "$GROUP_ID" --member-id <objectId-do-dono>
```

> **Por que "Assignment required" + atribuir só o grupo:** assim o Easy Auth (Entra) só emite token
> para membros do grupo. É a 1ª linha; a 2ª é o admin token entre `nora-admin` e o Spring; a 3ª é a
> allowlist de IP. Defense in depth (ADR 0023).

## Passo 3 — GitHub Secrets (repo `sys0xFF/nora`)

```
PG_PLATFORM_ADMIN_PASSWORD = <$pgpwd>
NORA_PLATFORM_INTERNAL_TOKEN = <$internal>
NORA_PLATFORM_ADMIN_TOKEN = <$admin>
EASYAUTH_CLIENT_ID = <$APP_ID>
EASYAUTH_CLIENT_SECRET = <$CLIENT_SECRET>
```

## Passo 4 — Ligar no Bicep e deployar

Em `infra/bicep/main.dev.bicepparam`:

```bicep
param enablePlatform = true
param adminIpSecurityRestrictions = [
  { name: 'operadores', ipAddressRange: '<CIDR-do-escritorio>', action: 'Allow' }
]
```

Push em `main` (ou workflow_dispatch) → `deploy-infra.yml` provisiona tudo. O deploy é idempotente.

## Passo 5 — Chaves de provider (pro switch de modelo em runtime)

Pra trocar o modelo de um serviço **ao vivo** sem deploy, a chave do provider precisa já estar
provisionada. OpenAI já está (`openai-api-key`). Para DeepSeek e Gemini (seeds do catálogo), adicione
as chaves no mecanismo `provider→secret` que worker/BFF usam (env/KV — alinhar com o outro arquiteto
ao plugar o hot-path). Sem a chave, o catálogo mostra o modelo mas a troca efetiva exige deploy
(limitação documentada, ADR 0024 / decisão #C).

## Verificação pós-deploy

```bash
# API subiu com o módulo platform (procure no log): "Control plane: migração ... OK — módulo HEALTHY"
# llm-config (precisa do internal token):
curl -H "X-Internal-Token: $internal" \
  "https://nora-api-dev.<domain>/internal/platform/llm-config?service=chat"
# -> {"provider":"deepseek","model":"deepseek-v4-flash","baseUrl":"...","enabled":true}

# admin (precisa do admin token; via nora-admin no fluxo real):
curl -H "X-Internal-Token: $admin" "https://nora-api-dev.<domain>/admin/platform/models"

# Console: abrir https://nora-admin-dev.<domain> -> redireciona pro login Entra (só membros do grupo).
```

## Rollback

`param enablePlatform = false` + deploy. O 2º Postgres e o `nora-admin` somem; a API volta a
`NORA_PLATFORM_ENABLED` ausente (módulo inerte). O caminho do cliente nunca dependeu disto (fail-soft,
ADR 0022).

## Notas de segurança

- `/admin/platform/**` no `nora-api` público é protegido só pelo admin token (o isolamento de
  rede/identidade está na borda do `nora-admin`). Mantenha o admin token forte e distinto do internal.
- Easy Auth do Container Apps **strippa** headers `X-MS-CLIENT-PRINCIPAL-*` do cliente antes de
  injetar os seus — por isso o `nora-admin` pode confiar neles e repassar `X-Operator-Email` ao Spring.
- Se `EASYAUTH_CLIENT_ID` estiver vazio no deploy, o `nora-admin` sobe **sem Easy Auth** (só IP
  allowlist) — só faça isso em janela controlada; rode o Passo 2 antes do go-live.
