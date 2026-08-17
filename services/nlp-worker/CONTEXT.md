# NLP Worker — Context for AI

> File generated to give AI agents context about the current state of the NLP Worker.

---

## Current State

The NLP Worker is functional with two operating modes:

| Mode | Env | Description |
|---|---|---|
| **Stub** | `USE_LLM_STUB=true` | Deterministic analysis by heuristics in PT-BR. No API cost. Default in CI and local dev. |
| **Real LLM** | `USE_LLM_STUB=false` | Provider-agnostic client (default OpenAI direct, `gpt-4o-mini`) with JSON Schema structured output. See ADR 0004. |

Both modes go through the **PII Shield** before the analysis.

---

## Module Architecture

### `clients/llm.py`
Provider-agnostic LLM client, based on the official `openai` SDK with a pluggable `base_url` (compatible with OpenAI direct, Azure OpenAI, Groq, OpenRouter, Ollama, etc.):

- `LlmClient.__init__(settings)` — validates `LLM_API_KEY` and configures the SDK.
- `LlmClient.chat_structured(...)` — `response_format=json_schema` (strict). Returns `(json_str, tokens_in, tokens_out)`.
- `LlmClient.chat_json(...)` — `response_format=json_object` fallback for providers that do not support a strict schema.
- `build_json_schema_for_analysis()` — generates the JSON Schema of `MeetingAnalysisV1`.

### `services/llm_analyzer.py`
Complete pipeline:
1. Loads the prompt from `prompts/meeting-analysis-v1.md` (`## SYSTEM` and `## USER` sections).
2. Injects `tenant_context_json`, `meeting_id`, `language`, `transcript` into the `{{...}}` placeholders.
3. Tries `chat_structured` (JSON Schema); if it fails, falls back to `chat_json`.
4. Validates the response with `MeetingAnalysisV1.model_validate()`.
5. Returns `AnalyzeResponse` with metadata (tokens, time, `modelVersion = f"{provider}-{model}"`).

### `routers/analyze.py`
- `USE_LLM_STUB=true` → `stub_analyzer.analyze()`.
- `USE_LLM_STUB=false` → `llm_analyzer.analyze(req, settings)`.
- Config errors → 503 `LLM_CONFIG_INVALID`.
- Provider errors → 500 `LLM_PROVIDER_ERROR`.

---

## Output Schema (MeetingAnalysisV1)

```json
{
  "summary": "## Objetivo\n...\n\n## Próximos Passos\n- ...",
  "decisions": [{"text": "...", "confidence": 0.9}],
  "actionItems": [{"title": "...", "assignee": "...", "dueDate": null, "priority": "HIGH", "sourceQuote": "..."}],
  "risks": [{"text": "...", "severity": "HIGH", "category": "COMPETITION", "sourceQuote": "..."}],
  "opportunities": [{"text": "...", "estimatedValue": "MEDIUM", "category": "UPSELL", "sourceQuote": "..."}],
  "sentimentOverall": "POSITIVE",
  "topics": ["ERP", "proposta comercial"],
  "participants": [{"name": "Carlos", "role": "Gerente Comercial", "mentionCount": 8}],
  "meetingId": "...",
  "metadata": {
    "modelVersion": "openai-gpt-4o-mini",
    "promptVersion": "meeting-analysis-v1",
    "tokensInput": 1500,
    "tokensOutput": 800,
    "processingMillis": 2500,
    "piiRedactionsApplied": 3
  }
}
```

### `summary` field in Markdown

The headings below are quoted VERBATIM: the prompt template makes the model emit them in
pt-BR, and any consumer that parses the summary matches on these exact strings. They are
data, not prose — changing them here would only make this document wrong.

- Objective paragraph.
- `## Decisões` — decisions, as a list.
- `## Próximos Passos` — next steps, as a `-` list.
- `## Observações` — relevant notes.
- Bold (`**...**`) for highlights.

### `participants` field (US13)
- `name` — the participant's name **as the model saw it**, which after the PII Shield is normally a
  `[[PERSON_NAME_n]]` placeholder. The prompt already instructs the model to record the placeholder
  as the name (`prompts/meeting-analysis-v1.md`, item 12).
- `role` — job title/function (if mentioned), otherwise `null`.
- `mentionCount` — how many times they took part/spoke, counted over the redacted text.

> **This array is emitted and nothing consumes it.** `WorkerDtos.AnalyzeResponse` in `services/api`
> has no `participants` field, so the backend never reads it and nothing is persisted from it. It is
> also not where participant identity is decided: `_redact_person_names` gives **every occurrence**
> its own number, so two mentions of one name are two placeholders and no algorithm on this side can
> join them. Deduplication and matching therefore run in the API, over the roster the user declared
> on upload — ADR 0048, which also says why the field is kept rather than removed.

