# NORA NLP Worker

Internal service (FastAPI) that analyses transcripts and returns a validated `MeetingAnalysisV1`.

## Quickstart

```bash
cd services/nlp-worker
python -m venv .venv && source .venv/bin/activate    # Windows: .venv\Scripts\activate
pip install -e ".[dev]"

cp .env.example .env.local
uvicorn nora_nlp.main:app --reload --port 8001
```

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
`"internalAuth": "on" | "off"` so the state of the gate is visible without reading the env.

## Execution modes

- `USE_LLM_STUB=true` (default): uses the deterministic stub in `services/stub_analyzer.py`. No external call, no cost. It lets the backend and the web evolve without depending on the LLM provider.
- `USE_LLM_STUB=false`: calls the real LLM provider via `services/llm_analyzer.py`, configured by `LLM_BASE_URL`/`LLM_API_KEY`/`LLM_MODEL` (default OpenAI direct, `gpt-4o-mini`). See ADR 0004.

## Structure

```
src/nora_nlp/
  main.py              # FastAPI app
  settings.py          # Settings via env
  security.py          # X-Internal-Token dependency (analysis routes only)
  models.py            # Pydantic models mirroring docs/api/llm-schemas/
  routers/
    health.py
    analyze.py
  services/
    pii_shield.py      # regex baseline for EMAIL/PHONE/CPF/CNPJ/CARD
    stub_analyzer.py   # deterministic heuristics (summary, decisions, ...)
    llm_analyzer.py    # real LLM pipeline (provider agnostic)
  clients/
    llm.py             # OpenAI SDK with pluggable base_url
  prompts/             # templates versionados (markdown)
tests/
  test_health.py
  test_internal_auth.py # the X-Internal-Token gate (conftest neutralizes it elsewhere)
  test_pii_shield.py
  test_analyze_stub.py # roda contra data/synthetic/
```

## Tests

```bash
pytest                        # all
pytest tests/test_pii_shield.py
ruff check . && ruff format --check .
```

## PII policy

Every transcript goes through `pii_shield.redact()` before any external call.
The worker never persists the raw text outside the scope of the request.
