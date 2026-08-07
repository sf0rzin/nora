---
title: "Cartografia de secrets e variáveis de ambiente"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# NORA — Cartografia de Secrets & Environment Variables

> **Documento operacional.** Última varredura: 2026-06-03 (auditoria sênior).
> Repo: `https://github.com/sf0rzin/nora.git` · Branch default: `main` · Environment GitHub: `dev` (sem protection rules, **vazio** — todos os secrets estão a nível de repositório).
>
> **Objetivo:** entender o que cada secret faz, como obter/regerar cada um, e onde ele mora (GitHub Secret vs GitHub Variable vs `.env.local`). Inclui checklist de recriação do zero.
>
> **Regra de ouro:** nunca commitar valores reais. Os `.env.example` carregam apenas **nomes** de variáveis. Em produção, os segredos de runtime vivem no **Azure Key Vault** (gerados/injetados pelo Bicep), e os Container Apps os consomem via `secretRef` + Managed Identity (UAI) — a chave nunca entra no bundle do browser.

---

## 1. Visão geral — as três camadas de configuração

NORA tem **três planos** distintos onde variáveis vivem. Não confundir:

| Camada | Onde mora | Quem consome | Exemplos |
|---|---|---|---|
| **GitHub Secrets** (repo) | Settings → Secrets and variables → Actions → Secrets | Os workflows do GitHub Actions (`deploy-infra.yml` etc.) — passam para o Bicep via `readEnvironmentVariable(...)` | `JWT_SECRET`, `OPENAI_API_KEY`, `PG_ADMIN_PASSWORD`, `CLOUDFLARE_TUNNEL_TOKEN` |
| **GitHub Variables** (repo) | mesma tela → aba Variables | Workflows, valores **não-secretos** | `NORA_EMAIL_FROM`, **`CF_ACCESS_AUD`** (hoje cadastrado errado — ver §5) |
| **Azure Key Vault** | `nora-kv-dev-XXXXXX` (criado pelo Bicep) | Os Container Apps em runtime, via `secretRef` + UAI | `azure-speech-key` (gerado pelo Bicep, **não** vem do GitHub), `postgres-password`, `jwt-secret`, `openai-api-key` (espelha o GitHub Secret) |
| **`.env.local`** (dev) | máquina do dev (gitignored) | `docker compose`, Spring (spring-dotenv), Next dev, worker dev | `POSTGRES_PASSWORD=nora_dev`, `JWT_SECRET=change-me...`, `LLM_API_KEY` opcional |

**Fluxo de produção (resumido):** GitHub Secret → (workflow `deploy-infra.yml` exporta como env var) → Bicep `readEnvironmentVariable` → grava no Key Vault → Container App lê via `secretRef`/UAI. Exceção: `AZURE_SPEECH_KEY` é **gerado pelo próprio Bicep** (módulo `speech.bicep` → `key1`) e não tem origem no GitHub.

---

## 2. Inventário por serviço

### 2.1 Backend — `services/api` (Spring Boot)

Lê env vars via `application.yml` (placeholders `${VAR:default}`) e `@Value`. Arquivos: `services/api/.env.example`, `services/api/src/main/resources/application.yml`.

