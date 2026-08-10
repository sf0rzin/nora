# CLAUDE.md — NORA

This file is the main project context for Claude Code and similar AI coding agents. Read it before making code changes.

## Project

NORA (Negotiation Observability & Revenue Assistant) is a SaaS conversational intelligence platform for meetings.

**Core promise:** transform meeting transcripts into summaries, decisions, action items and business intelligence using the customer's own company/product context.

**Primary goal:** strong FIAP Challenge 2026 / NEXT 2026 project that doubles as a production-ready commercial SaaS.

## Read First (in this order)

> **NOTE:** the `docs/` structure was reorganized in Sub-phase 1.10 (2026-05-14). Old paths (`docs/PROJECT.md`, `docs/development-standards.md`, etc.) **no longer exist**.

1. **`docs/product/vision.md`** — product and boundaries
2. **`docs/product/roadmap.md`** — prioritized backlog + sub-phase history + what's ahead
3. **`docs/engineering/architecture.md`** — end-to-end flows + stack rationale + DDD layers
4. **`docs/engineering/standards.md`** — code and PR conventions
5. **`docs/adr/`** — durable architectural decisions (see `docs/adr/README.md` for the canonical ADR index)
6. **`docs/product/glossary.md`** — NORA terms (Productivity Score, Customer Confidence, etc.)

For operational context (self-hosted deploy, runbooks):

7. **`docs/operations/proxmox-deploy.md`** — runbook for deploying on the Proxmox VM + the 9 self-hosting pitfalls (**replaces `azure-deploy.md`**)
8. **`docs/operations/azure-decommission.md`** — safe shutdown order for Azure (data rescue → DNS → RG deletion)
9. **`docs/operations/production-readiness-gaps.md`** — prod-readiness gaps (those anchored in Azure were partially superseded by ADR 0034)
10. **`docs/operations/azure-deploy.md`** — **historical.** Runbook from the Azure era + the 8 Azure for Students pitfalls. Do not operate from it

For academic context (FIAP Challenge):

11. **`docs/challenge/fiap-challenge-2026.md`** — FIAP context, rubric, deadlines
12. **`docs/challenge/personas-and-empathy-map.md`** — 3 personas + empathy map
13. **`docs/challenge/use-case-diagram.md`** — UML use cases

## Current scope

NORA is **migrating off Azure to a self-hosted Proxmox VM** (ADR 0034, 2026-08-07).
Production on Azure went **down** (522 on `nora.systems` / `api.nora.systems`; the Azure
for Students subscription was most likely deactivated). Rescuing the Postgres data is the
top priority — see `docs/operations/azure-decommission.md`. Stack:

- **Web + Backend + NLP Worker + Desktop** vertical slice all functional
- **Backend** is Spring Boot 3 (Java 21) + Postgres 16 (`pgvector/pgvector:pg16` container) + Flyway, with **IAM AWS-style** (Root + Users + Groups + Policies) and **multi-tenancy** via `tenant_id` filter (ADR 0002) + RLS (ADR 0026/0028, **three** roles: `nora_app`, `nora_telemetry`, admin/owner)
- **NLP Worker** is FastAPI (Python 3.12) with **PII Shield** (PERSON_NAME + EMAIL + CPF + CNPJ + PHONE + CREDIT_CARD per ADR 0012) and **JSON Schema strict** LLM output (ADR 0003) via **provider-agnostic client** (ADR 0004, default OpenAI `gpt-4o-mini`)
- **Web** is Next.js 14 + TypeScript + **Tailwind cru, no shadcn** (ADR 0013) with editorial palette OKLCH + Inter + Instrument Serif fonts
- **Desktop** is Tauri 2 + Rust with **Whisper STT running on-device** (ADR 0035 — the Python sidecar and the Azure Speech token broker are both removed) — maintained by @pollotherunner
- **Infra** is `infra/proxmox/docker-compose.yml` (compose project `nora`) on a single Debian VM: **Cloudflare Tunnel as the only ingress** (no inbound port), Caddy routing by Host, secrets in **SOPS + age**, observability via OTel Collector + Prometheus + Loki + Grafana. **Deploy is PULL** (`deploy-proxmox.yml` publishes an immutable release pointer; the host pulls) — never push, because the repo is public (ADR 0017)
- `infra/bicep/` is **legacy** — the Azure infra it describes is being torn down

For up-to-date status of each backlog story, see `docs/product/backlog.md` (DONE / PARTIAL / MISSING per US).

## Stack (verified versions)