---

## Environment Variables

```env
WORKER_PORT=8001
LOG_LEVEL=info

# Provider-agnostic LLM (ADR 0004). Default: OpenAI direct.
LLM_PROVIDER=openai
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=sk-...
LLM_MODEL=gpt-4o-mini
LLM_TEMPERATURE=0.2

USE_LLM_STUB=false   # true for stub (default in CI/dev)

# Internal service-to-service auth (ADR 0023 §3-4). The API sends this value as the
# `X-Internal-Token` header on /analyze, /split and /analyze-live.
# Set        -> the header is required; 401 on mismatch.
# Empty      -> those three routes answer 503 (fail-closed).
# Empty + NORA_WORKER_ALLOW_UNAUTHENTICATED=true -> open, with a WARN. Local dev only.
# /healthz and /readyz are never gated: the container healthcheck calls them with no header.
NORA_WORKER_INTERNAL_TOKEN=
NORA_WORKER_ALLOW_UNAUTHENTICATED=true
```

### Switching provider
- **OpenAI direct (default)**: leave the defaults; fill in `LLM_API_KEY`.
- **Azure OpenAI**: `LLM_BASE_URL=https://<resource>.openai.azure.com/openai/deployments/<deploy>` and use `LLM_MODEL=<deployment>`.
- **Groq**: `LLM_BASE_URL=https://api.groq.com/openai/v1`, `LLM_MODEL=llama-3.3-70b-versatile`.
- **OpenRouter**: `LLM_BASE_URL=https://openrouter.ai/api/v1`, `LLM_MODEL=openai/gpt-4o-mini`.
- **Local Ollama**: `LLM_BASE_URL=http://localhost:11434/v1`, `LLM_MODEL=llama3.1`.

---

## File Structure

```
services/nlp-worker/src/nora_nlp/
├── __init__.py
├── main.py                    # FastAPI app
├── models.py                  # Pydantic schemas (includes Participant)
├── security.py                # X-Internal-Token dependency (analysis routes only)
├── settings.py                # env-based config (LLM_*, NORA_WORKER_*)
├── clients/
│   ├── __init__.py
│   └── llm.py                 # LlmClient agnostic + JSON schema builder
├── prompts/
│   ├── README.md
│   ├── meeting-analysis-v1.md # Prompt with SYSTEM/USER sections
│   └── pii-shield-v1.md       # Prompt fallback for complex PII
├── routers/
│   ├── __init__.py
│   ├── analyze.py             # POST /analyze (stub or LLM)
│   └── health.py              # GET /healthz
└── services/
    ├── __init__.py
    ├── pii_shield.py          # Regex PII redaction
    ├── stub_analyzer.py       # Deterministic heuristic analysis
    └── llm_analyzer.py        # Pipeline LLM (provider agnostic)
```

---

## Tests

| File | Description |
|---|---|
| `test_health.py` | Health endpoint. |
| `test_pii_shield.py` | PII redaction (email, phone, cpf, cnpj). |
| `test_analyze_stub.py` | Stub analysis with synthetic data. |
| `test_llm_analyzer.py` | LLM pipeline with a mock (prompt loading, validation, context injection, JSON mode fallback). |

The stub is the default in CI; no test depends on an external key.

---

## Stories Covered

| Story | Status |
|---|---|
| US11 — Automatic meeting summary | Implemented (LLM + stub). |
| US12 — Task and decision extraction | Implemented (LLM + stub). |
| US13 — Participant identification | Implemented (`participants` field). |
| US14 — Company context in processing | Implemented (tenant context injection in the prompt). |

---

## Next Steps (not implemented on this branch)

1. **Embeddings / RAG** (US15) — retrieve relevant context via Azure AI Search.
2. **PII Shield with LLM** — fallback for complex proper names.
3. **Retry/backoff** in `LlmClient` for transient failures.
4. **Streaming** of the response for long meetings.
5. **Temporal Health Score** — scoring per tenant across multiple meetings.
6. **Backend integration** — calling the worker from the transcript upload.

---

## Useful Commands

```bash
cd services/nlp-worker
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

# Tests
python -m pytest tests/ -v

# Lint + format
ruff check src/ tests/
ruff format --check src/ tests/

# Run locally (stub)
USE_LLM_STUB=true python -m nora_nlp.main

# Run locally (real LLM, OpenAI direct)
USE_LLM_STUB=false LLM_API_KEY=sk-... python -m nora_nlp.main
```
