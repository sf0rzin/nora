# 0024 — Dynamic model catalog + modality router + runtime resolution

- Status: accepted
- Date: 2026-05-28
- Deciders: Co-architects (Opus) + Stratfy (PO/owner)
- Extends: ADR 0004 (provider-agnostic) — it does not supersede it; 0004 remains the basis. Related: ADR
  0003 (strict JSON Schema), ADR 0022/0023.

## Context

ADR 0004 made the LLM provider-agnostic, but the model/provider choice is **static via env var**
at deploy time (`LLM_PROVIDER/LLM_BASE_URL/LLM_API_KEY/LLM_MODEL`), one instance = one model. ADR 0004 §
Operational admits that **cost is monitored manually** and warns that **strict structured output has
irregular support outside gpt-4o** (the reason OpenAI is the default).

The control plane (ADR 0022) needs: a model catalog with CRUD, **per-service model switching at
runtime without a redeploy**, and routing by **modality** (text → text model; audio/video/image
→ multimodal model). Models in play as of May 2026: DeepSeek V4 Flash (text, cheap), gpt-4o-mini
(text+vision, strict first-class), Gemini 3.5 Flash (multimodal).

## Decision

1. **Dynamic catalog in the database** (`llm_models`, platform database — ADR 0022) with CRUD via
   `/admin/platform/models`. A **closed but editable** catalog (seeds: DeepSeek V4 Flash,
   gpt-4o-mini, Gemini 3.5 Flash). Each model models the axes of ADR 0004 (`provider`, `baseUrl`,
   `model`, `modality`) + pricing (`priceInput/Output/CachedInputPerMTok`) + the flag
   **`supportsStrictJsonSchema`**.
2. **Per-service binding** (`llm_config`): each service (`chat`, `analysis`, `multimodal`) points to
   a model. Editable at runtime via `PUT /admin/platform/config/{service}`.
3. **Modality router:** the service determines the modality. `analysis`/`chat` are text;
   `multimodal` (audio/video/image, future) uses a `modality=multimodal` model. Validation on binding:
   - `analysis` requires `supportsStrictJsonSchema=true` (this protects the strict pipeline — ADR 0003);
   - `multimodal` requires `modality=multimodal`.
4. **Runtime resolution with a ~60s cache** (Caffeine `expireAfterWrite(60s)`): the consumer reads
   `GET /internal/platform/llm-config?service=…`. Switch propagation ≤ 60s, without a redeploy.
5. **SOFT fallback, fail-soft to the env default:** if the config breaks (platform off/down, binding
   missing, model disabled), the resolver returns the service's **env default** config (`LLM_*`).
   It **never takes down chat/worker** — an inviolable design rule. The hot-path always gets a 200.
6. **API key outside the catalog** (ADR 0023 / decision #C): `llm-config` returns only
   `provider/model/baseUrl/enabled`; the key is resolved by the consumer via `provider → secret`. The
   catalog (exposed in the operator's UI) **never** stores a key.
7. **Cost telemetry** (`usage_events`): tokens × the catalog's pricing, recalculated server-side
   (the catalog is the source of truth for price). `status=stub` events do not count.

## Consequences

**Positive:**
- The operator switches the model per service without a deploy; realizes savings (e.g., chat→DeepSeek) immediately.
- Centralized pricing ⇒ consistent cost, with no hardcoded prices scattered around.
- `supportsStrictJsonSchema` prevents the operator from breaking the analysis pipeline by accident.
- ADR 0004 remains valid: the env default is the fallback floor; nothing breaks if the platform disappears.

**Negative / trade-offs:**
- Switching to a provider **without a provisioned key** requires a deploy (the key is not in the catalog).
  Accepted and documented.
- A 60s cache ⇒ the switch is not instantaneous (up to 60s). Acceptable.
- Cost may slightly underestimate in SDK retry scenarios (previous attempts do not appear in
  `usage`) and on cache-hit (the cache-hit price is applied only if known). Documented.
- The dynamic catalog is an evolution of ADR 0004's static config — hence this successor ADR (0004
  is immutable, referenced).

## Alternatives Considered

1. **Keep the static env var (ADR 0004 status quo)** — rejected: it does not allow runtime switching or
   automated cost telemetry (a gap that ADR 0004 already admitted).
2. **Dynamic config without fallback (hard-fail)** — rejected: an error in the platform database would take
   chat/worker down for all tenants. Unacceptable.
3. **The key in the catalog (full config via llm-config)** — rejected: it exposes a secret in the operator's UI
   and in the HTTP contract. The key stays out (decision #C).
4. **Long cache / no cache** — no cache hits the DB on the hot-path; a long cache delays the switch too much.
   60s balances it (and it matches the Caffeine pattern already used in `AuthRateLimiter`).

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-28 | Co-architects + Stratfy | Creation. Extends ADR 0004. Seed of `chat` → DeepSeek V4 Flash (analysis stays gpt-4o-mini strict). A conscious exception to ADR 0014 authorized. |