| Variável | Função | Onde no prod | Obrigatório |
|---|---|---|---|
| `DATASOURCE_URL` / `DATASOURCE_USERNAME` / `DATASOURCE_PASSWORD` | Conexão Postgres principal. Em prod a URL/usuário saem do Bicep; a senha = secret `postgres-password` no KV (origem GitHub `PG_ADMIN_PASSWORD`). | Bicep + KV | Sim |
| `JWT_SECRET` | Segredo HMAC (HS256) que assina os JWT de auth. **min 32 chars.** Em prod = secret `jwt-secret` no KV (origem GitHub `JWT_SECRET`). | GitHub Secret → KV | Sim |
| `JWT_ALGORITHM` (default `HS256`) / `JWT_RS256_PRIVATE_KEY_PEM` / `JWT_RS256_KID` | Suporte opcional a RS256 (assimétrico c/ JWKS). **Hoje não usado** (default HS256). PEM só obrigatório se virar RS256. | não setado | Não |
| `RESEND_API_KEY` | Envio de e-mail (verificação de conta, reset de senha). Vazio → `LogEmailSender` (links no log). | GitHub Secret → KV | Não (degrada) |
| `NORA_EMAIL_FROM` | Remetente dos e-mails. | GitHub **Variable** | Não |
| `AZURE_SPEECH_KEY` / `AZURE_SPEECH_REGION` | Broker de token efêmero para o desktop (ADR 0009). A chave **é gerada pelo Bicep** (não vem do GitHub) e vai para o KV como `azure-speech-key`. | KV (origem Bicep) | Não (só desktop STT) |
| `NLP_WORKER_BASE_URL` | URL interna do worker NLP. Em prod = FQDN interno do Container App. | Bicep | Sim |
| `CORS_ALLOWED_ORIGINS`, `NORA_APP_PUBLIC_BASE_URL`, `NORA_FRONTEND_BASE_URL`, `AUTH_COOKIE_SECURE`, `AUTH_COOKIE_DOMAIN`, `EXPOSE_DEV_TOKENS` | Hardening de auth/CORS/cookies. Setados pelo Bicep em prod. | Bicep (valores literais) | — |
| `NORA_RLS_ENFORCE` (default false) | Liga Row Level Security do Postgres (ADR 0002/0019). | não setado | Não |
| **Control plane (só quando `NORA_PLATFORM_ENABLED=true`):** | | | |
| `NORA_PLATFORM_INTERNAL_TOKEN` | Token serviço-a-serviço (worker/BFF → `/internal/platform/**`). KV `internal-service-token`. | GitHub Secret → KV | Sim (platform on) |
| `NORA_PLATFORM_ADMIN_TOKEN` | Token do console (nora-admin → `/admin/platform/**`). KV `admin-bridge-token`. Least-privilege, distinto do internal. | GitHub Secret → KV | Sim (platform on) |
| `PLATFORM_DATASOURCE_*` | 2º Postgres (plataforma, blast radius isolado — ADR 0022). Senha = KV `postgres-platform-password` (origem GitHub `PG_PLATFORM_ADMIN_PASSWORD`). | Bicep + KV | Sim (platform on) |
| `NORA_PLATFORM_HEALTH_APP_ID` / `NORA_PLATFORM_HEALTH_API_KEY` | Telemetria de saúde via App Insights REST. **Não provisionado em lugar nenhum** → painel de saúde fica "unavailable". | **faltando** | Não (degrada) |
| `NORA_PLATFORM_FALLBACK_*`, `NORA_PLATFORM_BUSINESS_ENABLED` | Fallback do resolver de modelo + toggle de telemetria de negócio. | defaults | Não |

### 2.2 NLP Worker — `services/nlp-worker` (FastAPI)

Lê via Pydantic `Settings` (`services/nlp-worker/src/nora_nlp/settings.py`). Arquivo: `services/nlp-worker/.env.example`.

| Variável | Função | Onde no prod | Obrigatório |
|---|---|---|---|
| `LLM_PROVIDER` (default `openai`) | Provider agnóstico (ADR 0004). | Bicep (literal `openai`) | Não |
| `LLM_BASE_URL` (default `https://api.openai.com/v1`) | Endpoint compatível com OpenAI Chat Completions. | Bicep | Não |
| `LLM_API_KEY` | Chave do provider LLM. Em prod = secretRef `openai-api-key` no KV (origem GitHub `OPENAI_API_KEY`). | GitHub Secret → KV | Não (stub se vazio) |
| `LLM_MODEL` (default `gpt-4o-mini`), `LLM_TEMPERATURE` (0.2) | Modelo/temperatura. | Bicep | Não |
| `USE_LLM_STUB` (default true) | Quando true/vazio, usa stub local sem custo de API. Em prod o Bicep seta `false` se `OPENAI_API_KEY` existir. | Bicep | — |
| `WORKER_PORT` (8001), `LOG_LEVEL` | Operacional. | Bicep | — |
| `AZURE_SEARCH_ENDPOINT` / `AZURE_SEARCH_INDEX` | RAG (Azure AI Search) — só quando `enableSearch=true`. | Bicep | Não |

