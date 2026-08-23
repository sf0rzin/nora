# NLP Worker — Context for AI

> File generated to give AI agents context about the current state of the NLP Worker.

---

## Current State

The NLP Worker is functional with two operating modes:

| Mode | Env | Description |
|---|---|---|
| **Stub** | `USE_LLM_STUB=true` | Deterministic analysis by heuristics in PT-BR. No API cost. Default in CI and local dev. |
| **Real LLM** | `USE_LLM_STUB=false` | Provider-agnostic client (default OpenAI direct, `gpt-4o-mini`) with JSON Schema structured output. See ADR 0004. |

Both modes go through the **PII Shield** before the analysis. Since 2026-08-23 the shield is two
layers — deterministic rules, then an optional statistical backstop for person names — and which
of the two is running depends on the install, not on a setting. See `services/pii_ner.py` below.

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

### `services/pii_ner.py`
A statistical backstop for `PERSON_NAME`, behind the deterministic shield and subordinate to it:
it can only ever **add** a redaction, never free a span the rules claimed. spaCy runs the
`pt_core_news_sm` pt-BR pipeline; LOC/GPE spans veto an overlapping PER so `Sao Paulo` does not
become a person, all-caps is ceded to the deterministic patterns, and a span must survive
edge-trimming with at least two tokens left.

Measured over the 5,664-case corpus on 2026-08-23:

| Mode | Leak | False redaction |
|---|---|---|
| Deterministic only | 120/5664 (2.12%) | 512/5507 (9.30%) |
| With the backstop | 23/5664 (0.41%) | 609/5507 (11.06%) |

**Which mode runs is a packaging question, and it is the reason it is documented here rather
than under a flag.** `spacy` is a hard dependency in `pyproject.toml`; the model is not on PyPI
and is pinned by URL in `requirements-ner.txt`, which the Dockerfile, the `worker` CI job and
the venv setup all install from. With the model absent the module returns no spans, the
deterministic shield behaves exactly as it did before the layer existed, and **nothing says so**:
no failure, `/readyz` still answers `ready`, every response is still a 200. Hence the hard
dependency, and hence the `RUN python -c "... spacy.load(...)"` line in the Dockerfile — an image
that cannot run the second layer fails to build instead of shipping at the first row's rates.

The layer costs ~98 MB of RSS per process (measured; `--workers 2` pays it twice), ~0.7s once at
first load, and ~2ms per sentence.

### `routers/analyze.py`
- `USE_LLM_STUB=true` → `stub_analyzer.analyze()`.
- `USE_LLM_STUB=false` → `llm_analyzer.analyze(req, settings, budget=...)`.
- Every route opens a `TimeBudget` first, before the PII Shield and the baseline — neither is free
  on a 1MB file, and a budget that started after them would be measuring the wrong thing.
- Config errors → 503 `LLM_CONFIG_INVALID`.
- Provider errors → 500 `LLM_PROVIDER_ERROR`.
- Model answered outside its schema, or with something that is not JSON → 502 `LLM_RESPONSE_INVALID`.
  It is its own branch because `json.JSONDecodeError` subclasses `ValueError`: without it, a model
  breaking its contract was reported as `LLM_CONFIG_INVALID` and sent the reader to check
  credentials that were fine.
- The wall-clock budget ran out → 504 `LLM_BUDGET_EXCEEDED`. The worker is healthy; the work did
  not fit. See `time_budget.py`.

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

There is **no variable for the PII backstop**, and that absence is deliberate rather than
pending: the layer is on when `pt_core_news_sm` is installed and off when it is not, so a flag
would be a second source of truth that could disagree with the process. `pip install -r
requirements-ner.txt` is the switch.

