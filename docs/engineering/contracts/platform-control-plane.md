---
title: "Contrato — Control Plane de Operador + Telemetria de IA"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# Contrato — Control Plane de Operador + Telemetria de IA

> **Nota de supersessão (2026-06-06):** a identidade de borda do operador descrita neste contrato
> migrou de **Easy Auth (Entra)** para **Cloudflare Tunnel + Access** (ADR 0025). As menções a Easy
> Auth / Entra / `X-MS-CLIENT-PRINCIPAL-*` abaixo permanecem para registro histórico do contrato
> congelado; o mecanismo de autenticação do operador na borda é hoje o Cloudflare Access. O restante
> do contrato (paths, tokens, headers `X-Internal-Token` / `X-Operator-Email`) permanece válido.

> **Status:** CONGELADO (v1) — 2026-05-28. Ponto de encaixe do app `nora-admin` (Next) e dos
> hot-paths (worker / BFF de chat). Mudança de assinatura aqui exige acordo entre os dois arquitetos
> + dono. Decisões de origem: design note + ruling de 2026-05-28 (ADRs 0022/0023/0024).

## 1. Domínios de segurança

São **três** superfícies distintas na API Spring (`nora-api`), separadas por path e por chain de
segurança própria (a chain JWT por-tenant existente fica intacta):

| Path | Quem chama | Auth | Header de identidade |
|---|---|---|---|
| `/internal/platform/**` | worker NLP, BFF de chat (web) | `X-Internal-Token` = **service token** | — |
| `/admin/platform/**` | app `nora-admin` (Next), server-side | `X-Internal-Token` = **admin token** | `X-Operator-Email` (auditoria) |
| (resto) | clientes/tenants | JWT/cookie `nora_access` (inalterado) | — |

**Easy Auth (Entra) + `ipSecurityRestrictions` vivem na borda do `nora-admin`**, não no Spring. O
`nora-admin` autentica o operador (grupo Entra), lê `X-MS-CLIENT-PRINCIPAL-*` e chama o Spring
server-side com o **admin token** + `X-Operator-Email`. O Spring **nunca** lê header de Easy Auth.

**Tokens** (secrets KV, injetados como env no `nora-api`, worker e web conforme o caso):
- `NORA_PLATFORM_INTERNAL_TOKEN` → habilita `/internal/platform/**`.
- `NORA_PLATFORM_ADMIN_TOKEN` → habilita `/admin/platform/**`. Se não setado, cai no internal token
  (com WARN no log). **Recomendado manter distintos** (least-privilege: vazamento do token do worker
  não dá acesso a mutações de admin).

Respostas de auth: token ausente/inválido → **401**. (Não usamos 403 aqui — não há modelo de
permissão fina de operador no v1; é tudo-ou-nada por token.)

`X-Internal-Token` comparado com `MessageDigest.isEqual` (constant-time). Tokens nunca logados.

---

## 2. `/internal/platform/*` — service-to-service (hot-path)

### GET `/internal/platform/llm-config?service={chat|analysis|multimodal}`

Resolve o modelo ativo por serviço (binding `llm_config` → `llm_models`), com **cache server-side
~60s** e **fallback SOFT**.

**200** (sempre 200 no hot-path, mesmo em fallback):
```json
{ "provider": "deepseek", "model": "deepseek-v4-flash", "baseUrl": "https://api.deepseek.com/v1", "enabled": true }
```
- `enabled` = `binding.enabled` AND `model.enabled` AND `featureFlag("service."+service).enabled`.
- **Fallback SOFT** (retorna config do **env default** do serviço, `enabled` conforme feature flag),
  acionado quando: plataforma desabilitada (`nora.platform.enabled=false`), banco de plataforma
  fora, binding ausente, ou modelo desabilitado. **Nunca 5xx** — o consumidor não pode quebrar.
- `service` inválido → **400**.

