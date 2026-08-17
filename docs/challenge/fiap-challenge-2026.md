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
| **Technical validation (tests)** | Coverage measured by CI on every run, not quoted from a snapshot: worker **92.4%** over `nora_nlp` (863 tests), backend **77.1-77.3%** instruction / **61.5-61.6%** branch (578 tests), `apps/web` **6.2%** statement whole-app (104 tests; a unit suite over five `src/lib` modules, no page or component) — measured 2026-08-17, see the "Measured coverage" section below |
| **Functional demonstration (deploy)** | NORA runs self-hosted on a single bare-metal host (ADR 0034/0036), behind Cloudflare Tunnel at `nora.systems`. The Azure deployment this rubric item originally pointed at is gone — no subscription, no export (ADR 0036) |
| **Pitch / final presentation** | [`demo-script.md`](demo-script.md) — block-by-block script with a plan B per block, paired with the seed in `scripts/seed-demo.sh`. **The running time is declared there and nowhere else**, because this page and the roadmap used to carry two different numbers for a script that did not exist |

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
- **Test coverage on the three areas CI actually gates** (ADR 0018, ADR 0042) — a JaCoCo rule over `PolicyEvaluator` (instruction >= 90%, branch >= 75%), `--cov-fail-under=90` over the PII shield, and per-module coverage floors on four `apps/web/src/lib` modules. ADR 0018's ">85% across IAM, Auth and PII" is the aspiration; those three gates are what blocks a merge. See "Measured coverage" below for what the rest of the code actually measures
- **AGPL-3.0 License** (ADR 0017) — protection against clone-and-compete

### Measured coverage

Two different things get confused whenever this project quotes a coverage number, and this section separates them because the rubric line is graded on the first while the engineering claim rests on the second.

**What is measured** — every CI run. `scripts/report-coverage.sh` reads the report the test run just wrote (JaCoCo's CSV, coverage.py's data file, Vitest's coverage summary) and prints it to the job log and to the run summary page. It measures nothing itself, so a figure read in CI and a figure read on a workstation come from one implementation.

| Scope | Measured 2026-08-17 | How |
|---|---|---|
| Spring backend, overall | **77.1-77.3%** instruction · 61.5-61.6% branch · 78.0-78.1% line (578 tests) | `mvn -B verify` → JaCoCo |
| ↳ *why that row is a range* | three runs of the same branch gave 77.1%, 77.2% and 77.3%; the per-area rows below did not move at all | the aggregate is good to about ±0.1 point |
| Backend IAM packages (`*.iam`) | 90.9% instruction · 80.4% branch | idem |
| Backend Auth packages (`*.identity`, `*.security`) | 93.8% instruction · 72.8% branch | idem |
| `PolicyEvaluator` — the one gated class | 96.3% instruction · 86.0% branch | idem |
| NLP worker, whole package | **92.4%** statement (863 tests) | `pytest --cov=nora_nlp` |
| Worker PII shield — the one gated module | 96.6% statement | idem |
| `apps/web`, whole app | **6.2%** statement · 5.2% branch · 6.0% line (104 tests) | `npm run test:coverage` → Vitest + v8 |
| ↳ *why that row is so low* | the unit suite covers five `src/lib` modules; **no page and no component has a unit test** | the three Playwright e2e specs exercise routing, headers and CSP, and are not counted here |
| `apps/web` gated modules | `redact.ts` 96.6% · `markdown.ts` 97.7% · `tasks-export.ts` 100% · `password-policy.ts` 100% statement | idem |
| `apps/web/src/lib/api/client.ts` | 36.5% statement — reported, deliberately not gated | one `request()` plus 66 one-line wrappers; the percentage counts wrappers |

**What is gated** — three narrow rules, and only three. A regression anywhere outside them fails nothing:

- `services/api/pom.xml` — a JaCoCo rule over the single class `PolicyEvaluator` (instruction >= 90%, branch >= 75%), `haltOnFailure`
- `.github/workflows/ci.yml` — `pytest --cov=nora_nlp.services.pii_shield --cov-fail-under=90` over that one module
- `apps/web/vitest.config.mts` — per-module `coverage.thresholds` over `redact.ts`, `markdown.ts`, `tasks-export.ts` and `password-policy.ts`, applied by the `web` job's test run (ADR 0042). Each is a **floor below the measured rate**, so it fires on a regression rather than certifying a level

The table above is a report, not a threshold. Making it one would mean picking a global minimum, which ADR 0018 considered and rejected on the grounds that forcing a number on boilerplate produces valueless tests.

**Why the date matters.** Until 2026-08-17 this document quoted "worker 87%, backend 67%" as *real measured* coverage. Those figures were measured on 2026-05-13 and never re-taken, across roughly seventy merged pull requests — and the same document, three sections down, correctly described the two narrow gates. It contradicted itself. The fix was not a better one-off measurement but a measurement that happens on every run, which is why the row above says "measured" and names the day it last ran rather than a day in May.

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

- **Sub-phase 1.11 — Demo Polish Plan A** (2-3 agentic weeks): what remains is polishing the internal UX (dashboard, meeting detail, tasks, settings), which is item (d) and is subjective by construction. Everything else is delivered: Customer Confidence full-stack (PR #148), removal of AUTH_FILTER_HARD_CAP (batch scanning in `MeetingService.listAllForAuthFilter`), the PolicyEvaluator operators (StringEquals, StringIn, StringLike, DateGreaterThan, DateLessThan, fail-closed), the demonstration seed (`scripts/seed-demo.sh` plus the `meridian-erp` material in `data/synthetic/`) and the demo script ([`demo-script.md`](demo-script.md)). The seed's vendor is fictional rather than TOTVS, for the reasons recorded in `data/synthetic/README.md`
- **Sub-phase 1.12 — Production Hardening** (if there is time left before the pitch): a separate rg-nora-prod, monitoring alerts, secrets rotation, RLS cutover/enforcement in prod. Operational LGPD is already delivered (ADR 0029: `DELETE /privacy/meetings/{id}` + scheduled RetentionSweeper). **It can be left until after the pitch without harming the demo.**

## History

| Date | Change |
|---|---|
| 2026-05-14 | Doc created in Sub-phase 1.10 (Docs Refresh) consolidating the FIAP × TOTVS framing |
| 2026-06-06 | Doc x code reconciliation + standardization |
| 2026-08-17 | Coverage re-measured in CI and the document's contradiction about it resolved: the rubric row no longer calls a figure from May "real measured", and a "Measured coverage" section now separates what is measured from what is gated |
| 2026-08-17 | The pitch row of the rubric table and the 1.11 line point at `demo-script.md` instead of restating a duration. The running time this page used to assert is gone: it disagreed with the one in the roadmap, and neither described a script that existed |
