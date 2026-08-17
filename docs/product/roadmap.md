# Roadmap — NORA

> **What this document is:** the record of what shipped, in the order it shipped, and the declared
> plan for what is ahead. It replaces the old `docs/plano-de-execucao.md` (archived — discontinued
> on 2026-05-14, because it described a week-by-week split between two developers that never matched
> the real flow: one maintainer, working with AI coding agents in parallel worktrees).
>
> **What it is not:** the authority on anything else. This document used to call itself "living and
> official"; between 2026-05-23 and 2026-08-17 its §2 planned against an Azure subscription that
> ADR 0034 had already shut down, so the claim was false in the one section that made it. It is
> dropped rather than repeated.
>
> **Where the authority actually sits:**
>
> | To know | Read |
> |---|---|
> | What is in scope, and what was closed | the accepted ADRs — [0038](../adr/0038-post-pitch-scope-realignment.md) for scope, [0039](../adr/0039-cloud-stt-openai-ephemeral-token.md) for STT, [0040](../adr/0040-pii-scope-analysis-transcription-subprocessor.md) for the PII boundary, [0041](../adr/0041-nora-as-mcp-server.md) for MCP |
> | Per-story status (DONE / PARTIAL / MISSING / WONT) | [`docs/product/backlog.md`](backlog.md) — the single source. **This document does not restate it** |
> | The schema, and how far the migrations go | [`docs/engineering/data-model.md`](../engineering/data-model.md) |
> | What is deployed, and on what | [`README.md`](../../README.md) §Current state and [ADR 0036](../adr/0036-substrate-is-a-single-bare-metal-host.md) |
> | Test coverage | [`docs/engineering/standards.md`](../engineering/standards.md) §Test coverage targets |
>
> **Structure:** §1 what happened · §2 what is ahead · §3 where the original execution plan's later
> phases went · §4 process decisions.

## 1. History

The `1.X` numbering corresponds to a coherent delivery slice, normally 1+ merged PRs that deliver verifiable value. Sub-phases may be implicit (accumulated pre-audit) or explicit (planned + executed).

**Some of what follows describes infrastructure that no longer exists.** Sub-phases 1.4, 1.6, 1.7
and 1.9 were built on Azure; the subscription was shut down on 2026-08-07 (ADR 0034) and the
substrate corrected to a single bare-metal host on 2026-08-10 (ADR 0036). Those rows are **kept as
written and marked historical below** — they are the record of what was actually done, and editing
the past so it agrees with today's decision is a mistake this repository has already made and
undone once.

