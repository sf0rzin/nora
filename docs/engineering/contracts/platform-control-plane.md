# Contract — Operator Control Plane + AI Telemetry

> **Supersession note (2026-06-06):** the operator edge identity described in this contract
> migrated from **Easy Auth (Entra)** to **Cloudflare Tunnel + Access** (ADR 0025). The mentions of Easy
> Auth / Entra / `X-MS-CLIENT-PRINCIPAL-*` below remain as a historical record of the frozen
> contract; the operator's authentication mechanism at the edge is today Cloudflare Access. The rest
> of the contract (paths, tokens, `X-Internal-Token` / `X-Operator-Email` headers) remains valid.

> **Status:** FROZEN (v1) — 2026-05-28. Attachment point for the `nora-admin` app (Next) and for the
> hot-paths (worker / chat BFF). Changing a signature here requires updating every consumer
> + the owner. Originating decisions: design note + ruling of 2026-05-28 (ADRs 0022/0023/0024).

## 1. Security domains

There are **three** distinct surfaces in the Spring API (`nora-api`), separated by path and by their own
security chain (the existing per-tenant JWT chain stays intact):

| Path | Who calls | Auth | Identity header |
|---|---|---|---|
| `/internal/platform/**` | NLP worker, chat BFF (web) | `X-Internal-Token` = **service token** | — |
| `/admin/platform/**` | `nora-admin` app (Next), server-side | `X-Internal-Token` = **admin token** | `X-Operator-Email` (auditing) |
| (rest) | clients/tenants | JWT/cookie `nora_access` (unchanged) | — |

**Easy Auth (Entra) + `ipSecurityRestrictions` live at the edge of `nora-admin`**, not in Spring. The
`nora-admin` authenticates the operator (Entra group), reads `X-MS-CLIENT-PRINCIPAL-*` and calls Spring
server-side with the **admin token** + `X-Operator-Email`. Spring **never** reads an Easy Auth header.

**Tokens** (KV secrets, injected as env into `nora-api`, the worker and web as applicable):
- `NORA_PLATFORM_INTERNAL_TOKEN` → enables `/internal/platform/**`.
- `NORA_PLATFORM_ADMIN_TOKEN` → enables `/admin/platform/**`. If not set, it falls back to the internal token
  (with a WARN in the log). **Recommended to keep them distinct** (least-privilege: a leak of the worker's token
  does not grant access to admin mutations).

Auth responses: missing/invalid token → **401**. (We do not use 403 here — there is no fine-grained operator
permission model in v1; it is all-or-nothing by token.)

`X-Internal-Token` compared with `MessageDigest.isEqual` (constant-time). Tokens are never logged.

---

## 2. `/internal/platform/*` — service-to-service (hot-path)

### GET `/internal/platform/llm-config?service={chat|analysis|multimodal}`

Resolves the active model per service (binding `llm_config` → `llm_models`), with **server-side cache
~60s** and **SOFT fallback**.