### 2.3 Web — `apps/web` (Next.js + BFF)

Arquivo: `apps/web/.env.example`. `NEXT_PUBLIC_*` são **baked in build-time** (entram no bundle do browser — **nunca** colocar segredo aí).

| Variável | Função | Onde no prod | Obrigatório |
|---|---|---|---|
| `NEXT_PUBLIC_API_BASE_URL` | URL pública da API (build-arg no Docker). | `build-images.yml` build-arg / Bicep | Sim |
| `NEXT_PUBLIC_USE_MOCKS` | Liga fixtures sem backend. Prod = `false`. | Dockerfile/Bicep | — |
| `LLM_API_KEY`, `LLM_BASE_URL`, `LLM_MODEL`, `LLM_PROVIDER` | Chat IA server-side (BFF `/api/chat`). A chave fica **server-side** (secretRef KV `openai-api-key`), nunca no browser. | KV via Bicep | Não (503 se vazio) |
| `LLM_KEY_OPENAI` / `LLM_KEY_DEEPSEEK` / `LLM_KEY_GOOGLE` | Chat multi-provider (ADR 0024). secretRefs `openai-api-key` / `deepseek-api-key` / `gemini-api-key` (origem GitHub `OPENAI_API_KEY`/`DEEPSEEK_API_KEY`/`GEMINI_API_KEY`). | GitHub Secret → KV | Não |
| `NORA_PLATFORM_INTERNAL_TOKEN` | BFF chama `/internal/platform/{llm-config,usage}`. secretRef `internal-service-token`. | GitHub Secret → KV | Não |

### 2.4 Admin — `apps/admin` (console do operador, control plane)

Arquivos: `apps/admin/src/lib/{access.ts,data.ts}`.

| Variável | Função | Onde no prod | Obrigatório |
|---|---|---|---|
| `PLATFORM_API_BASE_URL` | Base da API Spring para o console (server-side). | Bicep | Sim (platform on) |
| `PLATFORM_INTERNAL_TOKEN` | Token p/ chamar `/admin/platform/**`. secretRef `admin-bridge-token` (origem GitHub `NORA_PLATFORM_ADMIN_TOKEN`). | GitHub Secret → KV | Sim (platform on) |
| `CF_ACCESS_TEAM_DOMAIN` | Team domain do Cloudflare Access (`stratfy.cloudflareaccess.com`). Valida JWT `Cf-Access-Jwt-Assertion` contra JWKS (Tier 2). | Bicep (literal) | Não (degrada) |
| `CF_ACCESS_AUD` | AUD da Access App (`admin.nora.systems`). Valida o audience do JWT do Access. **Hoje chega vazio por bug — ver §5.** | GitHub **Variable** (deveria) | Não (degrada) |
| `NORA_ADMIN_USE_MOCKS` | Liga/desliga data layer real. Prod = `false`. | Bicep | — |

### 2.5 Infra local — raiz + `infra/docker`

Arquivos: `.env.example` (raiz), `infra/docker/docker-compose.yml`.

| Variável | Função | Onde no prod | Obrigatório |
|---|---|---|---|
| `POSTGRES_DB` / `POSTGRES_USER` / `POSTGRES_PASSWORD` | Postgres do docker-compose **local** (dev). Default `nora`/`nora`/`nora_dev`. | só `.env.local` | dev |
| `POSTGRES_BIND` / `ADMINER_BIND` | Bind de rede (default `127.0.0.1`). | só `.env.local` | Não |
| `REGISTRY_PASSWORD` | Pull de imagem privada GHCR (opcional; vazio = imagens públicas). | GitHub Secret (faltando hoje) | Não |

### 2.6 Desktop — `apps/desktop` (FORA DE ESCOPO)

