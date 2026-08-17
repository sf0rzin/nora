# Backlog — NORA

> Living backlog, maintained in MoSCoW format with **real status per user story** (DONE / PARTIAL /
> MISSING / WONT).
>
> **Rewritten 2026-08-17 against the code, at commit `4017bb4` on `main`.** Measured at that
> anchor: 27 migrations (last `V027__composite_fk_iam_user_attachments.sql`), 16 controllers in
> `services/api/src/main/java/br/com/nora/api/api/controllers/`, 41 ADRs. Every status below was
> re-derived by opening the file it cites; no status is carried over on trust. Where a claim could
> not be verified, it says so instead of guessing.
>
> **Why the rewrite.** The previous revision declared itself current as of 2026-05-14 and worked with a
> closed universe of 58 stories. Two things were wrong with that. First, the repository shipped an
> entire product surface after that date with no story anywhere in this document: the chat assistant
> and its persistent sessions, NORA Flows and its canvas, OAuth integrations across nine providers,
> Projects, the printable meeting report, operational LGPD and the platform control plane with its
> operator console. Second, the closed universe was not 58 — the document only ever carried 51
> numbered stories, and the summary table at the end summed to numbers that matched neither the
> rows above it nor each other. Both are fixed here.
>
> Sources of truth for the statuses, in this order: the code at the anchor commit, the migrations in
> `services/api/src/main/resources/db/migration/`, the accepted ADRs, and the PR that merged the
> work. The 2026-08 realignment is recorded in **ADR 0038** (scope), **ADR 0039** (STT),
> **ADR 0040** (PII scope) and **ADR 0041** (MCP); this document records what those decided, it does
> not decide anything itself.
>
> To follow the execution history of the sub-phases that produced each status, see
> `docs/product/roadmap.md`.

## 1. Epics

| ID | Epic | Tier | Description |
|---|---|---|---|
| **E1** | Identity & Access | Core + Enterprise | Signup, login, password recovery, invitations, account self-service, IAM/RBAC |
| **E2** | Meeting Ingestion | Core + Enterprise | Text upload (single and batch), splitting a file with several meetings; audio and live capture |
| **E3** | AI Processing | Core + Enterprise | Analysis pipeline, NLP, summary, task extraction, embeddings |
| **E4** | Dashboard & Insights | Core + Enterprise | Meeting visualization, search, filters, history, report and export |
| **E5** | Task Management | Core | Extracted tasks, status, assignment, due date, export |
| **E6** | Interoperability — inbound MCP, outbound OAuth | Core | Two directions that used to be one epic. **Outbound**: NORA acts on other tools through OAuth connectors (ADR 0031). **Inbound**: external MCP clients query NORA (ADR 0041, read-only first cut, migration V029) |
| **E7** | Enterprise Administration | Enterprise | Tenant configuration, company context, corporate domain |
| **E8** | Enterprise IAM (AWS-style) | Enterprise | Root user, Users, Groups and Policies (Effect/Action/Resource[/Condition]) managed by the tenant itself, plus a per-user permission boundary that caps them (US44) |
| **E9** | Meeting Productivity | Core + Enterprise | Opt-in assessment: the user declares the objective and expected outcomes; NORA measures coverage and assigns a Productivity Score |
| **E10** | Customer Confidence | Enterprise | The customer's/lead's confidence in the company, assessed per meeting. The aggregate on top of it (Account Health) was closed by ADR 0038 §4 |
| **E11** | Conversational Assistant | Core | Chat over the workspace: streaming answers grounded in the tenant's own meetings and company context, with persistent sessions |
| **E12** | Automation — NORA Flows | Core | Event-triggered workflows built on a visual canvas, with execution history (ADR 0030, ADR 0032) |
| **E13** | Privacy & Data Lifecycle | Core + Enterprise | Erasure, retention and the boundaries of what leaves the tenant (ADR 0029, ADR 0040) |
| **E14** | Platform Control Plane | Operator (not a customer tier) | Model catalog, runtime model resolution, AI cost telemetry, operator console (ADR 0022-0025) |

E6 used to be called "MCP Integrations" and described "Connection with Claude MCP, Google Calendar,
task managers". ADR 0041 established why that framing was wrong: what had shipped is the
**outbound** direction over OAuth, and MCP is the **inbound** one, which had never had a line of
code until US27. The epic now names both.

E10 used to be called "Customer Confidence & Account Health". The aggregate half was closed by
ADR 0038 §4, so the epic no longer promises it.

## 2. MoSCoW Prioritization + Real Status

> **M** = Must Have · **S** = Should Have · **C** = Could Have · **W** = Won't Have (v1)
>
> **Status:**
> - **DONE** = implemented, merged, reachable in the product (with or without minor debt)
> - **PARTIAL** = part delivered, named points missing
> - **MISSING** = not implemented, and still in scope
> - **WONT** = decided against. It is not waiting for a criterion; it will not be built

**The MoSCoW ruler, stated so the new rows cannot rewrite history.** `M` means "in the declared
v1.0 MVP", which was fixed on 2026-05-14 and is reproduced in §6. Everything that shipped after
that date is classified `S` or `C` regardless of how central it feels today — the chat assistant is
`S`, not `M`, because the MVP was defined before it existed and promoting it retroactively would
falsify what the MVP was. `W` is reserved for what is out of v1 scope; a `W` story that later ships
by another route (US28, US29) moves to `S`, because it is no longer "won't", and a `W` story that
is decided against keeps `W` and takes the `WONT` status.

Stories numbered **US52 and above are new in this revision**. They describe work that was already
merged and had no story; the priority column reflects the ruler above, not the order the work was
done in.

### E1 — Identity & Access

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US01 | Create an account with e-mail and password | M | DONE | `AuthController.signup` (`services/api/.../AuthController.java:97`) · PR #4 | — |
| US02 | Verification e-mail after signup | M | DONE | `AuthController.verifyEmail` (line 121) · migration V003 · PR #4 | E-mail delivery depends on the Resend/log adapter (configurable per env) |
| US03 | E-mail/password login | M | DONE | `AuthController.login` (line 128) · PRs #4 + #59 | — |
| US04 | Reset password via link | M | DONE | `AuthController.requestPasswordReset` (line 305) + `confirmPasswordReset` (line 334) · PRs #4, #47 | — |
| US05 | Corporate SSO (Google/Entra ID/SAML) | **W** | **WONT** | No code, and none is planned | **Closed by ADR 0038 §4.** Its ADR 0014 criterion was "the first paying Enterprise tenant explicitly requires it"; ADR 0038 §1 declares there will be no paying tenant. It is also the largest build on the old deferral list, and it buys a second login path for an IAM that already has one |
| US06 | Invitation to the tenant by corporate e-mail | M | DONE | `InvitationController.invite` (`InvitationController.java:64`) + `accept` (line 79) · migration V010 · V018 (token stored as SHA-256) · ADR 0011 · PR #55 | — |
| US52 | Change my own password from inside the app | S | DONE | `POST /auth/password/change` (`AuthController.java:248`) · security section of the settings hub in `apps/web/src/app/(app)/settings/context/page.tsx` · PR #231 | — |
| US53 | Sign out of every device at once | S | DONE | `POST /auth/logout-all` (`AuthController.java:273`) · revokes the whole refresh-token family (ADR 0020, migration V014) | — |
| US54 | Edit or delete my own account | S | DONE | `PATCH /users/me` (`UsersController.java:38`) and `DELETE /users/me` (line 57) · `SettingsFlowIntegrationTest` · PR #231 | Deletion is the soft-delete of ADR 0021, not an erasure. Erasing a whole tenant is US80 and is a declared deferral |
| US55 | Ask for the verification e-mail again | C | DONE | `POST /auth/verify-email/resend` (`AuthController.java:289`) | — |

