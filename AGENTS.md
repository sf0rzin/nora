# AGENTS.md — NORA

This file is the main project context for AI coding agents. Read it before making code changes.

## Project

NORA (Negotiation Observability & Revenue Assistant) is a SaaS conversational intelligence platform for meetings.

**Core promise:** transform meeting transcripts into summaries, decisions, action items and business intelligence using the customer's own company/product context.

**Primary goal:** a FIAP Challenge 2026 / NEXT 2026 project built to the standards of a commercial SaaS rather than to those of an assignment. It is not operating commercially.

## Read First (in this order)

1. **`docs/product/vision.md`** — product and boundaries
2. **`docs/product/roadmap.md`** — prioritized backlog + sub-phase history + what's ahead
3. **`docs/engineering/architecture.md`** — end-to-end flows + stack rationale + DDD layers
4. **`docs/engineering/standards.md`** — code and PR conventions
5. **`docs/adr/`** — durable architectural decisions (see `docs/adr/README.md` for the canonical ADR index)
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

- **Web + Backend + NLP Worker + Desktop** vertical slice all functional
- **Backend** is Spring Boot 3 (Java 21) + Postgres 16 (`pgvector/pgvector:pg16` container) + Flyway, with **IAM AWS-style** (Root + Users + Groups + Policies) and **multi-tenancy** via `tenant_id` filter (ADR 0002) + RLS (ADR 0026/0028, **three** roles: `nora_app`, `nora_telemetry`, admin/owner). RLS is **enforced on the deployed stack** since 2026-08-10 (the API connects as `nora_app`, NOBYPASSRLS, and `RlsEnforceTelemetryGuard` refuses to boot on a half-applied cutover) and **off by default in the repository**, so locally the application filter is the only control. Identity and IAM tables are exempt by design — ADR 0028, because login resolves a user by global e-mail before any tenant exists
- **NLP Worker** is FastAPI (Python 3.12) with **PII Shield** (PERSON_NAME + EMAIL + CPF + CNPJ + PHONE + CREDIT_CARD per ADR 0012) and **JSON Schema strict** LLM output (ADR 0003) via **provider-agnostic client** (ADR 0004, default OpenAI `gpt-4o-mini`)
- **Web** is Next.js 16 + TypeScript + **raw Tailwind, no shadcn** (ADR 0013) with editorial palette OKLCH + Inter + Instrument Serif fonts. It has **no test suite**
- **Desktop** is Tauri 2 + Rust with **Whisper STT running on-device** (ADR 0035). The Python sidecar and the Azure Speech token broker are **off the default path but still in the tree**: `stt-azure` is still in `default` in `src-tauri/Cargo.toml`, `apps/desktop/sidecar/` still builds, and `AzureSpeechTokenBroker` still compiles — the runtime default is `LocalSttNoopBroker`. Deleting them is pending validation of local STT on all three targets — maintained by @pollotherunner
- **Infra** is `infra/host/docker-compose.yml` (compose project `nora`) on a single bare-metal Ubuntu host, no hypervisor (ADR 0036): **Cloudflare Tunnel as the only ingress for HTTP** (no web port published), Caddy routing by Host, secrets in **SOPS + age**, observability via OTel Collector + Prometheus + Loki + Grafana. **Deploy is PULL** — nothing pushes to the host and GitHub holds no credential for it, because the repo is public (ADR 0017). `deploy-host.yml` publishes an immutable release pointer (`release/prod/<sha>`), but **nothing on the host reads it**: `bootstrap-host.sh` installs a timer whose `ExecStart=` runs `deploy.sh --if-changed` with no `--tag`, so it re-probes the tag already running — whose digest never changes — and never discovers a newer release. Rolling forward is a manual `deploy.sh --tag sha-<short>` today. The same tunnel also carries **SSH** at `ssh.nora.systems`, behind a Cloudflare Access allow-list (ADR 0037), for networks that block outbound 22. **Port 22 was open to the internet when last measured (2026-08-11: `ufw` inactive, `iptables` INPUT policy `ACCEPT`, no rule naming port 22)** and is deliberately kept as the recovery path, because reaching the host through the tunnel depends on Cloudflare, Access and the identity provider all being up. No firewall enforces that reachability — re-measure before relying on it

For up-to-date status of each backlog story, see `docs/product/backlog.md` (DONE / PARTIAL / MISSING per US).

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
| Next.js | 16.3.0 | `apps/web/package.json` |
| React | 18.3.1 | `apps/web/package.json` |
| TypeScript | ^5.6.3 | `apps/web/package.json` |
| Tailwind CSS | ^3.4.13 | `apps/web/package.json` |
| Tauri (desktop) | 2, on-device STT via `whisper-rs` pinned at `=0.16.0` (ADR 0035) | `apps/desktop/src-tauri/Cargo.toml` |
| Orchestration | Docker Compose, project `nora` | `infra/host/docker-compose.yml` (`name:`) |
| Ingress | Cloudflare Tunnel `cloudflared:2026.5.2` + `caddy:2.8-alpine` | `infra/host/docker-compose.yml` |
| Secrets | SOPS + age (`secrets.env.sops`; private key only on the host) | `infra/host/` |
| Observability | OTel Collector 0.115.1 · Prometheus v3.1.0 (`--storage.tsdb.retention.time=30d`) · Loki 3.3.2 · Alloy v1.7.1 · Grafana 11.5.1 | `infra/host/docker-compose.yml` |
| Hosting | Single bare-metal Ubuntu 24.04 host, no hypervisor (ADR 0036) | `docs/adr/0036-substrate-is-a-single-bare-metal-host.md` |