Apenas para referência (não auditado). Não compartilha secrets de servidor. Variáveis locais: `NORA_API_BASE_URL`, `NORA_SIDECAR_PATH`, `GDK_BACKEND`/`WEBKIT_DISABLE_DMABUF_RENDERER` (workaround Wayland Linux). Em prod o bundle Tauri usa `vars.NORA_API_BASE_URL` (GitHub Variable) baked em build-time pelo `ci.yml`. **Esta Variable também não existe hoje** (cai no fallback hardcoded do Azure dev) — não crítico.

---

## 3. Estado atual no GitHub (cruzamento)

### 3.1 GitHub Secrets (repo) — 15 cadastrados

| Secret | Serviço | Status | Consumido por |
|---|---|---|---|
| `AZURE_CLIENT_ID` | infra | present | `deploy-infra.yml` (azure/login OIDC) |
| `AZURE_TENANT_ID` | infra | present | `deploy-infra.yml` |
| `AZURE_SUBSCRIPTION_ID` | infra | present | `deploy-infra.yml` |
| `PG_ADMIN_PASSWORD` | api/infra | present | `deploy-infra.yml` → Bicep → KV `postgres-password` |
| `JWT_SECRET` | api | present | `deploy-infra.yml` → KV `jwt-secret` |
| `OPENAI_API_KEY` | worker/web | present | `deploy-infra.yml` → KV `openai-api-key` |
| `DEEPSEEK_API_KEY` | web | present | `deploy-infra.yml` → KV `deepseek-api-key` |
| `GEMINI_API_KEY` | web | present | `deploy-infra.yml` → KV `gemini-api-key` |
| `RESEND_API_KEY` | api | present | `deploy-infra.yml` → KV `resend-api-key` |
| `PG_PLATFORM_ADMIN_PASSWORD` | api (platform) | present | `deploy-infra.yml` → KV `postgres-platform-password` |
| `NORA_PLATFORM_INTERNAL_TOKEN` | api/web/worker | present | `deploy-infra.yml` → KV `internal-service-token` |
| `NORA_PLATFORM_ADMIN_TOKEN` | api/admin | present | `deploy-infra.yml` → KV `admin-bridge-token` |
| `CLOUDFLARE_API_TOKEN` | infra | present | `cloudflare-setup.yml`, `cloudflare-tunnel.yml` |
| `CLOUDFLARE_TUNNEL_TOKEN` | admin (infra) | present | `deploy-infra.yml` → KV `cloudflare-tunnel-token` |
| **`CF_ACCESS_AUD`** | admin | **inconsistente** | é Secret, mas workflow lê como `vars.` → **chega vazio** (ver §5) |

### 3.2 GitHub Variables (repo) — 1 cadastrada

| Variable | Valor | Status |
|---|---|---|
| `NORA_EMAIL_FROM` | `NORA <noreply@nora.systems>` | present |

### 3.3 Referenciados no workflow mas SEM Secret (órfãos)

| Nome | Situação |
|---|---|
| `EASYAUTH_CLIENT_ID` | **orphan** — referenciado em `deploy-infra.yml` (linhas 120, 172) mas não existe. Inerte por ADR 0025 (Entra trocado por Cloudflare). Resolve vazio sem quebrar. Recomendo **remover do workflow**. |
| `EASYAUTH_CLIENT_SECRET` | **orphan** — idem (linhas 121, 173). |

### 3.4 Só local / gerados pelo Azure (nunca vão para o GitHub)

| Nome | Origem |
|---|---|
| `AZURE_SPEECH_KEY` | **gerado pelo Bicep** (`speech.bicep` → `key1`), vai para o KV `azure-speech-key`. Não precisa cadastrar no GitHub. |
| `POSTGRES_PASSWORD` (dev) | só `.env.local` (docker-compose). |
| `REGISTRY_PASSWORD` | opcional; só se imagens GHCR forem privadas. |
| `NORA_PLATFORM_HEALTH_APP_ID` / `_API_KEY` | telemetria de saúde — **não provisionado** (gap, painel fica unavailable). |
| `JWT_RS256_PRIVATE_KEY_PEM` / `JWT_RS256_KID` | só se trocar HS256→RS256 (não é o caso hoje). |

---

## 4. Como obter / regerar cada secret (passo a passo)

### Provedores de LLM