### E2 — Meeting Ingestion

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US07 | Transcript upload (`.txt`, `.vtt`, `.srt`) | M | DONE | `MeetingsController.upload` (`MeetingsController.java:175`) · migration V004 · PR #5 | — |
| US08 | Audio/video upload (`.mp3`, `.mp4`) | **W** | **WONT** | `ALLOWED_FORMATS = {TXT,VTT,SRT}` (`MeetingsController.java:79`), `ALLOWED_EXTENSIONS` (line 691) | **WONT by ADR 0046 §2**, and the reason is now verifiable where the inherited one was not. ADR 0014 gated this on ">30% of uploads are audio in a pilot", a criterion with no pilot to measure. The real reason: transcription is a **streaming realtime** session since ADR 0039/0045, so a file upload needs the provider's **batch** API — a second provider surface, credential path, cost model and failure mode. It is a new capability wearing the clothes of a small addition |
| US09 | Live capture on the Desktop | **W** (declared W in the backlog, but **implemented**) | DONE | `apps/desktop/src-tauri/src/system_audio.rs` (WASAPI loopback), `audio_capture.rs`, `audio_resample.rs`, `stt.rs`, `live_analysis.rs` · `POST /meetings/live-analyze` (`MeetingsController.java:545`) · PRs #8, #65 · ADR 0008 | **Windows-only** — `system_audio.rs:14` raises `compile_error!` on any other target, and the macOS (BlackHole) and Linux (PulseAudio) paths were deleted by ADR 0038 §2, never having been exercised. **The transcription engine was replaced**: ADR 0039 (contract in ADR 0045) moved STT to OpenAI's realtime API over an ephemeral session token minted by `POST /stt/sessions`; `stt_cloud.rs` and `stt_token.rs` are the client, and `stt_local.rs` / `whisper_model.rs` are out of the tree. The audio now leaves the machine unredacted — ADR 0040 is where that is recorded. Validation in a real Windows/Teams environment is still pending |
| US10 | Name and categorize the meeting at upload | S | DONE | `MeetingUploadMetadata` accepts `title` (line 12) and `tags` (line 19) · consumed at `MeetingsController.java:175` | — |
| US56 | Upload several transcripts in one go | S | DONE | Multi-file dropzone in `apps/web/src/app/(app)/meetings/upload/page.tsx` (header, "Batch (multi-upload)") · PR #253 | With 2+ files the per-meeting title, start, end and format fields are hidden: the title derives from the file name and the format from the extension |
| US57 | Split one file that contains several meetings | S | DONE | `POST /meetings/split-preview` (`MeetingsController.java:220`) · worker `/split` endpoint · `SplitPreviewIntegrationTest` · PRs #254, #255 | Preview-then-confirm; the segmentation is heuristic and the user accepts or rejects the cut |

### E3 — AI Processing

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US11 | Automatic meeting summary | M | DONE | `services/nlp-worker/src/nora_nlp/llm_analyzer.py` + `stub_analyzer.py` · canonical schema in `docs/api/llm-schemas/meeting-analysis-v1.schema.json` · ADR 0003 · PRs #7, #32 | — |
| US12 | Extracted tasks and decisions | M | DONE | `actionItems` + `decisions` in `MeetingAnalysisV1` · `GET /tasks` (`TasksController.java:53`) | — |
| US13 | Identify mentioned participants | S | DONE | Deterministic matching in `ParticipantMatcher.java` (normalisation + e-mail identifier + first/last token pair) · `GET /meetings/participants` (`MeetingsController.java`) · dedup applied to the listing's avatar stack · `ParticipantMatcherTest`, `ParticipantIdentityServiceTest`, `ParticipantIdentityIntegrationTest` (tenant boundary, per-item IAM, erasure) · ADR 0048 · migration V004, **no new migration** | **Closed on the declared roster, and the reason is in ADR 0048.** The worker's `Participant` (`models.py`, `name`/`role`/`mentionCount`) is NOT what got matched and could not be: `routers/analyze.py` redacts before the analyzers run, `_redact_person_names` numbers every occurrence separately so two mentions of one string are two placeholders, and `WorkerDtos.AnalyzeResponse` has no `participants` field, so the backend never read that array at all. Matching runs on the API side over `meeting_participants` — the names the user typed — and NORA builds no person-identity index over transcript text. **Not closed:** nicknames and diminutives ("Bia" / "Beatriz Souza") are out of reach of a deterministic rule; an identity cannot be corrected, because ADR 0048 §4 persists nothing so that ADR 0029 erasure carries it; and the worker still emits a `participants` array nothing consumes |
| US14 | Company context injected into the LLM | M | DONE | `TenantContextController` · migration V005 · injected into the analysis prompt by `AnalysisService` | The chat path reads the same context only since PR #467 — that is US69, recorded separately because it was a distinct gap |
| US15 | Semantic search via embeddings | S | DONE | `EmbeddingService.java` · `HttpEmbeddingClient.java` · migration V021 (`meeting_embeddings`) · `GET /meetings/search` (`MeetingsController.java:125`) · `RagSearchIntegrationTest` · PR #206 | **Correction to the previous revision, which claimed this runs "via pgvector". It does not.** `V021` stores the vector as a JSON array in a `TEXT` column and `EmbeddingService.cosine` (line 75) computes the similarity in Java over the tenant's rows. The image is `pgvector/pgvector:pg16`, but the extension is never created. Adequate for tens or hundreds of meetings per tenant; an ANN index is the future optimization. Indexing is best-effort and can silently not happen (no credential, provider failing), which used to be permanent — US86 is the path back |
| US58 | An abandoned analysis is released instead of hanging forever | S | DONE | `StuckAnalysisSweeper.java` — moves to `FAILED` every meeting left untouched in `PROCESSING` beyond the configured window · PR #470 | The window is floored at the worker timeout plus a margin, so a slow-but-healthy analysis is not reaped. Time is the only signal that separates "running" from "abandoned" |

### E4 — Dashboard & Insights

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US16 | Chronological meeting panel | M | DONE | `MeetingsController.list` (`MeetingsController.java:289`) · `apps/web/src/app/(app)/dashboard/page.tsx` | — |
| US17 | Detail of a meeting | M | DONE | `MeetingsController.get` (line 414) · `apps/web/src/app/(app)/meetings/[id]/page.tsx` | — |
| US18 | Search by keyword/period | M | DONE | `list` accepts `search`, `status`, `from`, `to` (`MeetingsController.java:291-296`) | — |
| US19 | Scope-restricted visibility via IAM | M | DONE | `AuthorizationService.isAllowed` + `IamScopingIntegrationTest` · every endpoint gated by `@RequiresPermission` deny-by-default since PR #407 · PR #35 | `PolicyEvaluator` supports `StringEquals`/`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` |
| US20 | Root sees everything in the tenant | M | DONE | Bypass in `AuthorizationService` · `PolicyEvaluator` | — |
| US21 | Trends panel (themes + task load) | C | DONE | `TrendsController` (`GET /trends`) · `application/trends/TrendsService` + `TopicNormalizer` · `TrendsRepositoryAdapter` · migration V030 (`meeting_action_items.completed_at`) · `apps/web/src/app/(app)/trends/` · `trendsAggregate_excludesAMeetingCoveredByASpecificDeny` | **Reactivated by ADR 0038 §5**; delivered on the topics the analysis already persists, not on the embeddings. That choice makes the panel independent of whether a tenant's US86 backfill has been *run* — the sentence this row carried while the story was open. Two limits are stated on the wire rather than hidden: theme matching is `LEXICAL` (spellings are folded, synonyms are not), and no ranking is produced below 5 analysed meetings in the range. Completions before V030 are dated from the old `updated_at`, an upper bound rather than a measurement |
| US59 | Printable meeting report (save as PDF) | S | DONE | `apps/web/src/app/(app)/meetings/[id]/report/page.tsx` + `report/print-button.tsx` — A4 print CSS, native `window.print` dialog, zero PDF libraries · PR #225 | The shell chrome is hidden by a `<style>` scoped to the route rather than by restructuring the `(app)` layout |
| US60 | Export a meeting as Markdown | S | DONE | `apps/web/src/lib/report/markdown.ts` (`meetingToMarkdown`, `meetingReportFileName`) · `apps/web/src/app/(app)/meetings/[id]/export-menu.tsx` — generated client-side, downloaded via Blob, no server round-trip · PR #225 | — |
| US61 | Projects view: meetings grouped by tag | C | DONE | `apps/web/src/app/(app)/projects/page.tsx` · PR #165 | Client-side grouping over `MeetingListItem.tags`, with drill-down through `?tag=`. **There is no project entity and no backend**: a "project" is a tag, so renaming or merging one is not possible |

