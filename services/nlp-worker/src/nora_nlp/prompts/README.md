# Prompts — NORA NLP Worker

Each `.md` file here is a versioned prompt. A behaviour change requires a new version (e.g. `meeting-analysis-v2.md`).

## Conventions

- The **system prompt** defines the identity and the inviolable rules.
- The **user prompt** carries the tenant context + the transcript.
- The **schema** is referenced in `docs/api/llm-schemas/` and sent as `response_format=json_schema` in the call to the LLM provider (default OpenAI; see ADR 0004).
- Variables use `{{snake_case}}` so they can be rendered via Jinja2/string.format.
- The output is always validated with Pydantic before being returned to the backend.

## Current version

| Prompt | Version | Schema |
|---|---|---|
| Meeting Analysis | v1 | `meeting-analysis-v1.schema.json` |
| PII Shield | v1 | `pii-redaction-v1.schema.json` |
| Live Highlights | v1 | inline in `live_analyzer._build_json_schema_for_live` |
| Meeting Split | v1 | inline in `split_analyzer._build_json_schema_for_split` |
