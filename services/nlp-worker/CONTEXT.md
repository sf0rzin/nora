# NLP Worker — Contexto para IA

> Arquivo gerado para dar contexto a agentes de IA sobre o estado atual do NLP Worker.

---

## Estado Atual

O NLP Worker está funcional com dois modos de operação:

| Modo | Env | Descrição |
|---|---|---|
| **Stub** | `USE_LLM_STUB=true` | Análise determinística por heurísticas em PT-BR. Sem custo de API. Default em CI e dev local. |
| **LLM Real** | `USE_LLM_STUB=false` | Cliente provider-agnostic (default OpenAI direto, `gpt-4o-mini`) com structured output JSON Schema. Ver ADR 0004. |

Ambos os modos passam pelo **PII Shield** antes da análise.

---

## Arquitetura dos Módulos

### `clients/llm.py`
Client LLM agnóstico de provider, baseado na SDK oficial `openai` com `base_url` plugável (compatível com OpenAI direto, Azure OpenAI, Groq, OpenRouter, Ollama, etc.):

- `LlmClient.__init__(settings)` — valida `LLM_API_KEY` e configura SDK.
- `LlmClient.chat_structured(...)` — `response_format=json_schema` (strict). Retorna `(json_str, tokens_in, tokens_out)`.
- `LlmClient.chat_json(...)` — fallback `response_format=json_object` para providers que não suportam strict schema.
- `build_json_schema_for_analysis()` — gera o JSON Schema do `MeetingAnalysisV1`.

### `services/llm_analyzer.py`
Pipeline completo:
1. Carrega prompt de `prompts/meeting-analysis-v1.md` (seções `## SYSTEM` e `## USER`).
2. Injeta `tenant_context_json`, `meeting_id`, `language`, `transcript` nos placeholders `{{...}}`.
3. Tenta `chat_structured` (JSON Schema); se falhar, faz fallback para `chat_json`.
4. Valida resposta com `MeetingAnalysisV1.model_validate()`.
5. Retorna `AnalyzeResponse` com metadata (tokens, tempo, `modelVersion = f"{provider}-{model}"`).

### `routers/analyze.py`
- `USE_LLM_STUB=true` → `stub_analyzer.analyze()`.
- `USE_LLM_STUB=false` → `llm_analyzer.analyze(req, settings)`.
- Erros de config → 503 `LLM_CONFIG_INVALID`.
- Erros de provider → 500 `LLM_PROVIDER_ERROR`.

---

## Schema de Saída (MeetingAnalysisV1)

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

### Campo `summary` em Markdown
- Parágrafo de objetivo.
- `## Decisões` — lista.
- `## Próximos Passos` — lista com `-`.
- `## Observações` — notas relevantes.
- Negrito `**texto**` para destaques.

### Campo `participants` (US13)
- `name` — nome do participante.
- `role` — cargo/função (se mencionado), senão `null`.
- `mentionCount` — quantas vezes participou/falou.

---

## Variáveis de Ambiente

```env
WORKER_PORT=8001
LOG_LEVEL=info

# Provider de LLM agnóstico (ADR 0004). Default: OpenAI direto.
LLM_PROVIDER=openai
LLM_BASE_URL=https://api.openai.com/v1
LLM_API_KEY=sk-...
LLM_MODEL=gpt-4o-mini
LLM_TEMPERATURE=0.2

USE_LLM_STUB=false   # true para stub (default em CI/dev)
```

### Trocar de provider
- **OpenAI direto (default)**: deixe os defaults; preencha `LLM_API_KEY`.
- **Azure OpenAI**: `LLM_BASE_URL=https://<resource>.openai.azure.com/openai/deployments/<deploy>` e use `LLM_MODEL=<deployment>`.
- **Groq**: `LLM_BASE_URL=https://api.groq.com/openai/v1`, `LLM_MODEL=llama-3.3-70b-versatile`.
- **OpenRouter**: `LLM_BASE_URL=https://openrouter.ai/api/v1`, `LLM_MODEL=openai/gpt-4o-mini`.
- **Ollama local**: `LLM_BASE_URL=http://localhost:11434/v1`, `LLM_MODEL=llama3.1`.

---

## Estrutura de Arquivos

```
services/nlp-worker/src/nora_nlp/
├── __init__.py
├── main.py                    # FastAPI app
├── models.py                  # Pydantic schemas (inclui Participant)
├── settings.py                # env-based config (LLM_*)
├── clients/
│   ├── __init__.py
│   └── llm.py                 # LlmClient agnostic + JSON schema builder
├── prompts/
│   ├── README.md
│   ├── meeting-analysis-v1.md # Prompt com seções SYSTEM/USER
│   └── pii-shield-v1.md       # Prompt fallback para PII complexo
├── routers/
│   ├── __init__.py
│   ├── analyze.py             # POST /analyze (stub ou LLM)
│   └── health.py              # GET /healthz
└── services/
    ├── __init__.py
    ├── pii_shield.py          # Regex PII redaction
    ├── stub_analyzer.py       # Análise heurística determinística
    └── llm_analyzer.py        # Pipeline LLM (provider agnostic)
```

---

## Testes

| Arquivo | Descrição |
|---|---|
| `test_health.py` | Health endpoint. |
| `test_pii_shield.py` | Redação de PII (email, phone, cpf, cnpj). |
| `test_analyze_stub.py` | Análise stub com dados sintéticos. |
| `test_llm_analyzer.py` | Pipeline LLM com mock (prompt loading, validação, context injection, fallback JSON mode). |

Stub é o default em CI; nenhum teste depende de chave externa.

---

## Stories Atendidas

| Story | Status |
|---|---|
| US11 — Resumo automático da reunião | Implementado (LLM + stub). |
| US12 — Extração de tarefas e decisões | Implementado (LLM + stub). |
| US13 — Identificação de participantes | Implementado (campo `participants`). |
| US14 — Contexto da empresa no processamento | Implementado (tenant context injection no prompt). |

---

## Próximos Passos (não implementados nesta branch)

1. **Embeddings / RAG** (US15) — recuperar contexto relevante via Azure AI Search.
2. **PII Shield com LLM** — fallback para nomes próprios complexos.
3. **Retry/backoff** no `LlmClient` para falhas transitórias.
4. **Streaming** de resposta para reuniões longas.
5. **Health Score temporal** — scoring por tenant ao longo de múltiplas reuniões.
6. **Integração backend** — chamada do worker a partir do upload de transcrição.

---

## Comandos Úteis

```bash
cd services/nlp-worker
python -m venv .venv && source .venv/bin/activate
pip install -e ".[dev]"

# Testes
python -m pytest tests/ -v

# Lint + format
ruff check src/ tests/
ruff format --check src/ tests/

# Rodar localmente (stub)
USE_LLM_STUB=true python -m nora_nlp.main

# Rodar localmente (LLM real, OpenAI direto)
USE_LLM_STUB=false LLM_API_KEY=sk-... python -m nora_nlp.main
```
