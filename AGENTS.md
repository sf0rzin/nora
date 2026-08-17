# AGENTS.md — NORA

This file is the main project context for AI coding agents. Read it before making code changes.

## Project

NORA (Negotiation Observability & Revenue Assistant) is a SaaS conversational intelligence platform for meetings.

**Core promise:** transform meeting transcripts into summaries, decisions, action items and business intelligence using the customer's own company/product context.

**Primary goal:** a FIAP Challenge 2026 / NEXT 2026 project built to the standards of a commercial SaaS rather than to those of an assignment. It is not operating commercially.

**The destination is declared** (ADR 0038 §1): a FIAP deliverable plus a portfolio piece, with no real users — not few, none. This is a statement of destination, not of quality; the commercial-SaaS standard *is* the portfolio argument. It is also a ceiling: anything argued from "when we have customers" is arguing from a future that is not planned. Its main consequence is that the operations block is a **declared deferral** with a written reason per item (ADR 0038 §6), not an open backlog.

## Read First (in this order)

1. **`docs/product/vision.md`** — product and boundaries
2. **`docs/product/roadmap.md`** — prioritized backlog + sub-phase history + what's ahead
3. **`docs/engineering/architecture.md`** — end-to-end flows + stack rationale + DDD layers
4. **`docs/engineering/standards.md`** — code and PR conventions
5. **`docs/adr/`** — durable architectural decisions (see `docs/adr/README.md` for the canonical ADR index). Read **0038–0041** first: they are the 2026-08-16 realignment, and they supersede ADR 0014 (scope) and ADR 0035 (STT)
6. **`docs/product/glossary.md`** — NORA terms (Productivity Score, Customer Confidence, etc.)

For operational context (self-hosted deploy, runbooks):

7. **`docs/operations/host-deploy.md`** — runbook for deploying on the production host + the self-hosting pitfalls
8. **`docs/operations/ssh-over-tunnel.md`** — reaching the host from a network that blocks outbound 22 (ADR 0037), and the rollback
9. **`docs/operations/production-readiness-gaps.md`** — prod-readiness gaps (those anchored in Azure were partially superseded by ADR 0034, then ADR 0036)

For academic context (FIAP Challenge):

10. **`docs/challenge/fiap-challenge-2026.md`** — FIAP context, rubric, deadlines
11. **`docs/challenge/personas-and-empathy-map.md`** — 3 personas + empathy map
12. **`docs/challenge/use-case-diagram.md`** — UML use cases

## Current scope

NORA runs on a **single self-hosted bare-metal host** (ADR 0034, 2026-08-07; substrate corrected
by ADR 0036, 2026-08-10). Azure is gone — no subscription, no export, nothing to decommission —
and is not being recreated. ADR 0034 records that there was no production data and no user base
at the time of migration, so the Postgres content is reproducible demo material. Stack:

- **Web + Backend + NLP Worker + Desktop** vertical slice all functional, plus the **operator console** (`apps/admin`) as a fifth surface
- **Backend** is Spring Boot 3 (Java 21) + Postgres 16 (`pgvector/pgvector:pg16` container) + Flyway, with **IAM AWS-style** (Root + Users + Groups + Policies) and **multi-tenancy** via `tenant_id` filter (ADR 0002) + RLS (ADR 0026/0028, **three** roles: `nora_app`, `nora_telemetry`, admin/owner). RLS is **enforced on the deployed stack** since 2026-08-10 (the API connects as `nora_app`, NOBYPASSRLS, and `RlsEnforceTelemetryGuard` refuses to boot on a half-applied cutover) and **off by default in the repository**, so locally the application filter is the only control. Identity and IAM tables are exempt by design — ADR 0028, because login resolves a user by global e-mail before any tenant exists
- **NLP Worker** is FastAPI (Python 3.12) with **PII Shield** (PERSON_NAME + EMAIL + CPF + CNPJ + PHONE + CREDIT_CARD per ADR 0012, plus **ADDRESS** since ADR 0043 — a deterministic street-type recogniser, not NER, so an address with a lower-cased or purely numeric name is out of scope) and **JSON Schema strict** LLM output (ADR 0003) via **provider-agnostic client** (ADR 0004, default OpenAI `gpt-4o-mini`). The shield measures itself: `services/nlp-worker/tests/test_pii_corpus.py` gates a leak rate and a false-redaction rate on a corpus of ~5,900 cases, and each rate carries a ceiling, a ratchet and a dated goal (ADR 0043)
- **Web** is Next.js 16 + TypeScript + **raw Tailwind, no shadcn** (ADR 0013) with an OKLCH token palette (`apps/web/src/styles/tokens.css`) and **DM Sans + JetBrains Mono** loaded through `next/font/google` (design system v3, `apps/web/src/app/layout.tsx`; JetBrains Mono is reserved for code contexts). Its automated suite is **Vitest unit tests plus three Playwright e2e specs**, both run by the `web` job: the e2e specs cover security headers, route protection and CSP violations, and the unit suite (ADR 0042) covers six `src/lib` modules — the `request()` function every one of `client.ts`'s 69 exported wrappers goes through, the Markdown report builder, the task-list CSV/Markdown exporter, the BFF's PII redaction, the password policy and the trends panel's date/axis helpers. **No page and no component has a unit test**, so whole-app coverage is around 6%; the coverage gate is per module and only on `redact.ts`, `markdown.ts`, `tasks-export.ts` and `password-policy.ts`
- **Operator console** is `apps/admin`, a second Next.js 16 app: model catalogue plus AI cost telemetry (ADR 0022, 0023, 0024, 0025). It is **fail-closed** — without both `CF_ACCESS_TEAM_DOMAIN` and `CF_ACCESS_AUD` it refuses to render and answers 403 naming them, and fabricated data requires `NORA_ADMIN_USE_MOCKS` to spell exactly `true`. Only `/healthz` answers unauthenticated. It has its own `admin` job in `ci.yml` (lint, typecheck, build) and is inside `ci-gate`
- **Desktop** is Tauri 2 + Rust and **Windows-only** (ADR 0038 §2). The macOS capture path (the BlackHole virtual driver, plus an unfinished ScreenCaptureKit detection) and the Linux one (PulseAudio `parecord`) were deleted along with `Info.plist`, `icon.icns`, the `bundle.macOS` floor and the non-Windows CI matrix entries: three Tauri builds per merge were being paid for to keep two platforms nobody had ever run. `system_audio.rs` now carries a `compile_error!` on any other target. Its **local React UI was deleted too** — the router and its five pages compiled into every bundle and were never rendered — so the surfaces are **capture + overlay + dock**, and the `main` window loads the remote web application at `https://nora.systems/dashboard` rather than anything bundled. **STT is the provider's realtime API** (ADR 0039, contract in ADR 0045): the desktop opens one WebSocket per track with a short-lived credential minted by `POST /stt/sessions`, so the key never leaves the server and the audio never crosses NORA's infrastructure. The consequence is written in ADR 0039 and must not be softened anywhere it is summarised: **per-tenant attribution happens at session issuance, so the cost telemetry is an estimate, not a measurement**. The on-device engine is gone — `whisper-rs`, `sha2`, `stt_local.rs`, `whisper_model.rs`, the whole `[features]` section and the `NORA_STT_BACKEND` selector — and with it the CMake/`MAX_PATH` steps both desktop workflows carried. **Azure Speech is gone from both halves**: the Python sidecar, the `stt-azure` Cargo feature, `SpeechController`, `SpeechTokenService`, `AzureSpeechTokenBroker` and `POST /speech/token` no longer exist in any form. ADR 0008 and ADR 0035 describe a three-platform client with on-device Whisper and stay as they are — accepted ADRs are immutable, and ADR 0038 and ADR 0039 are where the reductions are recorded
- **Infra** is `infra/host/docker-compose.yml` (compose project `nora`) on a single bare-metal Ubuntu host, no hypervisor (ADR 0036): **Cloudflare Tunnel as the only ingress for HTTP** (no web port published), Caddy routing by Host, secrets in **SOPS + age**, observability via OTel Collector + Prometheus + Loki + Grafana. **Deploy is PULL** — nothing pushes to the host and GitHub holds no credential for it, because the repo is public (ADR 0017). `deploy-host.yml` publishes an immutable release pointer (`release/prod/<sha>`), but **nothing on the host reads it**: `bootstrap-host.sh` installs a timer whose `ExecStart=` runs `deploy.sh --if-changed` with no `--tag`, so it re-probes the tag already running — whose digest never changes — and never discovers a newer release. Rolling forward is a manual `deploy.sh --tag sha-<short>` today. The same tunnel also carries **SSH** at `ssh.nora.systems`, behind a Cloudflare Access allow-list (ADR 0037), for networks that block outbound 22. **Port 22 was open to the internet when last measured (2026-08-11: `ufw` inactive, `iptables` INPUT policy `ACCEPT`, no rule naming port 22)** and is deliberately kept as the recovery path, because reaching the host through the tunnel depends on Cloudflare, Access and the identity provider all being up. No firewall enforces that reachability — re-measure before relying on it

