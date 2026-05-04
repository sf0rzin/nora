# CLAUDE.md — NORA

This file is the main project context for Claude Code and similar AI coding agents. Read it before making code changes.

## Project

NORA (Negotiation Observability & Revenue Assistant) is a SaaS conversational intelligence platform for meetings.

Core promise: transform meeting transcripts into summaries, decisions, action items and business intelligence using the customer's own company/product context.

Primary goal: build a strong FIAP Challenge 2026 / NEXT 2026 project without throwing away production-quality architecture.

## Read First

Before implementing any feature, read these files in order:

1. `docs/PROJECT.md` — product, architecture and roadmap
2. `docs/development-standards.md` — engineering rules and repo conventions
3. `docs/backlog-mvp.md` — prioritized MVP backlog and story IDs
4. `docs/visao-do-produto.md` — product boundaries and non-goals
5. `docs/plano-de-execucao.md` — execution plan and parallel work split

## Current MVP Scope

Build the Web + Backend + NLP Worker vertical slice first:

- Login with e-mail/password and JWT
- Basic tenant model
- Text transcript upload (`.txt`, `.vtt`, `.srt`)
- Product/company context configuration per tenant
- NLP/LLM processing: summary, decisions, tasks, risks/opportunities
- Dashboard of meetings
- Meeting detail page
- Basic task list
- Enterprise access control with fixed roles and scoped visibility

Post-MVP / roadmap:

- Desktop app with Tauri/WASAPI real-time capture
- SSO with Entra ID/SAML
- Audio/video upload with Azure AI Speech
- Full MCP integrations
- Salesforce/HubSpot native integration
- Custom roles

## Stack

- Frontend: Next.js + TypeScript + Tailwind + shadcn/ui
- Backend: Java 21 + Spring Boot 3 + DDD + OpenAPI
- NLP Worker: Python 3.12 + FastAPI + Pydantic
- Database: Postgres 16 + Flyway migrations; Oracle model remains for academic delivery
- AI: Provider agnóstico via API Chat Completions (default OpenAI `gpt-4o-mini` no MVP; Azure OpenAI em Enterprise). Ver `docs/adr/0004-llm-provider-strategy.md`. Saída obrigatória via JSON Schema (ADR 0003).
- Search/RAG: Azure AI Search in production, local stub acceptable during MVP
- Cloud: Azure
- CI/CD: GitHub Actions

## Non-Negotiables

- Never bypass tenant isolation. Every tenant-owned table must carry `tenant_id`.
- Never rely only on frontend filtering for authorization.
- Never send raw PII to an LLM before redaction.
- Never hardcode TOTVS-specific knowledge in product logic. Tenant context must be configurable.
- Keep Desktop, SSO, full MCP and Salesforce as post-MVP unless explicitly requested.
- Keep code changes scoped to the story/issue being implemented.
- Do not commit secrets. Use `.env.example` for variable names only.
- Add or update tests for every meaningful behavior change.

## Architecture Rules

- Backend follows DDD layers: `domain`, `application`, `infrastructure`, `api`.
- Controllers are thin. Business rules belong in application/domain services.
- Domain objects must not depend on Spring, database, HTTP or external SDKs.
- Python worker returns validated structured JSON. No free-form LLM output should cross service boundaries.
- Frontend uses typed API clients and schema validation at boundaries.
- Shared contracts should be documented through OpenAPI and example JSON payloads.

## How To Work

- Implement one backlog story or technical task per branch.
- Use branch names like `feat/us11-meeting-summary`, `fix/tenant-scope`, `docs/standards`.
- Reference backlog IDs in commits and PRs when possible.
- Before editing, inspect existing patterns in the target module.
- After editing, run the smallest relevant verification command and report what passed/failed.

## AI Collaboration Pattern

For large tasks, split prompts into small implementation units:

1. Define the contract or data model.
2. Implement the smallest backend/worker/frontend slice.
3. Add tests.
4. Run verification.
5. Review security and tenant isolation.

Use Opus-style models for architecture, data model, security review and hard refactors. Use Sonnet-style models for focused implementation, tests, UI components and CRUD flows.