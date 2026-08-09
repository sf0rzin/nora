# Progress — FIAP Pitch 2026-06-15 (NORA 100% Real + NORA Flows)

> Living log of the paired run (Anthony + Fable 5 Ultracode). Update it at every slice.
> Canonical goal: ../../GOAL.md

## How to use
- Check off each DoD item when it becomes **REAL** (not just coded: genuinely verified).
- Record open/pending HANDOFFs so context is not lost between sessions.
- Note risks for the human review before going on stage.

## PR chain (2026-06-11)
`#219` redesign v3 + chat sessions (the base for everything) → `#220` Flows Phase 0 (event bus + engine +
real email) → `#221` Google OAuth → `#227` Slack + extra triggers. In parallel on top of `#220`:
`#223` /fluxos canvas → `#225` Phase 3 polish (PII badge, polling, live sidebar, tags, export
MD/PDF) → `#226` integrations hub + Gmail/Calendar blocks on the canvas. Outside the chain (based on
main): `#222` real admin telemetry, `#224` settings endpoints.
**Suggested merge order: #219 → #220 → #221 → #227 → #223 → #225 → #226 → #222 → #224.**

## Status by phase
| Phase | Item | State | Verified? | Notes |
|---|---|---|---|---|
| 0 | Event bus + MeetingAnalysisCompletedEvent | ✅ coded | IT green | ADR 0030; DomainEventPublisher port + post-commit adapter; emission in AnalysisService.run() fail-soft (PR #220) |
| 0 | Flows storage + engine (V023+, RLS) | ✅ coded | IT green | V023 (workflows + workflow_executions, RLS following the V022 pattern); WorkflowEngine BFS + ConditionEvaluator + ActionRegistry; API /workflows CRUD + test + executions (PR #220) |
| 0 | Real "Send email" action (Resend) | ✅ coded | IT green + standalone real send accepted by Resend (id 24df5078) | EmailSender.sendWorkflowNotification PROPAGATES failure; awaiting Anthony's visual confirmation in the inbox |
| 0 | Google OAuth spike | 🟨 backend ready | 26 tests green (Google stubbed) | ADR 0031: V024 + state HMAC + AES-GCM + runtime refresh + gmail_send_email/calendar_create_event actions (PR #221); MISSING handoff (Google Cloud project + client id/secret) |
| 1 | /fluxos canvas (grid + nodes + Test) | ✅ coded | typecheck+build green; diff reviewed | React Flow v12 (ADR 0032), list + editor + executions with log, nav/middleware/palette (PR #223); still needs to be run live against the API |
| 1 | Live anchor scenario | ⬜ pending | — | depends on merging the chain + deploy (or a full-stack local run) |
| 2 | Gmail (real OAuth) | 🟨 backend ready | IT green (stub) | missing Google Cloud handoff + block in the canvas catalog |
| 2 | Google Calendar (real OAuth) | 🟨 backend ready | IT green (stub) | same |
| 2 | Slack (real OAuth) | 🟨 backend ready | 335-test suite green (Slack stubbed) | PR #227: OAuth v2 + slack_post_message + /invite hint; missing the Slack app (handoff) |
| 3 | Settings (Account/Security/Workspace) save | 🟨 backend ready | 8 IT scenarios green | PR #224: GET /auth/me, PATCH /users/me, password/change, logout-all, GET/PUT tenant, resend verification; MISSING wiring the front (tabs in /settings/context) |
| 3 | LGPD DELETE /users/me | 🟨 backend ready | IT green (deletion + email rebirth) | password required + personal-tenant guard (409); missing the danger-zone front end |
| 3 | Chat survives reload | ✅ was already real | confirmed during mapping | sessions persisted via ?s= (commit 7ecb528, PR #219); live sidebar + rename/delete on the polish branch |
| 3 | Dashboard pagination + polling | ✅ was already real | confirmed during mapping | PR #219; only DETAIL polling was missing → polish branch |
| 3 | Real PII badge | 🟨 on the polish branch | — | type metadata.piiRedactionsApplied (the backend already returns it) |
| 3 | Report export (MD/PDF) | 🟨 on the polish branch | — | client-side MD + print route for native PDF |
| 3 | Admin health + business metrics | ✅ coded | typecheck+build green | PR #222 — the backend already existed, the slice was front-end only |
| 4 | More triggers/actions/conditions | 🟨 almost | TriggerEvents IT green | PR #227: action_item.created + meeting.risk_detected triggers (HIGH only) emitted post-commit; 4 conditions + 4 actions in the engine; missing schedule.cron and a create-task action |
| 4 | Templates + dry-run + polish | ⬜ pending | — | |

## Human HANDOFFs (open / resolved)
| Date | Request | Status | Result |
|---|---|---|---|
| 2026-06-11 | Confirm the Resend proof email in the inbox (axonogenesis@proton.me, subject "NORA Flows - prova de envio real (Fase 0)") | ✅ resolved | it arrived in the inbox |
| 2026-06-11 | Create a Google Cloud project + OAuth Client ID/Secret + redirect URIs + enable the Gmail/Calendar APIs (steps in the api's .env.example) | ✅ resolved | consent screen in Testing mode; credentials in User env vars + GitHub Secrets |
| 2026-06-11 | Authorize merging the #219→#220→#221→#223 chain (CI green) | ✅ resolved | EVERYTHING merged (through #232) and DEPLOYED; smoke green in production |
| 2026-06-11 | Create a Slack app (chat:write + channels:read) and set SLACK_OAUTH_CLIENT_ID/SECRET | 🟡 open | wiring ready in the Bicep |
| 2026-06-11 | Validate live: connect Google in /integracoes + anchor scenario (flow → email + Calendar) | 🟡 retest | the 1st round (06/12) found analysis 422 + Calendar 400 — fixed and deployed; the script still needs to be repeated |

## Deploy (2026-06-11, end of day)
- Production updated: API revision 34 Healthy with image `sha-80c9a06` (HEAD), web/admin/worker likewise.
- Smoke in production: healthz 200, resend 202, OAuth callback 302 → /integracoes, /workflows and /integrations 401 without auth.
- 3 latent infra bugs fixed along the way: ServerIsBusy race in Postgres (#229), missing embedding secretRefs in the apiApp (#230), and a UTF-8 BOM in the GitHub Secrets written via a PowerShell pipe (rewritten via --body; see memory reference-gh-secret-bom-powershell).
- **Deploy procedure**: deploy-infra resets the images to `:latest` (stale) — ALWAYS run `gh workflow run build-images.yml --ref main` afterwards, which re-pins `sha-<commit>` on the 4 apps.

## Bug-bash after the Grand Finale test (2026-06-12)
Anthony ran the script live in production and reported problems; all were diagnosed and fixed the same day (PRs #235–#240, all merged and deployed):
- **Analysis failing (422)** — `WorkerDtos` was sending `commercialPlaybook`/`keyFeatures`; the Pydantic worker (`extra="forbid"`) only accepts `objectionHandling`/`keyDifferentiators`. An old mismatch, exposed when the 06/11 deploy pushed the new worker image. Fix + `WorkerDtosContractTest` locking the contract (#235). It was NOT the OpenAI key (local = KV = valid, both tested against the API).
- **calendar_create_event 400** — `OffsetDateTime.toString()` omits zeroed seconds and Google requires full RFC3339. Explicit formatter + the provider's error body in the execution log (#235). Anthony's OAuth was perfect (Gmail from the same flow went out).
- **138× ClassCastException Instant→Timestamp in 12h** — raw cast in the chat sessions and tasks adapters (the sidebar polls sessions → it froze the sidebar and "made the site slow"). `instanceof` guard (#235).
- **Slow site** — web was running with 0.25 vCPU/0.5Gi and `minReplicas 0` (cold start on every idle period). Raised to 1 vCPU/2Gi always warm + API 1 vCPU/2Gi, via az (immediate effect) and persisted in the Bicep (#236).
- **Spontaneous logout** — a benign refresh race (multi-tab and timer+interceptor) hit reuse detection and revoked the entire family. A 60s tolerance window in the backend (anchored on first use; logout is not included) + unified single-flight in the front end (#238); IT realigned to the new contract (#239 — main stayed red for ~30min because the agent had no Docker for the IT; lesson: run ITs in CI before merging).
- **Polish requested by the PO** — admin 100% DM Sans with no mono (#237); Home scrolling on the document (root cause: an internal scroller in `.app-main`), orbs removed from Home/Projects, Active/Paused switch grouped with Test/Save in the editor, deterministic "blurred macro"-style avatars with 8 palettes (#240).
- **Pending from the feedback**: dynamic Calendar properties derived from the meeting data + confirmation when the AI does not know the time (a feature — design the minimal slice before coding; it touches the analysis schema 3 days before the pitch).

## Integrations push + UX after the retest (night of 2026-06-12, PRs #242–#250)
The PO's Grand Finale retest PASSED (real email + Calendar). Following that, a nighttime batch:
- **Post-transcript-upload card** (#242): no automatic redirect — "Transcrição enviada" with live status (queued → analyzing → ready + a "Ver análise" CTA), free actions ("Enviar outra"/"Ir para o Início") and a bridge to Flows (it warns that active flows will run; with no flow, it suggests creating one). The PO's idea.
- **Integrations catalog** (#243): `docs/product/integracoes-possiveis.md` — 10 free/multi-user integrations with a credential tutorial and standardized env vars.
- **Sidebar without flicker + sober copy** (#244); **professional Flows email** (#245): `MarkdownLite` (MD→HTML escape-first, no library) + a template with a NORA frame — the summary was arriving with literal asterisks.
- **Generic webhook + Discord** (#246): `call_webhook` (POST JSON, stable contract, SSRF guard resolving DNS and blocking private ranges) and `discord_post_message` (embed via a channel webhook) + blocks on the canvas. Zero credentials.
- **OAuth framework + GitHub/Notion/Todoist/Linear** (#247): `OAuthProviderConfig`/`Directory` + `GenericOAuthHttpClient` (single token exchange; Google/Slack untouched), generic callback `/{provider}/oauth/callback`, V025 expands the provider CHECK, 4 actions (issue/page/task/issue from an action item), hub + Bicep. ⚠️ in GitHub Actions the GitHub secret is called `GH_OAUTH_CLIENT_ID/SECRET` (the GITHUB_ prefix is reserved).
- **Canvas blocks for the 4 OAuth providers** (#248) — and an old gap discovered: slack_post_message had no block (covered in #250).
- **Microsoft + Telegram + Trello** (#249): the framework gained `supportsRefresh` (60s skew, Google semantics) for Graph (`outlook_send_email`, `mscalendar_create_event`); Telegram WITHOUT OAuth (pairing by a 10min TTL code + t.me deep link + "Verificar conexão" via getUpdates; chat_id encrypted as a connection); Trello with a pasted token validated at /1/members/me. V026 expands the providers. 355 tests.
- **Final canvas blocks** (#250): Slack, Outlook, MS Calendar, Telegram, Trello.
**Total: 13 real integration actions in Flows.** Operational lessons: agent branches need a merge from main + an arity adjustment when the contract evolves in parallel (ActionItemView gained dueDate in #246 and broke tests in #245/#247 — resolved in the worktrees before CI); never edit an accented file with PowerShell cmdlets (mojibake — use Edit/perl).

**Awaiting the PO's credentials** (local env vars → to propagate): SLACK_OAUTH_*, GITHUB_OAUTH_* (repo secret: GH_OAUTH_*), NOTION_OAUTH_*, TODOIST_OAUTH_*, LINEAR_OAUTH_*, NORA_TELEGRAM_BOT_TOKEN, TRELLO_API_KEY, MS_OAUTH_*. Webhook/Discord work without credentials.

## Ingestion: Calendar by the due date, multi-upload and giant files (2026-06-12/13, PRs #252–#255)
- **Calendar scheduled by the meeting's due date** (#252): `FollowUpSchedule` shared by Google+Outlook — without an explicit `startInDays`, the event is created on the nearest (strictly future) `dueDate` of the extracted action items; the origin of the date goes into the execution log. Manual user configuration always wins.
- **Batch multi-upload** (#253): the dropzone accepts multiple files; a concurrency pool of 2 over `uploadMeeting`, per-file progress + individual retry. Single-file unchanged.
- **Giant file with several meetings** (#254 worker+API, #255 front): the worker's `/split` segments a mixed transcript (PII Shield **line by line** to preserve the 1:1 mapping, strict JSON LLM returning boundaries by line, windows of ~240k chars with boundary merging; server-side anti-overlap/gap validation); API `POST /meetings/split-preview` (.txt-only, no persistence); front end with a "separar automaticamente" toggle → an "Encontramos N reuniões" screen (editable title, include/exclude, redacted preview, amber badge < 0.7) → **client-side slicing by line** → feeds into the multi-upload from #253.
- **Adversarial pre-merge review of #255** (ultracode, 21 agents, verification by skeptics): 8 confirmed findings, fixed at merge — (1) HIGH: the worker numbered lines with a raw `split('\n')` while the front end normalizes CRLF/CR→LF; a lone CR diverged the count → shifted cuts (the worker now normalizes the same way, +2 parity tests); (2) filename slug collision disambiguated; (3) synchronous guard against double-click/race after cancellation in `confirmSplit`; (4) >10MB error in PT-BR (413 `FILE_TOO_LARGE`) on the split path. False positives discarded after verification: the confidence badge inherits `opacity` from the parent (correct cascade); dates not propagated to the split is correct (a split = N distinct meetings). SplitPreviewIntegrationTest 6/6.

## Risks for the stage
- **Google in Testing mode: the refresh token expires in 7 DAYS** — reconnect Google in /integracoes the day before (06/14). Verification for the general public is not feasible by 06/15 (gmail.send is a restricted scope: weeks + CASA).
- Admin in a local demo shows MOCK by default (`NORA_ADMIN_USE_MOCKS` only turns off with "false").
- Cost telemetry uses a 24h window — generate traffic before the demo or pass `from`.
- `CF_ACCESS_AUD` read from vars (empty) in deploy-infra — the admin's Tier 2 degrades silently (pre-existing documented bug).
- The Flows event is in-process with no retry: a crash between commit and dispatch loses the trigger (mitigation: the Test button).
- Missing from Phase 4: the schedule.cron trigger, the "create task" action, flow templates and dry-run (none block the demo script).

## Recorded decisions (ADR)
- ADR 0030 — in-process post-commit event bus + workflow engine (PR #220)
- ADR 0031 — Google OAuth + token storage (AES-GCM, state HMAC) (PR #221)
- ADR 0032 — canvas with React Flow styled with NORA tokens (PR #223)
