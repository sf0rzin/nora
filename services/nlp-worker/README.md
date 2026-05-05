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

- `USE_LLM_STUB=true` (padrao): usa o stub deterministico em `services/stub_analyzer.py`. Sem chamada externa, sem custo. Permite que o backend e o web evoluam sem depender do provedor de LLM.
- `USE_LLM_STUB=false`: chama o provedor LLM real via `services/llm_analyzer.py`, configurado por `LLM_BASE_URL`/`LLM_API_KEY`/`LLM_MODEL` (default OpenAI direto, `gpt-4o-mini`). Ver ADR 0004.

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
    llm_analyzer.py    # pipeline LLM real (provider agnostic)
  clients/
    llm.py             # SDK OpenAI com base_url plugável
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
