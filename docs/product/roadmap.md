# Roadmap — NORA

> **Living and official** roadmap of NORA. Replaces the old `docs/plano-de-execucao.md` (archived — discontinued on 2026-05-14, because it described a week-by-week split between two developers that no longer matches the real flow: one maintainer, working with AI coding agents in parallel worktrees).
>
> **Structure:**
> 1. **History** — all Sub-phases 1.0 to 1.10 with PRs and ADRs (cross-checked with audit §11)
> 2. **Upcoming Sub-phases** — 1.11, 1.12, 1.13+ with target window, scope and prerequisites
> 3. **Post-MVP phase (long term)** — the vision of phases 4-9 of the original execution plan

## 1. History — Sub-phases 1.0 to 1.10

The `1.X` numbering corresponds to a coherent delivery slice, normally 1+ merged PRs that deliver verifiable value. Sub-phases may be implicit (accumulated pre-audit) or explicit (planned + executed).

| Sub-phase | Date | PRs | Delivery summary | Related ADRs |
|---|---|---|---|---|
| **1.0 (implicit, pre-audit)** | up to 2026-05-10 | #1, #3-#8, #22-#25, #29-#50 | Monorepo scaffolding; e-mail/password auth (US01-US04) with JWT; text upload (US07); LLM worker (US11-US14); Tauri desktop (US09); AWS-style IAM (US35-US40); Customer Confidence LLM schema (without persistence); Productivity opt-in; web auth flow; analysis persistence in the DB. The base for everything that came afterwards. | 0001-0009 |
| **1.1 — DS Sprint 1+2** | 2026-05-11 | #54 | EDA notebook (`notebooks/01-tf-idf-eda-meetings.ipynb` with 26 cells) + expanded synthetic dataset (12 .txt + 3 .vtt + 2 .srt + 3 JSON contexts) + `packages/nlp-baseline/` package (3 TF-IDF modules, 52 tests) | ADR 0010 |
| **1.2 — Enterprise Gaps** | 2026-05-12 | #55 | US32 (tenant corporate domain) + US06 (e-mail invitation) + Monaco JSON PolicyEditor with syntax highlighting + schema validation. 41 new tests. Approach: "Step 0 contracts before implementing" worked (schema-first reduced rework) | ADR 0011 |
| **1.3 — PII Hardening + UX** | 2026-05-12 | #59 | PII Shield expansion with PERSON_NAME (BR) — ~270 names + negative list of ~80 terms. Upload polling (web). Markdown rendering in the summary. httpOnly cookies (`nora_access` 15min JWT, `nora_refresh` 30d stateful UUID in V011). Catalogued debts: auth audit log, `logoutAllSessions` with no REST endpoint, PII ADDRESS | ADR 0012 |
| **1.4 — Bicep IaC** | 2026-05-12 | #62 | `main.bicep` + 8 modules (containerappsenv, containerapp, postgres, storage, keyvault, appinsights, loganalytics, speech) + dev bicepparam. Estimated dev cost: R$110-180/month. `infra` CI job in `ci.yml` | — |
| **1.5 — Desktop Briefing** | 2026-05-12 | (no PR — vault) | Vault `40-desktop-handoff/2026-05-12-update-pos-subfase-1.4.md`. Briefing for the friend who owns the desktop with Rust↔Python NDJSON contracts + macOS BlackHole roadmap (already merged in PR #37) + ScreenCaptureKit debt | — |
| **1.6 — Build/Push GHCR** | 2026-05-12 | #63 | `build-images.yml` workflow + 3 Dockerfiles (api, worker, web). Publishes `ghcr.io/sys0xff/nora-{api,worker,web}:{latest, sha-XXXXXXX, ref}`. Public images (manual step in the GHCR settings) | — |
| **1.7 — Deploy workflow + SP OIDC** | 2026-05-12 | #64 | `deploy-infra.yml` + Service Principal `sp-nora-github-deploy` (`Contributor` + `Role Based Access Control Administrator` roles on `rg-nora-dev`) + 3 federated credentials (main / pull_request / environment:dev). Lesson: a separate fed cred per (branch, environment) | — |
| **1.8 — Productivity Score full-stack** | 2026-05-12/13 | #67 | Migration V012 (`meeting_goals` + `productivity_assessments` tables) + worker model + stub + LLM analyzer + Spring backend endpoints + web 3 components (`MeetingGoalForm`, `MeetingProductivitySection`, `ProductivityScoreCard`). Lesson: subagents in a worktree may have a different CWD — watch out for absolute paths | ADR 0005 |
| **1.9 (implicit) — Real Azure deploy** | 2026-05-13 | #68-#75 | 8 infra fixes resolved: `centralus` region restriction, real GHCR images (not placeholders), Azure Speech + 2 UAIs + KV references in Container Apps, complete env vars, Postgres extensions via `azure.extensions`. Deploy success: `https://nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io`. 8 Azure for Students pitfalls catalogued (region restriction, per-service offer restriction, RP does not auto-register, KV soft-delete, Cognitive Services soft-delete, SpeechServices networkAcls, Contributor cannot create role assignments, Postgres CREATE EXTENSION blocked) | — |
| **1.10 — Docs Refresh** | 2026-05-13/14 | #76 | Audit `2026-05-13-audit-pre-subfase-1.10.md` (13 sections) + reorganisation of `docs/` into `product/` + `engineering/` + `operations/` + `security/` + `challenge/` + `adr/`. LICENSE AGPL-3.0 + SECURITY.md created. Memory consolidated. Audit, critical review and approval done as a block. | **6 new ADRs: 0013 (CSS strategy, proposed — Design refines) · 0014 (defer 14 US post-MVP, accepted as a block) · 0015 (Customer Confidence minimum persistence in 1.11, accepted vote a) · 0016 (production-readiness checklist, proposed — accepted in 1.12) · 0017 (LICENSE AGPL-3.0, accepted) · 0018 (test coverage targets, accepted)** |

| **Post-1.10 — Hardening (audit follow-ups)** | 2026-05-15+ | #114–#138 | Security/infra wave labelled "audit follow-up #N", **outside the history of named sub-phases**: V013 soft-delete (`deleted_at` + `@SQLRestriction`) · V014 refresh-token rotation + reuse-detection · V015 composite isolation FK · **V016 Row-Level Security** (+ `TenantRlsAspect`) · JWT RS256 + JWKS · expanded auth audit log · App Insights Java agent · upload hardening. | ADR 0019 (RLS + composite FK), ADR 0020 (token rotation), ADR 0021 (soft-delete) — created retroactively in the 2026-05-21 audit |
| **Post-1.10 — Customer Confidence (1.11a)** | 2026-05-21 | #148 | ADR 0015 implemented full-stack in 4 slices: migration **V017** (`customer_accounts` + `meeting_account_links` + `customer_confidence_assessments` + `customer_buying_signals` + `customer_objections`, all with RLS) · worker emits `customerConfidence` (sales→present, internal→null) · backend persists in the pipeline with an **authoritative trend** (±5 band) + get-or-create of the account · `GET /meetings/{id}` expands the return · `CustomerConfidenceCard` UI. CI green with Testcontainers. | ADR 0015 (applied) |
| **Post-1.10 — IAM/list debts (1.11b+c)** | 2026-05-23 | (1.11 b+c) | `PolicyEvaluator` gained `StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` (fail-closed retained) · `AUTH_FILTER_HARD_CAP` (silent ceiling of 500) removed via batched scanning (`MeetingService.listAllForAuthFilter`). New unit tests (PolicyEvaluator + MeetingService); `IamScopingIntegrationTest` untouched. | — |

> **Update (reconciliation 2026-05-21, post-PR #148):** (1) **Customer Confidence (US48-49) WAS implemented** full-stack in #148: V017 + worker emit + `AnalysisService` wiring (server-side trend) + `GET /meetings/{id}` + `CustomerConfidenceCard`. The landing page's narrative debt is **resolved**. (2) It was delivered as **V017** (the V013 slot from ADR 0015 ended up as soft-delete, #114). (3) **RLS**: schema delivered in V016 and completed in V019/V020 (full RLS + auth-aware scope) — only the operational cutover/enforcement in prod remains (ADR 0026/0028). (4) **Sub-phase 1.11 has already been delivered** in the code items: (a) Customer Confidence DONE; (b) AUTH_FILTER_HARD_CAP removed; (c) PolicyEvaluator operators implemented; remaining are (e) TOTVS seed and (f) demo script. Aggregated Account Health (US50-US51) **remains deferred** via ADR 0014.

### Cumulative metrics

- **200+ PRs** merged into `main` (latest: #206 Chat RAG on 2026-06-05; includes the "audit follow-up" hardening wave #114–#138 post-1.10)
- **ADRs**: see the canonical index in `docs/adr/README.md` (single source of truth for the count and the status). ADR 0006 partially superseded by 0015; the post-1.10 hardening was documented retroactively in ADR 0019 (tenant isolation defense-in-depth), 0020 (token rotation) and 0021 (soft-delete)
- **Migrations**: current schema described in `docs/engineering/data-model.md` (single source). Recent milestones: V016 RLS (schema), V017 Customer Confidence, V018 invite token hash, V019/V020 full RLS + auth-aware scope, V021 `meeting_embeddings`
- **NLP Worker**: **92.4%** statement coverage over `nora_nlp` (863 tests) — *measured 2026-08-17*. The PII shield alone is at **96.6%**, and it is the only worker scope CI gates (`--cov-fail-under=90`)
- **Spring backend**: **77.3%** instruction / **61.6%** branch / 78.1% line (578 tests) — *measured 2026-08-17*. The areas ADR 0018 names critical are above their target: IAM packages **90.9%** instruction, Auth/identity packages **93.8%**, `PolicyEvaluator` **96.3%** instruction / 86.0% branch
- **Next.js web**: no coverage figure exists. There is an automated suite — three Playwright e2e specs (security headers, route protection, CSP violations) run by the `web` job — but no coverage instrumentation, so the honest statement is *unmeasured*, not "0%". Unit-level coverage via Vitest is decided and not yet built
- These three lines are no longer hand-measured. `scripts/report-coverage.sh` runs in the `api` and `worker` jobs on every CI run and prints the current figures to the job log and the run summary page; re-read them there rather than trusting the date above

> The previous figures on these lines — worker 87%, backend 67% with 53% branch — were measured on **2026-05-13** and carried unchanged for three months. Both went up, so leaving them stale was not harmless in only one direction: the document was understating work that had been done. Worth recording alongside that: the old backend figure was written down as *instruction* coverage in ADR 0018 and as *line* coverage in `docs/engineering/standards.md`, which is two different numbers under one label. The lines above name the counter.
- **Azure dev cost**: R$110-180/month (within the R$500 of Azure for Students)

## 2. Upcoming Sub-phases — 1.11, 1.12, 1.13+

> Target windows are **agentic** (Opus models for architecture/security/data and Sonnet models for focused implementation/UI/tests, in parallel via worktrees), not human-hours. They reflect the real complexity of each slice, not human effort.

> **Real status (2026-05-23, reconciled):** **Sub-phase 1.11 is mostly delivered** (~3 of 6 code items): **(a) Customer Confidence** (#148), **(b) AUTH_FILTER_HARD_CAP** (silent ceiling of 500 removed — batched scanning in `MeetingService.listAllForAuthFilter`) and **(c) PolicyEvaluator operators** (`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan`, fail-closed retained) — Done. Missing: **(e) realistic TOTVS seed** (the current synthetics are generic acme/northwind — and there is tension with the dataset's "Hardcode Policy", which forbids a TOTVS tenant), **(f) demo script**. (d) UX polish is partial/subjective. From **Sub-phase 1.12**, item (b) **RLS has already been delivered ahead of schedule** (schema in V016, completed in V019/V020).

| Sub-phase | Target window | Scope | Prerequisites |
|---|---|---|---|
| **1.11 — Demo Polish Plan A** | 2-3 agentic weeks (target: close with buffer before the pitch) | (a) **Done (#148)** — **Minimum Customer Confidence** via ADR 0015: schema → persistence (V017) → read-only endpoint `GET /meetings/{id}` → `CustomerConfidenceCard` in MeetingDetail<br>(b) **Done (1.11b)** — **`AUTH_FILTER_HARD_CAP` fix**: silent ceiling of 500 removed; `MeetingService.listAllForAuthFilter` scans in batches before the IAM filter. SQL pushdown via JSONB+GIN remains a future performance optimisation<br>(c) **Done (1.11c)** — **`PolicyEvaluator` expansion**: `StringIn`, `StringLike`, `DateGreaterThan`, `DateLessThan` implemented (fail-closed retained for an unknown operator and a missing attribute)<br>(d) **Polished internal UX**: editorial v3 on the auth pages, fix of the improvised `position:fixed` on login, fine adjustments in MeetingDetail and the dashboard<br>(e) **Realistic TOTVS synthetic seed**: 5-7 meetings with TOTVS vocabulary (Protheus, RM, Datasul, Fluig, RM Consult) + 3 demo tenants (1 with Customer Confidence enabled) + Camila/Rafael/Lucas users + example policies<br>(f) **Demo script**: an 8-10 minute script covering Core (Lucas uploads, sees the summary, marks a task) → Enterprise (Camila configures a policy, Rafael sees only his scope) → Customer Confidence (Rafael sees the lead's signals). Includes a plan B if something fails live | Sub-phase 1.10 closed (docs refresh consolidated); ADR 0015 created and approved |
| **1.12 — Production Hardening** | 1-2 agentic weeks | (a) **`rg-nora-prod` separate** from `rg-nora-dev` (total isolation: distinct KV, Postgres, Storage, ACA env)<br>(b) **Postgres RLS** — schema delivered in **V016** and completed in **V019/V020** (full RLS + auth-aware scope); only the operational cutover/enforcement in prod remains (`nora_app` role `NOBYPASSRLS` + `nora.security.rls.enforce` flag), as per the cutover runbook (ADR 0026/0028)<br>(c) **Monitoring alerts** in Azure Monitor: P95 latency, 5xx rate, Postgres CPU/conn pool, KV access failures, Speech token exhaustion<br>(d) **Operational LGPD** — **Done (ADR 0029)**: `DELETE /privacy/meetings/{id}` (right to be forgotten) + scheduled `RetentionSweeper` + `PrivacyFlowIntegrationTest`. What remains is evolving the global `audit_events` table (not just IAM) and the retention declared per tenant<br>(e) **DR runbook** (`docs/operations/dr-runbook.md`): Postgres backup + restore drill + declared RTO/RPO + Bicep redeploy from zero<br>(f) **Secrets rotation**: rotation policy for JWT_SECRET, OPENAI_API_KEY, Postgres ConnectionString via KV versions + automatic redeploy<br>(g) **Test coverage targets** (ADR 0018 to be created): >85% in critical areas (IAM, Auth, PII, LLM analyzer), >60% for the rest, >50% web on the main pages, >70% backend branch coverage. Add Vitest on the web | Sub-phase 1.11 closed; ADR 0016 (rg-prod strategy) and ADR 0018 (coverage targets) to be created |
| **1.13+ — Post-pitch (15/06 onwards)** | Depends on the outcome of Plan A | **Scenario A — Plan A moves (TOTVS signals interest):** technical-commercial pitch dossier · due-diligence material (an expanded SECURITY.md, STRIDE threat model, complete LGPD checklist, multi-tenant cost projection) · support for the 1st commercial meeting · contracted POC roadmap<br>**Scenario B — Plan A neutral/negative:** Plan C content first (LinkedIn post covering NORA + 8 Azure pitfalls + AWS-style IAM + Productivity Score; Twitter thread; portfolio case) + Plan B commercial pivot (create a landing page with pre-order, define pilot pricing >= R$300/tenant/month based on the unit economics in audit §13, identify 3-5 B2B leads outside TOTVS) | FIAP/TOTVS pitch held 15/06/2026 |

### Criteria for a "closed Sub-phase"

For a sub-phase to be considered **closed** (`DONE`):

1. All the PRs in scope merged into `main` with CI green
2. Manual verification executed (minimum smoke test of the delivered flow)
3. New debts catalogued in the audit/memory (not silenced)
4. ADR created if the sub-phase introduced a durable decision
5. Roadmap updated, moving the sub-phase from "Upcoming" to "History"

## 3. Post-MVP phase (long term)

> The vision of phases 4-9 of NORA's original execution plan. Several of these phases have already been **absorbed by the 1.X sub-phases** or **became Won't Have v1 with a reactivation criterion**. This section keeps the long-term product vision without being an execution plan.

| Original phase | Status today | Where it was absorbed (or criterion) |
|---|---|---|
| **Complete Productivity Score** (US45-US47) | **Partially DONE** | US45 + US46 delivered in Sub-phase 1.8. US47 (MCP project state) = Won't Have v1 — reactivate when the first tenant asks for Jira/Linear integration |
| **Complete Customer Confidence** (US48-US51) | **Partially DONE** | US48-US49 delivered full-stack in #148 (V017 schema + worker emit + authoritative trend per account + `GET /meetings/{id}` + `CustomerConfidenceCard`). Aggregated Account Health + alerts (US50-US51) = post-pilot when 3+ tenants have >10 meetings each |
| **Audio upload** (US08) | MISSING / W | Reactivate when repeated demand in a pilot indicates it (>30% of the requested uploads are audio) or Azure Speech batch becomes cheap. Probably Pilot+1 |
| **MCPs (Calendar, Tasks, CRM)** (US27-US29, US47) | MISSING / W | Reactivate when the first paying Enterprise tenant asks for a concrete integration. They remain deferred as a roadmap concept (with no implementation in the monorepo) |
| **Desktop finalisation** | Partially DONE | Windows capture (WASAPI loopback) works. The client is Windows-only — the macOS (BlackHole) and Linux (PulseAudio) paths were deleted, along with the ScreenCaptureKit debt, because none of them had been exercised. Real Windows/Teams validation still pending |
| **SSO Entra ID / SAML** (US05) | MISSING / W | Reactivate when the first paying Enterprise tenant explicitly requires it |
| **Polish + Demo + Pitch** | in progress in Sub-phase 1.11 | Sub-phase 1.11 (Demo Polish Plan A) covers this for the 15/06 pitch |
| **Trends panel** (US21) | MISSING / C | Prerequisite US15 (semantic search) already delivered — provider-agnostic embeddings via pgvector + HTTP embedding client (PR #206, migration V021). The panel's own temporal analysis is still missing |
| **Policy templates + Simulator** (US41 + US43) | MISSING / S | US43 (simulator) will probably come up in 1.11 — without it, debugging policies is blind for the first paid pilot. US41 (templates) only when >3 tenants ask for fast onboarding |
| **Permission boundaries** (US44) | MISSING / C | Reactivate when organisational hierarchy + IAM delegation becomes a necessity. Probably Pilot+1 |
| **Tenant metrics and Export** (US33 + US34) | MISSING / S | Reactivate when 5+ paying tenants are in a pilot — without data it is not worth building |

### Long-term product vision (this is not an execution plan)

NORA evolves across three horizons:

1. **Horizon H1 (today → 15/06/2026 pitch)**: Plan A validation with TOTVS via a polished demo + visible Customer Confidence + the IAM model
2. **Horizon H2 (Q3-Q4 2026)**: first paid pilots (Plan A if TOTVS signed, or Plan B if it was a commercial pivot). Focus: complete Customer Confidence, tenant metrics, policy simulator, observability. Pricing floor R$300/tenant/month (dev/pilot unit economics estimate ~R$210/tenant in infra)
3. **Horizon H3 (2027+)**: commercial scale. MCPs (Calendar, Jira, Salesforce/HubSpot), corporate SSO, Audio upload via Whisper/Azure Speech batch, temporal Account Health, multi-region. Eventual exit via acquisition (TOTVS or a competitor) or organic SaaS growth

### Notes on cross-cutting prerequisites

- **Complete Customer Confidence** depends on retention/LGPD before it becomes paid (Account Health has retention implications). The operational LGPD base has already been delivered via ADR 0029 (`DELETE /privacy/meetings/{id}` + `RetentionSweeper`)
- **MCPs** depend on the evolution of the shared contracts schema (`packages/shared-contracts/`), which today already contains `error-codes.md`, `pii-types.json`, `processing-status.json` and `README.md`
- **SSO Entra ID** depends on SCIM/JIT provisioning + mapping of claims → IAM groups. Non-trivial; reserve 2-3 dedicated sub-phases
- **Multi-region** depends on stable RLS in prod (1.12) + Postgres replication + Storage replication. Reserve its own phase

## 4. Process decisions

Some decisions about **how** we work (not **what** to deliver) that affect the roadmap:

- **1.X numbering**: while the product is MVP/pre-GA. Version 2.X starts when the first paying tenant is in production (not dev/pilot)
- **Worktrees + parallel subagents**: work split by slice, merged via PR into `main`. Drift between worktrees is real debt (lesson from Sub-phase 1.1)
- **Audit as the basis for docs**: before each Docs Refresh (1.10, 1.13, ...) a read-only audit anchored in PR/migration/path is run. Without an audit, docs become fiction
- **Immutable ADRs**: once accepted, we do not edit them — we create a successor. ADR 0009 has a minor divergence between the doc (Proposed) and the index (accepted), to be resolved in 1.10
- **Sub-phases ≠ Sprints**: there is no fixed time cadence. A sub-phase closes when the scope is delivered, not when a timer runs out

## Document History

| Version | Date | Description |
|---|---|---|
| 1.0 | 2026-05-14 | **Initial creation** as a living roadmap. Replaces `docs/plano-de-execucao.md` (discontinued — it described a week-by-week split between two developers, outside the current real flow). Consolidates the history of the 11 Sub-phases (1.0-1.10) with a cross-check of audit `2026-05-13-audit-pre-subfase-1.10.md` §11. Defines the upcoming Sub-phases 1.11 (Demo Polish Plan A), 1.12 (Production Hardening), 1.13+ (post-pitch) with explicit scope and prerequisites. Includes the long-term vision (3 horizons H1-H3) and process notes |
| 1.1 | 2026-06-06 | Doc x code reconciliation + standardisation |
| 1.2 | 2026-08-17 | Cumulative metrics: coverage re-measured in CI after three months of "to be re-measured". Backend 77.3% instruction / 61.6% branch (was 67% / 53%), worker 92.4% (was 87%), web restated as unmeasured rather than 0%. `scripts/report-coverage.sh` now publishes all of it on every run, so the figures stop being a snapshot |
