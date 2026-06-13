# Prompts — NORA NLP Worker

Cada arquivo `.md` aqui é um prompt versionado. Mudança de comportamento exige nova versão (ex.: `meeting-analysis-v2.md`).

## Convenções

- **System prompt** define identidade e regras invioláveis.
- **User prompt** carrega contexto do tenant + transcrição.
- **Schema** é referenciado em `docs/api/llm-schemas/` e enviado como `response_format=json_schema` na chamada ao provedor LLM (default OpenAI; ver ADR 0004).
- Variáveis usam `{{snake_case}}` para serem renderizadas via Jinja2/string.format.
- Saída sempre validada com Pydantic antes de retornar ao backend.

## Versão atual

| Prompt | Versão | Schema |
|---|---|---|
| Meeting Analysis | v1 | `meeting-analysis-v1.schema.json` |
| PII Shield | v1 | `pii-redaction-v1.schema.json` |
| Live Highlights | v1 | inline em `live_analyzer._build_json_schema_for_live` |
| Meeting Split | v1 | inline em `split_analyzer._build_json_schema_for_split` |
