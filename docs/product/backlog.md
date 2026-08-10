# Backlog — NORA

> Living MVP backlog, maintained in MoSCoW format with **real status per user story** (DONE / PARTIAL / MISSING). Updated 2026-05-14, based on the audit `2026-05-13-audit-pre-subfase-1.10.md` §2 and §3.
>
> This document supersedes `docs/backlog-mvp.md` (moved here). Source of truth for the statuses: PRs merged into `main`, migrations `services/api/src/main/resources/db/migration/V*.sql`, retroactive audit.
>
> **Reconciled 2026-05-21 (post-PR #148):** Customer Confidence (US48-49) went from PARTIAL → **DONE** full-stack. The `2026-05-13` audit (and the doc×code reconciliation of 2026-05-21 that preceded it on the same day) were written **before** #148 was merged; this doc reflects the post-merge state.
>
> To understand the execution history of the sub-phases that delivered each status, see `docs/product/roadmap.md`.

---

## 1. Epics

| ID | Epic | Tier | Description |
|---|---|---|---|
| **E1** | Identity & Access | Core + Enterprise | Signup, login, password recovery, invitations, post-MVP SSO, IAM/RBAC |
| **E2** | Meeting Ingestion | Core + Enterprise | Text upload in the MVP; audio and live capture on the roadmap |
| **E3** | AI Processing | Core + Enterprise | Transcription, NLP, summary, task extraction, embeddings |
| **E4** | Dashboard & Insights | Core + Enterprise | Meeting visualization, search, filters, history |
| **E5** | Task Management | Core | Extracted tasks, status, assignment, export |
| **E6** | MCP Integrations | Core | Connection with Claude MCP, Google Calendar, task managers |
| **E7** | Enterprise Administration | Enterprise | Tenant configuration, company context, user management |
| **E8** | Enterprise IAM (AWS-style) | Enterprise | Root user, Users, Groups and Policies (Effect/Action/Resource[/Condition]) managed by the tenant itself |
| **E9** | Meeting Productivity | Core + Enterprise | Opt-in assessment: the user declares the objective and expected outcomes; NORA measures coverage and assigns a Productivity Score |
| **E10** | Customer Confidence & Account Health | Enterprise | The customer's/lead's confidence in the company assessed per meeting; aggregated temporal Account Health Score |

---

## 2. MoSCoW Prioritization + Real Status

> **M** = Must Have · **S** = Should Have · **C** = Could Have · **W** = Won't Have (v1)
>
> **Status:**
> - **DONE** = implemented, merged, in the flow (with or without minor debt)
> - **PARTIAL** = part delivered, some points missing
> - **MISSING** = not implemented

---

### E1 — Identity & Access

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US01 | Create an account with e-mail and password | M | DONE | `AuthController.signup` (`services/api/.../AuthController.java:63-75`) · PR #4 | — |
| US02 | Verification e-mail after signup | M | DONE | `AuthController.verifyEmail` (line 77-80) · migration V003 · PR #4 | E-mail delivery depends on the Resend/log adapter (configurable per env) |
| US03 | E-mail/password login | M | DONE | `AuthController.login` (line 83-108) · PRs #4 + #59 | — |
| US04 | Reset password via link | M | DONE | `AuthController.requestPasswordReset` + `confirmPasswordReset` (line 143-161) · PRs #4, #47 | — |
| US05 | Corporate SSO (Google/Entra ID/SAML) | **W** | MISSING | Marked W in the original backlog | Post-MVP. Deferred as a block via ADR 0014 |
| US06 | Invitation to the tenant by corporate e-mail | M | DONE | `InvitationController` · migration V010 · ADR 0011 · PR #55 | — |

### E2 — Meeting Ingestion

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US07 | Transcript upload (`.txt`, `.vtt`, `.srt`) | M | DONE | `MeetingsController.upload` (line 98-136) · migration V004 · PR #5 | — |
| US08 | Audio/video upload (`.mp3`, `.mp4`) | **W** | MISSING | `ALLOWED_FORMATS = {TXT,VTT,SRT}` in `MeetingsController.java:66` | Post-MVP. Deferred as a block via ADR 0014 |
| US09 | Live capture on the Desktop | **W** (declared W in the backlog, but **implemented**) | DONE | `apps/desktop/src-tauri/.../system_audio.rs`, `audio_capture.rs`, `stt_sidecar.rs` · PRs #8, #65 · ADRs 0008 + 0009 | Validation in a real Windows/Teams environment still pending. macOS via BlackHole works; native ScreenCaptureKit is nice-to-have (contributor's scope) |
| US10 | Name and categorize the meeting at upload | S | DONE | `MeetingUploadMetadata` accepts `title` and `tags` (`MeetingsController.java:107,120`) | — |

### E3 — AI Processing

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US11 | Automatic meeting summary | M | DONE | `services/nlp-worker/src/.../llm_analyzer.py` + `stub_analyzer.py` · canonical schema in `docs/api/llm-schemas/meeting-analysis-v1.schema.json` · PRs #7, #32 | — |
| US12 | Extracted tasks and decisions | M | DONE | `actionItems` + `decisions` in `MeetingAnalysisV1` · endpoint `/tasks` | — |
| US13 | Identify mentioned participants | S | **PARTIAL** | `Participant` model in `services/nlp-worker/src/.../models.py:104-109` · migration V004 | No dedup nor participant matching across meetings |
| US14 | Company context injected into the LLM | M | DONE | `TenantContextController` · migration V005 · injected into the prompt | — |
| US15 | Semantic search via embeddings | S | DONE | Provider-agnostic embeddings (Gemini/OpenAI) via pgvector + HTTP embedding client: `EmbeddingService.java` · `HttpEmbeddingClient.java` · migration V021 (`meeting_embeddings`) · `RagSearchIntegrationTest.java` · `GET /meetings/search` consumed by the Core chat as RAG context · **PR #206** | — |

### E4 — Dashboard & Insights

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US16 | Chronological meeting panel | M | DONE | `MeetingsController.list` · `apps/web/src/app/(app)/dashboard/page.tsx` | — |
| US17 | Detail of a meeting | M | DONE | `MeetingsController.get` · `apps/web/src/app/(app)/meetings/[id]/page.tsx` | — |
| US18 | Search by keyword/period | M | DONE | `list` accepts `search`, `from`, `to` (`MeetingsController.java:140-145`) | — |
| US19 | Scope-restricted visibility via IAM | M | DONE | `AuthorizationService.isAllowed` + `IamScopingIntegrationTest` · PR #35 | `PolicyEvaluator` supports `StringEquals`/`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` (Sub-phase 1.11c) |
| US20 | Root sees everything in the tenant | M | DONE | Bypass in `AuthorizationService` · `PolicyEvaluator.java:14` | — |
| US21 | Trends panel (themes + task load) | C | MISSING | No endpoint nor component | Deferred as a block via ADR 0014. Reactivate once US15 is turned on (depends on embeddings/temporal analysis) |

### E5 — Task Management

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US22 | Consolidated task list | M | DONE | `TasksController.list` · `apps/web/src/app/(app)/tasks/page.tsx` | — |
| US23 | Mark a task as completed | M | DONE | `TasksController.update` (line 53-77) | — |
| US24 | Edit task text | S | DONE | `update` accepts `title` | — |
| US25 | Export tasks as CSV/MD | S | MISSING | No endpoint | Deferred as a block via ADR 0014. Reactivate when pilot feedback indicates usage outside the app |
| US26 | Due date on a task | C | **PARTIAL** | `due_date` column in migration V005:82 | Date-picker UI not inspected (conservative PARTIAL) |

### E6 — MCP Integrations

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US27 | Claude MCP | **W** | MISSING | No code. MCPs remain a roadmap concept (no dedicated folder in the repo) | Post-MVP. Deferred as a block via ADR 0014 |
| US28 | Google Calendar MCP | **W** | MISSING | — | Post-MVP. Deferred as a block via ADR 0014 |
| US29 | Task manager MCPs (Linear/Jira/Notion) | **W** | MISSING | — | Post-MVP. Deferred as a block via ADR 0014 |

### E7 — Enterprise Administration

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US30 | Configure the company context | M | DONE | `TenantContextController` · migration V005 · PR #33 | — |
| US31 | Version history of the company context | S | MISSING | V005 only has `created_at`/`updated_at`. `data-model.md` foresaw a `version` column but the migration does not include it | Debt: trivial migration V014 (S). Deferred as a block via ADR 0014. Reactivate before prod GA (LGPD compliance requires it) |
| US32 | Tenant's corporate domain | M | DONE | `TenantController.updateDomain` · migration V009 · ADR 0011 · PR #55 | — |
| US33 | Tenant usage metrics | S | MISSING | No endpoint | Deferred as a block via ADR 0014. Reactivate when 5+ paying tenants in a pilot want to see ROI |
| US34 | Export of a consolidated report for the period | S | MISSING | No endpoint | Deferred as a block via ADR 0014. Reactivate once US33 is delivered (dependency on aggregations) |

### E8 — Enterprise IAM (AWS-style)

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US35 | Create IAM groups | M | DONE | `IamController.createGroup` · migration V006 · PR #35 | — |
| US36 | Create and version JSON policies | M | DONE | `createPolicy`/`updatePolicy` · `iam_policy_versions` table in V006 | Conditions: `StringEquals`/`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` (Sub-phase 1.11c) |
| US37 | Attach/detach policies to groups and users | M | DONE | `attachToGroup`/`attachToUser` etc | — |
| US38 | Add/remove users from groups | M | DONE | `addMember`/`removeMember` | — |
| US39 | Clear HTTP 403 when out of scope | M | DONE | `GlobalExceptionHandler` | Stability of the error message detail not checked in detail |
| US40 | IAM audit log | M | DONE | `IamController.listAudit` · `iam_audit_events` table in V006 | Auth audit log (login/logout/refresh) absent — the current pattern is IAM-only |
| US41 | Policy templates | S | MISSING | No endpoint. The `is_template` column is not in V006 | Deferred as a block via ADR 0014. Reactivate when >3 tenants ask for fast onboarding |
| US42 | Visual policy editor (form-based) | S | **PARTIAL** | Monaco JSON in `apps/web/src/components/.../policy-editor.tsx` (PR #55). It is JSON with syntax highlighting + schema validation, not form-based | Reactivate the form-based version once US43 (simulator) is online — usability increases together |
| US43 | Policy simulator ("can user X do Y on Z?") | S | MISSING | No endpoint | Deferred as a block via ADR 0014. Reactivate before the first paid pilot — without it, debugging policies is blind |
| US44 | Permission boundaries | C | MISSING | No code | Deferred as a block via ADR 0014. Reactivate when organizational hierarchy + IAM delegation becomes a need (probably Pilot+1) |

### E9 — Meeting Productivity

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US45 | Opt-in Productivity Score (declare the objective) | M | DONE | `MeetingsController.putGoal/deleteGoal` (line 258-292) · migration V012 · ADR 0005 · PR #67 | — |
| US46 | Productivity Score 0-100 + coverage per outcome | M | DONE | `ProductivityAssessment` model + schema + UI components (`MeetingProductivitySection`, `ProductivityScoreCard`) | — |
| US47 | MCP project state (pull Jira/Linear/Azure DevOps) | **W** | MISSING | — | Post-MVP. Deferred as a block via ADR 0014 |

### E10 — Customer Confidence & Account Health

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US48 | Customer Confidence Score 0-100 with signals and objections | M | **DONE** | Migration `V017__create_customer_confidence.sql` (5 tables + RLS) · the worker emits `customerConfidence` (`models.py:252` + stub + prompt) · persisted in the pipeline (`AnalysisService.java:127` → `CustomerConfidenceService.persist`) · `GET /meetings/{id}` returns `customerConfidence` (`MeetingDetailResponse`) · UI `CustomerConfidenceCard` (`meetings/[id]/page.tsx:182`) · **PR #148 (2026-05-21)** | Delivered as **V017** (the V013 slot from ADR 0015 ended up as soft-delete, #114). **Aggregated** Account Health (US50-51) remains deferred (ADR 0014) |
| US49 | Trend `IMPROVING`/`STABLE`/`DECLINING` | M | **DONE** | Trend is **authoritative on the server**: `CustomerConfidenceService.computeTrend` compares against the account's previous assessment (dead band ±5 pts), persisted in `customer_confidence_assessments.trend` · PR #148 | The worker's trend guess is ignored (the backend is the source of truth) |
| US50 | Aggregated Account Health Score per account | S | MISSING | `docs/data-model.md:437-453` foresees `account_health_snapshots` but there is no migration | Deferred as a block via ADR 0014. Reactivate post-pilot once 3+ tenants have enough data to aggregate |
| US51 | Alert when Account Health changes band | S | MISSING | No code | Deferred as a block via ADR 0014. Reactivate together with US50 |

---

## 3. Workstreams implemented beyond the original backlog

Work that was not in the original MoSCoW but came in via sub-phases or an architectural decision:

| Item | PR | ADR | Status |
|---|---|---|---|
| Stateful refresh tokens + httpOnly cookies | #59 | — | DONE (migration V011, cookie `nora_refresh` 30d, access `nora_access` 15min) |
| PII Shield with PERSON_NAME (BR) — ~270 names + negative list | #59 | ADR 0012 | DONE |
| Speech Token Broker (ephemeral Azure Speech token) | #29 | ADR 0009 | DONE |
| Live analysis endpoint (Desktop overlay highlights) | #65 | — | DONE (`POST /meetings/live-analyze`) |
| TF-IDF baseline package (`packages/nlp-baseline/`) | #54 | ADR 0010 | DONE (3 modules, 52 tests) |
| Expanded synthetic dataset (12 .txt + 3 .vtt + 2 .srt + 3 JSON contexts) | #54 | — | DONE |
| DS Sprint 1+2 notebook (`notebooks/01-tf-idf-eda-meetings.ipynb`) | #54 | — | DONE (26 cells) |
| Productivity Score full-stack | #67 | ADR 0005 | DONE (V012 + worker + backend + web, 3 components) |
| Editorial visual redesign v2 (NoraLogo soundwave, light palette, Inter + Instrument Serif) | #56, #58 | — | DONE |
| Complete Bicep IaC (9 modules + main + bicepparam) | #62 | — | DONE |
| GHCR build/push pipeline (3 images) | #63 | — | DONE |
| Azure deployment via OIDC (`deploy-infra.yml`) | #64 | — | DONE |
| Customer Confidence LLM schema | #25 | ADR 0006 | DONE (schema) |
| Customer Confidence full-stack (persistence + worker emit + endpoint + UI) | #148 | ADR 0015 | DONE (V017 + `AnalysisService` wiring + server-side trend + `CustomerConfidenceCard`) |
| `meeting_attributes` JSONB + GIN index | V007 + V008 | ADR 0007 | DONE (arbitrary attributes for IAM conditions) |
| Meeting reprocessing | #46 | — | DONE (`POST /meetings/{id}/reprocess`) |
| CORS configurable per env | #42 | — | DONE (`CORS_ALLOWED_ORIGINS` in `application.yml`) |
| `nora-architect` skill for Claude Code | #53 | — | DONE (in `.claude/skills/`) |

### Post-1.10 hardening wave (audit follow-ups #114–#138)

A security/infra workstream that came in after Sub-phase 1.10, labeled "audit follow-up #N". Documented retroactively in **ADR 0019** (RLS + composite FK), **ADR 0020** (token rotation) and **ADR 0021** (soft-delete) in the 2026-05-21 audit.

| Item | PR | Migration | Status |
|---|---|---|---|
| Soft-delete (`deleted_at` + `@SQLRestriction` + partial UNIQUEs) | #114 | V013 | DONE |
| Refresh-token rotation + reuse detection (token families) | #116 | V014 | DONE |
| JWT RS256 + JWKS endpoint (`/.well-known/jwks.json`) | #117 | — | DONE |
| Expanded auth audit log (login/refresh/logout) | #118 | — | DONE |
| App Insights Java Agent + role names | #136 | — | DONE |
| Composite FK isolation `meetings.(tenant_id,owner_user_id)→users` | #137 | V015 | DONE |
| **Row-Level Security** (`tenant_isolation` + `TenantRlsAspect`) | #138 | V016 → V019/V020 | DONE (schema V016 + full RLS/auth-aware scope V019/V020; cutover runbook in ADR 0026/0028). What remains is the operational cutover/enforcement in prod, not the schema |

---

## 4. State Summary (2026-05-14)

| MoSCoW | Total | DONE | PARTIAL | MISSING |
|---|---|---|---|---|
| **Must Have (M)** | 31 | **29** | **0** | **2** (US05*, US08*) |
| **Should Have (S)** | 15 | **7** | **2** (US13, US42) | **6** (US25, US31, US33, US34, US41, US43) |
| **Could Have (C)** | 5 | — | **1** (US26) | **4** (US21, US44, etc) |
| **Won't Have v1 (W)** | 7 | **1** (US09) | — | **6** |
| **Total** | **58** | **37** | **3** | **18** |

> *US05 and US08 are `M` in the original MoSCoW but were **re-labeled as W via a scope decision** (CLAUDE.md + PROJECT.md). Here they count as MISSING/W in practice.

**Effective MVP coverage** (M + S desirable for the demo):
- Must Have delivered: **29 of 31** (94%) — Customer Confidence (US48-49) was delivered full-stack in #148; only US05/US08 (re-labeled W) remain
- Should Have delivered: **9 of 14** (64%) — the main gap is exporting, tenant metrics, policy simulator

**Workstreams that set the product apart beyond the MoSCoW** (12 items): Productivity Score full-stack, PII PERSON_NAME, Bicep IaC, real Azure deployment, synthetic dataset + DS notebook, refresh tokens, Live analysis, visual redesign.

---

## 5. The "Defer Post-MVP" decision — ADR 0014

> Approved as a block by Stratfy on 2026-05-14. This decision closed 14 US as **Won't Have v1** with documented reactivation criteria. **Update:** US15 (semantic search) was subsequently delivered in PR #206 — see the note in the table below.

**General criterion:** the US below were postponed to free up focus for Sub-phase 1.11 (Demo Polish Plan A) and 1.12 (Production Hardening). None of them blocks the FIAP × TOTVS pitch (15/06/2026) nor the immediate Plan A.

| US | Title | Reactivation criterion |
|---|---|---|
| US05 | Entra ID/SAML SSO | When the first paying Enterprise tenant explicitly requires it (a concrete commercial signal) |
| US08 | Audio/video upload | When repeated demand in a pilot indicates it (>30% of uploads are audio) or Azure Speech batch becomes cheap (R$5/h) |
| US15 | Semantic search via embeddings | **No longer deferred** — delivered in PR #206 (provider-agnostic embeddings via pgvector + HTTP embedding client; migration V021). See E3 / US15 above |
| US21 | Trends panel (themes + task load) | After US15 is turned on. Without embeddings/temporal analysis the panel is shallow |
| US25 | CSV/MD task export | When pilot feedback indicates usage outside the app (>2 tenants asking) |
| US31 | Version history of the company context | Before prod GA — LGPD compliance needs an audit trail on the context. Trivial migration (V014) |
| US33 | Usage metrics per tenant | When 5+ paying tenants are in a pilot — without baseline data it is not worth building |
| US34 | Consolidated report export | Together with US33 (dependency on aggregations) |
| US41 | Policy templates | When >3 tenants ask for fast onboarding with pre-made policies |
| US43 | Policy simulator | **Before** the first paid pilot — without it, debugging policies is blind. High probability of moving up into 1.11 |
| US44 | Permission boundaries | When organizational hierarchy + IAM delegation becomes a real need (Pilot+1) |
| US47 | MCP project state | When the first tenant asks for Jira/Linear integration for the Productivity Score |
| **US50-51** | **Aggregated Account Health + alerts** | US48-49 (Customer Confidence per meeting) was delivered in #148 via ADR 0015. The **aggregated** set (temporal Account Health Score + band alerts) remains deferred: post-pilot when 3+ tenants have >10 meetings to aggregate |

> The reactivation criterion per US is descriptive, not blocking. Sub-phase 1.13+ can pick up any of them if the context justifies it.

---

## 6. Known visual bug

A previous version of this document (`docs/backlog-mvp.md`, before 2026-05-14) had a duplicated header on lines 158-159 of the "Priority Summary" table — fixed in this version.

---

## 7. MVP — Version 1.0 Scope

The NORA v1.0 MVP covers exclusively the stories classified as **Must Have**, distributed across the three central flows:

### Flow 1 — Core User (Lucas)
1. Create an account and log in
2. Upload a text transcript
3. Receive the summary, decisions and extracted tasks
4. View and manage the extracted tasks
5. Search meetings in the history

### Flow 2 — Root of the Enterprise tenant (Camila)
1. Configure the tenant with a corporate domain
2. Invite users; create AWS-style **groups** and **policies**
3. Attach policies to groups/users; add users to groups
4. Configure the company context (product context injection)
5. View all the tenant's meetings (Root has bypass)
6. Audit IAM changes

### Flow 3 — Enterprise User (Rafael)
1. Accept the invitation and log in with a corporate e-mail/password
2. View only the meetings allowed by the IAM policies applicable to their user/groups
3. Access the summary and tasks of the visible meetings
4. Receive a clear message (HTTP 403) when trying to access content outside their permissions

---

## 8. Acceptance Criteria — Critical Stories

### US11 — Generate the meeting summary

**Given that** a meeting has been processed successfully,
**when** the user opens the meeting detail,
**then** they must see a summary in Portuguese with: the meeting's objective, main points discussed, decisions taken and next steps.

**Business rules:**
- The summary must be between 150 and 500 words
- It must be generated within 30 seconds after processing
- It must use the company context (Enterprise) when available

---

### US14 — Company context in processing

**Given that** the admin has configured the company context,
**when** a meeting of the tenant is processed by NORA AI,
**then** the generated summary and tasks must reflect the terminology and priorities configured in the context.

**Business rules:**
- The context is injected as a base instruction into the AI prompt
- Updating the context does not reprocess old meetings
- The context is isolated per tenant (it does not leak between companies)

---

### US19 — Scope-restricted visibility (Enterprise)

**Given that** an Enterprise user has IAM policies that limit their access (e.g. condition `nora:Department = "sales"`),
**when** they open the meetings panel,
**then** they see only meetings whose attributes satisfy the applicable Allow policies and do not fall under Deny policies.

**Business rules:**
- The filter is applied in the backend (not only in the frontend)
- A direct URL access attempt to a resource outside the permissions returns `403`
- The tenant's Root has full bypass and sees everything

---

### US36 — IAM policies (Effect/Action/Resource/Condition)

**Given that** the Root opens "Configurações > IAM > Políticas",
**when** they create a new policy by submitting a JSON document with `version`, `statements[]` (each with `effect`, `action[]`, `resource[]` and an optional `condition`),
**then** the policy must be persisted with version 1, validated against the official schema and available to be attached to groups/users.

**Business rules:**
- Policies are always scoped to the tenant; they do not leak between tenants.
- Every change creates a new version in `iam_policy_versions` (immutable history).
- Evaluation follows the order: Root → Allow; otherwise, an explicit **Deny** wins; otherwise, require at least one applicable Allow; default Deny.
- Wildcards (`*`) are supported in `action` and `resource`.
- Conditions use AWS-style operators: `StringEquals`, `StringIn`, `StringLike`, `DateGreaterThan`, `DateLessThan` (Sub-phase 1.11c). Operators outside that list are fail-closed.
