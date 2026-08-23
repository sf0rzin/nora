# Prompts — NORA NLP Worker

Each `.md` file here is a versioned prompt. A behaviour change requires a new version (e.g. `meeting-analysis-v2.md`).

## Conventions

- The **system prompt** defines the identity and the inviolable rules.
- The **user prompt** carries the tenant context + the transcript.
- The **schema** is referenced in `docs/api/llm-schemas/` and sent as `response_format=json_schema` in the call to the LLM provider (default OpenAI; see ADR 0004).
- Variables use `{{snake_case}}` so they can be rendered via Jinja2/string.format.
- The output is always validated with Pydantic before being returned to the backend.

## Current version

Every prompt in this table is loaded by a `load_prompt` call in the worker, and the table holds
nothing else. `pii-shield-v1.md` was listed here as a current version for as long as it existed
and no line of Python ever loaded it — see the note below.

| Prompt | Version | Loaded by | Schema |
|---|---|---|---|
| Meeting Analysis | v1 | `llm_analyzer.analyze` | `meeting-analysis-v1.schema.json` |
| Live Highlights | v1 | `live_analyzer.analyze` | inline in `live_analyzer._build_json_schema_for_live` |
| Meeting Split | v1 | `split_analyzer.analyze` | inline in `split_analyzer._build_json_schema_for_split` |

## Removed: `pii-shield-v1.md`

It was a prompt asking the model to redact the PII a regex missed, and nothing loaded it. Listed
as a current version here and described in `../../../CONTEXT.md` as the fallback for complex PII,
it read as a safety net for exactly the gap the shield's corpus records as open — off-list proper
names — while being inert.

It is deleted rather than wired up, and that is the substantive part. Sending the transcript to
the provider so the provider can tell us which parts are personal data inverts the premise the
shield exists for: ADR 0012 puts the shield at the last gate BEFORE any provider call, and this
prompt could only work by crossing that gate with the raw text. A backstop for off-list names has
to run locally, which is what ADR 0012 defers to the NER work.

`docs/api/llm-schemas/pii-redaction-v1.schema.json` is the response schema this prompt would have
used and is now unreferenced.
