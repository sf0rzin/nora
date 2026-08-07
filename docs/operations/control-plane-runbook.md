---
title: "Runbook — Ligar o Control Plane (admin de operador + telemetria)"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# Runbook — Ligar o Control Plane (admin de operador + telemetria)

> Como promover o control plane de **OFF** (default) para **ON** no Azure. A identidade do
> operador é **Cloudflare Tunnel + Access** (ADR 0025, que substituiu o Easy Auth do ADR 0023
> após o tenant FIAP bloquear App Registration). ADRs 0022/0023/0024/0025. Contrato:
> `docs/engineering/contracts/platform-control-plane.md`. Borda Cloudflare: `cloudflare-access.md`.

## O que o IaC já faz vs. o que é manual

| IaC (Bicep, `enablePlatform=true`) | Manual (este runbook) |
|---|---|
| 2º Postgres `nora-pg-platform-dev`, db `nora_platform` | Provisionar o Cloudflare Tunnel (workflow `cloudflare-tunnel.yml`) |
| UAI `nora-uai-admin-dev` + acesso ao KV | Setar os GitHub Secrets (3 tokens de plataforma + `CLOUDFLARE_TUNNEL_TOKEN`) |
| Container App `nora-admin` (ingress **internal** + sidecar `cloudflared`) | Setar a Variable `CF_ACCESS_AUD` |
| Secrets no KV (tokens, senha pg-platform, tunnel token) | Garantir o Access App/allowlist (lane Cloudflare, `cloudflare-setup.yml`, **sem** `admin_hostname`) |
| Env do api/worker/web para o control plane | Publicar a imagem `nora-admin` (CI `build-images.yml`) |

## Pré-requisitos

- Imagem `ghcr.io/sf0rzin/nora-admin:latest` publicada e **Public** no GHCR (o Container App não
  tem creds de registry). A CI publica em push para `main` que toca `apps/admin/**`.
- `CLOUDFLARE_API_TOKEN` (conta `nora.systems`) com **Cloudflare Tunnel: Edit** + **DNS: Edit** +
  **Zone: Read**. É o mesmo token do `cloudflare-setup.yml`, com Tunnel:Edit adicionado.
