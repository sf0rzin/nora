# NORA NLP Worker

Servico interno (FastAPI) que analisa transcricoes e devolve uma `MeetingAnalysisV1` validada.

## Quickstart

```bash
cd services/nlp-worker
python -m venv .venv && source .venv/bin/activate    # Windows: .venv\Scripts\activate
pip install -e ".[dev]"

cp .env.example .env.local
uvicorn nora_nlp.main:app --reload --port 8001
```

Healthcheck: `GET http://localhost:8001/healthz`
Análise:     `POST http://localhost:8001/analyze`

## Modos de execucao

- `USE_LLM_STUB=true` (padrao): usa o stub deterministico em `services/stub_analyzer.py`. Sem chamada externa, sem custo. Permite que o backend e o web evoluam sem depender de provedor LLM externo.
- `USE_LLM_STUB=false`: tentaria chamar o provedor LLM real (default OpenAI `gpt-4o-mini` via API Chat Completions; provider configuravel por env, ver `docs/adr/0004-llm-provider-strategy.md`). Implementacao ainda nao esta plugada (entra em historia futura do backlog).

## Estrutura

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
  prompts/             # templates versionados (markdown)
tests/
  test_health.py
  test_pii_shield.py
  test_analyze_stub.py # roda contra data/synthetic/
```

## Testes

```bash
pytest                        # todos
pytest tests/test_pii_shield.py
ruff check . && ruff format --check .
```

## Politica de PII

Toda transcricao passa pelo `pii_shield.redact()` antes de qualquer chamada externa.
O worker nunca persiste o texto cru fora do escopo da requisicao.
