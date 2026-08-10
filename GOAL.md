# GOAL — NORA 100% REAL for the FIAP Pitch (deadline: 2026-06-15)

> Guiding document for a PAIRED autonomous run (Anthony + Fable 5 Ultracode 1M).
> Read this entire file before editing a single line. Then read CLAUDE.md
> and the docs it points to (docs/product, docs/engineering, docs/adr).

## North star (one sentence)
Make NORA Core, the Administrator Panel and Documents **100% functional and for
real** (zero dead buttons, zero stubs, zero "coming soon") and deliver the
flagship feature **NORA Flows**: a VISUAL automation builder in the style of Google
Stitch / n8n (canvas with a grid background + draggable nodes) where the user wires
TRIGGERS (e.g. "Meeting analyzed") to MCP-style ACTIONS (e.g. "Send email",
"Send report", "Create event in Google Calendar") — with REAL EXTERNAL
integrations (real OAuth), running end to end.

## Definition of "REAL" (the bar for this delivery)
- An email is REAL when it lands in an actual inbox.
- A Google/Slack integration is REAL when it goes through real OAuth and the action
  actually happens in the external account (email sent, event created, message
  posted).
- A button is REAL when it persists in the backend and survives a reload.
- There is NO "pretend it was sent", no "coming soon" toast, no mock on a production screen.
- Mock/stub mode is only allowed in automated tests and in explicit local dev.

## Golden rule
On every commit, the branch builds and the demo path works. Prefer ONE finished
real vertical slice over two half-done ones. Commercial-product level quality.

================================================================================
## PAIRED MODE + HUMAN HANDOFF PROTOCOL
================================================================================
You have a human collaborator (Anthony) available ~24/7, with Claude Max 20x and
willing to spend money on APIs/infra. Use that: when a task requires a
step only a human can do, **STOP and ask**, with an exact copy-pasteable instruction.
Never skip, never pretend, never leave a stub in its place.

Ask for a human handoff for (examples):
- Creating/configuring a project in the **Google Cloud Console** (OAuth consent screen,
  OAuth Client ID, Gmail/Calendar scopes, redirect URIs on nora.systems).
- Completing an **OAuth consent** flow in the browser (Google/Slack login).
- Creating an app/Bot token in **Slack** (workspace, scopes, install).
- Pasting a **secret** into the vault (server-side) or into the local .env — never into the repo.
- **Approving spend** (model upgrade, paid tier, new Azure resource) — say
  how much and why beforehand.
- **Visually verifying** a real external result (email received, calendar event,
  Slack message) — ask for a screenshot/confirmation.
- Confirming that the **OpenAI key is alive** (chat/analysis break with 502 if the
  key was revoked — see memory reference-rotacao-chaves-llm).

Handoff request format: a "🙋 HANDOFF HUMANO" block with (1) what I need,
(2) exact numbered steps, (3) what to hand back to me (token/URL/screenshot), (4) what
I do when you come back. Then keep working on whatever does not depend on it.

