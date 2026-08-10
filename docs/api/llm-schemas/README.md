# JSON Schemas — Structured LLM Output

These schemas are the **source of truth** for the formats the NLP Worker requires from the LLM.

- **Standard**: Draft 2020-12.
- **Usage**: pass as `response_format: { type: "json_schema", json_schema: ... }` in the call to the LLM provider (provider-agnostic — default is OpenAI directly with `gpt-4o-mini`; Azure OpenAI in Enterprise. See ADR 0004).
- **Validation**: Pydantic (Python) and Bean Validation (Java) must mirror these schemas.
- **Versioning**: breaking changes increment the suffix of the file name (`-v2`).

> **Fidelity caveat (2026-05-21):** `meeting-analysis-v1.schema.json` is the documentary contract, but the worker emits via `build_json_schema_for_analysis()` (`services/nlp-worker/src/nora_nlp/clients/llm.py`) and validates via the Pydantic `MeetingAnalysisV1` (`models.py`). The `customerConfidence` field is present in the schema, **is** emitted by the worker and persisted (see ADR 0015); `participants` and `baselineTerms` are emitted by the worker and were added to the schema in this reconciliation. Changing a field here requires synchronizing Pydantic + the inline builder.

| File | Content |
|---|---|
| `meeting-analysis-v1.schema.json` | Complete output of the main meeting analysis prompt |
| `pii-redaction-v1.schema.json` | Result of the PII shield before sending to the LLM |
| `tenant-context-embedding-v1.schema.json` | Indexable chunk of the tenant's context for RAG |

## Rules

1. **No optional fields without a clear default value.** If the AI does not know, it returns `null` or an empty array.
2. **Every source quote is mandatory** for actionable items (action items, risks, opportunities).
3. **Confidences are `0.0–1.0`**, never percentages.
4. **Closed enums.** Free-form categories only inside `topics`.
5. **Markdown only in the `summary` field** (rendered on the web via `react-markdown`). All other text fields are plain text, without markdown.