For up-to-date status of each backlog story, see `docs/product/backlog.md` (DONE / PARTIAL / MISSING per US).

### Scope decisions in force

The 2026-08-16 realignment is recorded in four ADRs. They are the current answer to "is this in scope?", and they are what a reader of ADR 0014 would otherwise get wrong.

| ADR | What it settles |
|---|---|
| **0038** — post-pitch scope realignment | Supersedes ADR 0014 and its deferral block; the 2026-06-15 pitch gate expired. **Enterprise is exactly IAM + Customer Confidence per meeting + multi-tenancy, and nothing else.** US05 (corporate SSO), US50/US51 (aggregated Account Health) and the Enterprise DPA/SLA are **WONT** — closed, not waiting. US21, US25, US31 and US43 came back into scope; US31 (migration V028), US25 (client-side CSV/Markdown export) and US43 (`POST /iam/simulate`) are built, and only US21 is still MISSING. The operations block became a declared deferral with one reactivation trigger: NORA acquires a user who is not the maintainer |
| **0039** — cloud STT with an ephemeral token | Supersedes ADR 0035. Transcription moves to OpenAI's streaming API; the desktop connects to the provider directly with a short-lived session credential. Because the audio never crosses our infrastructure, **per-tenant attribution happens at session issuance, and the cost telemetry of ADR 0024 is an estimate** — label it as one wherever it is displayed, never as measurement |
| **0040** — PII scoped to analysis | Rewrites the non-negotiable below and names transcription as an external subprocessor. **Supersedes nothing**: every control of ADR 0012 and ADR 0033 stays exactly as it is. It changes a sentence, not a pipeline |
| **0041** — NORA as an MCP server | **Active scope; nothing is built.** An inbound adapter inside `services/api`, every tool call resolving a real IAM principal through `PolicyEvaluator`, a hashed tenant-scoped bearer token instead of a full OAuth 2.1 authorization server, and a read-only first cut. ADR 0031's OAuth integrations are the **outbound** path and already shipped; MCP is the **inbound** one. The invariant to test against: an MCP client can never see more than the user it acts for can see in the web application |

## Stack

Every row below was read out of the file named beside it. Change the manifest, change this table.

| Component | Version | Verify in |
|---|---|---|
| Java | 21 | `services/api/pom.xml` (`java.version`) |
| Spring Boot | 3.5.16 | `services/api/pom.xml` (parent) |
| Flyway | inherited from Spring Boot 3.5.16 | `services/api/pom.xml` |
| Postgres | 16 (`pgvector/pgvector:pg16`; the pgvector extension is available but **not created** — ADR 0034 §excluded scope) | `infra/host/docker-compose.yml` |
| Python (worker) | >= 3.12 | `services/nlp-worker/pyproject.toml` |
| FastAPI | >= 0.115 | `services/nlp-worker/pyproject.toml` |
| Pydantic | >= 2.9 | `services/nlp-worker/pyproject.toml` |
| OpenAI SDK | >= 1.50 | `services/nlp-worker/pyproject.toml` |
| Next.js | 16.3.0 (same pin in both apps) | `apps/web/package.json`, `apps/admin/package.json` |
| React | 18.3.1 (same pin in both apps) | `apps/web/package.json`, `apps/admin/package.json` |
| TypeScript | ^5.6.3 (same pin in both apps) | `apps/web/package.json`, `apps/admin/package.json` |
| Tailwind CSS | ^3.4.13 (same pin in both apps) | `apps/web/package.json`, `apps/admin/package.json` |
| Tauri (desktop) | 2. STT is the provider's realtime API over `tokio-tungstenite` 0.28 (rustls, bundled roots), on a credential minted by `POST /stt/sessions`. There is **no `[features]` section** in the crate and no optional native dependency: `whisper-rs`, `sha2` and the `stt-local` feature went with ADR 0039/0045, and with them the C++ toolchain and `libclang` requirements | `apps/desktop/src-tauri/Cargo.toml` |
| Orchestration | Docker Compose, project `nora` | `infra/host/docker-compose.yml` (`name:`) |
| Ingress | Cloudflare Tunnel `cloudflared:2026.5.2` + `caddy:2.8-alpine` | `infra/host/docker-compose.yml` |
| Secrets | SOPS + age (`secrets.env.sops`; private key only on the host) | `infra/host/` |
| Observability | OTel Collector 0.115.1 · Prometheus v3.1.0 (`--storage.tsdb.retention.time=30d`) · Loki 3.3.2 · Alloy v1.7.1 · Grafana 11.5.1 | `infra/host/docker-compose.yml` |
| Hosting | Single bare-metal Ubuntu 24.04 host, no hypervisor (ADR 0036) | `docs/adr/0036-substrate-is-a-single-bare-metal-host.md` |