`docs/engineering/architecture.md` §1 carries the same table with the rationale for each choice.

## Non-Negotiables (inviolable rules)

- **Tenant isolation**: `tenant_id` in every tenant-owned table. Filter in the backend, never only in the frontend. ADR 0002
- **PII redaction**: PII never reaches the LLM raw. PIIShield in the worker is the last gate. ADR 0012
- **JSON Schema strict** on LLM output: `response_format=json_schema` (ADR 0003). Pydantic validation in the worker
- **Provider-agnostic LLM** (ADR 0004): default is OpenAI direct, Azure OpenAI in the future
- **DDD layers in the backend**: `domain` does not know Spring/HTTP/SDK. `application` orchestrates. `infrastructure` adapts. `api` is thin
- **No hardcoded TOTVS** in product code. Tenant context is configurable
- **ADRs are immutable** once accepted. Decision obsolete? Create a successor ADR (see `docs/adr/README.md`)
- **Defer scope creep**: ADR 0014 declares v1 closed. It deferred 14 US; US15 was subsequently delivered in PR #206, leaving 13 (US48/US49 addressed via ADR 0015). The gate ADR 0014 set was the FIAP pitch, held 2026-06-15 — reopening scope now needs a successor ADR, not a reading of that deadline
- **Tests**: two coverage gates are actually enforced in CI, and both are narrow. `mvn verify` runs a JaCoCo rule over the single class `PolicyEvaluator` — instruction >= 90%, branch >= 75% (`services/api/pom.xml`). The worker job runs `pytest --cov=nora_nlp.services.pii_shield --cov-fail-under=90` over that one module (`.github/workflows/ci.yml`). ADR 0018's ">85% sustained across IAM, Auth and PII" is the **aspiration**, not a gate — nothing blocks a regression outside those two scopes, and `apps/web` has no test suite at all
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
| 2026-08-16 | Renamed from `CLAUDE.md` to `AGENTS.md` and stripped of vendor-specific AI attribution, along with the rest of the repository: the `nora-architect` skill under `.claude/skills/` was deleted, two ADR history rows lost a `+ Claude` co-author, and the design-provenance comments and `hero-claude` CSS classes were renamed. The file keeps its role as the context an AI coding agent reads first. ADR 0037 still names `CLAUDE.md` because accepted ADRs are immutable and the reference is accurate for its date |
| 2026-08-11 | SSH over the existing tunnel recorded as ADR 0037 (applied 2026-08-10): `ssh.nora.systems` gated by a Cloudflare Access allow-list, sshd and port 22 untouched by that change. Added the runbook to "Read First" and the route to "Current scope". Also corrected a claim this file and two runbooks had been making in different directions: port 22 is open to the internet (measured — `ufw` inactive), so "no inbound port" was only ever true of the stack, not of the machine |
| 2026-08-10 | RLS enforce cutover executed on the deployed stack: the API connects as `nora_app` (NOBYPASSRLS), the operator aggregate reads through `nora_telemetry` (BYPASSRLS), and the API refuses to boot on a half-applied cutover. Off by default in the repository |
| 2026-08-10 | Substrate correction (ADR 0036): the host is a single bare-metal Ubuntu machine, no hypervisor. Renamed the infra directory, the deploy runbook and the deploy workflow to host-neutral names (now `infra/host/`, `docs/operations/host-deploy.md`, `.github/workflows/deploy-host.yml`); removed `infra/bicep/`, `azure-decommission.md` and `azure-deploy.md` (Azure is gone, not being decommissioned); updated "Read First", "Current scope" and the Stack table accordingly |
| 2026-08-10 | Documentation honesty pass: metadata frontmatter, invented owners/roles and decoration removed; stack versions re-verified against the manifests; superseded run brief and pre-presentation audit deleted |
| 2026-08-07 | Azure → self-hosted migration (ADR 0034) and local STT (ADR 0035): "Current scope", the Stack table and the `docs/operations/` pointers updated. `azure-deploy.md` becomes historical; the self-hosting runbook and the decommission runbook take its place. Both of those files were later deleted or renamed by ADR 0036 — this row records what happened on the date, not the paths as they stand today |
| 2026-06-06 | Doc × code reconciliation + standardization |
| 2026-05-14 | Rewritten during Sub-phase 1.10 (Docs Refresh): new `docs/` structure in subfolders (product/engineering/operations/challenge/security), updated references, new ADRs linked |
| (earlier) 2026-05-02+ | Original version created with the initial scaffolding |
