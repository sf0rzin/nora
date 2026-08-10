---
title: "Back-end × front-end gaps (web + admin)"
owner: NORA Architect (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
escopo: "NORA Core — web (apps/web) and admin (apps/admin) surfaces. Desktop out of scope."
---

# Back-end × front-end gaps (web + admin)

> Capabilities that the **back-end** (Spring `services/api`) or the **worker** exposes, but
> which the **front-end** (web and/or admin) does not yet consume — or consumes only with a *mock*.
> Every gap was confirmed by **adversarial verification**: an independent agent
> tried to refute it by looking for real consumption in the front end. Only the confirmed ones are here.

## Legend

- **Severity**: product relevance (high / medium / low).
- **Demo 15/06**: `critical` (blocks the demo flow) · `desirable` (adds value, does not block) · `post-MVP`.
- **Front status**: `absent` · `partial` (consumed in a limited way) · `orphan` (a wrapper exists in the `api-client` but with no *call-site*).

---

## Prioritized overview

| # | Capability | Endpoint | Surface | Severity | Demo 15/06 | Effort |
|---|---|---|---|---|---|---|
| 1 | Reprocess FAILED/COMPLETED meeting | `POST /meetings/{id}/reprocess` | web | **high** | **critical** | low |
| 2 | Right to be forgotten (LGPD) | `DELETE /privacy/meetings/{id}` | web | **high** | desirable | low |
| 3 | Telemetry — business metrics | `GET /admin/platform/telemetry/business` | admin | medium | desirable | medium |
| 4 | Telemetry — system health | `GET /admin/platform/telemetry/health` | admin | medium | desirable | medium |
| 5 | Semantic search on a dedicated screen | `GET /meetings/search` | web | medium | desirable | medium |
| 6 | Edit policy document (versioning) | `PUT /iam/policies/{id}` | web | medium | desirable | low |
| 7 | Remove meeting goal | `DELETE /meetings/{id}/goal` | web | medium | desirable | low |
| 8 | Policy detail + group members | `GET /iam/policies/{id}`, `GET /iam/groups/{id}/members` | web | low | post-MVP | medium |
| 9 | Cost telemetry filters (period/grouping) | `GET /admin/platform/telemetry/cost?groupBy` | admin | low | post-MVP | low |
| 10 | `baseUrl`/`priceCachedInputPerMTok` fields in the models form | `POST /admin/platform/models` | admin | low | post-MVP | low |
| — | Live analysis (`POST /meetings/live-analyze`) | — | — | — | **not a web gap** | — |
| — | Speech token broker (`POST /speech/token`) | — | — | — | **not a web gap** | — |

> The last two items were **investigated and discarded** as product gaps: they are
> correctly consumed by the Desktop (Tauri) and would only make sense there. Documented
> in section 3 to prevent them from reappearing as a "pending item" in future audits.

---

## 1. Critical for 15/06

### 1.1 Reprocess meeting (web)

- **Back-end**: `POST /meetings/{id}/reprocess` — `MeetingsController.java:344` (responds `202`).
- **Front status**: absent on web. **The Desktop already has it** (`apps/desktop/src/lib/meetings.ts:31` + a real button at `apps/desktop/src/pages/meeting-detail.tsx:253`).
- **Evidence**: `apps/web/src/app/(app)/meetings/[id]/page.tsx:86` shows only the static text *"A análise desta reunião falhou. Tente reprocessar."* — no button, no handler. The web `api-client` (`apps/web/src/lib/api/client.ts`) **does not have** a `reprocessMeeting()` wrapper.
- **Why it is critical**: it makes it possible to recover an analysis that fails **live on stage**. It is the only item that can stall the demo flow.
- **Recommendation**: create `reprocessMeeting(id)` in the `api-client` (`POST /meetings/{id}/reprocess`) and add a "Reprocessar" button to the error block of `meetings/[id]/page.tsx:81-89`, re-triggering the status polling. Mirror what the Desktop already does.

---

## 2. Desirable (add value, do not block the flow)

### 2.1 Right to be forgotten — LGPD (web)

- **Back-end**: `DELETE /privacy/meetings/{id}` — `PrivacyController.java:42` (ADR 0029, `meeting:update` *gate*). Performs a hard delete of the meeting + cascade of the raw PII.
- **Front status**: **absent on every surface** (web and desktop). No *call-site* for `/privacy/meetings`.
- **Why it matters**: the **landing page explicitly announces** the feature — `apps/web/src/components/landing/landing-content.tsx:149-150` ("Apaga tudo permanentemente em um clique. LGPD Art. 18") and the FAQ (line 354). Today it is only marketing *copy* with no implementation: a mismatch between the public promise and the product.
- **Recommendation**: a destructive "Apagar permanentemente" action in `meetings/[id]` with a *typed-confirm* confirmation modal (type the title), handling `404` without leaking existence and redirecting to the dashboard. Add `deleteMeeting()` to the `api-client`. It is also an excellent compliance differentiator to mention in the presentation.

### 2.2 Telemetry — business metrics (admin)

- **Back-end**: `GET /admin/platform/telemetry/business` — `PlatformAdminController.java:162`. Returns `analyses`, `tenantsActive`, `productivityAvg`, `customerConfidenceAvg` (wired end-to-end down to `PrimaryDbBusinessMetricsSource`).
- **Front status**: static *placeholder*. `apps/admin/src/app/telemetria/page.tsx:51-52` is a `<Placeholder>` "Próxima fatia"; `lib/data.ts` imports only `getCost`.
- **Why it matters**: these are exactly the "wow" numbers (average productivity, average customer confidence) that **sell the product in a presentation**. The back-end already computes everything.
- **Recommendation**: add `getBusiness()` in `apps/admin/src/lib/data.ts` and render the four indicators in the corresponding section.

### 2.3 Telemetry — system health (admin)

- **Back-end**: `GET /admin/platform/telemetry/health` — `PlatformAdminController.java:157` (`HealthSnapshot`: requests / failed / failureRate / p95LatencyMs per *role*, via App Insights).
- **Front status**: static *placeholder* in `telemetria/page.tsx:48-49`. The path already appears as a comment in `apps/admin/src/lib/contracts.ts:11`.
- **Recommendation**: `getHealth()` in `lib/data.ts` + rendering of the metrics per *role*.

### 2.4 Semantic search on a dedicated screen (web)

- **Back-end**: `GET /meetings/search?q&k` — `MeetingsController.java:115` (`MeetingSearchResponse`).
- **Front status**: partial. The endpoint is consumed **only by the chat BFF** as RAG context (`apps/web/src/app/api/chat/route.ts:154`), never by a results screen.
- **Verified nuance**: the dashboard's "Buscar reuniões…" field is **not** this endpoint — `dashboard/Filters.tsx:66` does substring filtering via `GET /meetings?search=` (paginated list), not vector search. The `/ buscar` and `Cmd+K` shortcuts in the dashboard footer are **decorative** `<kbd>` elements with no handler (`dashboard/page.tsx:228,231`).
- **Recommendation** (optional for the demo): a semantic search field (or a `Cmd+K` palette) that calls `GET /meetings/search` and lists results with `summarySnippet`.

### 2.5 Edit IAM policy document (web)

- **Back-end**: `PUT /iam/policies/{id}` — `IamController.java:152` (creates a new version).
- **Front status**: **orphan**. The `updatePolicyDocument()` wrapper exists at `apps/web/src/lib/api/client.ts:475` but **has no caller**. The IAM screen (`settings/iam/page.tsx`) imports only `createPolicy`/`deletePolicy` — today "editing" means deleting and recreating, **losing the versioning**.
- **Recommendation**: load the policy document back into the Monaco editor and add "Salvar alterações" calling `PUT /iam/policies/{id}`.

### 2.6 Remove meeting goal (web)

- **Back-end**: `DELETE /meetings/{id}/goal` — `MeetingsController.java:330`.
- **Front status**: **orphan**. `deleteMeetingGoal()` exists at `client.ts:207` but has zero *call-sites*. You can create/edit a goal, but never remove it through the interface.
- **Recommendation**: a "Remover objetivo" button in `MeetingProductivitySection`/`MeetingGoalForm`. The wrapper is already there — minimal effort.

---

## 3. Post-MVP and investigated-and-discarded items

### 3.1 Policy detail + group members (web) — post-MVP

- `GET /iam/groups/{id}/members` has a `listGroupMembers()` wrapper (`client.ts:442`) **with no call-site**; `GET /iam/policies/{id}` does not even have a wrapper. The current IAM UX requires pasting UUIDs by hand (`settings/iam/page.tsx:185`). Building a panel/*drawer* that lists members and details policies would replace the prototype UX.

### 3.2 Cost telemetry filters (admin) — post-MVP

- `getCost()` (`lib/data.ts:78`) hardcodes `groupBy="service"` and never passes `from`/`to`. The back-end supports `groupBy={tenant|model|service}` and a period window (`PlatformAdminController.java:148`). Add a period selector and a grouping *toggle*.

### 3.3 Model form fields (admin) — post-MVP

- The `AddModelForm` (`apps/admin/src/app/modelos/modelos-client.tsx`) does not expose `baseUrl` or `priceCachedInputPerMTok`, even though `NewModelInput` (`lib/data.ts:113,118`) and the back-end (`PlatformAdminController.java:83`) accept them. Required to register a self-hosted/proxy provider and to price *cache hits*.

### 3.4 Items discarded as product gaps (they are Desktop features)

| Capability | Endpoint | Why it is NOT a web gap |
|---|---|---|
| Live analysis (live overlay) | `POST /meetings/live-analyze` (`MeetingsController.java:373`) | Consumed by the Desktop (`apps/desktop/src-tauri/src/live_analysis.rs:103`). It would only exist on web if there were live capture in the browser — outside the product. |
| Speech token broker | `POST /speech/token` (`SpeechController.java:24`) | Consumed by the Desktop (`speech_token.rs:30`, `stt_sidecar.rs:251`). The *broker* only makes sense in the audio capture client. |

> Recorded here so that they do not show up again as a "web pending item" in future audits.

---

## 4. Demo configuration risks + *reverse gaps*

### 4.1 Configuration checklist before presenting

> These are not code to build — they are environment variables that, if wrong, make the
> product display fictitious data on stage.

- [ ] **Web**: `NEXT_PUBLIC_USE_MOCKS` **must not** be `true` (the default is already `false`). When on, `listMeetings()`/`getMeeting()` serve JSON *fixtures* (`apps/web/src/fixtures/*.json`) instead of the API.
- [ ] **Admin**: `NORA_ADMIN_USE_MOCKS=false` **explicitly** (the default is mock!) **+** `PLATFORM_API_BASE_URL` **+** `PLATFORM_INTERNAL_TOKEN`. Without this, the console shows a fictitious catalog (`deepseek-v4-flash`, `gemini-3.5-flash`), a fixed cost (`1.8423 USD / 412 calls`), and any mutation (create/remove model, *bind*) is a silent no-op.
- [ ] Check that `NEXT_PUBLIC_API_BASE_URL` points to the correct API.

### 4.2 *Reverse gaps* — the front end shows something the back-end does not have

Most are **honest** (the UI states "Em breve", it does not pretend to have data) — recorded only for transparency.

| Item | File | Nature |
|---|---|---|
| `/integrações` page — catalog of 8 MCP connectors | `app/(app)/integrations/page.tsx` | Honest: everything is "Em breve". There is no MCP back-end. Keep as roadmap. |
| `/projects` page — automatic grouping | `app/(app)/projects/page.tsx` | Honest: *empty-state*, zero API calls. Feature not implemented in any layer. |
| "Continuar com Microsoft" buttons (SSO) | `components/auth/auth-screen.tsx` | Dead button: shows "ainda não disponível". There is no OAuth/SAML in the back-end (US05 deferred). |
| Landing hero composer | `components/landing/landing-hero.tsx` | Marketing *reverse gap*: the prompt becomes `?q=` and leads to signup. Expected on a landing page. |
| `role` field in signup | `components/auth/auth-screen.tsx` | Collected and **discarded**: `SignupRequest` only accepts `{email, password, displayName}`. Optionally add it to the DTO if segmentation metrics are wanted. |
| Right to be forgotten on the landing page | `components/landing/landing-content.tsx` | **See item 2.1** — the back-end exists, the front end does not call it. This one is actionable. |

---

## Effort summary

- **1 critical flow item** (reprocess), low effort.
- **2 critical configuration items** (web + admin mocks), no code.
- **6 desirable items**, most of them low effort (several already have the wrapper in the `api-client`, only the *call-site* in the UI is missing).
- The Core flow (upload → analysis → summary/decisions/tasks → RAG chat) is **complete and real**.