- Cloudflare Access já configurado para `admin.nora.systems` (lane irmã, PRs #177/#178).

## Passo 1 — Gerar secrets de plataforma

```powershell
# Rode local; NÃO cole os valores em chat/PR. São 3 secrets:
[Convert]::ToBase64String((1..24 | ForEach-Object { Get-Random -Max 256 }))  # PG_PLATFORM_ADMIN_PASSWORD
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))  # NORA_PLATFORM_INTERNAL_TOKEN
[Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Max 256 }))  # NORA_PLATFORM_ADMIN_TOKEN
```

## Passo 2 — Provisionar o Cloudflare Tunnel

Roda o workflow **`cloudflare-tunnel.yml`** (Actions → Run workflow). Ele é idempotente e:

1. Cria/reusa o túnel `nora-admin` (remotely-managed).
2. Configura a rota `admin.nora.systems → http://localhost:3002` (o sidecar `cloudflared` serve o
   Next pelo localhost do pod).
3. Faz upsert do DNS `admin.nora.systems` → `<tunnel-id>.cfargotunnel.com` (proxied).
4. Imprime, no log do step **`Connector token + AUD`**: o **connector token** (para o Secret
   `CLOUDFLARE_TUNNEL_TOKEN`) e o **AUD** da Access App (para a Variable `CF_ACCESS_AUD`).

> **Por que Tunnel + Access (ADR 0025):** o `nora-admin` sobe com `ingress: internal` — **sem FQDN
> público**. A única porta de entrada é o túnel, atrás do Cloudflare Access (allowlist + OTP/SSO).
> Não há origem do Azure para contornar. Defesa em profundidade: rede (Access) + transporte (Tunnel)
> + app (validação do `Cf-Access-Jwt-Assertion` no Next, Tier 2) + serviço (admin token).

## Passo 3 — GitHub Secrets + Variable (repo `sf0rzin/nora`)

```
# Secrets:
PG_PLATFORM_ADMIN_PASSWORD   = <Passo 1>
NORA_PLATFORM_INTERNAL_TOKEN = <Passo 1>
NORA_PLATFORM_ADMIN_TOKEN    = <Passo 1>
CLOUDFLARE_TUNNEL_TOKEN      = <connector token do Passo 2>
# Variable (Variables tab — NÃO é secret):
CF_ACCESS_AUD                = <AUD do Passo 2>
```

> Sem os 3 tokens de plataforma, eles viram `'unset'` no KV (admin/internal destravados). Sem
> `CLOUDFLARE_TUNNEL_TOKEN`, o sidecar `cloudflared` não sobe e o admin fica internal/inacessível
> (seguro, mas offline). Sem `CF_ACCESS_AUD`, o Tier 2 degrada para edge-only (Tunnel + Access ainda
> protegem). `EASYAUTH_*` ficou inerte (ADR 0025) — pode ficar vazio.

## Passo 4 — Deploy

`enablePlatform = true` já está no `main.dev.bicepparam`. Merge do PR em `main` → `deploy-infra.yml`
provisiona tudo (idempotente). O `nora-admin` sobe internal; assim que `CLOUDFLARE_TUNNEL_TOKEN`
estiver setado, o `cloudflared` conecta e o túnel passa a servir.

## Passo 5 — Chaves de provider (para o switch de modelo em runtime)

Pra trocar o modelo de um serviço **ao vivo** sem deploy, a chave do provider precisa já estar
provisionada. OpenAI já está (`openai-api-key`). Para DeepSeek e Gemini, adicione `DEEPSEEK_API_KEY`
e `GEMINI_API_KEY` nos GitHub Secrets (ADR 0024 / decisão #C). Opcional — perguntar à PO se vale
agora.

## Passo 6 — Coordenação com a lane Cloudflare

Garantir o Access App + allowlist + OTP via `cloudflare-setup.yml` — **rodando com `admin_hostname`
VAZIO**. O DNS de `admin.nora.systems` agora é do túnel (Passo 2); passar `admin_hostname` faria o
`cloudflare-setup.yml` sobrescrever o CNAME do túnel com o FQDN direto. Atenção: **nunca passe
`admin_hostname` depois de ligar o túnel.**

## Verificação pós-deploy

```bash
# 1. API subiu com o módulo platform (procurar no log): "módulo HEALTHY"
# 2. llm-config (precisa do internal token):
curl -H "X-Internal-Token: $internal" \
  "https://nora-api-dev.<domain>/internal/platform/llm-config?service=chat"

# 3. Conector cloudflared conectado (réplica do nora-admin de pé):
az containerapp replica list -n nora-admin-dev -g rg-nora-dev -o table

# 4. Console: abrir https://admin.nora.systems -> redireciona para o Cloudflare Access (OTP/SSO,
#    só allowlist). Após login, o Next valida o Cf-Access-Jwt-Assertion (Tier 2) e renderiza.
#    O FQDN interno (nora-admin-dev.internal.<domain>) NÃO é acessível de fora.
```

## Rollback

`param enablePlatform = false` + deploy. O 2º Postgres, o `nora-admin` e o `cloudflared` somem; a API
volta a `NORA_PLATFORM_ENABLED` ausente (módulo inerte). O caminho do cliente nunca dependeu disto
(fail-soft, ADR 0022). O túnel/DNS no Cloudflare continuam (inertes sem conector); para remover, apague
o túnel `nora-admin` no painel/API e o CNAME.

## Notas de segurança

- `/admin/platform/**` no `nora-api` público é protegido pelo admin token (o isolamento de
  rede/identidade está na borda do `nora-admin` via Cloudflare). Mantenha o admin token forte e
  distinto do internal.
- O `nora-admin` não tem FQDN público (ingress internal). A entrada é só pelo Cloudflare Tunnel,
  atrás do Access — não há origem do Azure exposta para contornar.
- O Tier 2 (validação do `Cf-Access-Jwt-Assertion` no Next) roda em server component porque o
  middleware edge inlinaria `CF_ACCESS_*` em build-time. `/healthz` é route handler (fora do gate),
  então o probe do Container App funciona sem JWT.
- O `cloudflared` precisa de ≥1 réplica sempre de pé (o admin não escala a zero) — custo de compute
  pequeno, aceito no ADR 0025.
