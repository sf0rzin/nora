# GitHub Copilot Instructions — NORA

Use these instructions for all coding work in this repository.

## Project Context

NORA is a production-minded FIAP Challenge 2026 project: a conversational intelligence SaaS that transforms meeting transcripts into summaries, decisions, action items and business insights using tenant-specific product/company context.

Always read and respect (new structure after Sub-phase 1.10):

- `AGENTS.md`
- `docs/product/vision.md` (product + boundaries)
- `docs/product/backlog.md` (US tracking + MoSCoW)
- `docs/engineering/architecture.md` (end-to-end architecture)
- `docs/engineering/standards.md` (code conventions)
- `docs/adr/` (durable decisions — immutable)

## Scope Boundary

Five surfaces are functional: `apps/web`, `apps/admin` (operator console), `apps/desktop` (Windows-only), `services/api` and `services/nlp-worker`.

**Scope is declared, not open, and this file does not hold the list.** What is closed, what came back into scope and what is a declared deferral are enumerated in **ADR 0038 §4/§5/§6** and summarised in `AGENTS.md`. Anything in none of those three lists needs a successor ADR before it is built.

Two corrections to what this section said until 2026-08-17, because both would have sent an agent the wrong way:

- **SSO (US05) is WONT**, closed by ADR 0038 §4 — not "post-MVP".
- **MCP is built, inbound and read-only.** ADR 0041's MCP server is an inbound adapter inside `services/api` (`api/mcp/`, `POST /mcp`, credential in migration V029): five read tools, each resolving a real IAM principal and evaluating the same actions the web surface does. The OAuth integrations (ADR 0031) are the **outbound** direction and are not MCP. Write tools are out of scope until somebody decides which IAM actions an agent may exercise unattended.

Still genuinely out of scope: audio/video upload (`POST /meetings` takes a transcript file), and a native Salesforce/HubSpot integration, which was never tracked as a user story.

## Engineering Rules

- Keep changes scoped and consistent with existing patterns.
- Use DDD boundaries in the Java backend.
- Do not put business rules in controllers or frontend-only checks.
- Every tenant-owned entity must include `tenant_id` and authorization must be enforced server-side.
- Use validated structured outputs for LLM/NLP results.
- Never commit secrets or real credentials.
- Add tests for meaningful behavior changes.
- Prefer clear, explicit code over clever abstractions.
- Write commit messages in English — subject and body — keeping Conventional Commits
  (`type(scope): subject (#PR)`). Issues, PR descriptions and review threads may stay in
  Portuguese; the rule covers commit text only.
- **Write the PR title in English too.** `main` squashes and takes the commit subject from the
  PR title, so a Portuguese title lands a Portuguese commit. The `pr-title` job in `ci.yml`
  checks this (`scripts/check-pr-title.sh`): Portuguese, Conventional Commits shape, length.

## Documentation Rules

- Product decisions go in `docs/product/vision.md`.
- Engineering standards go in `docs/engineering/standards.md`.
- Durable architecture decisions go in `docs/adr/` (immutable once accepted — create successor ADR to amend).
- Backlog/user-story changes go in `docs/product/backlog.md`.
- Prompt templates belong inside the NLP worker source tree, not scattered in docs.