`docs/engineering/architecture.md` §1 carries the same table with the rationale for each choice.

## Non-Negotiables (inviolable rules)

- **Tenant isolation**: `tenant_id` in every tenant-owned table. Filter in the backend, never only in the frontend. ADR 0002
- **PII redaction, scoped to text and analysis**: **PII does not reach the analysis LLM raw. The worker's `PIIShield` is the last gate before analysis.** ADR 0012, ADR 0040. Until 2026-08-16 this line claimed coverage over every byte the product touches; that claim was withdrawn deliberately and in writing, because it was never fully true. **Do not restore it.** No control was weakened to get here — ADR 0040 supersedes neither ADR 0012 nor ADR 0033, and ADR 0029's erasure and retention paths are untouched. Two exposures are declared at the same volume as the promise, and neither is an invariant to defend:
  - **PERSON_NAME on the chat path is accepted residue** until the worker routing lands (ADR 0033). Structured PII — e-mail, phone, CPF, CNPJ, card, with check-digit and Luhn validation — *is* redacted in the BFF before both the chat provider and the embeddings provider
  - **Transcription is an external subprocessor that receives raw audio before any redaction exists** (ADR 0039, ADR 0040 §3). Audio cannot be redacted: redaction needs text, and text is what the transcriber produces — a property of the architecture, not a missing feature. This is in effect: ADR 0039's streaming path is built (contract in ADR 0045) and the on-device engine is gone. There is **no data processing agreement** with that provider (ADR 0038 §4 closed the DPA as scope), so the position is a demonstration posture, not a compliance posture, and the product's outward language must not imply otherwise
- **JSON Schema strict** on LLM output: `response_format=json_schema` (ADR 0003). Pydantic validation in the worker
- **Provider-agnostic LLM** (ADR 0004): any endpoint speaking OpenAI's Chat Completions API, selected by `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`. Default is OpenAI direct on `gpt-4o-mini`. Azure OpenAI is one compatible endpoint among several, not a plan — Azure left the project entirely with ADR 0034 and ADR 0036
- **DDD layers in the backend**: `domain` does not know Spring/HTTP/SDK. `application` orchestrates. `infrastructure` adapts. `api` is thin
- **No hardcoded TOTVS** in product code. Tenant context is configurable
- **ADRs are immutable** once accepted. Decision obsolete? Create a successor ADR (see `docs/adr/README.md`)
- **Scope is declared, not open**: **ADR 0038 supersedes ADR 0014** and its deferral block. ADR 0014's reactivation criteria were commercial ("the first paying Enterprise tenant requires it") and expired unrecorded with the 2026-06-15 pitch; do not reopen scope by reading them. What is closed (§4), what came back (§5) and what is deferred with a reason (§6) are enumerated in the scope table above. Anything not in one of those three lists needs a successor ADR before it is built
- **Tests**: three coverage gates are actually enforced in CI, and all three are narrow. `mvn verify` runs a JaCoCo rule over the single class `PolicyEvaluator` — instruction >= 90%, branch >= 75% (`services/api/pom.xml`). The worker job runs `pytest --cov=nora_nlp.services.pii_shield --cov-fail-under=90` over that one module (`.github/workflows/ci.yml`). The web job applies per-module `coverage.thresholds` to four `src/lib` files (`apps/web/vitest.config.mts`, ADR 0042); those numbers are **floors set below the measured rate**, so they fire on a regression, and raising coverage means raising the floor with it. ADR 0018's ">85% sustained across IAM, Auth and PII" is the **aspiration**, not a gate — nothing blocks a regression outside those three scopes. What every run *does* produce is the number: `scripts/report-coverage.sh` prints backend, worker and web coverage to the job log and the run summary (measured 2026-08-17: worker 92.4% statement over 863 tests; backend 77.1-77.3% instruction / 61.5-61.6% branch over 578 tests — a range, because the backend figure jitters between runs; web 6.2% statement whole-app over 104 tests, with the four gated modules at 96.6%, 97.7%, 100% and 100%). **Do not quote a coverage number from a document** — read the last CI run. `apps/web`'s whole-app figure is low because no page and no component has a unit test; `apps/admin` still has lint, typecheck and build in CI and no tests at all
- **Do not commit secrets**. Use `.env.example` for variable names