**200** (always 200 on the hot-path, even on fallback):
```json
{ "provider": "deepseek", "model": "deepseek-v4-flash", "baseUrl": "https://api.deepseek.com/v1", "enabled": true }
```
- `enabled` = `binding.enabled` AND `model.enabled` AND `featureFlag("service."+service).enabled`.
- **SOFT fallback** (returns the config of the service's **env default**, with `enabled` according to the feature flag),
  triggered when: the platform is disabled (`nora.platform.enabled=false`), the platform database is
  down, the binding is missing, or the model is disabled. **Never 5xx** — the consumer must not break.
- invalid `service` → **400**.

> **API key resolution (not part of the contract):** the provider's key does **not** travel here (decision
> #C). The consumer maps `provider → secret` locally (env/KV). Switching to a provider without a provisioned
> key requires a deploy. Seeds with a provisioned key: OpenAI (already), **DeepSeek** and **Gemini**
> (pending — the owner provisions them in KV).

### POST `/internal/platform/usage`

Cost event ingestion. **Fire-and-forget**: it never blocks the caller, never propagates an error.

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
- `service` required. `tenantId` optional (a dimension, not an FK). `promptTokens`/`completionTokens`
  ≥ 0. `status` ∈ `ok | error | stub | fallback` (a free-form string is normalized; default `ok`).
- `costUsd` is **best-effort**: the server **recalculates** it from the catalog pricing when it
  knows `(provider, model)`; it uses the payload's `costUsd` only as a fallback.

**202 Accepted** (empty body). If the platform is off/down, still **202** (event discarded +
log) — the caller does not notice. Invalid validation → **400**.

> **The ANALYSIS path does not use this endpoint:** the API emits the analysis usage **in-process**
> (`AnalysisService` → `UsageRecorder`), because it already has `tenantId` + tokens + model from the worker's
> `metadata`. This POST is for the **chat BFF** (and future external callers such as multimodal).

---

## 3. `/admin/platform/*` — operator console (via nora-admin)

All of them require the **admin token**; they record an audit entry with `X-Operator-Email`.

### Model catalog

`GET /admin/platform/models` → **200** list:
```json
[{
  "id": "uuid", "provider": "openai", "model": "gpt-4o-mini", "displayName": "GPT-4o mini",
  "baseUrl": "https://api.openai.com/v1", "modality": "text", "supportsStrictJsonSchema": true,
  "priceInputPerMTok": 0.15, "priceOutputPerMTok": 0.60, "priceCachedInputPerMTok": null,
  "enabled": true, "createdAt": "2026-05-28T12:00:00Z", "updatedAt": "2026-05-28T12:00:00Z"
}]
```

`POST /admin/platform/models` → **201** (model created). Body:
```json
{ "provider":"groq","model":"llama-3.3-70b","displayName":"Llama 3.3 70B","baseUrl":"https://api.groq.com/openai/v1",
  "modality":"text","supportsStrictJsonSchema":false,"priceInputPerMTok":0.59,"priceOutputPerMTok":0.79,
  "priceCachedInputPerMTok":null,"enabled":true }
```
- `modality` ∈ `text | multimodal`. Unique `(provider, model)` → conflict **409**.

`DELETE /admin/platform/models/{id}` → **204**. If the model is **bound** in `llm_config` →
**409** (unbind first). Nonexistent id → **404**.

### Per-service selection (binding)

`GET /admin/platform/config` → **200** all bindings:
```json
[{ "service":"chat","modelId":"uuid","provider":"deepseek","model":"deepseek-v4-flash","enabled":true,
   "updatedAt":"…","updatedBy":"op@nora" }]
```

`PUT /admin/platform/config/{service}` → **200** (binding updated). Body:
```json
{ "modelId": "uuid", "enabled": true }
```
- `service` ∈ `chat | analysis | multimodal`. `modelId` must exist.
- **Router validations by modality (ADR 0024) + strict (ADR 0003):**
  - `analysis` → the model needs `supportsStrictJsonSchema=true`, otherwise **422**.
  - `multimodal` → the model needs `modality=multimodal`, otherwise **422**.
  - `chat` → any modality.
- Invalidates the resolution cache (≤60s of propagation guaranteed in any case).

### Feature flags

`GET /admin/platform/flags` → **200** list:
```json
[{ "key":"service.chat","enabled":true,"description":"Chat IA do plano Core (BFF /api/chat)",
   "updatedBy":"seed","updatedAt":"…" }]
```
- **Keys have the `service.` prefix** (`service.chat`, `service.analysis`, `service.multimodal`,
  `service.search-embeddings`) — the resolver reads `service.{service}`. The consumer maps the prefix.
- v1 is read-only (no toggle PUT yet). `updatedBy`/`updatedAt` are additive (can be ignored).

### Telemetry

`GET /admin/platform/telemetry/cost?from={iso}&to={iso}&groupBy={tenant|model|service}` → **200**:
```json
{ "from":"…","to":"…","groupBy":"model",
  "rows":[{"key":"gpt-4o-mini","promptTokens":123456,"completionTokens":45678,"costUsd":1.234567,"events":42}],
  "totals":{"promptTokens":169134,"completionTokens":…,"costUsd":…,"events":…} }
```
- `from`/`to` ISO-8601. Default: last 24h. `groupBy` default `model`. Events with `status=stub` do not
  count toward cost.

`GET /admin/platform/telemetry/health` → **200** (reads Application Insights):
```json
{ "window":"PT1H","source":"application-insights","generatedAt":"…",
  "services":[{"role":"nora-api","requests":1234,"failed":3,"failureRate":0.0024,"p95LatencyMs":210}],
  "degraded":false }
```
- If App Insights is not configured / the query fails → **200** with `"source":"unavailable"` +
  `"note"` (soft, so the UI does not break).

`GET /admin/platform/telemetry/business?from={iso}&to={iso}` → **200** (can be cut; cross-tenant
operator-only):
```json
{ "from":"…","to":"…","enabled":true,
  "analyses":120,"tenantsActive":4,"productivityAvg":72.5,"customerConfidenceAvg":64.0 }
```
- If cut/turned off → **200** with `"enabled":false`.
- **v1:** `productivityAvg`/`customerConfidenceAvg` may come back `null` (not computed yet); the
  consumer must tolerate it. `analyses`/`tenantsActive` are real. RLS caveat: see the runbook /
  production-readiness-gaps (under RLS enforce, this cross-tenant read requires a BYPASSRLS role).

---

## 4. Integration notes (for the other Opus)

- **Worker** (`/analyze`): it may add the additive block `usage:{model,promptTokens,completionTokens}`
  to the response — the API ignores it safely (Jackson is tolerant) and measures the analysis by the `metadata`
  that **already exists**. The block is welcome but is **not a prerequisite** for the analysis telemetry
  to work.
- **Chat BFF** (`route.ts`): to measure cost it needs `stream_options.include_usage=true` +
  capturing the `usage` frame before `[DONE]`, and then `POST /internal/platform/usage` with
  `service:"chat"`, the session's `tenantId`, tokens, `latencyMs`, `status`. It reads the active model via
  `GET /internal/platform/llm-config?service=chat`.
- **nora-admin** (Next): server-side it adds `X-Internal-Token: <admin token>` and
  `X-Operator-Email: <email do Easy Auth>` to every call to `/admin/platform/**`. It never exposes the
  token to the browser.

## 5. Versioning

v1. Fields may be **added** (additive, non-breaking). Removing/renaming a field or changing
status/semantics = v2 with prior agreement.