**`OPENAI_API_KEY`** — chat principal + worker NLP (default `gpt-4o-mini`).
1. Conta: https://platform.openai.com/signup
2. Billing (precisa cartão): https://platform.openai.com/account/billing
3. Gerar key: https://platform.openai.com/api-keys → "Create new secret key" → copiar (formato `sk-...`, só aparece uma vez).

**`DEEPSEEK_API_KEY`** — chat multi-provider (ADR 0024, troca de modelo ao vivo).
1. Conta: https://platform.deepseek.com/
2. API keys: https://platform.deepseek.com/api_keys → criar key.

**`GEMINI_API_KEY`** — chat/multimodal (Google AI).
1. Google AI Studio: https://aistudio.google.com/app/apikey → "Create API key" (associada a um projeto Google Cloud).

> Todas as três são **opcionais**: vazias → o provider correspondente fica indisponível, mas o sistema não quebra (OpenAI vazio = worker em stub e chat 503).

### E-mail

**`RESEND_API_KEY`** — envio de e-mail transacional (verificação de conta, reset de senha).
1. Conta grátis (3.000 e-mails/mês): https://resend.com/signup
2. API key: https://resend.com/api-keys → "Create API Key" (formato `re_...`).
3. **Importante:** para usar `NORA_EMAIL_FROM = NORA <noreply@nora.systems>`, o domínio `nora.systems` precisa estar **verificado** em https://resend.com/domains (adicionar registros DNS — SPF/DKIM). Enquanto não verificar, use `onboarding@resend.dev` (sandbox).
4. Vazio → backend cai em `LogEmailSender` (links aparecem no log do app, sem e-mail real).

### Azure (OIDC federado — sem client secret)

**`AZURE_CLIENT_ID`**, **`AZURE_TENANT_ID`**, **`AZURE_SUBSCRIPTION_ID`** — autenticação do GitHub Actions no Azure via OIDC.
1. App Registration `sp-nora-github-deploy` no Entra (tenant `fiap.com.br`):
   `az ad app create --display-name sp-nora-github-deploy` → anota `appId` (= `AZURE_CLIENT_ID`).
2. `AZURE_TENANT_ID`: `az account show --query tenantId -o tsv`.
3. `AZURE_SUBSCRIPTION_ID`: `az account show --query id -o tsv`.
4. Service Principal + role Contributor **escopado no RG** (não na subscription):
   `az role assignment create --assignee <appId> --role Contributor --scope /subscriptions/<sub>/resourceGroups/rg-nora-dev`
5. **Federated credentials** (3 subjects — sem senha):
   - `repo:sf0rzin/nora:ref:refs/heads/main`
   - `repo:sf0rzin/nora:pull_request`
   - `repo:sf0rzin/nora:environment:dev` (obrigatório porque o job `deploy` usa `environment: dev`)
   Via portal: App Registration → Certificates & secrets → Federated credentials → Add. Issuer `https://token.actions.githubusercontent.com`, audience `api://AzureADTokenExchange`.
   Docs: https://learn.microsoft.com/azure/developer/github/connect-from-azure-openid-connect

### Postgres (senhas random)

**`PG_ADMIN_PASSWORD`** (Postgres principal) e **`PG_PLATFORM_ADMIN_PASSWORD`** (2º Postgres do control plane).
- Bicep exige `@minLength(12)`. Gere senhas fortes distintas:
  - PowerShell: `[Convert]::ToBase64String((1..32 | %{ Get-Random -Max 256 }))`
  - bash: `openssl rand -base64 32`
- Cole cada uma no Secret correspondente. **Não reutilize** entre os dois Postgres (blast radius isolado, ADR 0022).

### Auth / tokens internos

**`JWT_SECRET`** — segredo HMAC dos JWT (HS256, min 32 chars).
- `openssl rand -base64 48`  (ou `https://generate-secret.vercel.app/32`)
- O `application-prod.yml` rejeita o placeholder público fora de local/test (fail-closed).