### Switching provider
- **OpenAI direct (default)**: leave the defaults; fill in `LLM_API_KEY`.
- **Azure OpenAI**: `LLM_BASE_URL=https://<resource>.openai.azure.com/openai/deployments/<deploy>` and use `LLM_MODEL=<deployment>`.
- **Groq**: `LLM_BASE_URL=https://api.groq.com/openai/v1`, `LLM_MODEL=llama-3.3-70b-versatile`.
- **OpenRouter**: `LLM_BASE_URL=https://openrouter.ai/api/v1`, `LLM_MODEL=openai/gpt-4o-mini`.
- **Local Ollama**: `LLM_BASE_URL=http://localhost:11434/v1`, `LLM_MODEL=llama3.1`.

---

## File Structure

```
services/nlp-worker/
├── pyproject.toml             # deps, incl. the `spacy>=3.8,<3.9` pin and why it is required
├── requirements-ner.txt       # spaCy + the pinned pt_core_news_sm wheel URL, for the image layer
├── Dockerfile                 # model in its own layer + a build-time load assertion
└── src/nora_nlp/
    ├── __init__.py
    ├── main.py                    # FastAPI app
    ├── models.py                  # Pydantic schemas (includes Participant)
    ├── security.py                # X-Internal-Token dependency (analysis routes only)
    ├── settings.py                # env-based config (LLM_*, NORA_WORKER_*)
    ├── time_budget.py             # wall-clock budget for one request
    ├── clients/
    │   ├── __init__.py
    │   └── llm.py                 # LlmClient agnostic + JSON schema builder
    ├── prompts/
    │   ├── README.md
    │   ├── meeting-analysis-v1.md # Prompt with SYSTEM/USER sections
    │   ├── live-highlights-v1.md  # Prompt for /analyze-live
    │   └── meeting-split-v1.md    # Prompt for /split
    ├── routers/
    │   ├── __init__.py
    │   ├── analyze.py             # POST /analyze, /split, /analyze-live
    │   └── health.py              # GET /healthz, /readyz
    └── services/
        ├── __init__.py
        ├── pii_shield.py          # Deterministic PII redaction (the gate)
        ├── pii_ner.py             # Statistical PERSON_NAME backstop behind it
        ├── shield_walk.py         # Shared string-leaf walk over a structure
        ├── baseline.py            # TF-IDF baseline (packages/nlp-baseline, ADR 0010)
        ├── stub_analyzer.py       # Deterministic heuristic analysis
        ├── stub_live_analyzer.py
        ├── stub_split_analyzer.py
        ├── live_analyzer.py       # /analyze-live pipeline
        ├── split_analyzer.py      # /split pipeline (one provider call per window)
        ├── prompt_utils.py
        └── llm_analyzer.py        # Pipeline LLM (provider agnostic)
```

---

## Tests

| File | Description |
|---|---|
| `test_health.py` | Health endpoints. |
| `test_internal_auth.py` | The `X-Internal-Token` gate (conftest neutralizes it elsewhere). |
| `test_pii_shield.py` | PII redaction (email, phone, cpf, cnpj, card, person name) + the backstop. |
| `test_pii_corpus.py` | The 5,664-case corpus: leak and false-redaction ceilings, both pipelines. |
| `test_pii_over_transcripts.py` | The shield over whole multi-speaker documents in `data/synthetic/`. |
| `test_pii_gate_is_single.py` | A retry resends already-redacted text, so it cannot leak. |
| `test_analyze_stub.py` | Stub analysis with synthetic data. |
| `test_llm_analyzer.py` | LLM pipeline with a mock (prompt loading, validation, context injection, JSON mode fallback). |
| `test_llm_failure_paths.py` | The error taxonomy: 503 / 500 / 502 / 504. |
| `test_time_budget.py` | The wall-clock budget. |
| `test_split.py`, `test_analyze_live.py` | `/split` and `/analyze-live`. |
| `test_baseline.py` | The TF-IDF baseline call. |
| `test_schema_contract.py` | The response against `docs/api/llm-schemas/`. |

The stub is the default in CI; no test depends on an external key.

