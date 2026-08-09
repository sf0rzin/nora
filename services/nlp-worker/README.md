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

## Execution modes

- `USE_LLM_STUB=true` (default): uses the deterministic stub in `services/stub_analyzer.py`. No external call, no cost. It lets the backend and the web evolve without depending on the LLM provider.
- `USE_LLM_STUB=false`: calls the real LLM provider via `services/llm_analyzer.py`, configured by `LLM_BASE_URL`/`LLM_API_KEY`/`LLM_MODEL` (default OpenAI direct, `gpt-4o-mini`). See ADR 0004.

## Structure

```
src/nora_nlp/
  main.py              # FastAPI app
  settings.py          # Settings via env
  models.py            # Pydantic models espelhando docs/api/llm-schemas/
  routers/
    health.py
    analyze.py
  services/
    pii_shield.py      # regex baseline para EMAIL/PHONE/CPF/CNPJ/CARD
    stub_analyzer.py   # heuristica deterministica (sumario, decisoes, ...)
    llm_analyzer.py    # pipeline LLM real (provider agnostic)
  clients/
    llm.py             # SDK OpenAI com base_url plugável
  prompts/             # templates versionados (markdown)
tests/
  test_health.py
  test_pii_shield.py
  test_analyze_stub.py # roda contra data/synthetic/
```

## Tests

```bash
pytest                        # todos
pytest tests/test_pii_shield.py
ruff check . && ruff format --check .
```

## PII policy

Every transcript goes through `pii_shield.redact()` before any external call.
The worker never persists the raw text outside the scope of the request.
