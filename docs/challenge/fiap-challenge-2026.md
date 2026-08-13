# FIAP Challenge 2026 — NORA × TOTVS Partnership

## Context

NORA is a project for the Software Engineering course at FIAP, submitted to the **FIAP Challenge 2026** in partnership with TOTVS S.A.

It is built to work as a real product rather than only as an academic deliverable, so the FIAP rubric is one of several commitments it has to satisfy — alongside a possible commercial delivery through TOTVS (Plan A) and eventual operation as an independent SaaS (Plan B).

This page documents:

1. How NORA meets the FIAP Challenge 2026 rubric
2. Deadlines and target deliverables
3. Where each piece of the rubric is documented in the repository

## Academic rubric

> **NOTE:** items marked `?? unverified` need to be validated against the official rubric published by FIAP. This document was written based on previous FIAP Challenge deliverables; it may need specific adjustments for the 2026 edition.

### Expected academic deliverables

| Rubric item | Where it is in NORA |
|---|---|
| **Personas and empathy map** | [`personas-and-empathy-map.md`](personas-and-empathy-map.md) — 3 personas (Lucas Almeida, Camila Souza, Rafael Costa) |
| **Use case diagram (UML)** | [`use-case-diagram.md`](use-case-diagram.md) — mermaid with 20+ use cases |
| **Prioritized backlog (MoSCoW)** | [`../product/backlog.md`](../product/backlog.md) — US01-US51 with real DONE/PARTIAL/MISSING status |
| **Relational data model (Postgres)** | [`../engineering/data-model.md`](../engineering/data-model.md) — relational schema + applied Flyway migrations (canonical source for the migration set) |
| **Oracle data model (DB deliverable)** | [`../engineering/data-model-oracle.md`](../engineering/data-model-oracle.md) — Oracle 19c+ DDL equivalent to the Postgres schema |
| **Technical architecture (diagrams, flows)** | [`../engineering/architecture.md`](../engineering/architecture.md) — DDD layers, IAM flow, RAG pipeline, multi-tenancy |
| **Documented architectural decisions** | [`../adr/README.md`](../adr/README.md) — canonical ADR index (durable decisions with context + alternatives) |
| **Technical validation (tests)** | Real measured test coverage (worker 87%, backend 67%) — see ADR 0018 |
| **Functional demonstration (deploy)** | NORA runs self-hosted on a single bare-metal host (ADR 0034/0036), behind Cloudflare Tunnel at `nora.systems`. The Azure deployment this rubric item originally pointed at is gone — no subscription, no export (ADR 0036) |
| **Pitch / final presentation** | Sub-phase 1.11 creates a 15-20 min demo script |

### Technical differentiators (above the rubric minimum)

NORA delivers elements that go beyond the typical academic rubric:

- **AWS-style IAM** (ADR 0007) — an authorization model of Root + Users + Groups + Policies with Effect/Action/Resource/Condition, built in-house. Policy versioning + audit trail
- **BR-aware PII Shield** (ADR 0012) — redaction of EMAIL, CPF, CNPJ, PHONE, CREDIT_CARD, PERSON_NAME (BR list of ~270 names) before LLM calls. LGPD compliance by design
- **Provider-agnostic LLM** (ADR 0004) — an abstraction that allows switching from OpenAI direct → Azure OpenAI → local Whisper without changing the pipeline
- **Mandatory strict JSON Schema** (ADR 0003) — LLM output validated server-side, no free-form text cross-service
- **Multi-tenancy** (ADR 0002) — application filter in the MVP + Postgres RLS with the schema delivered and auth-aware scope; the operational cutover/enforcement in prod remains (ADR 0026/0028)
- **Opt-in Productivity Score** (ADR 0005) — analysis of the meeting's productivity against the declared goal, with the mandatory disclaimer "an indicator of the meeting, not of the participants"
- **Customer Confidence** (ADR 0006) — score per meeting with buying signals + objections, delivered full-stack with an authoritative per-account trend (PR #148)
- **Production-grade self-hosted deploy** (ADR 0034/0036) — pull-based rollout whose deploy path opens no inbound port (the machine's own sshd is a separate matter, and is open), secrets encrypted with SOPS + age, self-hosting pitfalls catalogued in `docs/operations/host-deploy.md`. Rolling forward is still a manual `deploy.sh --tag` — the release pointer is published but nothing on the host consumes it. The earlier Azure deployment (8 Azure for Students pitfalls, OIDC workflow, 14 resources via Bicep IaC) is gone — no subscription, no export
- **Test coverage on the two areas CI actually gates** (ADR 0018) — a JaCoCo rule over `PolicyEvaluator` (instruction >= 90%, branch >= 75%) and `--cov-fail-under=90` over the PII shield. ADR 0018's ">85% across IAM, Auth and PII" is the aspiration; those two gates are what blocks a merge, and `apps/web` has no test suite
- **AGPL-3.0 License** (ADR 0017) — protection against clone-and-compete

## Deadlines

| Milestone | Date | Status |
|---|---|---|
| Data modeling deliverable (Oracle) | ?? unverified | Material in `../engineering/data-model-oracle.md` |
| Partial presentation (sprint review) | ?? unverified | — |
| **FIAP Pitch / NEXT 2026** | **2026-06-15** | **Sub-phase 1.11 (Demo Polish) delivers the material** |
| Final FIAP delivery | ?? unverified | Sub-phases 1.11 + 1.12 cover it |

## Who works on this

- **@sf0rzin** — maintainer of the repository: product, backend, web, infrastructure and operations. `github.com/sf0rzin/nora`.
- **@pollotherunner** — collaborator on the Desktop app (Tauri). Isolated scope, outside the SaaS core.

Everyone who contributed is listed in the git history.

## Why NORA is more than an academic assignment

Three scenarios for NORA after the pitch:

- **Plan A** — TOTVS hires after seeing NORA in the demo (FIAP × TOTVS partnership, NORA goes from portfolio to a concrete hiring/institutional partnership offer)
- **Plan B** — commercial SaaS operated independently (long term, with a business co-founder if necessary)
- **Plan C** — technical portfolio / professional positioning of the members (the material already exists now, ready for publication)

The FIAP rubric is the **visible academic layer**; the commercial product runs in parallel as real code, deployed, monetizable.

## Next steps before the pitch (15/06)

- **Sub-phase 1.11 — Demo Polish Plan A** (2-3 agentic weeks): polish the internal UX (dashboard, meeting detail, tasks, settings) + realistic synthetic TOTVS seed + recorded demo script. Items previously listed here have already been delivered: Customer Confidence full-stack (PR #148), removal of AUTH_FILTER_HARD_CAP (batch scanning in `MeetingService.listAllForAuthFilter`) and the PolicyEvaluator operators (StringEquals, StringIn, StringLike, DateGreaterThan, DateLessThan, fail-closed)
- **Sub-phase 1.12 — Production Hardening** (if there is time left before the pitch): a separate rg-nora-prod, monitoring alerts, secrets rotation, RLS cutover/enforcement in prod. Operational LGPD is already delivered (ADR 0029: `DELETE /privacy/meetings/{id}` + scheduled RetentionSweeper). **It can be left until after the pitch without harming the demo.**

## History

| Date | Change |
|---|---|
| 2026-05-14 | Doc created in Sub-phase 1.10 (Docs Refresh) consolidating the FIAP × TOTVS framing |
| 2026-06-06 | Doc x code reconciliation + standardization |