**`NORA_PLATFORM_INTERNAL_TOKEN`** (serviço→serviço) e **`NORA_PLATFORM_ADMIN_TOKEN`** (console→API) — tokens opacos do control plane.
- Gere dois valores **distintos** (least-privilege):
  `openssl rand -hex 32`  (rode duas vezes)
- Se ficarem vazios com `enablePlatform=true`, viram `'unset'` no KV e destravam admin/internal — **sempre setar** antes do go-live.

### Cloudflare (control plane v2 — ADR 0025)

**`CLOUDFLARE_API_TOKEN`** — usado pelos workflows `cloudflare-setup.yml` e `cloudflare-tunnel.yml`.
1. https://dash.cloudflare.com/profile/api-tokens → "Create Token" → Custom token.
2. Permissões mínimas (caminho crítico):
   - Account · Access (Apps and Policies) · **Edit**
   - Account · Access (Service Tokens) · **Edit**
   - Account · Cloudflare Tunnel · **Edit**
   - Zone · DNS · **Edit**
   - Zone · Zone · **Read**
3. Opcional (full automation, cria OTP IdP via API): Account · Access (Organizations, Identity Providers, and Groups) · **Edit**.
4. Escopar na zona `nora.systems`.

**`CLOUDFLARE_TUNNEL_TOKEN`** — connector token do túnel do nora-admin.
1. Rodar o workflow `cloudflare-tunnel.yml` (workflow_dispatch). Ele cria/reusa o túnel e **imprime o token no log** do step "Connector token + AUD".
2. Copiar o valor e cadastrar como **Secret** `CLOUDFLARE_TUNNEL_TOKEN`.
3. Rotação: recriar o túnel pelo mesmo workflow gera novo token.

**`CF_ACCESS_AUD`** — AUD tag da Access App `admin.nora.systems` (identificador **público**, não-secreto).
1. Sai no **mesmo log** do step "Connector token + AUD" do `cloudflare-tunnel.yml` (precisa ter rodado `cloudflare-setup.yml` antes, que cria a Access App).
2. Cadastrar como **Variable** `CF_ACCESS_AUD` (ver §5 — hoje está como Secret, errado).

### Registry (opcional)