### E5 — Task Management

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US22 | Consolidated task list | M | DONE | `TasksController.list` (`TasksController.java:53`) · `apps/web/src/app/(app)/tasks/page.tsx` | — |
| US23 | Mark a task as completed | M | DONE | `PATCH /tasks/{id}` accepts `status` (`TasksController.java:85`) | — |
| US24 | Edit task text | S | DONE | the same handler accepts `title` | — |
| US25 | Export tasks as CSV/MD | S | DONE | `apps/web/src/lib/report/tasks-export.ts` (`tasksToCsv`, `tasksToMarkdown`, `taskExportFileName`) · `apps/web/src/app/(app)/tasks/export-menu.tsx` — generated client-side, downloaded via Blob, no server round-trip, same shape as US60 · `tasks-export.test.ts` (19 cases, including an RFC 4180 round trip of a title carrying a comma, a quote and a newline) | **Reactivated by ADR 0038 §5**, and deliberately client-side: no endpoint, because the list is already in the browser, none of the three conditions that would justify one holds (volume beyond the paginated response, an export that must be audited, a non-browser client), and a non-browser consumer would be served by the MCP server of ADR 0041, not by a file-download route. The CSV carries a UTF-8 BOM so Excel in pt-BR keeps the accents; it does **not** solve that same Excel splits on `;`, nor does it defend against spreadsheet formula injection — both are stated in the module header rather than left to be found |
| US26 | Due date on a task | C | DONE | `due_date` column in migration V005:82 · `TaskUpdateRequest` carries `dueDate` (`TaskUpdateRequest.java:18`) · `PATCH /tasks/{id}` writes it through `TaskService.updateDueDate` (`TasksController.java:113-115`) · date input in `apps/web/src/app/(app)/tasks/page.tsx` · `TaskDueDateFlowIntegrationTest` · PR #470 | An empty string clears the date and an absent field leaves it alone, which is the documented way to express both. The Flows follow-up scheduler only picks up dates strictly after today (`FollowUpSchedule.java`), and the UI does not warn when a past date is saved. **The previous revision marked this PARTIAL on evidence it admitted it had never inspected; it was then genuinely PARTIAL for a different reason — the write path did not exist — and PR #470 closed it** |

### E6 — Interoperability: inbound MCP, outbound OAuth

> The split this epic now makes is ADR 0041's. **Outbound** is NORA acting on other systems, which
> is OAuth. **Inbound** is an external client asking NORA questions, which is MCP. Both directions
> are built now; reading the two as one feature is what let three stories sit at MISSING while most
> of what they promised had shipped, and it is why US27 needed reframing before it could be built.

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US27 | NORA as an MCP server (inbound) | S | **DONE** | `POST /mcp` (`api/controllers/McpController.java`) speaking JSON-RPC 2.0 over Streamable HTTP · `api/mcp/` (protocol, tool catalogue, the five reads) · credential in migration V029 + `application/mcp/McpTokenService.java` + `infrastructure/security/McpSecurityConfig.java` · mint/list/revoke at `/mcp/tokens` with the settings surface in `apps/web/src/app/(app)/settings/mcp/` · `McpIsolationIntegrationTest`, `McpServerIntegrationTest` · ADR 0041 · `docs/engineering/architecture.md` §19 | **Reframed and reactivated by ADR 0041**, and now built. The old title was "Claude MCP", which names a client rather than the thing to build. DONE describes the first cut ADR 0041 §4 defines, and that cut is **read-only**: five tools over meetings, meeting detail, semantic search, tasks and Customer Confidence, each evaluating the same IAM action as the web surface. Two declared limits, neither of them debt discovered afterwards: §3 deliberately ships a hashed tenant-scoped bearer token instead of the OAuth 2.1 authorization server the MCP specification asks of a remote server, so a client that speaks only that flow needs a manually pasted token; and write tools are out until somebody decides which IAM actions an agent may exercise unattended |
| US28 | Meeting outcomes reach the calendar (Google and Microsoft, via OAuth) | S | **DONE** | `CalendarCreateEventAction.java` (`calendar_create_event`) and `MicrosoftCalendarCreateEventAction.java` (`mscalendar_create_event`) in `services/api/.../infrastructure/integration/actions/` · wired into Flows through `ActionRegistry` · connections in migrations V024/V026 · ADR 0031 · PRs #221, #249 | **The promise was met by OAuth, not by MCP** (ADR 0041), so the story is renamed to name the real mechanism and is not counted as MCP scope. Write-only: NORA creates events, it does not read the user's calendar back |
| US29 | Action items reach a task manager (via OAuth) | S | **DONE** | `NotionCreatePageAction.java`, `TodoistCreateTaskAction.java`, `LinearCreateIssueAction.java`, `TrelloCreateCardAction.java`, `GitHubCreateIssueAction.java` · migrations V025 (github, notion, todoist, linear) and V026 (trello) · ADR 0031 · PRs #247, #249 | Same reframing as US28. **Jira, named in the original title, was never built** — five other trackers were. Write-only in the same sense: issues are created, never read back |
| US62 | Connect and disconnect a third-party tool | S | DONE | `POST /integrations/{provider}/oauth/start` (`IntegrationsController.java:71`), `GET .../oauth/callback` (line 90), `DELETE /integrations/{provider}` (line 122) · `GET /integrations` (line 63) · `apps/web/src/app/(app)/integrations/page.tsx` · nine providers in `IntegrationProvider.java` · HMAC-signed state, tokens AES-GCM encrypted at rest by `TokenCipher` · ADR 0031 · `IntegrationFlowIntegrationTest`, `OAuthWave1FlowIntegrationTest` · PRs #221, #226, #247, #249 | `UNIQUE (tenant_id, provider)` — one connection per provider per tenant, not per user. `NORA_INTEGRATIONS_ENC_KEY` is fail-closed since PR #469: without it the API refuses to boot rather than storing tokens in the clear |
| US63 | Connect the providers that do not do the OAuth dance | C | DONE | Telegram pairs by code — `POST /integrations/telegram/pairing/start` (line 134) and `/verify` (line 146), with the bot `chat_id` kept in the `access_token` column; Trello takes a token pasted by the user — `POST /integrations/trello/token` (line 157) · migration V026 header · PR #249 | Two connection models sharing one table. The column name `access_token` is a poor fit for a Telegram `chat_id`, and the migration header is the only place that says so |
| US64 | Post a meeting outcome into a chat tool | S | DONE | `SlackPostMessageAction.java` (`slack_post_message`), `DiscordPostMessageAction.java`, `TelegramSendMessageAction.java` · PRs #227, #246, #249 | Discord goes through an incoming webhook rather than OAuth, so it has no row in `integration_connections` |
| US65 | Send e-mail through the user's own mailbox | S | DONE | `GmailSendAction.java` (`gmail_send_email`) and `OutlookSendEmailAction.java` (`outlook_send_email`) · PRs #221, #249 | Distinct from US76, which sends through NORA's own transactional sender and needs no connection |
| US66 | Call an arbitrary webhook from a flow | C | DONE | `CallWebhookAction.java` (`call_webhook`) · PR #246 | — |
| US47 | MCP project state (pull Jira/Linear/Azure DevOps) | **W** | **WONT** | No code | **Closed by ADR 0041 §Effect on the backlog.** It is inbound in name only: what it asks for is NORA *pulling* state out of three external trackers, which is ADR 0031's outbound lane tripled — three more OAuth apps to register and three more schemas to normalise. The MCP server decided in ADR 0041 needs none of it. Moved here from E9, where it never belonged |