## How we work

- **Implement one sub-phase or story per branch.** Naming: `feat/sub-X.Y-<slug>` or `feat/usZZ-<slug>` or `fix/<slug>` or `docs/<slug>`
- **Commit messages in English** — subject and body — keeping Conventional Commits: `type(scope): subject (#PR)`. This applies to humans and agents. Discussion, issues and PR descriptions remain free to be in Portuguese; the rule covers only the commit text. History prior to 2026-08-09 is mixed and stays as it is — do not rewrite it
- **Reference IDs** (US##, Sub-phase 1.X, ADR NNNN, PR #) in commits and PR descriptions
- **Before editing**, inspect the existing patterns in the target module (Grep/Glob)
- **After editing**, run the smallest relevant verification command (`mvn test`, `pytest`, `npm run typecheck`, `docker compose -f infra/host/docker-compose.yml config`) and report pass/fail
- **After touching documentation**, run both guard scripts and report their exit codes:
  - `bash scripts/check-doc-links.sh` — every relative markdown link must resolve. Renaming or deleting a document without fixing its inbound links fails here
  - `bash scripts/check-language.sh` — no Portuguese outside the allowlist declared at the top of that script. This is how the English rule above is actually kept. Adding a path to the allowlist requires an honest reason in the comment beside it
- **Update the docs** when code diverges: docs are part of the product, not an accessory

## Working with subagents

For large tasks, split the work into slices that can be implemented independently, dispatch each with a self-contained brief (`Agent` tool), and review the resulting diff rather than the summary. Record a durable decision as an ADR if one is missing.

Use Opus models for architecture, data modeling, security review and refactors. Use Sonnet models or subagents for focused implementation, tests, UI components and mechanical CRUD flows.

## Change history of this file

| Date | Change |
|---|---|
| 2026-08-17 | US21 shipped the trends panel (`GET /trends`, migration V030), which moved the two web bullets again: the unit suite covers **six** `src/lib` modules and `client.ts` is at **69** exported wrappers. The `66` this file, `apps/web/README.md` and `vitest.config.mts` all carried had already been stale before this change — US31 added two functions and nobody re-counted. The coverage-gate list is unchanged: the new module is reported, not gated |
| 2026-08-17 | **Transcription migrated to the cloud** (ADR 0039 built, contract recorded in ADR 0045). The Desktop bullet, the Tauri stack row and the PII subprocessor bullet stop describing an on-device engine and a pending migration: `POST /stt/sessions` mints the credential, `stt_cloud.rs` streams to the provider, and `whisper-rs`, `sha2`, `stt_local.rs`, `whisper_model.rs`, the crate's whole `[features]` section and the `NORA_STT_BACKEND` selector are out of the tree along with the CMake/`MAX_PATH` steps in both desktop workflows. The subprocessor exposure changes from "takes effect when it is built" to "in effect" |
| 2026-08-17 | US25 shipped the task-list export, so the two web bullets moved by one: the unit suite covers **five** `src/lib` modules and the web coverage gate now names **four** files. The measured figures were re-read from `npm run test:coverage` rather than adjusted by hand |
| 2026-08-17 | `apps/web` gained its first unit-test runner (Vitest, ADR 0042), so the Web bullet and the Tests bullet were rewritten: three coverage gates in CI now, not two, and the web figure is quoted with the fact that no page or component is tested rather than as a bare percentage. The row below, written earlier the same day, correctly said "three Playwright e2e specs but no unit runner" — it is left as it was, because it is the record of what was true when it was written |
| 2026-08-17 | Realigned against ADRs 0038–0041. The **PII non-negotiable was rescoped to text and analysis** and the two declared exposures (PERSON_NAME on the chat path, transcription as an external subprocessor) written beside it; the declared destination of ADR 0038 §1 added to "Project"; a scope table added covering what died, what came back, what is a declared deferral, and the MCP server as active-but-unbuilt scope; the desktop bullet rewritten for the deleted local UI and for ADR 0039 superseding ADR 0035 with the local engine still in the tree; `apps/admin` added as the fifth surface; the ADR 0014 bullet repointed at ADR 0038. The Stack table was re-read against the manifests and three rows were wrong: the web fonts are **DM Sans + JetBrains Mono**, not Inter + Instrument Serif; the four front-end pins are shared with `apps/admin`; and the Tauri row cited a superseded ADR. "Azure OpenAI in the future" and "`apps/web` has no test suite at all" were both corrected — the LLM client takes any OpenAI-compatible base URL, and `apps/web` has three Playwright e2e specs but no unit runner |
| 2026-08-16 | Desktop reduced to **Windows-only**. macOS and Linux capture, the macOS bundle metadata (`Info.plist`, `icon.icns`, `bundle.macOS.minimumSystemVersion`), the `whisper-rs` `metal` feature, the non-Windows CI matrix entries and the three-platform claims in vision/backlog/roadmap/architecture all removed; `deny.toml` evaluates one target, so the glib advisory `ignore` went with the Linux graph. ADRs 0008 and 0035 still describe three platforms and were not edited |
| 2026-08-16 | Renamed from `CLAUDE.md` to `AGENTS.md` and stripped of vendor-specific AI attribution, along with the rest of the repository: the `nora-architect` skill under `.claude/skills/` was deleted, two ADR history rows lost a `+ Claude` co-author, and the design-provenance comments and `hero-claude` CSS classes were renamed. The file keeps its role as the context an AI coding agent reads first. ADR 0037 still names `CLAUDE.md` because accepted ADRs are immutable and the reference is accurate for its date |
| 2026-08-11 | SSH over the existing tunnel recorded as ADR 0037 (applied 2026-08-10): `ssh.nora.systems` gated by a Cloudflare Access allow-list, sshd and port 22 untouched by that change. Added the runbook to "Read First" and the route to "Current scope". Also corrected a claim this file and two runbooks had been making in different directions: port 22 is open to the internet (measured — `ufw` inactive), so "no inbound port" was only ever true of the stack, not of the machine |
| 2026-08-10 | RLS enforce cutover executed on the deployed stack: the API connects as `nora_app` (NOBYPASSRLS), the operator aggregate reads through `nora_telemetry` (BYPASSRLS), and the API refuses to boot on a half-applied cutover. Off by default in the repository |
| 2026-08-10 | Substrate correction (ADR 0036): the host is a single bare-metal Ubuntu machine, no hypervisor. Renamed the infra directory, the deploy runbook and the deploy workflow to host-neutral names (now `infra/host/`, `docs/operations/host-deploy.md`, `.github/workflows/deploy-host.yml`); removed `infra/bicep/`, `azure-decommission.md` and `azure-deploy.md` (Azure is gone, not being decommissioned); updated "Read First", "Current scope" and the Stack table accordingly |
| 2026-08-10 | Documentation honesty pass: metadata frontmatter, invented owners/roles and decoration removed; stack versions re-verified against the manifests; superseded run brief and pre-presentation audit deleted |
| 2026-08-07 | Azure → self-hosted migration (ADR 0034) and local STT (ADR 0035): "Current scope", the Stack table and the `docs/operations/` pointers updated. `azure-deploy.md` becomes historical; the self-hosting runbook and the decommission runbook take its place. Both of those files were later deleted or renamed by ADR 0036 — this row records what happened on the date, not the paths as they stand today |
| 2026-06-06 | Doc × code reconciliation + standardization |
| 2026-05-14 | Rewritten during Sub-phase 1.10 (Docs Refresh): new `docs/` structure in subfolders (product/engineering/operations/challenge/security), updated references, new ADRs linked |
| (earlier) 2026-05-02+ | Original version created with the initial scaffolding |