| Component | Version |
|---|---|
| Java | 21 |
| Spring Boot | 3.3.5 |
| Postgres | 16 (`pgvector/pgvector:pg16`; pgvector extension **available but not created** — see ADR 0034 §excluded scope) |
| Flyway | inherited from Spring Boot 3.3.5 |
| Python (worker) | >= 3.12 |
| FastAPI | >= 0.115 |
| Pydantic | >= 2.9 |
| OpenAI SDK | >= 1.50 |
| Next.js | 14.2.15 |
| TypeScript | ^5.6 |
| Tailwind CSS | ^3.4 |
| Tauri (desktop) | 2 (on-device STT via `whisper-rs` — ADR 0035) |
| Orchestration | Docker Compose (project `nora`, `infra/proxmox/docker-compose.yml`) |
| Ingress | Cloudflare Tunnel (`cloudflared`) + Caddy 2.8 |
| Secrets | SOPS + age (`secrets.env.sops`; private key only on the host) |
| Observability | OTel Collector 0.115 + Prometheus 3.1 (30d) + Loki 3.3 + Alloy 1.7 + Grafana 11.5 |
| Bicep | **legacy** — `infra/bicep/` describes the Azure infra being shut down |

See `docs/engineering/architecture.md` §1 for the full table with where to verify each version.

## Non-Negotiables (inviolable rules)

- **Tenant isolation**: `tenant_id` in every tenant-owned table. Filter in the backend, never only in the frontend. ADR 0002
- **PII redaction**: PII never reaches the LLM raw. PIIShield in the worker is the last gate. ADR 0012
- **JSON Schema strict** on LLM output: `response_format=json_schema` (ADR 0003). Pydantic validation in the worker
- **Provider-agnostic LLM** (ADR 0004): default is OpenAI direct, Azure OpenAI in the future
- **DDD layers in the backend**: `domain` does not know Spring/HTTP/SDK. `application` orchestrates. `infrastructure` adapts. `api` is thin
- **No hardcoded TOTVS** in product code. Tenant context is configurable
- **ADRs are immutable** once accepted. Decision obsolete? Create a successor ADR (see `docs/adr/README.md`)
- **Defer scope creep**: ADR 0014 declares v1 closed. 13 US explicitly deferred (+ US48/US49 addressed via ADR 0015) — no new scope added until the FIAP pitch (2026-06-15)
- **Tests**: critical areas (IAM, Auth, PII) >85% coverage sustained (ADR 0018)
- **Do not commit secrets**. Use `.env.example` for variable names

## How we work

- **Implement one sub-phase or story per branch.** Naming: `feat/sub-X.Y-<slug>` or `feat/usZZ-<slug>` or `fix/<slug>` or `docs/<slug>`
- **Commit messages in English** — subject and body — keeping Conventional Commits: `type(scope): subject (#PR)`. This applies to humans and agents. Discussion, issues and PR descriptions remain free to be in Portuguese; the rule covers only the commit text. History prior to 2026-08-09 is mixed and stays as it is — do not rewrite it
- **Reference IDs** (US##, Sub-phase 1.X, ADR NNNN, PR #) in commits and PR descriptions
- **Before editing**, inspect the existing patterns in the target module (Grep/Glob)
- **After editing**, run the smallest relevant verification command (`mvn test`, `pytest`, `npm run typecheck`, `docker compose -f infra/proxmox/docker-compose.yml config`) and report pass/fail
- **Update the docs** when code diverges: docs are part of the product, not an accessory

## Working with subagents

For large tasks, split the work into slices that can be implemented independently, dispatch each with a self-contained brief (`Agent` tool), and review the resulting diff rather than the summary. Record a durable decision as an ADR if one is missing.

Use Opus models for architecture, data modeling, security review and refactors. Use Sonnet models or subagents for focused implementation, tests, UI components and mechanical CRUD flows.

## Change history of this file

| Date | Change |
|---|---|
| 2026-08-10 | Documentation honesty pass: metadata frontmatter, invented owners/roles and decoration removed; stack versions re-verified against the manifests; superseded run brief and pre-presentation audit deleted |
| 2026-08-07 | Azure → Proxmox migration (ADR 0034) and local STT (ADR 0035): "Current scope", the Stack table and the `docs/operations/` pointers updated. `azure-deploy.md` becomes historical; `proxmox-deploy.md` and `azure-decommission.md` take its place |
| 2026-06-06 | Doc × code reconciliation + standardization |
| 2026-05-14 | Rewritten during Sub-phase 1.10 (Docs Refresh): new `docs/` structure in subfolders (product/engineering/operations/challenge/security), updated references, new ADRs linked |
| (earlier) 2026-05-02+ | Original version created with the initial scaffolding |
