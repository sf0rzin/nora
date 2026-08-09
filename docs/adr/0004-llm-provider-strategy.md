# 0004 — LLM Provider Strategy (agnostic, OpenAI as the default)

- Status: accepted
- Date: 2026-05-04
- Deciders: NORA Team
- Partially supersedes: 0001 (in the part that names Azure OpenAI as the single provider)

## Context

The initial documentation (`PROJECT.md`, ADR 0001) assumed **Azure OpenAI** as the LLM provider because of the Microsoft × TOTVS partnership, availability in the Brazil region and the enterprise SLA.

In practice, during the MVP setup, we identified:

1. **Access to Azure OpenAI requires manual approval** from Microsoft via a corporate form. The process is slow (days to weeks) and typically denies student / individual accounts. A real blocker for the team to start US11–US14 in the backlog.
2. The team has no corporate budget to unblock access within the Sprint 1+2 timeframe.
3. The MVP's projected consumption is low: on the order of hundreds to a few thousand meeting analyses over the entire academic cycle. Total estimated cost below **US$ 5–10**.
4. The contract with the LLM is already stable (JSON Schemas in `docs/api/llm-schemas/` + ADR 0003). Switching provider does not require changing the rest of the system.

Therefore, it makes sense to make the worker **provider-agnostic** and choose the simplest and cheapest provider for the MVP, keeping Azure OpenAI as an upgrade for Enterprise once access is approved.

## Decision

1. The NLP worker talks to **any provider compatible with OpenAI's Chat Completions API** (same SDK, same request/response format). That covers OpenAI direct, Azure OpenAI, Groq, OpenRouter, Together AI, local Ollama and any others that follow the de facto standard.
2. The **default provider in the MVP is OpenAI direct** with the `gpt-4o-mini` model.
3. The worker's environment variables are generalized:
   - `LLM_PROVIDER` (informational label: `openai`, `azure`, `groq`, `openrouter`, `ollama`, `together`, etc.)
   - `LLM_BASE_URL`
   - `LLM_API_KEY`
   - `LLM_MODEL`
   - `LLM_TEMPERATURE`
   - `USE_LLM_STUB` continues to exist and **continues to be the default in CI and local dev**.
4. The Azure OpenAI provider remains **supported and recommended for Enterprise tenants** once access is approved — just point `LLM_BASE_URL` at the Azure endpoint, adjust the model/deployment and use the `AZURE_OPENAI_API_KEY` in the `LLM_API_KEY` field.
5. Embeddings and semantic search follow the same principle: **any endpoint compatible with OpenAI Embeddings**. In the MVP we use OpenAI's `text-embedding-3-small`; in Enterprise, Azure OpenAI + Azure AI Search (a separate ADR when that part comes in).

## Why OpenAI direct and not OpenRouter

We evaluated OpenRouter as an alternative to consolidate billing and have model flexibility. It was rejected for the MVP:

- **+5% markup** over the price of the same model on OpenAI.
- An extra hop → higher latency and one more point of failure.
- Strict Structured Output (`response_format: json_schema`) has irregular support depending on the chosen model — on OpenAI it is first-class.
- OpenRouter's real advantage (switching provider without changing code) **is already covered** by the worker's agnostic design. If we ever want OpenRouter, we just change the `.env`.

## Why Groq is not the default (and why it remains a documented fallback)

Groq has an excellent free tier, is compatible with OpenAI's SDK, and Llama 3.3 70B works well in PT-BR. It remains the recommended option for anyone who **does not want to spend anything**.

We did not pick it as the default because:

- The free tier has rate limits that can be annoying in long demos.
- Strict Structured Output on open-source models is less reliable than on `gpt-4o-mini`.
- For the pitch and the delivery, the predictability of structured JSON is worth more than zero cost.

## Consequences

**Positive**
- The team is unblocked to start US11–US14 immediately, without depending on Azure approval.
- Total estimated MVP cost < US$ 10. It fits in a single US$ 5 credit purchase on OpenAI.
- The code stays portable: switching provider is an `.env` change, not a code change.
- The deterministic stub (`USE_LLM_STUB=true`) remains the default for CI and local dev — no test depends on an external key.

**Negative / trade-offs**
- We move away from the "everything on Azure" promise of the original PROJECT.md. Mitigated: we document that Azure comes back as the Enterprise production provider once approved, with no code change.
- Dependency on an external American provider (OpenAI). Mitigated: sensitive data already goes through the PII Shield before any call (PII ADR).

## Operational

- Keys live in the local `.env` (never committed) and in GitHub Actions Secrets for CI when we run integration (not in the MVP yet).
- The cost estimate must be monitored manually by the key owner during the Sprint cycle.
- When Azure access is approved for the TOTVS tenant, open a new migration ADR and adjust the Enterprise environment's `.env`.
