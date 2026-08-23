# NORA NLP Worker

Internal service (FastAPI) that analyses transcripts and returns a validated `MeetingAnalysisV1`.

## Quickstart

```bash
cd services/nlp-worker
python -m venv .venv && source .venv/bin/activate    # Windows: .venv\Scripts\activate
pip install -e ".[dev]"
pip install -r requirements-ner.txt            # pt_core_news_sm — see "PII policy"

cp .env.example .env.local
uvicorn nora_nlp.main:app --reload --port 8001
```

That second `pip install` is not optional decoration: without the model the PII Shield runs at
five times the leak rate and says nothing about it. See "PII policy" below for the measurement.

Healthcheck: `GET http://localhost:8001/healthz`
Analysis:    `POST http://localhost:8001/analyze`

## Internal authentication

The analysis routes (`/analyze`, `/split`, `/analyze-live`) require the `X-Internal-Token`
header. The API sends it; `NORA_WORKER_INTERNAL_TOKEN` is what the worker compares it against,
in constant time. Reaching the port is not supposed to be enough to spend an LLM call.

With no token configured those three routes answer `503 INTERNAL_AUTH_NOT_CONFIGURED` rather
than serving anyone — fail-closed. For local work, either set a token on both sides or set
`NORA_WORKER_ALLOW_UNAUTHENTICATED=true` (what `.env.example`, and therefore `make env`, ships).

`/healthz` and `/readyz` stay open: the container healthcheck calls `/healthz` with no header,
and a gated one would leave the container unhealthy forever. `/readyz` reports
`"internalAuth": "on" | "closed" | "open"` so the state of the gate is visible without reading
the env. Three values and not two, because `off` used to mean two opposite things: a worker that
refuses every call because no token is configured, and a worker deliberately left open by
`NORA_WORKER_ALLOW_UNAUTHENTICATED`. `closed` is the first, `open` is the second, and only one of
them is a reason to page somebody.

## Execution modes

- `USE_LLM_STUB=true` (default): uses the deterministic stub in `services/stub_analyzer.py`. No external call, no cost. It lets the backend and the web evolve without depending on the LLM provider.
- `USE_LLM_STUB=false`: calls the real LLM provider via `services/llm_analyzer.py`, configured by `LLM_BASE_URL`/`LLM_API_KEY`/`LLM_MODEL` (default OpenAI direct, `gpt-4o-mini`). See ADR 0004.

## Structure

```
pyproject.toml         # deps, incl. the spacy pin and why it is required rather than an extra
requirements-ner.txt   # spaCy + the pinned pt_core_news_sm wheel URL (the model is not on PyPI)
Dockerfile             # the NER layer is cached on its own + asserted at build time
src/nora_nlp/
  main.py              # FastAPI app
  settings.py          # Settings via env
  security.py          # X-Internal-Token dependency (analysis routes only)
  models.py            # Pydantic models mirroring docs/api/llm-schemas/
  time_budget.py       # wall-clock budget for one request
  routers/
    health.py
    analyze.py
  services/
    pii_shield.py      # deterministic redaction for EMAIL/PHONE/CPF/CNPJ/CARD/PERSON_NAME
    pii_ner.py         # statistical PERSON_NAME backstop behind it (spaCy, pt_core_news_sm)
    stub_analyzer.py   # deterministic heuristics (summary, decisions, ...)
    llm_analyzer.py    # real LLM pipeline (provider agnostic)
  clients/
    llm.py             # OpenAI SDK with pluggable base_url
  prompts/             # templates versionados (markdown)
tests/
  test_health.py
  test_internal_auth.py # the X-Internal-Token gate (conftest neutralizes it elsewhere)
  test_pii_shield.py    # per-case redaction + the backstop's own behaviour
  test_pii_corpus.py    # the 5,664-case corpus: leak and false-redaction ceilings
  test_analyze_stub.py  # roda contra data/synthetic/
```

## Tests

```bash
pytest                        # all
pytest tests/test_pii_shield.py
pytest -rs                    # names the PII mode: 7 items skip without pt_core_news_sm
ruff check . && ruff format --check .
```

## PII policy

Every transcript goes through `pii_shield.redact()` before any external call. The worker never
persists the raw text outside the scope of the request.

The shield is two layers, and only the first one is the contract:

1. **Deterministic** (`services/pii_shield.py`) — regex, check digits and two frequency lists,
   for EMAIL, PHONE, CPF, CNPJ, CARD and PERSON_NAME. This is the gate. It is what the
   per-case assertions in `tests/test_pii_shield.py` and every rate in
   `tests/test_pii_corpus.py` are written against.
2. **Statistical backstop** (`services/pii_ner.py`) — spaCy with the `pt_core_news_sm` pt-BR
   pipeline, for the person names no list can hold: a surname behind a genitive, a full name
   with a product wedged into the middle. It can only ever *add* a redaction; nothing in it
   frees a span the first layer claimed, so its worst case is over-redaction rather than a
   leak that was not already there.

### The two modes, measured

Over the 5,664-case corpus, on 2026-08-23:

| Mode | Leak | False redaction |
|---|---|---|
| Deterministic only | 120/5664 (**2.12%**) | 512/5507 (**9.30%**) |
| With the backstop | 23/5664 (**0.41%**) | 609/5507 (**11.06%**) |

A 5.2x reduction in the rate the non-negotiable is about, bought with 1.76 points of
over-redaction. Both halves are ceilings in CI, because a leak rate published without a
false-redaction rate beside it is how a redaction defect gets closed by redacting everything.

### Which mode is running

Whichever one the install produced. `spacy` is a hard dependency in `pyproject.toml`; the model
is not on PyPI and is pinned by URL in `requirements-ner.txt`:

```bash
pip install -r requirements-ner.txt
```

With the model absent, `pii_ner` returns no spans and the deterministic shield behaves exactly
as it did before the layer existed. Nothing fails, `/readyz` still answers `ready`, every
response is still a 200 — the service simply runs at the first row's rates. That silence is why
the packaging refuses to make the model optional and why the Dockerfile loads the pipeline at
build time: an image that cannot run the second layer fails to build instead of shipping.

The suite reports the mode too. The six tests marked `needs_backstop` and
`test_the_backstop_moves_both_rates` skip when the model is not installed, so a `-rs` run says
out loud which pipeline was measured.
