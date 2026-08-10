# 0003 — LLM output via mandatory JSON Schema

- Status: accepted
- Date: 2026-05-02

## Context

NORA depends on stable LLM outputs to feed the UI, dashboards, action items and auditing. Free text breaks the application, makes testing harder and prevents the prompt from evolving without impacting downstream consumption.

Azure OpenAI supports `response_format: { type: "json_schema" }` on GPT-4o and similar models.

## Decision

Every NLP worker call that produces structured data consumed by the application **must**:

1. Define a versioned JSON Schema in `docs/api/llm-schemas/` (the source of truth) and mirror it as a Pydantic model in the worker.
2. Send the schema to the API with `response_format=json_schema` (strict mode when available).
3. Validate the response with Pydantic before returning it to the backend. If invalid, **reject** (HTTP 502 from the internal call) with limited retries and structured logging.
4. Version prompts tied to the schema (`promptVersion`, `modelVersion` recorded in `meeting_analyses`).
5. Schema breaks increment the version (`-v2`); the old version stays supported for at least one release to allow idempotent reprocessing.

The Java backend validates the payload again at the HTTP boundary via Bean Validation/DTOs generated from the same OpenAPI/Schema, avoiding trust in an internal service.

## Consequences

- The UI never receives an unknown field without warning.
- Worker tests can use transcript fixtures → validated JSON.
- A model change (e.g. GPT-4o → GPT-5) is swapped via configuration; the schema keeps stability.
- Minimal extra prompt cost (the schema instruction fits in the system message).
- Validation failures generate retries — watch the cost if the LLM starts erring frequently (alarm).

## Alternatives Considered

- **Free text + regex parsing.** Rejected: fragile and hostile to maintenance.
- **Function calling (tools).** Acceptable, but more verbose for a single output. We keep it as a future option for flows with multiple calls/tools.
- **Relying on "JSON mode" without a schema.** Rejected: it guarantees syntactic JSON, but not the shape. A strict schema prevents missing fields and invalid enums.
