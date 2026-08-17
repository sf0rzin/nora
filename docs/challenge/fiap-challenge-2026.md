# FIAP Challenge 2026 — NORA × TOTVS Partnership

## Context

NORA is a project for the Software Engineering course at FIAP, submitted to the **FIAP Challenge 2026** in partnership with TOTVS S.A.

It is built to work as a real product rather than only as an academic deliverable, so the FIAP rubric is one of several commitments it has to satisfy — alongside a possible commercial delivery through TOTVS (Plan A) and eventual operation as an independent SaaS (Plan B).

This page documents:

1. How NORA meets the FIAP Challenge 2026 rubric
2. Deadlines and target deliverables
3. Where each piece of the rubric is documented in the repository

## Academic rubric

> **NOTE:** this rubric was reconstructed from previous FIAP Challenge editions and has **never been checked against the official 2026 rubric**. It may be wrong about what is required, and the milestones after the 2026-06-15 pitch are unknown. What needs confirming, and from whom, is listed under [Open items to confirm with FIAP](#open-items-to-confirm-with-fiap) — not left as a mark inside a table cell, which is where the previous version of this warning went to be ignored.

### Expected academic deliverables

| Rubric item | Where it is in NORA |
|---|---|
| **Personas and empathy map** | [`personas-and-empathy-map.md`](personas-and-empathy-map.md) — 3 personas (Lucas Almeida, Camila Souza, Rafael Costa) |
| **Use case diagram (UML)** | [`use-case-diagram.md`](use-case-diagram.md) — mermaid with 20+ use cases |
| **Prioritized backlog (MoSCoW)** | [`../product/backlog.md`](../product/backlog.md) — 86 stories with a real status each: 73 DONE, 2 PARTIAL, 7 MISSING, 4 WONT. It said "US01-US51" until 2026-08-17, which undercounted by 35: the rewrite added the stories for surfaces that had shipped and were never recorded |
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
- **BR-aware PII Shield** (ADR 0012, ADR 0043) — redaction of EMAIL, CPF, CNPJ, PHONE, CREDIT_CARD, ADDRESS and PERSON_NAME before LLM calls. What makes it a differentiator is not the type list but that it is **measured against an adversarial corpus**: the leak rate went 9.60% → 2.12% while false redaction did not rise, both measured on a byte-identical corpus, and the test now carries a dated goal (1.0% leak by 2027-06-30) instead of a ceiling
- **Provider-agnostic LLM** (ADR 0004) — an abstraction that survived being exercised twice for real, which is the only evidence that an abstraction works: the provider moved OpenAI direct → Azure OpenAI → back to OpenAI direct when Azure went (ADR 0034/0036), and transcription moved from an on-device engine to a streaming cloud API (ADR 0039/0045) without the analysis pipeline changing
- **Mandatory strict JSON Schema** (ADR 0003) — LLM output validated server-side, no free-form text cross-service
- **Multi-tenancy** (ADR 0002) — application filter plus Postgres RLS, and the two states have to be told apart: RLS is **enforced on the deployed stack** since 2026-08-10 (`nora_app` is `NOBYPASSRLS`, and `RlsEnforceTelemetryGuard` refuses a half-applied cutover). What is still deferred is flipping the *repository* default, so a local checkout runs with the application filter alone — ADR 0028, ADR 0038 §6g
- **NORA as an MCP server** (ADR 0041) — an external MCP client (Claude Desktop, an IDE, a coding agent) reads meetings, tasks, semantic search and Customer Confidence from NORA. The interesting part is the constraint: every tool call resolves a real IAM principal and goes through the same `PolicyEvaluator` as the web surface, with `meeting:read` and `task:read` rather than an MCP permission vocabulary — so an MCP client can never see more than the user it acts for. Read-only first cut, tenant-scoped bearer token stored only as a SHA-256 hash
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
| **FIAP Pitch / NEXT 2026** | **2026-06-15** | **Held.** The material is [`demo-script.md`](demo-script.md) plus the seed in `scripts/seed-demo.sh`. The outcome is not recorded in this repository |

That is the whole table, and the shrinkage is the point. It used to carry three more rows whose date
column read `?? unverified` — a data modeling deliverable, a partial presentation and the final
delivery. They are not deleted; they moved to the section below, because a milestone whose date
nobody knows is not a deadline, it is an open question, and a table cell is where an open question
goes to be scrolled past.

### Open items to confirm with FIAP

Nothing in this repository can answer these. They need the official rubric and calendar for the 2026
edition, and until someone reads those, the honest answer to "when is the next delivery" is that the
project does not know.

| What has to be confirmed | Who confirms it | What is already ready if the answer arrives |
|---|---|---|
| Whether a **data modeling deliverable (Oracle)** is required this edition, and when | maintainer, against FIAP's published calendar | [`../engineering/data-model-oracle.md`](../engineering/data-model-oracle.md) — the Oracle DDL mirror, complete through migration V030 |
| Whether there is a **partial presentation / sprint review**, and when | idem | the same demo material as the pitch; the script is built in blocks precisely so it survives an unknown slot length |
| The date and format of the **final FIAP delivery** | idem | the rubric table above maps every expected artefact to a file that exists |
| Whether **NEXT 2026** has a date after the 2026-06-15 pitch, and whether this project is expected at it | idem | — |

**Recorded 2026-08-17, on the maintainer's own statement: the next pitch date and the next NEXT date
are unknown.** The 2026-06-15 above is the pitch that already happened, not a future one. This
paragraph exists so that a reader six months from now can tell "nobody has checked yet" apart from
"there is nothing after the pitch" — two very different states that an empty table renders
identically.

## Who works on this

- **@sf0rzin** — maintainer of the repository: product, backend, web, infrastructure and operations. `github.com/sf0rzin/nora`.
- **@pollotherunner** — collaborator on the Desktop app (Tauri). Isolated scope, outside the SaaS core.

Everyone who contributed is listed in the git history.

## Why NORA is more than an academic assignment

These were written as three scenarios *after* the pitch. The pitch has happened, and
[ADR 0038](../adr/0038-post-pitch-scope-realignment.md) §1 has since picked one of them explicitly,
which is why they are no longer equally live:

- **Plan A** — TOTVS hires after seeing NORA in the demo (FIAP × TOTVS partnership, NORA goes from portfolio to a concrete hiring/institutional partnership offer). Still possible; nothing in this repository can advance it
- **Plan B** — commercial SaaS operated independently. **Not the current destination.** ADR 0038 §1 states that NORA is not operating commercially and is not acquiring users, and every deferral in that ADR inherits its reason from that line. Plan B reopens if and only if the single reactivation trigger fires: NORA acquires a user who is not the maintainer
- **Plan C** — technical portfolio and professional positioning. **This is the declared destination**, together with the FIAP deliverable

The distinction ADR 0038 draws is worth keeping in view, because it is easy to read the paragraph
above as the project lowering its standard. It is not: the repository keeps being built to a
commercial SaaS standard, because that standard *is* the portfolio argument. What changed is who the
work is for — a jury and a technical reader, rather than a tenant.

## What is still open

The pitch this section used to count down to was held on 2026-06-15, and the two sub-phases it
described have both been overtaken — one by delivery, the other by a decision.

- **Sub-phase 1.11 — Demo Polish: delivered.** Customer Confidence full-stack (PR #148), the removal
  of `AUTH_FILTER_HARD_CAP` (batch scanning in `MeetingService.listAllForAuthFilter`), the
  `PolicyEvaluator` operators (`StringEquals`, `StringIn`, `StringLike`, `DateGreaterThan`,
  `DateLessThan`, fail-closed), the demonstration seed (`scripts/seed-demo.sh` plus the
  `meridian-erp` material in `data/synthetic/`) and the demo script
  ([`demo-script.md`](demo-script.md)). The seed's vendor is fictional rather than TOTVS, for the
  reasons recorded in `data/synthetic/README.md`. What was called "internal UX polish" landed with
  the v3 redesign
- **Sub-phase 1.12 — Production Hardening: no longer a list of tasks.** Most of what it named was
  Azure vocabulary — a separate `rg-nora-prod` is a resource group, and there is no subscription
  (ADR 0034/0036). The block that survives is declared **deferred scope** by
  [ADR 0038](../adr/0038-post-pitch-scope-realignment.md) §6 rather than pending work, and that ADR
  is the list; repeating it here is how the two copies start to disagree. Operational LGPD was
  already delivered before the pitch (ADR 0029: `DELETE /privacy/meetings/{id}` plus the scheduled
  `RetentionSweeper`)

**What is genuinely open, in this repository, is not on the academic side at all.** The rubric
artefacts are complete: every row of the table above points at a file that exists and describes the
code as it is. The open items are the four in [Open items to confirm with FIAP](#open-items-to-confirm-with-fiap),
and they are open because nobody has read the 2026 rubric yet — not because something is unbuilt.

## History

| Date | Change |
|---|---|
| 2026-05-14 | Doc created in Sub-phase 1.10 (Docs Refresh) consolidating the FIAP × TOTVS framing |
| 2026-06-06 | Doc x code reconciliation + standardization |
| 2026-08-17 | Coverage re-measured in CI and the document's contradiction about it resolved: the rubric row no longer calls a figure from May "real measured", and a "Measured coverage" section now separates what is measured from what is gated |
| 2026-08-17 | The pitch row of the rubric table and the 1.11 line point at `demo-script.md` instead of restating a duration. The running time this page used to assert is gone: it disagreed with the one in the roadmap, and neither described a script that existed |
| 2026-08-17 | **Rewritten for after the pitch.** No sentence treats 2026-06-15 as future any more, and the countdown section is now "What is still open". The three `?? unverified` cells became a named "Open items to confirm with FIAP" section with an owner per row, recording on the maintainer's own statement that the next pitch and NEXT dates are unknown — the point being that a reader can tell "nobody has checked" from "there is nothing left". Four stale technical claims corrected in the same pass: the backlog is 86 stories and not "US01-US51", the LLM abstraction's example no longer ends at a local Whisper that ADR 0039 deleted, RLS is enforced on the deployed stack and it is the *repository* default that is deferred, and the PII line names ADDRESS and the measured leak rate. The MCP server was added as a differentiator, and the three Plans were reconciled with ADR 0038 §1, which has already chosen among them |