**`REGISTRY_PASSWORD`** — só se as imagens GHCR forem privadas. PAT do GitHub com escopo `read:packages` (https://github.com/settings/tokens). Hoje as imagens são públicas → não necessário.

---

## 5. Inconsistências encontradas (corrigir antes/depois de recriar)

### 5.1 CRÍTICO — `CF_ACCESS_AUD` cadastrado como Secret mas lido como Variable
- **Sintoma:** `deploy-infra.yml` linhas 124 e 176 usam `CF_ACCESS_AUD: ${{ vars.CF_ACCESS_AUD }}`. Não existe Variable com esse nome (só `NORA_EMAIL_FROM`). O valor chega **vazio** no Bicep (`cfAccessAud=''`), então o nora-admin **não valida o audience** do JWT do Cloudflare Access → Tier 2 degrada para edge-only, **silenciosamente**.
- **Evidência:** o próprio `cloudflare-tunnel.yml` (linhas 163, 185) instrui setar como **Variable**, e o comentário do `deploy-infra.yml` (linha 25) diz "não-secreto". Logo o Secret foi cadastrado no lugar errado.
- **Correção (escolher uma):**
  - **(A) recomendada:** deletar o Secret `CF_ACCESS_AUD` e criar uma **Variable** `CF_ACCESS_AUD` com o mesmo valor (AUD é público). Nenhuma mudança de código.
  - (B) trocar o workflow para `${{ secrets.CF_ACCESS_AUD }}` (mantém como Secret) — funciona, mas contraria o design (AUD não é segredo) e o runbook.

### 5.2 MÉDIO — `EASYAUTH_CLIENT_ID` / `EASYAUTH_CLIENT_SECRET` órfãos no workflow
- Referenciados em `deploy-infra.yml` mas não existem como Secrets. Inertes por ADR 0025 (Entra abandonado a favor de Cloudflare). Resolvem vazio sem quebrar. **Recomendo remover as 4 linhas** do workflow para reduzir confusão.

### 5.3 BAIXO — Telemetria de saúde do control plane não provisionada
- `NORA_PLATFORM_HEALTH_APP_ID` / `NORA_PLATFORM_HEALTH_API_KEY` não estão em Secret nem no Bicep. O painel de saúde do operador (App Insights REST) fica "unavailable". Se quiser ligar: criar uma API key do App Insights (`nora-ai-dev`) e o App ID, e provisioná-los como env do Container App da API (hoje exige editar o Bicep — não há param).

### 5.4 BAIXO — `NORA_EMAIL_FROM` aponta para domínio não-sandbox
- Variable = `NORA <noreply@nora.systems>`. Só funciona se `nora.systems` estiver **verificado no Resend**. Caso contrário, e-mails falham silenciosamente. Verificar em https://resend.com/domains ou voltar para o sandbox `onboarding@resend.dev`.

### 5.5 INFORMATIVO — Dependabot/supply-chain
- O run "npm_and_yarn in /apps/web" do Dependabot está **falhando** e os **Dependabot alerts estão desabilitados** no repo. Não é secret, mas é dívida de segurança relevante para um SaaS de produção. Habilitar em Settings → Code security.

---

## 6. Tabela de decisão: Secret vs Variable vs `.env.local`

| Item | GitHub Secret | GitHub Variable | `.env.local` (dev) |
|---|:---:|:---:|:---:|
| `OPENAI_API_KEY`, `DEEPSEEK_API_KEY`, `GEMINI_API_KEY` | Sim | — | opcional |
| `RESEND_API_KEY` | Sim | — | opcional |
| `JWT_SECRET` | Sim | — | Sim (valor dev fraco) |
| `PG_ADMIN_PASSWORD`, `PG_PLATFORM_ADMIN_PASSWORD` | Sim | — | Sim (`nora_dev`) |
| `AZURE_CLIENT_ID`/`TENANT_ID`/`SUBSCRIPTION_ID` | Sim | — | — |
| `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_TUNNEL_TOKEN` | Sim | — | — |
| `NORA_PLATFORM_INTERNAL_TOKEN`, `NORA_PLATFORM_ADMIN_TOKEN` | Sim | — | — |
| `REGISTRY_PASSWORD` (se privado) | Sim | — | — |
| **`CF_ACCESS_AUD`** (público) | Não (hoje errado) | Sim **corrigir** | — |
| `NORA_EMAIL_FROM` | — | Sim | opcional |
| `NEXT_PUBLIC_API_BASE_URL`, `NORA_API_BASE_URL` (build) | — | Sim (opcional) | Sim |
| `AZURE_SPEECH_KEY` | Não (gerado pelo Bicep) | Não | opcional |
| `POSTGRES_PASSWORD`, `POSTGRES_DB/USER` (dev) | — | — | Sim |

---

## 7. Checklist — recriar TUDO do zero

### Fase 1 — Provedores externos (gerar valores)
- [ ] OpenAI: criar conta + billing + `OPENAI_API_KEY`
- [ ] DeepSeek: `DEEPSEEK_API_KEY` (opcional)
- [ ] Gemini (Google AI Studio): `GEMINI_API_KEY` (opcional)
- [ ] Resend: `RESEND_API_KEY` + verificar domínio `nora.systems` (ou usar sandbox)
- [ ] Cloudflare: `CLOUDFLARE_API_TOKEN` com as 5 permissões (§4)

### Fase 2 — Azure (OIDC)
- [ ] App Registration `sp-nora-github-deploy` → `AZURE_CLIENT_ID`
- [ ] `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`
- [ ] Role Contributor escopado em `rg-nora-dev`
- [ ] 3 federated credentials (main / pull_request / environment:dev)

### Fase 3 — Segredos gerados (random)
- [ ] `PG_ADMIN_PASSWORD` = `openssl rand -base64 32`
- [ ] `PG_PLATFORM_ADMIN_PASSWORD` = outro `openssl rand -base64 32`
- [ ] `JWT_SECRET` = `openssl rand -base64 48`
- [ ] `NORA_PLATFORM_INTERNAL_TOKEN` = `openssl rand -hex 32`
- [ ] `NORA_PLATFORM_ADMIN_TOKEN` = outro `openssl rand -hex 32`

### Fase 4 — Cadastrar no GitHub (Settings → Secrets and variables → Actions)
**Secrets (14):** `AZURE_CLIENT_ID`, `AZURE_TENANT_ID`, `AZURE_SUBSCRIPTION_ID`, `PG_ADMIN_PASSWORD`, `PG_PLATFORM_ADMIN_PASSWORD`, `JWT_SECRET`, `OPENAI_API_KEY`, `DEEPSEEK_API_KEY`, `GEMINI_API_KEY`, `RESEND_API_KEY`, `NORA_PLATFORM_INTERNAL_TOKEN`, `NORA_PLATFORM_ADMIN_TOKEN`, `CLOUDFLARE_API_TOKEN`, `CLOUDFLARE_TUNNEL_TOKEN`.
**Variables (2):** `NORA_EMAIL_FROM`, **`CF_ACCESS_AUD`** (corrigindo o bug — NÃO cadastrar como Secret).
- [ ] Via CLI (exemplos):
  - `gh secret set JWT_SECRET` (cola o valor quando pedir)
  - `gh variable set CF_ACCESS_AUD --body "<aud-da-access-app>"`
  - `gh variable set NORA_EMAIL_FROM --body "NORA <noreply@nora.systems>"`

### Fase 5 — Cloudflare (ordem importa)
- [ ] Rodar `cloudflare-setup.yml` (cria Access App + Policy + IdP) → pega o **AUD**
- [ ] Setar **Variable** `CF_ACCESS_AUD` com o AUD
- [ ] Rodar `cloudflare-tunnel.yml` (cria túnel) → pega o **connector token**
- [ ] Setar **Secret** `CLOUDFLARE_TUNNEL_TOKEN`

### Fase 6 — Deploy
- [ ] Push em `main` (ou rodar `deploy-infra.yml` manual) → Bicep grava os secrets no Key Vault e sobe os Container Apps
- [ ] Conferir health: `GET <apiUrl>/actuator/health` (o workflow já faz isso)
- [ ] `AZURE_SPEECH_KEY` — **não** cadastrar; o Bicep gera e injeta no KV automaticamente

### Fase 7 — Higiene (correções §5)
- [ ] Garantir que `CF_ACCESS_AUD` é **Variable**, não Secret
- [ ] (opcional) remover `EASYAUTH_CLIENT_ID/SECRET` do `deploy-infra.yml`
- [ ] (opcional) habilitar Dependabot alerts + resolver o run falhando em `/apps/web`

### `.env.local` mínimo para rodar DEV (não vai para o GitHub)
```dotenv
# raiz/.env.local (docker-compose)
POSTGRES_DB=nora
POSTGRES_USER=nora
POSTGRES_PASSWORD=nora_dev

# services/api/.env.local
DATASOURCE_URL=jdbc:postgresql://localhost:5432/nora
DATASOURCE_USERNAME=nora
DATASOURCE_PASSWORD=nora_dev
JWT_SECRET=change-me-please-min-32-chars-long-aaaa
RESEND_API_KEY=            # vazio = LogEmailSender

# services/nlp-worker/.env.local
USE_LLM_STUB=true          # true = sem custo de API
LLM_API_KEY=               # preencher só se quiser LLM real

# apps/web/.env.local
NEXT_PUBLIC_API_BASE_URL=http://localhost:8080
NEXT_PUBLIC_USE_MOCKS=true
```

---

*Fontes (evidência): `infra/bicep/main.bicep`, `infra/bicep/main.dev.bicepparam`, `.github/workflows/{deploy-infra,cloudflare-setup,cloudflare-tunnel,build-images,ci}.yml`, `services/api/src/main/resources/application.yml`, `services/nlp-worker/src/nora_nlp/settings.py`, `apps/web/src/app/api/chat/route.ts`, `apps/admin/src/lib/{access,data}.ts`, e todos os `.env.example`. Estado dos secrets/variables coletado via `gh secret list` / `gh variable list` em 2026-06-03.*