> **Resolução de API key (não vem no contrato):** a chave do provider **não** trafega aqui (decisão
> #C). O consumidor mapeia `provider → secret` localmente (env/KV). Trocar para um provider sem chave
> provisionada exige deploy. Seeds com chave provisionada: OpenAI (já), **DeepSeek** e **Gemini**
> (pendentes — dono provisiona no KV).

### POST `/internal/platform/usage`

Ingestão de evento de custo. **Fire-and-forget**: nunca bloqueia o caller, nunca propaga erro.

**Request:**
```json
{
  "service": "chat",
  "provider": "openai",
  "model": "gpt-4o-mini",
  "tenantId": "5b1c…uuid ou null",
  "promptTokens": 1234,
  "completionTokens": 567,
  "costUsd": 0.0,
  "latencyMs": 850,
  "status": "ok"
}
```
- `service` obrigatório. `tenantId` opcional (dimensão, não FK). `promptTokens`/`completionTokens`
  ≥ 0. `status` ∈ `ok | error | stub | fallback` (string livre é normalizada; default `ok`).
- `costUsd` é **best-effort**: o servidor **recalcula** a partir do pricing do catálogo quando
  conhece `(provider, model)`; usa o `costUsd` do payload só como fallback.

**202 Accepted** (corpo vazio). Se a plataforma estiver off/fora, ainda **202** (evento descartado +
log) — o caller não percebe. Validação inválida → **400**.

> **Caminho da ANÁLISE não usa este endpoint:** a API emite o usage da análise **in-process**
> (`AnalysisService` → `UsageRecorder`), porque já tem `tenantId` + tokens + modelo do `metadata` do
> worker. Este POST é para o **BFF de chat** (e futuros callers externos como multimodal).

---

## 3. `/admin/platform/*` — console do operador (via nora-admin)

Todos exigem **admin token**; gravam auditoria com `X-Operator-Email`.

### Catálogo de modelos

`GET /admin/platform/models` → **200** lista:
```json
[{
  "id": "uuid", "provider": "openai", "model": "gpt-4o-mini", "displayName": "GPT-4o mini",
  "baseUrl": "https://api.openai.com/v1", "modality": "text", "supportsStrictJsonSchema": true,
  "priceInputPerMTok": 0.15, "priceOutputPerMTok": 0.60, "priceCachedInputPerMTok": null,
  "enabled": true, "createdAt": "2026-05-28T12:00:00Z", "updatedAt": "2026-05-28T12:00:00Z"
}]
```

`POST /admin/platform/models` → **201** (modelo criado). Body:
```json
{ "provider":"groq","model":"llama-3.3-70b","displayName":"Llama 3.3 70B","baseUrl":"https://api.groq.com/openai/v1",
  "modality":"text","supportsStrictJsonSchema":false,"priceInputPerMTok":0.59,"priceOutputPerMTok":0.79,
  "priceCachedInputPerMTok":null,"enabled":true }
```
- `modality` ∈ `text | multimodal`. Único `(provider, model)` → conflito **409**.

`DELETE /admin/platform/models/{id}` → **204**. Se o modelo estiver **bindado** em `llm_config` →
**409** (desbinde antes). Id inexistente → **404**.

### Seleção por serviço (binding)

`GET /admin/platform/config` → **200** todos os bindings:
```json
[{ "service":"chat","modelId":"uuid","provider":"deepseek","model":"deepseek-v4-flash","enabled":true,
   "updatedAt":"…","updatedBy":"op@nora" }]
```

`PUT /admin/platform/config/{service}` → **200** (binding atualizado). Body:
```json
{ "modelId": "uuid", "enabled": true }
```
- `service` ∈ `chat | analysis | multimodal`. `modelId` deve existir.
- **Validações de router por modalidade (ADR 0024) + strict (ADR 0003):**
  - `analysis` → modelo precisa `supportsStrictJsonSchema=true` senão **422**.
  - `multimodal` → modelo precisa `modality=multimodal` senão **422**.
  - `chat` → qualquer modalidade.
- Invalida o cache de resolução (≤60s de propagação garantida de qualquer forma).

### Feature flags

`GET /admin/platform/flags` → **200** lista:
```json
[{ "key":"service.chat","enabled":true,"description":"Chat IA do plano Core (BFF /api/chat)",
   "updatedBy":"seed","updatedAt":"…" }]
```
- **Keys têm prefixo `service.`** (`service.chat`, `service.analysis`, `service.multimodal`,
  `service.search-embeddings`) — o resolver lê `service.{service}`. O consumidor mapeia o prefixo.
- v1 read-only (sem PUT de toggle ainda). `updatedBy`/`updatedAt` são aditivos (ignoráveis).

### Telemetria

`GET /admin/platform/telemetry/cost?from={iso}&to={iso}&groupBy={tenant|model|service}` → **200**:
```json
{ "from":"…","to":"…","groupBy":"model",
  "rows":[{"key":"gpt-4o-mini","promptTokens":123456,"completionTokens":45678,"costUsd":1.234567,"events":42}],
  "totals":{"promptTokens":169134,"completionTokens":…,"costUsd":…,"events":…} }
```
- `from`/`to` ISO-8601. Default: últimas 24h. `groupBy` default `model`. Eventos `status=stub` não
  contam custo.

`GET /admin/platform/telemetry/health` → **200** (lê Application Insights):
```json
{ "window":"PT1H","source":"application-insights","generatedAt":"…",
  "services":[{"role":"nora-api","requests":1234,"failed":3,"failureRate":0.0024,"p95LatencyMs":210}],
  "degraded":false }
```
- Se o App Insights não estiver configurado/consulta falhar → **200** com `"source":"unavailable"` +
  `"note"` (soft, para a UI não quebrar).

`GET /admin/platform/telemetry/business?from={iso}&to={iso}` → **200** (cortável; cross-tenant
operador-only):
```json
{ "from":"…","to":"…","enabled":true,
  "analyses":120,"tenantsActive":4,"productivityAvg":72.5,"customerConfidenceAvg":64.0 }
```
- Se cortado/desligado → **200** com `"enabled":false`.
- **v1:** `productivityAvg`/`customerConfidenceAvg` podem vir `null` (ainda não calculados); o
  consumidor deve tolerar. `analyses`/`tenantsActive` são reais. Caveat RLS: ver runbook /
  production-readiness-gaps (sob RLS enforce, esta leitura cross-tenant exige role BYPASSRLS).

---

## 4. Notas de integração (para o outro Opus)

- **Worker** (`/analyze`): pode adicionar o bloco aditivo `usage:{model,promptTokens,completionTokens}`
  na resposta — a API o ignora com segurança (Jackson tolerante) e mede a análise pelo `metadata`
  que **já existe**. O bloco é bem-vindo mas **não é pré-requisito** para a telemetria da análise
  funcionar.
- **BFF de chat** (`route.ts`): para medir custo precisa de `stream_options.include_usage=true` +
  capturar o frame `usage` antes do `[DONE]`, e então `POST /internal/platform/usage` com
  `service:"chat"`, `tenantId` da sessão, tokens, `latencyMs`, `status`. Lê o modelo ativo via
  `GET /internal/platform/llm-config?service=chat`.
- **nora-admin** (Next): server-side adiciona `X-Internal-Token: <admin token>` e
  `X-Operator-Email: <email do Easy Auth>` em toda chamada a `/admin/platform/**`. Nunca expõe o
  token ao browser.

## 5. Versionamento

v1. Campos podem ser **adicionados** (aditivo, não-breaking). Remoção/renome de campo ou mudança de
status/semântica = v2 com acordo prévio.