### E7 — Enterprise Administration

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US30 | Configure the company context | M | DONE | `TenantContextController` (`GET` line 40, `PUT` line 51) · migration V005 · `apps/web/src/app/(app)/settings/context/page.tsx` · PR #33 | — |
| US31 | Version history of the company context | S | DONE | migration `V028__tenant_context_versions.sql` (immutable table + `tenant_contexts.current_version` + backfill of version 1 + RLS) · `TenantContextRepositoryAdapter.save` writes the version in the upsert transaction · `GET /tenant/context/versions` and `GET /tenant/context/versions/{version}` (`TenantContextController`) · history block in `settings/context/page.tsx` · `TenantContextHistoryIntegrationTest` | Reactivated by ADR 0038 §5. **No restore endpoint, deliberately**: the UI loads a past version into the form and the user saves, which appends version N+1 through the audited write path instead of rewriting history. A save that does not change the document writes no version. Version 1 of a pre-V028 context carries an approximate `created_at` — the true one was never recorded |
| US32 | Tenant's corporate domain | M | DONE | `TenantController.updateDomain` (`TenantController.java:71`) · migration V009 · ADR 0011 · `TenantDomainIntegrationTest` · PR #55 | — |
| US33 | Tenant usage metrics | S | DONE | `GET /usage` (`UsageController.java:78`) · `application/usage/UsageService` + `UsageView` · `UsageEventRepository.tenantSeries` (a NEW query with `WHERE tenant_id = :tenantId`, **not** the operator's cross-tenant `aggregate`) · `TrendsRepository.meetingsPerBucket` · `apps/web/src/app/(app)/usage/page.tsx` · `UsageServiceTest` (10 cases) | **Reactivated by ADR 0046 §1**, and the tenant-facing half is now built. Two limits are on the wire rather than in a comment: `ai.state` is `UNAVAILABLE` when the control plane of ADR 0022 is off — which is the default in local, test and CI — so the AI figures are absent rather than zero there, and it is `WITHHELD_RESTRICTED_SCOPE` for a caller whose IAM position distinguishes meetings, because `usage_events` carries no meeting id and no per-item answer exists. Cost is `CATALOG_LIST_PRICE`, never an invoice, and live transcription is counted in `unmeteredCalls` because ADR 0045 §4 attributes a session issued, not a minute transcribed |
| US34 | Export of a consolidated report for the period | S | DONE | `apps/web/src/lib/report/usage-report.ts` (`usageToCsv`, `usageToMarkdown`, `usageReportFileName`) · `apps/web/src/app/(app)/usage/export-menu.tsx` — generated client-side, downloaded via Blob, no server round-trip, same shape as US25/US60 · `usage-report.test.ts` (14 cases) | Client-side for the reason US25 recorded, re-checked against the three conditions that would justify an endpoint: the payload is bounded by the range ceiling, the export is not an audited artefact, and a non-browser consumer belongs to the MCP server of ADR 0041. Escaping and the UTF-8 BOM are imported from `tasks-export` rather than reimplemented. An AI column the API did not measure is written **empty**, not `0` — a spreadsheet sums a zero and charts it |

### E8 — Enterprise IAM (AWS-style)

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US35 | Create IAM groups | M | DONE | `IamController.createGroup` (`IamController.java:67`) · migration V006 · PR #35 | — |
| US36 | Create and version JSON policies | M | DONE | `createPolicy` (line 126) / `updatePolicy` (line 147) · `iam_policy_versions` table in V006 | Conditions: `StringEquals`/`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` |
| US37 | Attach/detach policies to groups and users | M | DONE | `IamController.java:169-196` · composite FK `(tenant_id, user_id)` on `iam_user_policies` since V027 | — |
| US38 | Add/remove users from groups | M | DONE | `IamController.java:94` / line 102 · composite FK on `iam_user_groups` since V027 | — |
| US39 | Clear HTTP 403 when out of scope | M | DONE | `GlobalExceptionHandler` · `AuthorizationCoverageIntegrationTest` asserts every endpoint is gated | Stability of the error-message detail is not asserted |
| US40 | IAM audit log | M | DONE | `IamController.listAudit` (line 207) · `iam_audit_events` table in V006 | The auth audit log (login/refresh/logout) exists separately since PR #118; there is still no single global `audit_events` table |
| US41 | Policy templates | S | DONE | `GET /iam/policy-templates` (`IamController.listPolicyTemplates`) · `PolicyTemplateCatalog` in `domain/iam/` · `PolicyTemplateCatalogTest` · `IamPolicyTemplatesIntegrationTest` · the templates block on `apps/web/src/app/(app)/settings/iam/page.tsx` | **Reactivated by ADR 0046 §1**, and delivered as a **code-shipped catalogue: no `is_template` column, no table, no migration** — the reserved V033 was deliberately not taken. A flagged row would still be an attachable policy id, so the attach path would need a new branch to refuse it. Named cost: no per-tenant customisation and no "save as template". There is also no instantiate endpoint — the client posts the template document to `POST /iam/policies`, so the resulting policy is created and evaluated by the same code as a hand-written one |
| US42 | Visual policy editor (form-based) | S | DONE | `apps/web/src/components/policy-form-editor.tsx` beside the Monaco editor · conversion and round trip in `apps/web/src/lib/iam/policy-document.ts` with `policy-document.test.ts` · form/JSON toggle in `apps/web/src/app/(app)/settings/iam/page.tsx` · PR #55 shipped the JSON half | **Reactivated by ADR 0046 §1.** The form offers only the five condition operators `PolicyEvaluator` implements — a sixth would build an Allow that never allows — and it REFUSES to open a document it cannot represent exactly instead of dropping fields on save. Building it exposed a read/write asymmetry: `GET /iam/policies` emitted the domain record's names (`actions`, `resources`, `"ALLOW"`) while the write side parses `action`/`resource`, so the existing edit action loaded a document its own schema rejected. Fixed in the same PR |
| US43 | Policy simulator ("can user X do Y on Z?") | S | DONE | `POST /iam/simulate` (`IamController.java`), gated by its own `iam:policy:simulate` action · `PolicyEvaluator.explain` · simulator section in `apps/web/src/app/(app)/settings/iam/page.tsx` · `IamSimulationIntegrationTest` | The answer carries the reason and the deciding statement, and the Root case is reported as `ROOT_BYPASS` instead of a mute `true`. `isAllowed` is `explain(...).allowed()`, so the explanation cannot drift from the decision. No user picker: no endpoint lists the tenant's users, so the field takes a user id like the rest of the screen |
| US44 | Permission boundaries | C | DONE | `GET`/`PUT`/`DELETE /iam/users/{userId}/boundary` (`IamController`) · the five-argument overloads of `PolicyEvaluator.explain`/`isAllowed`/`hasAnyAllow`/`uniformDecision` · migration **V033** (`iam_permission_boundaries`) · ADR 0049 · `PolicyEvaluatorBoundaryTest` + `IamPermissionBoundaryIntegrationTest` · the boundary block on `apps/web/src/app/(app)/settings/iam/page.tsx` | **Reactivated by ADR 0046 §1**, on the thinnest argument of the seven and the ADR says so. Built as **one concept, not a hierarchy**: at most one boundary per **user** and none on groups (two group boundaries combine wrongly in both directions), the cap applied inside the single traversal US43 established so the simulator reports it (`BOUNDARY_NOT_PERMITTED`, `BOUNDARY_EXPLICIT_DENY`) instead of a misleading "no statement matched", and **absence = unrestricted**, never deny-all. Named limits: the tenant **Root is not capped** and a boundary aimed at it is refused (a cap on Root has no key, and there is no support desk); AWS's `iam:PermissionsBoundary` condition key is **not** built, so a delegated admin with `iam:attachment:create` can still create an unbounded user — what a boundary guarantees is that the admin cannot escalate *themselves*; one extra query per authorization decision |

### E9 — Meeting Productivity

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US45 | Opt-in Productivity Score (declare the objective) | M | DONE | `PUT /meetings/{id}/goal` (`MeetingsController.java:476`) and `DELETE` (line 499) · migration V012 · ADR 0005 · `ProductivityFlowIntegrationTest` · PR #67 | — |
| US46 | Productivity Score 0-100 + coverage per outcome | M | DONE | `ProductivityAssessment` model + JSON Schema + UI (`MeetingProductivitySection`, `ProductivityScoreCard`) · PR #67 | — |

> US47 used to live here. It was never about productivity — it was about pulling state out of
> external trackers — and ADR 0041 closed it. It is recorded in E6 with the rest of the
> interoperability stories.

### E10 — Customer Confidence

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US48 | Customer Confidence Score 0-100 with signals and objections | M | **DONE** | Migration `V017__create_customer_confidence.sql` (5 tables + RLS) · the worker emits `customerConfidence` (`models.py` + stub + prompt) · persisted in the pipeline (`AnalysisService` → `CustomerConfidenceService.persist`) · `GET /meetings/{id}` returns `customerConfidence` · UI `CustomerConfidenceCard` · `CustomerConfidenceFlowIntegrationTest` · PR #148 | Delivered as **V017** — the V013 slot ADR 0015 reserved was used by soft-delete (PR #114) |
| US49 | Trend `IMPROVING`/`STABLE`/`DECLINING` | M | **DONE** | Trend is authoritative on the server: `CustomerConfidenceService.computeTrend` compares against the account's previous assessment (dead band ±5 pts), persisted in `customer_confidence_assessments.trend` · PR #148 | The worker's trend guess is deliberately ignored |
| US50 | Aggregated Account Health Score per account | **W** | **WONT** | `account_health_snapshots` was foreseen in the data model and never migrated | **Closed by ADR 0038 §4.** It aggregates over time across accounts and there is nothing to aggregate: no tenants, no history. It stops being debt in `docs/engineering/data-model.md` and becomes closed scope |
| US51 | Alert when Account Health changes band | **W** | **WONT** | No code | **Closed by ADR 0038 §4.** It alerts on the band computed by US50 and cannot outlive it. `docs/challenge/use-case-diagram.md:273` still describes this alert firing; that document has not caught up |

### E11 — Conversational Assistant

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US67 | Ask NORA about my workspace in a chat | S | DONE | `apps/web/src/app/api/chat/route.ts` — streaming BFF, runs entirely server-side so the LLM key never reaches the browser · `apps/web/src/app/(app)/chat/page.tsx` · ADR 0004 (provider-agnostic) · PR #165 | Input is budgeted rather than unbounded: the last 16 messages, 8k characters per message and 24k across the retained history |
| US68 | Chat answers grounded in my own meetings | S | DONE | The route calls `GET /meetings/search?q=&k=6` (`route.ts:268`), which is US15's embedding search, and falls back to the most recent meetings when the search returns nothing · PR #206 | Inherits US15's gap: meetings analysed before PR #206 have no embedding and can only be reached by the recency fallback |
| US69 | Chat answers use the company context | S | DONE | The route fetches `GET /tenant/context` (`route.ts:366`) and injects products, ICP, glossary and competitors into the system prompt · PR #467 | **This was the product's central claim and the chat did not do it until PR #467.** The analysis path (US14) had been reading the same context since V005 |
| US70 | Persistent chat sessions I can revisit, rename and delete | S | DONE | `ChatSessionController.java` — `GET /chat/sessions` (line 48), `POST` (line 57), `GET /{id}` (line 67), `POST /{id}/messages` (line 75), `PATCH /{id}` (line 87), `DELETE /{id}` (line 97) · migration V022 (`chat_session`, `chat_message`, both with RLS `tenant_isolation`) · sidebar `apps/web/src/components/core/app-sidebar-sessions.tsx` · `ChatSessionIsolationIntegrationTest` · PR #219 | Sessions are scoped by tenant **and** by user: a user never sees another user's sessions, not even Root |

### E12 — Automation (NORA Flows)

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US71 | Build an automation on a visual canvas | S | DONE | `apps/web/src/app/(app)/flows/` — `page.tsx`, `new/page.tsx`, `[id]/page.tsx`, `catalog.tsx`, `flow-editor.tsx`, `side-panel.tsx` · React Flow styled with NORA tokens · ADR 0032 · PRs #223, #226, #248, #250 | The block that lands on an empty canvas is the first trigger in `CATALOG`, so the array order is load-bearing — flagged in the file's own comment after PR #468 added two more triggers |
| US72 | Automations fire on what the analysis found | S | DONE | Three dispatched **event** triggers in `TriggerType.java` — `meeting.analysis_completed`, `action_item.created`, `meeting.risk_detected` — published by `AnalysisService` and handled by `WorkflowEngine` · in-process post-commit event bus, ADR 0030 · `TriggerEventsIntegrationTest` · PRs #220, #227, #468 | Two of the three existed in the backend for months without appearing in the canvas catalog; PR #468 exposed them. The fourth trigger, `schedule.cron`, is not one of these — it is fired by a timer, not by the bus (US75) |
| US73 | Test a flow before activating it | C | DONE | `POST /workflows/{id}/test` (`WorkflowsController.java:128`), gated by its own `workflow:test` IAM action because it really executes the wired actions | A test run sends real e-mail and creates real issues against the tenant's own connections. That is deliberate and is why it is a distinct IAM action, but there is no dry-run mode |
| US74 | See a flow's execution history | S | DONE | `GET /workflows/{id}/executions` (`WorkflowsController.java:135`) · `workflow_executions` in migration V023 with `status` CHECK in `RUNNING`/`SUCCESS`/`FAILED` and a `log_json` JSONB trail · `ExecutionLogBuilder.java` · `WorkflowFlowIntegrationTest` | — |
| US75 | Flows on a schedule (cron) | C | DONE | `ScheduledFlowRunner.java` — a `@Scheduled` tick that claims each due run in `workflow_schedules` (migration **V032**) before executing it · `ScheduleSpec.java` (the restricted vocabulary: hourly / daily / weekly, compiled to a canonical cron expression) · `TriggerType.SCHEDULE_CRON.hasDispatcher()` is now true and `WorkflowDefinitionParser` accepts it with a valid schedule and refuses it without one · offered in the canvas as the "on a schedule" trigger block (`catalog.tsx`, `side-panel.tsx`) · ADR 0047, architecture §23 · `ScheduleSpecTest`, `ScheduledFlowRunnerTest` | **Reactivated by ADR 0046 §1, built.** Deliberately **not a digest**: the run fires once per meeting analysed since the previous window opened (the fan-out `action_item.created` has), because every condition and action reads a one-meeting context and no aggregate placeholder exists. A period with no analysed meetings writes no execution row, so "it ran and found nothing" is invisible in the history — the alternative filled a 50-row history with no-ops. A timer-fired run carries **no principal**: it acts under the tenant's integration connections, so revoking `workflow:write` does not stop a flow already created (ADR 0047 §6) |
| US76 | Send the meeting summary by e-mail from a flow | S | DONE | `SendEmailAction.java` + `MarkdownLite.java` (renders the summary's Markdown into the message body) · PR #245 | Goes through NORA's own transactional sender; US65 is the variant that sends through the user's connected mailbox |
| US77 | Schedule the calendar follow-up from the extracted due date | C | DONE | `FollowUpSchedule.java` — shared by the Google and Outlook calendar actions; picks the nearest action-item due date strictly after today, falls back to "tomorrow", and an explicit `startInDays` on the node always wins · PR #252 | A due date extracted for today is skipped on purpose (it may already have passed), which is invisible to the user |

### E13 — Privacy & Data Lifecycle

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US78 | Permanently erase a meeting | S | DONE | `DELETE /privacy/meetings/{id}` (`PrivacyController.java:50`) — physical hard delete, distinct from ADR 0021's soft delete; the FK cascade purges the transcript (`raw_text`, the PII), participants, tags and analyses · authorization runs on the loaded meeting's attributes so an attribute-scoped Deny still fires · ADR 0029 · `PrivacyFlowIntegrationTest` · PR #204 | — |
| US79 | Retention window that purges old meetings automatically | C | DONE | `RetentionSweeper.java` · `NORA_PRIVACY_RETENTION_DAYS` · ADR 0029 · PR #204, semantics documented honestly in PR #468 | **`0` and any negative value mean retention is OFF, and that is the shipped default** — the purge is an irreversible hard delete with CASCADE, and no environment should start deleting because a variable went unset. There is no value meaning "purge immediately"; the smallest window is one day. The window is **global**: one number for every tenant, with no per-tenant column and no link to tenant status. The public landing page claims otherwise and is frozen by ADR 0038 pending a separate decision, tracked in issue #456 |
| US80 | Delete an entire tenant, and export my data (LGPD portability) | S | MISSING | `PrivacyController` carries exactly one mapping — the per-meeting erasure of US78. There is no tenant deletion and no portability export | **Declared deferral, ADR 0038 §6h**, with a single reactivation trigger for the whole operations block: NORA acquires a user who is not the maintainer. Both are duties owed to the data subjects of a live service, and there is no subject to owe them to. ADR 0029 §Negative already names the open semantic question |

### E14 — Platform Control Plane (operator surface)

> Not a customer tier. This is the surface the maintainer operates the platform from, separated from
> per-tenant IAM by ADR 0023 and reached only through Cloudflare Access by ADR 0025.

| ID | Title | MoSCoW | Status | Evidence | Known debt |
|---|---|---|---|---|---|
| US81 | Manage the AI model catalog without a deploy | S | DONE | `GET/POST /admin/platform/models` (`PlatformAdminController.java:80,85`), `DELETE /models/{id}` (line 105), `GET /config` (line 120) and `PUT /config/{service}` (line 130) · separate platform datasource, ADR 0022 · ADR 0024 · `apps/admin/src/app/models/page.tsx` · PR #172 | — |
| US82 | Services resolve their model at runtime | S | DONE | `GET /internal/platform/llm-config?service={chat\|analysis\|multimodal}` (`PlatformInternalController.java:38`) · consumed by `apps/web/src/app/api/chat/route.ts` · ADR 0024 | **Soft fallback by design**: if resolution fails the caller uses the `LLM_*` environment instead of failing, so the chat never goes down because the control plane is unreachable. The cost is that an operator's model switch can silently not apply |
| US83 | See what the AI is costing, per service and per tenant | S | DONE | `POST /internal/platform/usage` (`PlatformInternalController.java:48`, fire-and-forget, always 202) · `GET /admin/platform/telemetry/cost` (`PlatformAdminController.java:150`) · `apps/admin/src/app/telemetry/page.tsx` · ADR 0024 · PR #172 | Cost is computed from the token counts providers report, not from an invoice. ADR 0039 notes that once STT moves to an ephemeral session token the audio path is not measured at all — sessions issued and minutes estimated, not bytes counted |
| US84 | The operator console is unreachable without operator identity | S | DONE | `apps/admin/` behind Cloudflare Tunnel + Access · ADR 0023, ADR 0025 · `PlatformSecurityIntegrationTest` · **fail-closed by default since PR #471**: with no `CF_ACCESS_*` configured the console answers 403 and renders no data, and the mock path is an explicit opt-in (`NORA_ADMIN_USE_MOCKS`) | `/healthz` stays open on purpose. The app is in the `ci-gate` with lint, typecheck and build since PR #471 |
| US85 | Platform health and business telemetry | C | DONE | `GET /admin/platform/telemetry/health` (`PlatformAdminController.java:159`) and `/telemetry/business` (line 164) · `GET /admin/platform/flags` (line 115) | Platform-wide, for the operator. The tenant-facing equivalent is US33 — `GET /usage` — which is a different endpoint on a different plane, not this one reused: it takes the tenant JWT and binds one tenant in the WHERE clause |
| US86 | Reindex meetings the RAG index never got | S | DONE | `GET`/`POST /admin/platform/embeddings/backfill` · `EmbeddingBackfillService.java` · platform migration `V002` (retires the dead `service.search-embeddings` flag) · `EmbeddingBackfillIntegrationTest` · ADR 0044 | Indexing at the end of an analysis is best-effort, so a missing credential or a failing provider left a meeting out of `meeting_embeddings` forever — the only remedy was a full reprocess, paying for a whole LLM analysis to obtain one vector. The backfill reindexes from the summary already stored, and the same query covers a change of embedding model. **Operator-triggered on purpose**: it is billed per meeting, so there is no startup catch-up and no scheduled sweep, and a run is one tenant at a time with a ceiling. No console UI yet — it is `curl` behind Cloudflare Access |

## 3. Workstreams implemented beyond the backlog

Work that was not in the MoSCoW and came in via a sub-phase or an architectural decision. Rows that
describe something the repository no longer contains are kept and marked **historical** with the ADR
that closed them — deleting them would lose the traceability that ADR 0014's own Alternative 2, and
then ADR 0038's, argued is worth keeping.

| Item | PR | ADR | Status |
|---|---|---|---|
| Stateful refresh tokens + httpOnly cookies | #59 | — | DONE (migration V011, cookie `nora_refresh` 30d, access `nora_access` 15min) |
| Refresh-token rotation + reuse detection (token families) | #116 | ADR 0020 | DONE (V014) |
| PII Shield with PERSON_NAME (BR) — ~270 names + negative list | #59 | ADR 0012 | DONE. Scope restated by **ADR 0040**: the non-negotiable is now "PII never reaches the **analysis** LLM in the clear"; cloud transcription (ADR 0039) is a declared external subprocessor upstream of it |
| Structured PII redaction on the chat path (BFF) | #165 | ADR 0033 | DONE (`redactPii` in `apps/web/src/app/api/chat/route.ts`, applied to the search query, the context and the messages before anything leaves) |
| Speech Token Broker (ephemeral Azure Speech token) | #29 | ADR 0009 | **Historical.** Deleted with the Azure Speech path: ADR 0034 shut down the subscription that held the resource, ADR 0035 replaced the engine, and `SpeechController` + `SpeechTokenService` + the broker left the backend in PR #460. ADR 0039 rebuilds the *pattern* against OpenAI — what died was the vendor, not the design |
| Live analysis endpoint (Desktop overlay highlights) | #65 | ADR 0039 | DONE (`POST /meetings/live-analyze`, `MeetingsController.java:545`) |
| TF-IDF baseline package (`packages/nlp-baseline/`) | #54 | ADR 0010 | DONE (3 modules, 52 tests) |
| Expanded synthetic dataset (12 .txt + 3 .vtt + 2 .srt + 3 JSON contexts) | #54 | — | DONE (`data/synthetic/`, `data/samples/`) |
| DS Sprint 1+2 notebook (`notebooks/01-tf-idf-eda-meetings.ipynb`) | #54 | — | DONE (26 cells) |
| Productivity Score full-stack | #67 | ADR 0005 | DONE (V012 + worker + backend + web) |
| Editorial visual redesign v2, then the v3 chat-first shell | #56, #58, #219 | — | DONE |
| Complete Bicep IaC (9 modules + main + bicepparam) | #62 | ADR 0034, ADR 0036 | **Historical.** `infra/bicep/` was deleted when the platform left Azure. The substrate is a single bare-metal host with Docker Compose (`infra/host/`) |
| Azure deployment via OIDC (`deploy-infra.yml`) | #64 | ADR 0034, ADR 0036 | **Historical.** The workflow is gone; `deploy-host.yml` publishes an immutable release pointer and the host is rolled forward by `deploy.sh --tag`. ADR 0038 §6e records that nothing on the host consumes the pointer |
| GHCR build/push pipeline | #63 | ADR 0034 | DONE and still current (`.github/workflows/build-images.yml`, images `nora-{api,worker,web,admin}`) |
| App Insights Java Agent + role names | #136 | ADR 0034, ADR 0036 | **Historical.** Application Insights went with the Azure subscription; observability is Prometheus + Loki + Grafana on the host |
| Customer Confidence LLM schema | #25 | ADR 0006 | DONE (schema) |
| Customer Confidence full-stack | #148 | ADR 0015 | DONE (V017 + `AnalysisService` wiring + server-side trend + `CustomerConfidenceCard`) |
| `meeting_attributes` JSONB + GIN index | V007 + V008 | ADR 0007 | DONE (arbitrary attributes for IAM conditions) |
| Meeting reprocessing | #46 | — | DONE (`POST /meetings/{id}/reprocess`, `MeetingsController.java:514`) |
| Soft-delete (`deleted_at` + `@SQLRestriction` + partial UNIQUEs) | #114 | ADR 0021 | DONE (V013) |
| JWT RS256 + JWKS endpoint (`/.well-known/jwks.json`) | #117 | — | DONE (`JwksController.java:32`) |
| Expanded auth audit log (login/refresh/logout) | #118 | — | DONE |
| Composite FK isolation `meetings.(tenant_id,owner_user_id) → users` | #137 | ADR 0019 | DONE (V015) |
| Composite FK on the IAM attachment tables | #382 | ADR 0019 | DONE (V027, same remedy as V015 applied to `iam_user_groups` and `iam_user_policies`) |
| **Row-Level Security** (`tenant_isolation` + `TenantRlsAspect`) | #138 | ADR 0019, ADR 0026, ADR 0028 | DONE. Schema V016, completed by V019/V020 (full coverage + auth-aware scope). **Enforced on the deployed stack since 2026-08-10**; off by default in the repository, which ADR 0038 §6g records as a declared deferral of the default and nothing else |
| CORS configurable per env | #42 | — | DONE (`CORS_ALLOWED_ORIGINS`) |
| Internal token required on the NLP worker routes | #469 | — | DONE (`X-Internal-Token`; the worker was reachable unauthenticated from inside the network) |
| `nora-architect` skill for AI coding agents | #53 | — | **Historical.** Delivered in #53, removed 2026-08-16 when vendor-specific AI tooling was stripped from the repository |
| Repository normalised to English + two guard scripts | #410 | — | DONE (`scripts/check-doc-links.sh`, `scripts/check-language.sh`). Both are nets, not proofs: the language guard keys on accents plus a curated word list and never sees commit messages |

## 4. State Summary (2026-08-17)

Recounted by parsing the §2 tables, not by hand. Two parallel deliveries each recounted with only
their own story once, and the Total row was found stale in the same pass — so the check runs in both
directions: the per-row sums against the Total row, and `DONE + PARTIAL + MISSING + WONT` against the
number of stories.

| MoSCoW | Total | DONE | PARTIAL | MISSING | WONT |
|---|---|---|---|---|---|
| **Must Have (M)** | 28 | **28** | 0 | 0 | 0 |
| **Should Have (S)** | 40 | **38** | **1** (US13) | **1** (US80) | 0 |
| **Could Have (C)** | 12 | **12** | 0 | 0 | 0 |
| **Won't Have v1 (W)** | 6 | **1** (US09) | 0 | 0 | **5** (US05, US08, US47, US50, US51) |
| **Total** | **86** | **79** | **1** | **1** | **5** |

> **The numerals in this table are recomputed by script**, not by hand, after the parallel branches
> of the 2026-08 wave merge. Each branch sees only its own delivery, so a branch that recounted the
> whole table would produce a number that is wrong twice over — which is how the Total row went
> stale once already. The STORY LISTS in the cells are maintained per branch and are accurate; the
> counts beside them may lag by one wave.

**What changed against the 2026-05-14 revision, and why the totals move so much:**

- **34 new stories (US52-US85)** for surfaces that were shipped and unrecorded: chat and its
  sessions, Flows, OAuth integrations, Projects, the printable report and Markdown export, batch
  upload and transcript splitting, account self-service, operational LGPD and the control plane.
- **The old totals did not sum.** That revision claimed 58 stories while carrying 51 rows, claimed
  31 Must Have where the rows show 28, and claimed 15 Should Have where the rows show 13. The
  numbers above were counted, not carried.
- **Statuses corrected in both directions.** US28 and US29 went MISSING → DONE (the promise was met
  by OAuth, not MCP). US26 was PARTIAL on evidence the document itself admitted it had never
  inspected, and is DONE since PR #470. US50 and US51 went MISSING → WONT.
- **US15's evidence was factually wrong** and is corrected: the similarity runs in Java over a JSON
  vector in a `TEXT` column; pgvector is not in use.
- **`WONT` is a new status.** ADR 0038 §4 draws a line ADR 0014 collapsed: "deferred" means
  reactivatable under a criterion, and these four are not waiting for anything.
- **US86 was added after that revision** (ADR 0044), for the same reason the 34 above were: the RAG
  index had a defect nobody had written down.
- **US25, US31, US43 and US21 all went MISSING → DONE** after that revision, in the same wave. They
  are the four stories ADR 0038 §5 reactivated, and none of them is open any more.
- **US21 was delivered on `meeting_analyses.topics` rather than on the embeddings**, which is why
  its row no longer carries "only worth anything once the backfill has been run": the panel does not
  read the RAG index at all.
- **US27 went MISSING → DONE** with the MCP server (ADR 0041, migration V029). It is the one story
  in this realignment that ADDED scope rather than closing it, and the DONE covers the read-only
  first cut ADR 0041 §4 defines, not the write tools it explicitly leaves undecided.

**Effective coverage**

- Must Have: **28 of 28** (100%). The v1.0 MVP of §6 is complete.
- Should Have: **38 of 40** (95%). The two that remain are tenant-wide erasure and portability
  (US80, a declared deferral under ADR 0038 §6h rather than open work) and one partial, US13. All
  four of the ADR 0038 reactivations have landed, US27 left this list when the MCP server shipped,
  and US33, US34, US41 and US42 left it under ADR 0046 §1.
- Could Have: **12 of 12** (100%). Scheduled flows (US75) shipped under ADR 0047 and permission
  boundaries (US44) under ADR 0049 — the last of the seven stories ADR 0046 §1 reactivated.

## 5. Scope decisions in force

The deferral table that used to sit here duplicated ADR 0014. ADR 0014 is superseded, and this
section now points rather than copies: **the ADR decides, the backlog records.**

| Story | Decision | Where it is decided |
|---|---|---|
| US05 — Corporate SSO | **WONT** | ADR 0038 §4 |
| US50 — Aggregated Account Health | **WONT** | ADR 0038 §4 |
| US51 — Account Health band alert | **WONT** | ADR 0038 §4 |
| US47 — MCP project state | **WONT** | ADR 0041 §Effect on the backlog |
| Enterprise DPA and SLA (not user stories) | **Closed** | ADR 0038 §4 |
| US21 — Trends panel | **Reactivated**, and delivered — migration V030 | ADR 0038 §5 |
| US25 — CSV/MD task export | **Reactivated**, and delivered | ADR 0038 §5 |
| US31 — Company-context history | **Reactivated**, and delivered — migration V028 | ADR 0038 §5 |
| US43 — Policy simulator | **Reactivated**, and delivered — `POST /iam/simulate` | ADR 0038 §5 |
| US27 — NORA as an MCP server | **Reframed and reactivated**, and delivered — read-only first cut, migration V029 | ADR 0041 |
| US28, US29 — calendar and task managers | **Reframed**: delivered by OAuth, removed from MCP scope | ADR 0041 §Effect on the backlog |
| US80 — tenant deletion and LGPD export | **Declared deferral**, reaffirmed rather than reopened | ADR 0038 §6h · ADR 0046 §2 |
| US33 — Tenant usage metrics | **Reactivated**, and delivered — `GET /usage` | ADR 0046 §1 |
| US34 — Consolidated period report | **Reactivated**, and delivered — client-side, no endpoint | ADR 0046 §1 |
| US41 — Policy templates | **Reactivated**, and delivered — code-shipped catalogue, no migration | ADR 0046 §1 |
| US42 — Form-based policy editor | **Reactivated**, and delivered — form beside the JSON editor | ADR 0046 §1 |
| US44 — Permission boundaries | **Reactivated** on the thinnest argument of the seven, and **delivered** — migration V033; the five questions a cap raises are decided in ADR 0049 | ADR 0046 §1 · ADR 0049 |
| US13 — Mentioned participants | **Reactivated**, and delivered — deterministic matching over the declared roster, no new migration | ADR 0046 §1 · ADR 0048 |
| US75 — Flows on a schedule (cron) | **Reactivated**, and built — the scheduler's five undocumented semantics are decided in ADR 0047 | ADR 0046 §1 · ADR 0047 |
| US08 — Audio/video upload | **WONT** — batch transcription is a second provider surface, not a small addition to a streaming one | ADR 0046 §2 |

**The limbo this paragraph used to describe is closed by ADR 0046.** It read: *"Still deferred, and
honestly so: US08, US33, US34, US41, US44 — ADR 0038 neither kills these five (§4) nor reactivates
them (§5) … open scope with no scheduled trigger."* That was accurate and it was not a status. A row
whose state rests on nobody having decided is indistinguishable from a forgotten one, and worse,
because it reads as deliberate — which is the exact failure ADR 0038 was written to end and had
reproduced in a corner of its own sorting.

ADR 0046 empties it: **US33, US34, US41, US42, US44, US13 and US75 are reactivated and built**;
**US08 becomes WONT** with a reason that can be checked, replacing an inherited commercial criterion
that never could be. **Every story in §2 now has a status some accepted decision is responsible
for** — which matters more than any single row, because one unexplained status makes the others
unverifiable too.

The operations block — monitoring alerts, off-host backup, restore-drill cadence, secret rotation,
the roll-forward consumer, desktop code signing and the RLS repository default — is not backlog
scope and is not listed here. ADR 0038 §6 records each item with what exists, what is deferred and
why, under a single reactivation trigger: NORA acquires a user who is not the maintainer.

## 6. MVP — Version 1.0 Scope

The NORA v1.0 MVP covers exactly the stories classified **Must Have**, distributed across three
central flows. It is complete (§4), and it is reproduced unchanged because it is the ruler §2 uses.

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

## 7. Acceptance Criteria — Critical Stories

### US11 — Generate the meeting summary

**Given that** a meeting has been processed successfully,
**when** the user opens the meeting detail,
**then** they must see a summary in Portuguese with: the meeting's objective, main points discussed,
decisions taken and next steps.

**Business rules:**
- The summary must be between 150 and 500 words
- It must be generated within 30 seconds after processing
- It must use the company context (Enterprise) when available

### US14 — Company context in processing

**Given that** the admin has configured the company context,
**when** a meeting of the tenant is processed by NORA AI,
**then** the generated summary and tasks must reflect the terminology and priorities configured in
the context.

**Business rules:**
- The context is injected as a base instruction into the AI prompt
- Updating the context does not reprocess old meetings
- The context is isolated per tenant (it does not leak between companies)

### US19 — Scope-restricted visibility (Enterprise)

**Given that** an Enterprise user has IAM policies that limit their access (e.g. condition
`nora:Department = "sales"`),
**when** they open the meetings panel,
**then** they see only meetings whose attributes satisfy the applicable Allow policies and do not
fall under Deny policies.

**Business rules:**
- The filter is applied in the backend (not only in the frontend)
- A direct URL access attempt to a resource outside the permissions returns `403`
- The tenant's Root has full bypass and sees everything

### US36 — IAM policies (Effect/Action/Resource/Condition)

**Given that** the Root opens "Settings > IAM > Policies",
**when** they create a new policy by submitting a JSON document with `version`, `statements[]` (each
with `effect`, `action[]`, `resource[]` and an optional `condition`),
**then** the policy must be persisted with version 1, validated against the official schema and
available to be attached to groups/users.

**Business rules:**
- Policies are always scoped to the tenant; they do not leak between tenants.
- Every change creates a new version in `iam_policy_versions` (immutable history).
- Evaluation follows the order: Root → Allow; otherwise, an explicit **Deny** wins; otherwise,
  require at least one applicable Allow; default Deny.
- Wildcards (`*`) are supported in `action` and `resource`.
- Conditions use AWS-style operators: `StringEquals`, `StringIn`, `StringLike`,
  `DateGreaterThan`, `DateLessThan`. Operators outside that list are fail-closed.

### US70 — Persistent chat sessions

**Given that** a user has held conversations with NORA,
**when** they open the application on any device,
**then** they must see their own previous sessions in the sidebar, be able to reopen one with its
full message history, rename it and delete it.

**Business rules:**
- A session is scoped by tenant **and** by user: no user sees another user's sessions, and the
  tenant's Root has no bypass here.
- The scope is enforced in the repository, not only in the query the UI happens to send.
- Deleting a session deletes its messages (`chat_message` cascades from `chat_session`).

### US72 — A flow fires on what the analysis found

**Given that** a tenant has an active flow whose trigger is one of the three dispatched events,
**when** a meeting analysis completes,
**then** every matching flow must execute after the transaction commits, and the run must be
recorded in `workflow_executions` with its status and log — whether it succeeded or failed.

**Business rules:**
- The three dispatched triggers are `meeting.analysis_completed`, `action_item.created` and
  `meeting.risk_detected`. All three come out of the same analysis round.
- The bus is in-process and post-commit: a flow never observes data that was rolled back.
- `schedule.cron` is the fourth trigger and does not come from the bus: a timer fires it (US75,
  ADR 0047), once per meeting analysed since the previous run's window opened. Before US75 it had
  no dispatcher and was refused on save, because a flow that could never run must not be storable
  in the `ACTIVE` state — which is still the rule, now satisfied by having a dispatcher.
- A failing action fails its own run and is logged; it does not fail the analysis that triggered it.
