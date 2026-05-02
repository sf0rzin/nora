# GitHub Copilot Instructions — NORA

Use these instructions for all coding work in this repository.

## Project Context

NORA is a production-minded FIAP Challenge 2026 project: a conversational intelligence SaaS that transforms meeting transcripts into summaries, decisions, action items and business insights using tenant-specific product/company context.

Always read and respect:

- `CLAUDE.md`
- `docs/PROJECT.md`
- `docs/development-standards.md`
- `docs/backlog-mvp.md`

## MVP Boundary

Prioritize Web + Backend + NLP Worker. The first real product slice is text transcript upload, tenant context, structured NLP analysis, dashboard, meeting detail and scoped Enterprise access.

Treat Desktop real-time capture, SSO, full MCP integrations, audio/video upload and Salesforce/HubSpot as post-MVP unless the user explicitly asks otherwise.

## Engineering Rules

- Keep changes scoped and consistent with existing patterns.
- Use DDD boundaries in the Java backend.
- Do not put business rules in controllers or frontend-only checks.
- Every tenant-owned entity must include `tenant_id` and authorization must be enforced server-side.
- Use validated structured outputs for LLM/NLP results.
- Never commit secrets or real credentials.
- Add tests for meaningful behavior changes.
- Prefer clear, explicit code over clever abstractions.

## Documentation Rules

- Product decisions go in `docs/PROJECT.md`.
- Engineering standards go in `docs/development-standards.md`.
- Durable architecture decisions go in `docs/adr/` when that folder exists.
- Backlog/user-story changes go in `docs/backlog-mvp.md`.
- Prompt templates belong inside the NLP worker source tree, not scattered in docs.