**The suite runs in both PII modes, and says which one it measured.** Seven test items — the six
marked `needs_backstop` in `test_pii_shield.py` plus `test_the_backstop_moves_both_rates` in
`test_pii_corpus.py` — skip when `pt_core_news_sm` is not installed, so `pytest -rs` names the
pipeline instead of leaving it to be inferred. Everything else, including every deterministic
ceiling, is measured with the backstop held off by the `report` fixture and is identical in both
modes. A green run with those seven skipped is a run that never exercised the second layer, so
any CI job that gates on this suite has to install the model rather than let them skip.

---

## Stories Covered

| Story | Status |
|---|---|
| US11 — Automatic meeting summary | Implemented (LLM + stub). |
| US12 — Task and decision extraction | Implemented (LLM + stub). |
| US13 — Participant identification | Implemented (`participants` field). |
| US14 — Company context in processing | Implemented (tenant context injection in the prompt). |

---

## Next Steps

**Four of the six items this list carried were already done, and one of those was closed scope.**
The list was written when the worker was a branch nobody had merged and was never revisited; it is
sorted below into what shipped, what is open, and what will not be built. Item 1 was the worst of
them: it named **Azure AI Search**, a service this project has not used since ADR 0034 shut the
subscription down.

**Already delivered, and not in this worker:**

- **Embeddings / RAG (US15)** — built, in `services/api`. `EmbeddingService` and
  `HttpEmbeddingClient` index a meeting's stored summary and score cosine similarity in Java over
  a JSON vector in a `TEXT` column (migration V021); the extension in `pgvector/pgvector:pg16` is
  deliberately not created. The worker has no part in it.
- **Retry/backoff in `LlmClient`** — the OpenAI SDK's own, `max_retries=2`, applied around each
  call. `tests/test_pii_gate_is_single.py` asserts the property that matters: a retry resends the
  same already-redacted body, so it cannot leak text the shield removed.
- **Backend integration** — `AnalysisService` calls the worker on upload; `NlpWorkerProperties`
  holds the base URL and the deadline, and `StuckAnalysisSweeper` releases an analysis the worker
  never finished.

**Delivered since, and in this worker:**

- **A local backstop for off-list proper names** — `services/pii_ner.py`, 2026-08-23. The shield
  recognised a name by shape plus two frequency lists, so a name on neither list and in no
  recognised shape was published; ADR 0012 had deferred the fix to NER at internationalisation.
  It runs **locally**, which was the binding constraint: the `pii-shield-v1.md` prompt that used
  to sit in `prompts/` proposed asking the provider to do the redaction, which requires sending
  the provider the raw text and inverts the premise of the gate. That file is deleted. Corpus
  leak 2.12% → 0.41%, false redaction 9.30% → 11.06%.
- **A wall-clock deadline propagated across the analysers** — `time_budget.py`. The per-call
  timeout multiplied by the retries exceeded the caller's deadline, and `/split` calls the
  provider once per window, so no pair of constants fixed it. The budget is fixed at the start
  of the request, ahead of the shield and the baseline, and passed to the three analysis
  functions; exhausting it is a 504 `LLM_BUDGET_EXCEEDED`.

**Still open:**

- **Single-token off-list names.** The backstop refuses a span that trims below two tokens, so
  `A proposta da Costa foi aceita` still leaks. It is a deliberate floor, not an oversight —
  letting the model claim lone capitalised words moved false redaction from 11.06% to 15.87%
  over the corpus. Closing it needs a better separator, and the limit is pinned in
  `test_the_single_token_leak_stays_open`.
- **Streaming** of the response for long meetings. The chat streams, but that is the BFF calling
  the provider directly, not this worker.

**Will not be built:** a temporal Health Score across meetings per tenant. That is US50/US51,
**WONT** by ADR 0038 §4 — it aggregates over a history that does not exist.

---

## Useful Commands

```bash
cd services/nlp-worker
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

# The pt-BR NER model for the PERSON_NAME backstop. Not on PyPI, so it is pinned by URL.
# Skip it and the shield runs at 2.12% leak instead of 0.41%, silently.
pip install -r requirements-ner.txt

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