| Sub-phase | Date | PRs | Delivery summary | Related ADRs |
|---|---|---|---|---|
| **1.0 (implicit, pre-audit)** | up to 2026-05-10 | #1, #3-#8, #22-#25, #29-#50 | Monorepo scaffolding; e-mail/password auth (US01-US04) with JWT; text upload (US07); LLM worker (US11-US14); Tauri desktop (US09); AWS-style IAM (US35-US40); Customer Confidence LLM schema (without persistence); Productivity opt-in; web auth flow; analysis persistence in the DB. The base for everything that came afterwards. | 0001-0009 |
| **1.1 — DS Sprint 1+2** | 2026-05-11 | #54 | EDA notebook (`notebooks/01-tf-idf-eda-meetings.ipynb` with 26 cells) + expanded synthetic dataset (12 .txt + 3 .vtt + 2 .srt + 3 JSON contexts) + `packages/nlp-baseline/` package (3 TF-IDF modules, 52 tests) | ADR 0010 |
| **1.2 — Enterprise Gaps** | 2026-05-12 | #55 | US32 (tenant corporate domain) + US06 (e-mail invitation) + Monaco JSON PolicyEditor with syntax highlighting + schema validation. 41 new tests. Approach: "Step 0 contracts before implementing" worked (schema-first reduced rework) | ADR 0011 |
| **1.3 — PII Hardening + UX** | 2026-05-12 | #59 | PII Shield expansion with PERSON_NAME (BR) — ~270 names + negative list of ~80 terms. Upload polling (web). Markdown rendering in the summary. httpOnly cookies (`nora_access` 15min JWT, `nora_refresh` 30d stateful UUID in V011). Catalogued debts: auth audit log, `logoutAllSessions` with no REST endpoint, PII ADDRESS | ADR 0012 |
| **1.4 — Bicep IaC** *(historical — Azure)* | 2026-05-12 | #62 | `main.bicep` + 8 modules (containerappsenv, containerapp, postgres, storage, keyvault, appinsights, loganalytics, speech) + dev bicepparam. Estimated dev cost: R$110-180/month. `infra` CI job in `ci.yml` | — |
| **1.5 — Desktop Briefing** | 2026-05-12 | (no PR — vault) | Vault `40-desktop-handoff/2026-05-12-update-pos-subfase-1.4.md`. Briefing for the friend who owns the desktop with Rust↔Python NDJSON contracts + macOS BlackHole roadmap (already merged in PR #37) + ScreenCaptureKit debt | — |
| **1.6 — Build/Push GHCR** | 2026-05-12 | #63 | `build-images.yml` workflow + 3 Dockerfiles (api, worker, web). Publishes `ghcr.io/sys0xff/nora-{api,worker,web}:{latest, sha-XXXXXXX, ref}`. Public images (manual step in the GHCR settings) | — |
| **1.7 — Deploy workflow + SP OIDC** *(historical — Azure)* | 2026-05-12 | #64 | `deploy-infra.yml` + Service Principal `sp-nora-github-deploy` (`Contributor` + `Role Based Access Control Administrator` roles on `rg-nora-dev`) + 3 federated credentials (main / pull_request / environment:dev). Lesson: a separate fed cred per (branch, environment) | — |
| **1.8 — Productivity Score full-stack** | 2026-05-12/13 | #67 | Migration V012 (`meeting_goals` + `productivity_assessments` tables) + worker model + stub + LLM analyzer + Spring backend endpoints + web 3 components (`MeetingGoalForm`, `MeetingProductivitySection`, `ProductivityScoreCard`). Lesson: subagents in a worktree may have a different CWD — watch out for absolute paths | ADR 0005 |
| **1.9 (implicit) — Real Azure deploy** *(historical — Azure)* | 2026-05-13 | #68-#75 | 8 infra fixes resolved: `centralus` region restriction, real GHCR images (not placeholders), Azure Speech + 2 UAIs + KV references in Container Apps, complete env vars, Postgres extensions via `azure.extensions`. Deploy success: `https://nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io` (dead since ADR 0034). 8 Azure for Students pitfalls catalogued (region restriction, per-service offer restriction, RP does not auto-register, KV soft-delete, Cognitive Services soft-delete, SpeechServices networkAcls, Contributor cannot create role assignments, Postgres CREATE EXTENSION blocked) | — |
| **1.10 — Docs Refresh** | 2026-05-13/14 | #76 | Audit `2026-05-13-audit-pre-subfase-1.10.md` (13 sections) + reorganisation of `docs/` into `product/` + `engineering/` + `operations/` + `security/` + `challenge/` + `adr/`. LICENSE AGPL-3.0 + SECURITY.md created. Memory consolidated. Audit, critical review and approval done as a block. | **6 new ADRs: 0013 (CSS strategy, proposed — Design refines) · 0014 (defer 14 US post-MVP, accepted as a block — since superseded by 0038) · 0015 (Customer Confidence minimum persistence in 1.11, accepted vote a) · 0016 (production-readiness checklist, proposed — since partially superseded by 0034) · 0017 (LICENSE AGPL-3.0, accepted) · 0018 (test coverage targets, accepted)** |
| **Post-1.10 — Hardening (audit follow-ups)** | 2026-05-15+ | #114–#138 | Security/infra wave labelled "audit follow-up #N", **outside the history of named sub-phases**: V013 soft-delete (`deleted_at` + `@SQLRestriction`) · V014 refresh-token rotation + reuse-detection · V015 composite isolation FK · **V016 Row-Level Security** (+ `TenantRlsAspect`) · JWT RS256 + JWKS · expanded auth audit log · App Insights Java agent (historical — Azure) · upload hardening. | ADR 0019 (RLS + composite FK), ADR 0020 (token rotation), ADR 0021 (soft-delete) — created retroactively in the 2026-05-21 audit |
| **Post-1.10 — Customer Confidence (1.11a)** | 2026-05-21 | #148 | ADR 0015 implemented full-stack in 4 slices: migration **V017** (`customer_accounts` + `meeting_account_links` + `customer_confidence_assessments` + `customer_buying_signals` + `customer_objections`, all with RLS) · worker emits `customerConfidence` (sales→present, internal→null) · backend persists in the pipeline with an **authoritative trend** (±5 band) + get-or-create of the account · `GET /meetings/{id}` expands the return · `CustomerConfidenceCard` UI. CI green with Testcontainers. | ADR 0015 (applied) |
| **Post-1.10 — IAM/list debts (1.11b+c)** | 2026-05-23 | (1.11 b+c) | `PolicyEvaluator` gained `StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` (fail-closed retained) · `AUTH_FILTER_HARD_CAP` (silent ceiling of 500) removed via batched scanning (`MeetingService.listAllForAuthFilter`). New unit tests (PolicyEvaluator + MeetingService); `IamScopingIntegrationTest` untouched. | — |

> **Update (reconciliation 2026-05-21, post-PR #148):** (1) **Customer Confidence (US48-49) WAS implemented** full-stack in #148: V017 + worker emit + `AnalysisService` wiring (server-side trend) + `GET /meetings/{id}` + `CustomerConfidenceCard`. The landing page's narrative debt is **resolved**. (2) It was delivered as **V017** (the V013 slot from ADR 0015 ended up as soft-delete, #114). (3) **RLS**: schema delivered in V016 and completed in V019/V020 (full RLS + auth-aware scope) — only the operational cutover/enforcement in prod remains (ADR 0026/0028). (4) **Sub-phase 1.11 has already been delivered** in the code items: (a) Customer Confidence DONE; (b) AUTH_FILTER_HARD_CAP removed; (c) PolicyEvaluator operators implemented; remaining are (e) TOTVS seed and (f) demo script. Aggregated Account Health (US50-US51) **remains deferred** via ADR 0014.
>
> *Read the note above as of its own date.* Its point (4) still holds. Its point (3) was closed on
> 2026-08-10 — RLS is enforced on the deployed stack — and its point about US50/US51 was overtaken
> on 2026-08-16: ADR 0038 §4 closed them outright, so they are no longer deferred but **WONT**.

### After 2026-05-23 — what shipped, by theme

Sub-phase numbering stopped being maintained after 1.11b+c, while delivery did not. Reconstructing
a numbering after the fact would invent a plan that never existed, so this stretch is recorded by
theme instead, in date order, with the ADR that decided it and the PRs that carried it. **Delivery
status per user story is in [`backlog.md`](backlog.md); this table is chronology, not status.**

| Theme | Date | PRs | What it delivered | ADRs |
|---|---|---|---|---|
| **Chat-first Core + AI chat** | 2026-05-28 | #165 | The web surface became conversational: streaming chat through a BFF route (`/api/chat`) so the provider key never reaches the browser, plus Projects (meetings grouped by tag) and the meeting detail rework | ADR 0004 |
| **Control plane + operator console** | 2026-05-28/06-01 | #172 | Separate platform datasource, model catalogue editable without a deploy, runtime model resolution per service, AI cost telemetry, and `apps/admin` as a fifth surface reached only through Cloudflare Access | ADR 0022, 0023, 0024, 0025 |
| **RLS completed, cutover designed** | 2026-06-04 | — | V019/V020: full RLS coverage plus auth-aware scope, and the three-role provisioning + enforce cutover that ADR 0026 got wrong and ADR 0028 fixed | ADR 0026, 0028 |
| **Branch protection + required CI gate** | 2026-06-04 | — | `main` protected, `ci-gate` as the aggregating required check, and the admin-bypass path written down for a solo owner | ADR 0027 |
| **Operational LGPD** | 2026-06-05 | #204 | `DELETE /privacy/meetings/{id}` — a physical hard delete cascading to the raw transcript — plus the opt-in `RetentionSweeper` and `PrivacyFlowIntegrationTest` | ADR 0029 |
| **Semantic search / RAG** | 2026-06-05 | #206 | Migration V021 (`meeting_embeddings`), `EmbeddingService` + `HttpEmbeddingClient` with provider-agnostic embeddings, `GET /meetings/search`, consumed by the chat as RAG context. **Similarity is computed in Java over a JSON array in a `TEXT` column — `pgvector` is available in the image and deliberately never created** (ADR 0034 §excluded scope) | — |
| **Persistent chat sessions + design v3** | 2026-06-11 | #219, #225 | Migration V022 (`chat_session`, `chat_message`, both with RLS), the session sidebar, and the v3 redesign across the Core pages — the real content of what 1.11(d) called "polished internal UX" | — |
| **NORA Flows** | 2026-06-11/13 | #220, #223, #226, #227, #245, #246, #248, #250, #252 | In-process post-commit event bus, workflow engine, migration V023, the React Flow canvas, execution history, and the action set (e-mail, webhook, chat tools, calendar follow-up) | ADR 0030, 0032 |
| **OAuth integrations** | 2026-06-11/13 | #221, #226, #247, #249 | Nine providers across migrations V024–V026, HMAC-signed state, tokens AES-GCM encrypted at rest, and three connection models (OAuth dance, pairing code, pasted token) | ADR 0031 |
| **Report, export, batch, split** | 2026-06-11/13 | #225, #253, #254, #255 | Printable A4 meeting report with no PDF library, client-side Markdown export, multi-file upload, and preview-then-confirm splitting of a file containing several meetings | — |
| **PII on the chat path** | 2026-07-06 | — | Structured PII redacted in the BFF before both the chat provider and the embeddings provider, with PERSON_NAME on that path declared as accepted residue rather than silently missing | ADR 0033 |
| **Security audit follow-up + deny-by-default** | 2026-08-09/10 | #382, #407 | 15 of 16 findings closed, including the composite isolation FK on the IAM attachment tables (**V027**), and `@RequiresPermission` applied deny-by-default across every endpoint | ADR 0019 |
| **Repository normalised to English** | 2026-08-10 | #410 | The whole tree translated, plus `scripts/check-doc-links.sh` and `scripts/check-language.sh` to keep it that way. Both are nets, not proofs — the language guard keys on accents plus a curated word list and never sees a commit message | — |
| **Exit from Azure → one bare-metal host** | 2026-08-07/10 | — | Azure shut down entirely (no subscription, no export, nothing to decommission); Docker Compose on a single bare-metal Ubuntu host, Cloudflare Tunnel as the only HTTP ingress, SOPS + age for secrets, pull-based deploy, Prometheus/Loki/Grafana in place of Application Insights, and SSH carried over the same tunnel. RLS **enforced on the deployed stack** from 2026-08-10 | ADR 0034, 0036, 0037 |
| **STT decided twice** | 2026-08-07 → 2026-08-16 | — | ADR 0035 moved transcription onto the client (Whisper in Tauri) when the Azure Speech resource died; ADR 0039 superseded it on 2026-08-16 and moved transcription to OpenAI's streaming API behind an ephemeral session token. **Neither the deletion of the on-device engine nor the new path is built** — see §2 | ADR 0035, 0039 |
| **Audit → realignment (86 findings, 19 decisions)** | 2026-08-16 | #457–#471 | A seven-front audit left 86 surviving findings and the maintainer closed 19 decisions on them. First wave in the code: Azure Speech deleted from both halves, the desktop reduced to Windows and its unrendered local UI removed, three container-level exposures closed, the chat given the company context, Flows trigger parity, honest retention semantics, and the operator console made fail-closed and put inside `ci-gate` | ADR 0038, 0039, 0040, 0041 |
| **Documentation reconciliation** | 2026-08-16/17 | #478–#484 | The four realignment ADRs written; the data model brought to V027; the whole HTTP surface described in `openapi.yaml` with a CI check gating drift; `AGENTS.md`, the architecture document, the backlog (85 user stories, every status re-derived from the code) and the vision reconciled | ADR 0038–0041 |
| **A unit-test runner for `apps/web`** | 2026-08-17 | — | Vitest plus v8 coverage in the `web` job, closing the runner ADR 0018 planned for Sub-phase 1.12 and never built. 85 tests over four `src/lib` modules — the shared `request()` behind all 66 `client.ts` wrappers, the Markdown report builder, the BFF PII redaction (with a mirror test that reads the worker's PII Shield off disk) and the password policy (mirroring the backend's constants and DTO bounds). **No page and no component is tested**, so whole-app coverage is 5.5%; the gate is per module and only on three files | ADR 0018, 0042 |

### Cumulative metrics

- **334 PRs** merged into `main` (measured 2026-08-17; the most recent merged number is #484). The count includes the "audit follow-up" hardening wave #114–#138 and the 2026-08 realignment wave
- **ADRs**: 44 numbered, of which seven (0038–0044) record the August 2026 realignment and its first builds. `docs/adr/README.md` is the canonical index and the single source for status — several ADRs are partially superseded and the index is where that is tracked
- **Migrations**: 27, ceiling `V027__composite_fk_iam_user_attachments.sql`. `docs/engineering/data-model.md` is the single source for the schema. Recent milestones: V016/V019/V020 RLS, V017 Customer Confidence, V021 `meeting_embeddings`, V022 chat sessions, V023 workflows, V024–V026 integration connections, V027 composite FK on the IAM attachment tables
- **HTTP surface**: 16 controllers in `services/api`, described in full by `docs/api/openapi.yaml`, whose coverage against the code is checked in CI by `scripts/check-openapi-coverage.sh`
- **Test coverage**: **not restated here.** The figures this section carried until 2026-08-17 — worker 87%, backend 67% line / 53% branch, web 0% — were measured on **2026-05-13**, carried a "to be re-measured" note that nobody acted on, and were quoted for three months across the pitch material. They are withdrawn rather than hand-refreshed: `docs/engineering/standards.md` §Test coverage targets is the single source, and `scripts/report-coverage.sh` now re-measures on every run in the `api`, `worker` and `web` jobs. Read the last CI run, not this bullet
- **Infrastructure cost**: **no number.** The line this section used to carry ("Azure dev cost R$110-180/month") described a subscription ADR 0034 shut down on 2026-08-07. The bare-metal host's real running cost has never been measured, and an unmeasured number is worse than none

## 2. What is ahead

Three facts from [ADR 0038](../adr/0038-post-pitch-scope-realignment.md) frame everything in this
section, and none of them was true when the previous §2 was written on 2026-05-14:

1. **The destination is a FIAP deliverable plus a portfolio.** NORA is not operating commercially
   and is not acquiring users. Nothing below is planned for a customer, and anything argued from
   "when we have customers" is arguing from a future that is not planned (ADR 0038 §1).
2. **The maintainer is solo.** Scope that does not fit one person is scope to cut, not to
   distribute.
3. **The 2026-06-15 FIAP × TOTVS pitch was held.** The gate that ADR 0014 hung every deferral on
   expired two months before anyone wrote down what happens next; ADR 0038 is that writing.

The previous version of this section planned a separate `rg-nora-prod`, Azure Monitor alert rules,
Key Vault secret rotation and a disaster-recovery runbook redeployed from Bicep. **All of it is
deleted, not rescheduled.** Azure is gone (ADR 0034/0036), and the operations block it described is
a declared deferral with a written reason per item (ADR 0038 §6). The document it named,
`docs/operations/dr-runbook.md`, was never written and is not planned.

### 2.1 Closing out Sub-phase 1.11

Two of the six items of 1.11 never shipped. (a), (b) and (c) are in §1; (d) "polished internal UX"
was subjective and its real content landed with the v3 redesign (#219, #225) — it is retired rather
than tracked, because there is no state in which it is finishable.

| Item | State on 2026-08-17 |
|---|---|
| **(e) Realistic demonstration seed** | **In progress, not in `main`.** A seed that populates a local environment with tenants, users, IAM policies and analysed meetings, driven through the product's own HTTP API rather than raw SQL — the schema has RLS, soft-delete and composite FKs, and a raw insert bypasses every validation the product applies |
| **(f) Demo script** | **In progress, not in `main`.** This document and `docs/challenge/fiap-challenge-2026.md` used to promise two different durations for a script that did not exist. The duration will be declared in the demo document and in no other file, this one included |

Neither is claimed as delivered here. When they land, they are reachable from
`docs/challenge/` and `scripts/`, and this table is what should be updated.

There is a real tension in (e) worth stating rather than discovering: a demonstration seed with a
specific vendor's vocabulary sits next to the non-negotiable "**no hardcoded TOTVS in product
code**". The distinction is that a seed is *data*. What the rule forbids is the product knowing a
vendor — a branch in the analyzer, a term in a prompt, a default in the backend. A demo tenant
whose company context names that vendor's products goes through the same `PUT /tenant/context` any
customer would use, and is the proof that the context is configurable.

### 2.2 The rubric artefacts (in progress, mostly landed)

Not a feature slice. The FIAP jury reads the backlog, the data model, the architecture and the API
specification, and on 2026-08-16 all four described a product from two quarters earlier. Most of
this is merged (#478–#484, listed in §1). What is still open:

- the Oracle mirror of the data model (`docs/engineering/data-model-oracle.md`), which covers
  V001–V016 while the schema is at V027, and says so in its own header;
- `docs/challenge/fiap-challenge-2026.md`, which still writes the 2026-06-15 pitch in the future
  tense and carries three unverified deadlines;
- the coverage measurement described in §1 (PR #477);
- items (e) and (f) above.

### 2.3 The decided builds

Every row here was decided by an accepted ADR while none of it was built. **A row struck through is
delivered**; the rest are still ahead. Per-story status stays in [`backlog.md`](backlog.md); this
table is what each build is and what decided it.

| Build | Decided by | Note |
|---|---|---|
| **Cloud STT with an ephemeral session token** | ADR 0039 (supersedes 0035) | The desktop connects to OpenAI's streaming API directly with a short-lived credential minted by the backend; the key never leaves the server and the audio never crosses NORA's infrastructure. The consequence is stated in the ADR and must not be softened: per-tenant attribution happens at session issuance, so the cost telemetry of ADR 0024 is an **estimate**, not a measurement. The tree still carries the on-device engine (`whisper-rs`, `stt_local.rs`, `whisper_model.rs`); it is removed **by that migration**, not before, or the desktop is left with no transcription at all |
| **NORA as an MCP server (inbound)** | ADR 0041 | An inbound adapter inside `services/api`, every tool call resolving a real IAM principal through `PolicyEvaluator`, a tenant-scoped bearer token stored only as a SHA-256 hash, read-only first cut. The invariant to test against: an MCP client can never see more than the user it acts for sees in the web application. This is the one item the realignment **adds**; ADR 0041 records why, and the case against it |
| ~~**PII Shield: ADDRESS, and the shapes that still leak**~~ **Delivered** | ADR 0012 (debt), ADR 0040 (scope), ADR 0043 (the work) | ADDRESS is emitted since ADR 0043, as a deterministic street-type recogniser rather than NER. Three of the shapes the corpus catalogued are closed — the genitive and phrase-head leak, a product name between the halves of a name, and an all-caps pair in running prose — and the measured leak rate went 9.60% → 2.12% with the false-redaction rate falling as well, both on a corpus held identical across the two measurements. What remains is named and dated rather than left open: `test_pii_corpus.py` now carries a goal of 1.0% leak and 4.0% false redaction by 2027-06-30, each pointing at the one shape that stands in the way |
| ~~**A unit-test runner for `apps/web`**~~ | **Delivered** — ADR 0018, ADR 0042 | The web app had three Playwright e2e specs and no unit runner, so ADR 0018's coverage line had nothing to measure on the largest surface. Vitest closed it; §1 has what that delivery contained, and ADR 0042 has which parts of it are a gate |
| **US21 trends panel · ~~US25 task export~~ · US31 company-context history · US43 policy simulator** | ADR 0038 §5 | Four stories ADR 0014 deferred and ADR 0038 brought back; **US25 is delivered** and the other three are not. US21's stated criterion ("after US15 is on") had already been met by #206 and nobody noticed. US43 matters out of proportion to its size: IAM is the Enterprise tier's main artefact, and a simulator is what makes it demonstrable instead of merely present. US25 needed no ADR: it added no route and no decision that outlives it — CSV and Markdown built client-side from the list the tasks screen already holds, on the shape US60 set for the meeting report |
| ~~**Embeddings backfill**~~ | **Delivered** — ADR 0044 | Meetings analysed before #206, or while the provider was failing, had no embedding and were invisible to semantic search and to the chat's grounding, permanently: indexing is best-effort and nothing ever came back. `GET`/`POST /admin/platform/embeddings/backfill` reindex from the summary already stored on the meeting — no second LLM analysis — bounded per run and billed through the existing cost telemetry. Same mechanism repairs a change of embedding model |

### 2.4 The substrate these land on

Stated here because the previous §2 planned against the wrong one for three months. What exists
today, measured, not assumed:

- **One bare-metal Ubuntu host**, no hypervisor, Docker Compose project `nora` (ADR 0036).
- **Cloudflare Tunnel is the only HTTP ingress** — no web port is published — with Caddy routing by
  Host behind it, and SSH carried over the same tunnel behind an Access allow-list (ADR 0025, 0037).
- **Secrets in SOPS + age**, the private key on the host only.
- **Deploy is pull, and its consumer was never written.** `deploy-host.yml` publishes an immutable
  release pointer (`release/prod/<sha>`) and moves `release/prod/current`; nothing on the host reads
  either. The installed timer runs `deploy.sh --if-changed` with no `--tag`, so it re-probes the tag
  already running — whose digest never changes — and never discovers a newer release. Rolling
  forward is a manual `deploy.sh --tag sha-<short>`. The workflow's own header says so, and
  ADR 0038 §6e records it as a declared deferral: one operator with SSH is an acceptable substitute
  for an automatic consumer. **Writing that consumer is the one substrate item that is real work
  rather than a flag flip**, and it is not scheduled.
- **Observability without paging.** Prometheus, Loki, Grafana and a provisioned dashboard exist;
  there is no Alertmanager, no `rule_files` and no contact point. Deferred because there is nobody
  on call — an alert with no recipient is a configuration file (ADR 0038 §6a).
- **Backup on the host only.** An hourly logical dump with a checksum beside each file, and no copy
  off the host (ADR 0036 §3, restated by ADR 0038 §6b). `restore-drill.sh` is real and has never
  been run, so the RTO floor has never been measured (ADR 0038 §6c).

**`docs/operations/production-readiness-gaps.md` has not caught up.** It still plans its gaps
against Key Vault, Application Insights and Flexible Server PITR, and calls the work "Sub-phase
1.12". ADR 0038 §Consequences names it as an unreconciled document; where the two disagree, the ADR
is the authority, and this roadmap does not schedule what that document plans.

### 2.5 Not planned, and the difference between the two ways of not being planned

- **Closed scope (`WONT`)** — corporate SSO (US05), aggregated Account Health (US50) and its band
  alert (US51), and the Enterprise DPA and SLA. These are not waiting for a criterion (ADR 0038 §4).
  US47 — pulling project state out of external trackers — is closed by ADR 0041.
- **Open, with no scheduled trigger** — US08 (audio upload), US33 and US34 (tenant metrics and
  period export), US41 (policy templates), US44 (permission boundaries). ADR 0038 neither kills nor
  reactivates them; each of their ADR 0014 criteria was commercial, and ADR 0038 §1 says none of
  those conditions will occur. "Open with no trigger" is a weaker statement than "deferred until X", and
  it is the true one.
- **The operations block** — one reactivation trigger for all of it: **NORA acquires a user who is
  not the maintainer** (ADR 0038 §6).

### Criteria for a "closed Sub-phase"

For a sub-phase to be considered **closed** (`DONE`):

1. All the PRs in scope merged into `main` with CI green
2. Manual verification executed (minimum smoke test of the delivered flow)
3. New debts catalogued in the audit/memory (not silenced)
4. ADR created if the sub-phase introduced a durable decision
5. Roadmap updated, moving the sub-phase from "What is ahead" to "History"

## 3. Where the original execution plan's later phases went

> The original execution plan had phases 4-9. Several were absorbed by the 1.X sub-phases, several
> were closed outright by the August 2026 realignment, and the rest are open with no trigger. This
> table records **where each one went**, not its status — per-story status is in
> [`backlog.md`](backlog.md), and duplicating it here is how the two documents drifted apart in the
> first place.

| Original phase | Where it went |
|---|---|
| **Complete Productivity Score** (US45-US47) | US45 + US46 absorbed by Sub-phase 1.8. US47 was never about productivity — it was about pulling state out of external trackers — and ADR 0041 closed it |
| **Complete Customer Confidence** (US48-US51) | US48-US49 absorbed by #148 (V017 + worker emit + server-side trend + `CustomerConfidenceCard`). The aggregate on top of them, US50/US51, is **closed scope** by ADR 0038 §4: it aggregates over history that does not exist |
| **Audio upload** (US08) | Open, no trigger. Its old reactivation criterion mentioned Azure Speech batch becoming cheap; that path died with the subscription (ADR 0034/0036) and no replacement is planned |
| **MCPs (Calendar, Tasks, CRM)** (US27-US29, US47) | Split by ADR 0041 into two directions that were never one feature. **Outbound** — NORA acting on other tools — shipped as nine OAuth integrations (ADR 0031), which is what US28 and US29 actually asked for. **Inbound** — an external client querying NORA — is US27, the MCP server, and is §2.3 |
| **Desktop finalisation** | Windows capture (WASAPI loopback) works. The client is Windows-only by ADR 0038 §2 — the macOS (BlackHole) and Linux (PulseAudio) paths and the ScreenCaptureKit debt were deleted, having never been exercised — and the local UI went with them. Transcription is being replaced (§2.3, ADR 0039). Real Windows/Teams validation is still pending |
| **SSO Entra ID / SAML** (US05) | **Closed** by ADR 0038 §4 |
| **Polish + Demo + Pitch** | The pitch was held on 2026-06-15. The polish landed with the v3 redesign; the demo material is §2.1 |
| **Trends panel** (US21) | **Reactivated** by ADR 0038 §5 — its prerequisite (US15, semantic search) shipped in #206. The gap that made that prerequisite weaker than it looked — no backfill, so meetings analysed before #206 stayed invisible — is closed by ADR 0044; the index now has to be *run*, not just merged |
| **Policy templates + Simulator** (US41 + US43) | US43 **reactivated** by ADR 0038 §5. US41 open with no trigger |
| **Permission boundaries** (US44) | Open, no trigger. It needs an organisational hierarchy and IAM delegation that nothing else asks for |
| **Tenant metrics and Export** (US33 + US34) | Open, no trigger. The operator-facing telemetry that exists (`/admin/platform/telemetry/*`) is a different thing behind Cloudflare Access |

### The long-term horizons, and why they are historical

This section used to project three horizons: pitch validation, then paid pilots in Q3-Q4 2026 with
a pricing floor per tenant, then commercial scale in 2027+ with multi-region and an eventual exit by
acquisition. **That is recorded as a scenario that was written, not as a plan that is running.**
ADR 0038 §1 declares the destination — a FIAP deliverable and a portfolio — and a roadmap that
keeps a commercial ladder in it while the accepted ADR says there will be no paying tenant is
exactly the kind of statement this repository has spent several passes removing.

What replaces it is one sentence: **NORA is finished when the FIAP deliverables are met and the
repository stands up as a portfolio artefact.** The remaining engineering is §2, and it is bounded
by what one person can carry.

### Notes on cross-cutting prerequisites

- **The MCP server** depends on the IAM principal resolution being reachable from a non-HTTP entry
  point — ADR 0041's invariant is that a tool call and a web request go through the same
  `PolicyEvaluator`. It does not depend on the shared contracts package, which was the old note here
- **The trends panel** depends on the embeddings backfill having been *run* for the tenant on
  display, not just on US15 being merged. The mechanism exists (ADR 0044); a panel built on a
  half-filled index still has to say so instead of drawing a flat line
- **Cloud STT** depends on a decision the desktop's own cleanup left open: `http_proxy.rs` signs
  requests with an `access-token` from the keyring, and the login form that was the only writer of
  that secret was deleted. The live surfaces authenticate through the web session instead. That
  producer has to be settled before the ephemeral-token path is built

## 4. Process decisions

Some decisions about **how** we work (not **what** to deliver) that affect the roadmap:

- **1.X numbering**: it stays. The old rule was that 2.X starts "when the first paying tenant is in production", and ADR 0038 §1 declares there will be none — so the trigger is unreachable and the rule is retired rather than left as a promise
- **Worktrees + parallel subagents**: work split by slice, merged via PR into `main`. Drift between worktrees is real debt (lesson from Sub-phase 1.1)
- **Audit as the basis for docs**: before a documentation pass, a read-only audit anchored in PR/migration/path is run. It has happened three times — 2026-05-13 (before 1.10), 2026-05-21, and the seven-front audit of 2026-08-16 that produced ADRs 0038-0041. Without an audit, docs become fiction, and this document is the proof: its §2 planned against a dead cloud for three months
- **Immutable ADRs**: once accepted, we do not edit them — we create a successor. The ADR 0009 status divergence this note used to flag was resolved: both the document and the index now record it as superseded by 0035
- **Sub-phases ≠ Sprints**: there is no fixed time cadence. A sub-phase closes when the scope is delivered, not when a timer runs out
- **Delivery is recorded by theme when numbering lapses**: §1 stopped inventing sub-phase numbers after 1.11b+c rather than assigning them retroactively. A number applied after the fact describes a plan that never existed

## Document History

| Version | Date | Description |
|---|---|---|
| 1.0 | 2026-05-14 | **Initial creation** as a living roadmap. Replaces `docs/plano-de-execucao.md` (discontinued — it described a week-by-week split between two developers, outside the current real flow). Consolidates the history of the 11 Sub-phases (1.0-1.10) with a cross-check of audit `2026-05-13-audit-pre-subfase-1.10.md` §11. Defines the upcoming Sub-phases 1.11 (Demo Polish Plan A), 1.12 (Production Hardening), 1.13+ (post-pitch) with explicit scope and prerequisites. Includes the long-term vision (3 horizons H1-H3) and process notes |
| 1.1 | 2026-06-06 | Doc x code reconciliation + standardisation |
| **1.2** | **2026-08-17** | **Rewritten against the real substrate and ADR 0038-0041.** §1 kept as the record and extended: the Azure-era sub-phases are marked historical rather than edited, and everything delivered after 2026-05-23 — chat and RAG, the control plane, Flows, OAuth integrations, operational LGPD, the exit from Azure, the realignment — is added by theme, because sub-phase numbering lapsed and inventing one retroactively would describe a plan that never existed. §2 replaced entirely: the old plan for a separate `rg-nora-prod`, Azure Monitor alerts, Key Vault rotation and a DR runbook that was never written is deleted rather than rescheduled, and what is ahead is now the close-out of 1.11(e)/(f), the rubric artefacts, the builds ADRs 0039/0040/0041 decided, the four stories ADR 0038 §5 reactivated, and the substrate they land on — one bare-metal host, Cloudflare Tunnel, SOPS + age, and a pull deploy whose consumer was never written. Cumulative metrics corrected: 334 merged PRs, 41 ADRs, migration ceiling V027, the Azure cost line deleted with no number put in its place, and the May 2026 coverage figures withdrawn in favour of `docs/engineering/standards.md`. §3 no longer restates per-story status — it records where each original phase went and points at the backlog — and the H1-H3 commercial horizons are marked historical against ADR 0038 §1 |