================================================================================
## SECRETS, KEYS AND SPEND
================================================================================
- No secrets in the repo. Variable names in .env.example; values in the
  server-side vault (Anthony's toolbelt) or .env (gitignored).
- Google OAuth Client Secret, Slack Bot Token, Resend API key, OpenAI key →
  vault/env, never committed, never echoed into a log.
- OAuth redirect URIs must point at the real domain (nora.systems / api.
  nora.systems) and/or localhost for dev — ask the human to register them.
- When spend is needed, state the estimated cost and ask for approval (handoff).

================================================================================
## SEQUENCE (burn risk early; branch always green)
================================================================================
Everything below is IN-SCOPE and needs to become REAL. The order exists to remove risk
first and keep the demo alive — not to cut scope.

### PHASE 0 — Foundation + de-risking the scariest part
1. **Real event bus** in the backend: `ApplicationEventPublisher` emitting events
   AFTER commit (TransactionSynchronization). Emit `MeetingAnalysisCompletedEvent`
   at the completion point of `AnalysisService.run()`.
2. **Flows storage + engine** (migrations V023+: `workflows` +
   `workflow_executions`, with tenant_id + RLS) and `WorkflowEngine` (async listener)
   + `ActionExecutor` (port + adapters).
3. **REAL "Send email" action** via `ResendEmailSender` (already exists) — prove the
   event→action pipeline end to end with an integration test, BEFORE the canvas.
4. **Google OAuth spike (with human handoff):** create the Google Cloud project, OAuth
   client, redirect URIs, prove a real "send email via Gmail API" on a minimal
   path. Removes the biggest risk while there is still time to recover.

### PHASE 1 — Canvas + anchor scenario running live
5. `/flows` route in Core: canvas with a **grid background**, draggable nodes
   (little squares: Trigger / Condition / Action), edges connecting them, a parameter
   sidebar, **Salvar** and **Testar** buttons (runs and shows the execution log).
6. **Anchor scenario running live:** transcript upload → COMPLETED analysis
   → the event fires the flow → real email + real report. Execution history
   shows success with a log.

### PHASE 2 — REAL external MCP integrations (real OAuth)
7. **Gmail** (send email through the user's Google account) — real OAuth, token
   storage with refresh rotation, MCP adapter.
8. **Google Calendar** (create an event from an action item / meeting) — real.
9. **Slack** (post a summary/alert to a channel) — real.
   Each connector: real OAuth flow + secure token storage + adapter behind
   the `ActionExecutor` port. The connector hub in `integracoes/` becomes real
   (status "Conectado"/"Conectar", never "em breve").

### PHASE 3 — Close out 100% of Core / Admin / Documents (zero stubs)
10. **Settings that actually save** + the missing backend endpoints:
    - `GET /auth/me`, `PATCH /users/me` (displayName) → Account tab.
    - `POST /auth/password/change`, `POST /auth/logout-all` → Security tab.
    - `GET /tenant`, `PUT /tenant/name` → Workspace tab.
    - `DELETE /users/me` (LGPD, hard-delete) → Danger zone.
    - `POST /auth/verify-email/resend` → resend verification.
11. **Chat** survives a reload (minimal sessionStorage or rehydrate from the session).
12. **Dashboard**: real pagination (prev/next) + auto-refresh while PROCESSING
    (on the dashboard and on the meeting detail).
13. **PII badge** with a real counter (`metadata.piiRedactionsApplied`).
14. **Documents**: complete tags (not just tags[0]); real **report export**
    (Markdown and PDF) from the analysis.
15. **Admin**: "Saúde do sistema" telemetry (latency/error/throughput via App
    Insights) and "Métricas de negócio" (meetings/chats/conversion) with real data.

### PHASE 4 — Flows depth + polish
16. More triggers (`action_item.created`, `meeting.risk_detected`,
    `schedule.cron`), more actions (create task, consolidated report), conditions
    (Productivity Score < N, tag, priority, customerConfidence < N).
17. Ready-made flow templates, dry-run/simulator, and UX refinements
    (chat stop/retry, microcopy, empty states).

================================================================================
## NORA FLOWS — SPECIFICATION
================================================================================

### Triggers (domain events emitted by the backend)
- `meeting.analysis_completed` (the anchor — emit in AnalysisService, post-commit)
- `action_item.created`
- `meeting.risk_detected` (high severity)
- `schedule.cron` (e.g. daily at 9am — reuse the @Scheduled pattern)

### Conditions (simple evaluator; no condition = always fires)
`Productivity Score < N`, `tag == X`, `priority == HIGH`,
`customerConfidence.score < N`.

### Actions (MCP style — `ActionExecutor` port + adapters)
- **Send email** (internal, Resend) — REAL
- **Send report** (generates the meeting summary/report in MD/PDF and sends/downloads it) — REAL
- **Send email via Gmail** (the user's Google account, OAuth) — REAL
- **Create event in Google Calendar** (OAuth) — REAL
- **Post to Slack** (OAuth/Bot token) — REAL
- **Create task** (action item) — REAL

### Canvas (UI)
Grid background (Stitch/n8n), draggable nodes, edges, parameter sidebar,
Save + Test with a log. Raw Tailwind + OKLCH tokens + DM Sans (ADR 0013). Evaluate
React Flow vs. a custom canvas; decide and record it in an ADR. If React Flow, style it
with the NORA tokens (without bringing in another design system).

### Backend (DDD, respecting the layers)
- Migrations V023+: `workflows(id, tenant_id, name, trigger_type, definition_json,
  active, created_at)` + `workflow_executions(id, workflow_id, tenant_id,
  event_type, status, log_json, created_at)` — tenant_id + RLS on both.
- `WorkflowEngine` (async listener) matches events → the tenant's active workflows →
  evaluates conditions → executes actions via `ActionExecutor`.
- OAuth tokens: a secure table per tenant/user, refresh rotation.
- Endpoints: `GET/POST/PUT/DELETE /workflows`, `POST /workflows/{id}/test`,
  `GET /workflows/{id}/executions`, plus OAuth callbacks
  (`/integrations/{provider}/oauth/callback`). IAM + RLS + tenant_id.
- **Record an ADR** for the event bus + workflow engine + OAuth/token strategy.

================================================================================
## NON-NEGOTIABLE CONSTRAINTS (break one = revert)
================================================================================
- **Tenant isolation**: tenant_id in every new table; backend filter + RLS. ADR 0002.
- **PII never raw into the LLM**: PIIShield is the last gate. ADR 0012.
- **JSON Schema strict** on any new LLM output. ADR 0003.
- **DDD layers**: domain does not know Spring/HTTP/SDK; application orchestrates;
  infrastructure adapts; api is thin.
- **DM Sans only** + **raw Tailwind (no shadcn)** + OKLCH tokens via var(--token). ADR 0013.
- **UI in PT-BR** (the project's language until the pitch).
- **No secrets in the repo**; .env.example for the names; values in the vault/env.
- **Spotless**: `mvn spotless:apply` before every backend commit (CI runs
  spotless:check first; GJF alone does not fix importOrder).
- **Individual Core WITHOUT IAM** (Stratfy decision).
- **DO NOT touch the desktop app** (apps/desktop — separate collaborator).
- **DO NOT break** the existing auth, IAM, multitenancy or chat.

================================================================================
## WHERE TO WORK (real code map)
================================================================================
- Core front:    apps/web/src/app/(app)/            (create `fluxos/`)
- Settings:      apps/web/src/app/(app)/settings/    (Account/Security/Workspace)
- Chat:          apps/web/src/app/(app)/chat/page.tsx + apps/web/src/app/api/chat/route.ts
- Dashboard:     apps/web/src/app/(app)/dashboard/page.tsx
- Connectors:    apps/web/src/app/(app)/integrations/page.tsx  (becomes a real hub)
- API client:    apps/web/src/lib/api/client.ts + types.ts
- Design system: apps/web/src/styles/tokens.css + components.css
- Admin:         apps/admin/src/app/                 (telemetria/page.tsx → real)
- Backend ctrls: services/api/src/main/java/br/com/nora/api/api/controllers/
- Backend app:   services/api/src/main/java/br/com/nora/api/application/
  (AnalysisService.run() = event emission point)
- Reuse:         infrastructure/email/ResendEmailSender.java (REAL email),
  infrastructure/config/AsyncConfig.java (tenant propagation across threads),
  application/privacy/RetentionSweeper.java (@Scheduled), domain/meeting (status).
- Migrations:    services/api/src/main/resources/db/migration/ (next: V023+)
- Worker:        services/nlp-worker/ (structured action items = the fuel)

================================================================================
## WORK LOOP
================================================================================
1. Take the highest-priority item not yet done (Phase 0 → 4 / DoD).
2. Implement the smallest REAL vertical slice (back+front together when needed).
3. Verify (commands below). If a human step is needed, open a HANDOFF and carry on
   with whatever does not depend on it.
4. `mvn spotless:apply` (if backend). Small commit referencing IDs. Branch
   always green.
5. Update docs/goals/pitch-progress.md (what became REAL, what is missing, risks).
6. Repeat. Record durable decisions in an ADR. Stop when the DoD is met
   or no safe work remains without a handoff; deliver a summary + demo script +
   risks for human review before the stage.

### Verification per slice
- Backend:   `mvn -q -pl services/api test` + `mvn spotless:apply`
- Worker:    `pytest` in services/nlp-worker
- Web/Admin: `npm run typecheck` + `npm run build`
- Infra:     `az bicep build` (if touching infra)
- REAL:      run the app and trigger the real path (email arrives, OAuth
             completes, event shows up) — ask for human verification when external.

================================================================================
## DEMO SCRIPT (06/15) — has to run live
================================================================================
1. Login → Core. Show chat with persisted sessions + the meetings dashboard.
2. Settings: edit name / change password / rename workspace — everything really saves.
3. NORA Flows: build on the canvas [Reunião analisada] → [Enviar e-mail] →
   [Criar evento no Google Calendar]. Save.
4. Upload a transcript. The analysis finishes. The flow fires by itself:
   a real email arrives + a real event shows up in Google Calendar.
5. Open the flow's execution history: green log, end to end.
6. Admin: show models, costs and system health with real data.

================================================================================
## KICKOFF PROMPT (paste as the 1st message of the run)
================================================================================
You are NORA's engineering agent, running PAIRED with Anthony (available
~24/7, with a budget for APIs/infra) until the pitch (2026-06-15). Your full
objective is in GOAL.md — read it ENTIRELY first, along with CLAUDE.md and the docs
it points to. Then map the current state before editing.

Principles:
- EVERYTHING has to become REAL (see "Definition of REAL"): zero stubs, zero "coming soon",
  zero fake sends. External integrations via real OAuth.
- Work through the Goal in sequence (Phase 0 → 4), burning risk early (event bus +
  OAuth first), always leaving the branch buildable and the demo alive.
- When you need a human step (OAuth, Google Cloud, Slack app, pasting a
  secret, approving spend, verifying an external result), OPEN A HUMAN HANDOFF with
  exact instructions and carry on with whatever does not depend on it. Do not pretend, do not skip.
- For each slice: smallest real increment → relevant verification (mvn test /
  pytest / npm typecheck+build / az bicep build) → `mvn spotless:apply` if backend
  → small commit referencing IDs → update docs/goals/pitch-progress.md.
- Respect ALL the non-negotiable constraints (tenant isolation + RLS, PII, JSON
  Schema strict, DDD, DM Sans + raw Tailwind + OKLCH, PT-BR, no secrets,
  spotless, Core without IAM). DO NOT touch the desktop. DO NOT break auth/IAM/chat.
- Record durable decisions in an ADR (event bus, workflow engine, OAuth/token).

Stop when the Definition of Done is 100% met or the only work left depends on a
human handoff. Deliver: (1) what is done and REAL, (2) what still needs you,
(3) a step-by-step demo script, (4) risks for the stage.
