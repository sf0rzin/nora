# NLP Worker — Contexto para IA

> Arquivo gerado para dar contexto a agentes de IA sobre o estado atual do NLP Worker.
> Ultima atualizacao: 2026-05-04

---

## Estado Atual

O NLP Worker esta funcional com dois modos de operacao:

| Modo | Env | Descricao |
|---|---|---|
| **Stub** | `USE_LLM_STUB=true` | Analise deterministica por heuristicas em PT-BR. Sem custo de API. |
| **LLM Real** | `USE_LLM_STUB=false` | OpenRouter (qualquer modelo) com structured output (JSON schema). |

Ambos os modos passam pelo **PII Shield** antes da analise.

---

## Arquitetura dos Modulos

### `clients/openrouter.py`
Client OpenRouter usando OpenAI SDK compativel:
- `OpenRouterClient.__init__(settings)` — valida API key
- `OpenRouterClient.chat_structured(...)` — envia system+user prompt com JSON schema, retorna `(json_str, tokens_in, tokens_out)`
- `OpenRouterClient.chat_json(...)` — fallback com JSON mode (sem schema strict), para modelos que nao suportam structured outputs
- `build_json_schema_for_analysis()` — gera o JSON Schema para o `response_format`

### `services/llm_analyzer.py`
Pipeline completo do LLM:
1. Carrega prompt de `prompts/meeting-analysis-v1.md` (secoes `## SYSTEM` e `## USER`)
2. Injeta `tenant_context_json`, `meeting_id`, `language`, `transcript` nos placeholders `{{...}}`
3. Tenta `chat_structured` (JSON schema); se falhar, faz fallback para `chat_json`
4. Valida resposta com `MeetingAnalysisV1.model_validate()`
5. Retorna `AnalyzeResponse` com metadata (tokens, tempo, versao do modelo)

### `routers/analyze.py`
- `USE_LLM_STUB=true` -> `stub_analyzer.analyze()` (inalterado)
- `USE_LLM_STUB=false` -> `llm_analyzer.analyze(req, settings)`
- Erros de config -> 503 `LLM_CONFIG_INVALID`
- Erros de LLM -> 500 `LLM_PROVIDER_ERROR`

---

## Schema de Saida (MeetingAnalysisV1)

```json
{
  "summary": "## Objetivo\n...\n\n## Proximos Passos\n- ...",
  "decisions": [{"text": "...", "confidence": 0.9}],
  "actionItems": [{"title": "...", "assignee": "...", "dueDate": null, "priority": "HIGH", "sourceQuote": "..."}],
  "risks": [{"text": "...", "severity": "HIGH", "category": "COMPETITION", "sourceQuote": "..."}],
  "opportunities": [{"text": "...", "estimatedValue": "MEDIUM", "category": "UPSELL", "sourceQuote": "..."}],
  "sentimentOverall": "POSITIVE",
  "topics": ["ERP", "proposta comercial"],
  "participants": [{"name": "Carlos", "role": "Gerente Comercial", "mentionCount": 8}],
  "meetingId": "...",
  "metadata": {
    "modelVersion": "openrouter-openai/gpt-4o",
    "promptVersion": "meeting-analysis-v1",
    "tokensInput": 1500,
    "tokensOutput": 800,
    "processingMillis": 2500,
    "piiRedactionsApplied": 3
  }
}
```

### Campo `summary` em Markdown
O campo `summary` retorna **Markdown formatado** com:
- Paragrafo de objetivo
- `## Decisoes` — lista de decisoes
- `## Proximos Passos` — lista com `-`
- `## Observacoes` — notas relevantes
- Negrito `**texto**` para destaques

### Campo `participants` (US13)
Identifica participantes da reuniao:
- `name` — nome do participante
- `role` — cargo/funcao (se mencionado)
- `mentionCount` — quantas vezes participou/falou

---

## Variaveis de Ambiente

```env
WORKER_PORT=8001
LOG_LEVEL=info

LLM_PROVIDER=openrouter
OPENROUTER_API_KEY=sk-or-...
OPENROUTER_MODEL=openai/gpt-4o
OPENROUTER_BASE_URL=https://openrouter.ai/api/v1

USE_LLM_STUB=false   # true para stub, false para OpenRouter real
```

### Modelos populares no OpenRouter
- `openai/gpt-4o` — melhor qualidade
- `openai/gpt-4o-mini` — mais barato, bom para testes
- `anthropic/claude-sonnet-4` — alternativa da Anthropic
- `google/gemini-2.5-flash` — alternativa Google
- `meta-llama/llama-3.1-70b-instruct` — open source

---

## Estrutura de Arquivos

```
services/nlp-worker/src/nora_nlp/
├── __init__.py
├── main.py                    # FastAPI app
├── models.py                  # Pydantic schemas (inclui Participant)
├── settings.py                # env-based config (OpenRouter)
├── clients/
│   ├── __init__.py
│   └── openrouter.py          # OpenRouter client + JSON schema builder
├── prompts/
│   ├── README.md
│   ├── meeting-analysis-v1.md # Prompt com secoes SYSTEM/USER
│   └── pii-shield-v1.md       # Prompt fallback para PII complexo
├── routers/
│   ├── __init__.py
│   ├── analyze.py             # POST /analyze (stub ou LLM)
│   └── health.py              # GET /healthz
└── services/
    ├── __init__.py
    ├── pii_shield.py          # Regex PII redaction
    ├── stub_analyzer.py       # Analise heuristica deterministica
    └── llm_analyzer.py        # Analise via OpenRouter
```

---

## Testes

17 testes passando:

| Arquivo | Testes | Descricao |
|---|---|---|
| `test_health.py` | 1 | Health endpoint |
| `test_pii_shield.py` | 4 | Redacao de PII (email, phone, cpf, cnpj) |
| `test_analyze_stub.py` | 4 | Analise stub com dados sinteticos |
| `test_llm_analyzer.py` | 8 | LLM analyzer com mock (prompt loading, validacao, context injection, fallback JSON) |

---

## Stories Atendidas

| Story | Status |
|---|---|
| US11 — Resumo automatico da reuniao | Implementado (LLM + stub) |
| US12 — Extracao de tarefas e decisoes | Implementado (LLM + stub) |
| US13 — Identificacao de participantes | Implementado (campo `participants`) |
| US14 — Contexto da empresa no processamento | Implementado (tenant context injection no prompt) |

---

## Proximos Passos (nao implementados nesta branch)

1. **Embeddings / RAG** (US15) — recuperar contexto relevante via Azure AI Search
2. **PII Shield com LLM** — fallback para nomes proprios complexos
3. **Retry/backoff** no client OpenRouter para transient failures
4. **Streaming** de resposta para reunioes longas
5. **Health Score temporal** — scoring por tenant ao longo de multiplas reunioes
6. **Integracao backend** — endpoint POST /meetings/upload que chama o worker

---

## Comandos Uteis

```bash
# Instalar deps
cd services/nlp-worker
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

# Rodar testes
python -m pytest tests/ -v

# Lint + format
ruff check src/ tests/
ruff format --check src/ tests/

# Rodar localmente (stub)
USE_LLM_STUB=true python -m nora_nlp.main

# Rodar localmente (OpenRouter real)
USE_LLM_STUB=false OPENROUTER_API_KEY=sk-or-... python -m nora_nlp.main
```
