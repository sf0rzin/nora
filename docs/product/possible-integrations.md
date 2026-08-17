# Possible integrations in NORA Flows — catalogue + credential tutorials

> **This catalogue has been executed.** It was written as a build plan, and every one of the ten
> numbered sections below has since shipped (ADR 0031, migrations V024–V026). What stays useful is
> the second half of each section — the step by step for obtaining the credentials, which is what
> anyone reconnecting a provider or registering a new app still needs. What is no longer true is the
> framing: these are not "possible" integrations, and the ranking is not a recommendation.
>
> **Two corrections to the workflow line this banner replaces.** It said credentials are wired
> through "GitHub Secrets, Bicep, backend, block on the canvas". **Bicep is gone** — the Azure
> deployment it belonged to was shut down with no subscription and no export (ADR 0034/0036); the
> substrate is one bare-metal host and secrets reach it through SOPS + age
> (`docs/operations/environment-secrets.md`). And a per-user OAuth credential is not an environment
> variable at all: it is stored per tenant in `integration_connections` (V024), encrypted at rest.
> The env-var path is only for the **app-level** credential — the client id and secret NORA
> registers once with each provider.
>
> Entry criterion for the catalogue: **free** (no per-user/per-use charge at our
> volume) and **multi-user** (each NORA user connects their own account — it is not one
> hardcoded account of ours).

## Current state (measured 2026-08-17)

Counted from `services/api/src/main/java/br/com/nora/api/infrastructure/integration/actions/` and
from the provider `CHECK` constraints in migrations V024–V026, rather than from memory.

| | |
|---|---|
| Flow actions implemented | **13** — `send_email` (Resend), Gmail, Google Calendar, Outlook, Microsoft Calendar, Slack, Discord, Telegram, GitHub, Notion, Todoist, Linear, Trello, plus the generic webhook |
| OAuth providers registered | **9** — `google`, `microsoft`, `slack`, `telegram`, `github`, `notion`, `todoist`, `linear`, `trello` |
| Direction | **Outbound only.** NORA writes into these tools; it does not read them back. The inbound direction is the MCP server (ADR 0041), which is a different mechanism and a different document |

The table this replaces listed three integrations and was dated 2026-06-12. It said Slack was
"backend ready, the app still has to be created" — Slack shipped, and so did the nine after it.

## The build order that was actually followed

Kept as a record rather than deleted, because the ordering argument is still the right one for
whoever adds the eleventh: cheapest credential first, most visible on stage first. It is **not** a
list of things to do — all of it is done.

1. **Generic webhook** — zero credentials, huge impact ("integrates with anything")
2. **Discord** — zero global credentials (per-channel webhook), visual demo
3. **Slack** — backend already ready, only the app is missing (15 min)
4. **Telegram** — free bot in 2 min, phone notification live on stage
5. **GitHub** — OAuth App with no review, "action item becomes an issue"
6. **Notion** — free OAuth, "summary becomes a page in the workspace"
7. **Trello / Todoist / Linear** — free OAuth, "action item becomes a card/task"
8. **Microsoft Outlook/Teams** — possible and free, but the Entra ID setup is more of a hassle

## 1. Generic webhook (HTTP POST) — no credentials needed

**What it enables:** the "Call webhook" action — NORA does a POST with the event JSON
(summary, action items, risks) to any URL the user pastes in. It is what makes
Flows "integrable with anything" (n8n, Zapier, Make, internal systems).

**Cost / multi-user:** free by definition; each user pastes their own URL into the node.

**Credential required: NONE.** There is no env var — the URL field lives in the canvas node
itself. The action still has to be implemented.

## 2. Discord — per-channel webhook (no app, no OAuth)

**What it enables:** the "Post to Discord" action — meeting summary/alerts in a channel.

**Cost / multi-user:** free. Each user generates the webhook for THEIR OWN server/channel
and pastes the URL into the node (same model as the generic webhook, with a nicely
formatted payload — embeds with the NORA colour, summary/action fields).

**Credential required: NONE globally.** Tutorial we will surface in the product:

1. In Discord, open the server → desired channel → **Edit channel** → **Integrations**.
2. **Webhooks** → **New webhook** → give it a name (e.g. "NORA") → **Copy webhook URL**.
3. Paste the URL into the "Post to Discord" node on the canvas. Done.

## 3. Slack — OAuth v2 (backend already ready!)

**What it enables:** the `slack_post_message` action (already implemented) — post a
summary/alert in a chosen channel. The integrations hub already has the connector waiting
for the credentials.

**Cost / multi-user:** free (Slack's free plan is enough). With **public distribution
enabled** (free, no review for basic bot scopes), any workspace connects via OAuth.

**Tutorial (≈15 min):**

1. Go to <https://api.slack.com/apps> → **Create New App** → **From scratch**.
2. Name: `NORA` · Workspace: yours (it is only the workspace that "owns" the app).
3. **OAuth & Permissions** menu:
   - Under **Redirect URLs**, add: `https://api.nora.systems/integrations/slack/oauth/callback`
   - Under **Scopes → Bot Token Scopes**, add: `chat:write` and `channels:read`.
4. **Manage Distribution** menu → **Distribute App** → enable public distribution
   (checklist: remove hard-coded secrets etc. — just tick them, our app already complies).
5. **Basic Information** menu → **App Credentials** section: copy the **Client ID** and
   **Client Secret**.
6. Save them in your user env vars:
   - `SLACK_OAUTH_CLIENT_ID`
   - `SLACK_OAUTH_CLIENT_SECRET`

## 4. Telegram — bot (single token, no OAuth)

**What it enables:** the "Send on Telegram" action — a notification in a private chat or
group when the analysis finishes / a high risk is detected. Strong demo: the phone buzzes on stage.

**Cost / multi-user:** free and no review. Model: ONE NORA bot; each user
connects by sending `/start` to the bot (NORA captures the `chat_id` via the deep link
`t.me/<bot>?start=<code>` — the backend does the pairing).

**Tutorial (≈2 min):**

1. In Telegram, talk to **@BotFather** → `/newbot`.
2. Display name: `NORA` · username: something like `nora_flows_bot` (it must end in `bot`).
3. BotFather replies with the **token** (format `123456:ABC-DEF...`).
4. Save it in your user env var: `NORA_TELEGRAM_BOT_TOKEN`

## 5. GitHub — OAuth App (action item becomes an issue)

**What it enables:** the "Create GitHub issue" action — each action item from the meeting
becomes an issue in the chosen repository. Great for the "from the meeting straight to the
backlog" narrative.

**Cost / multi-user:** free, no review process — any GitHub user authorises the app on the spot.

**Tutorial (≈5 min):**

1. GitHub → **Settings** (of your profile) → **Developer settings** → **OAuth Apps** →
   **New OAuth App**.
2. Application name: `NORA` · Homepage: `https://nora.systems`
   · Authorization callback URL: `https://api.nora.systems/integrations/github/oauth/callback`
3. **Register application** → copy the **Client ID** → **Generate a new client secret** → copy it.
4. Save them in your user env vars:
   - `GITHUB_OAUTH_CLIENT_ID`
   - `GITHUB_OAUTH_CLIENT_SECRET`

## 6. Notion — public integration OAuth (summary becomes a page)

**What it enables:** the "Create Notion page" action — the meeting report (summary,
decisions, action items) becomes a page in the user's workspace.

**Cost / multi-user:** free. A Notion "public integration" can be authorised by any
workspace via OAuth **without needing to be listed in the gallery**
(it is the gallery listing that requires review — we do not need it).

**Tutorial (≈10 min):**

1. Go to <https://www.notion.so/my-integrations> → **New integration**.
2. Type: **Public** · Name: `NORA` · owning workspace: yours.
3. Under **OAuth Domain & URIs**, redirect URI:
   `https://api.nora.systems/integrations/notion/oauth/callback`
   (it will also ask for a site/privacy policy — you can use `https://nora.systems`).
4. Capabilities: **Insert content** and **Read content** are enough.
5. Copy the **OAuth client ID** and **OAuth client secret**.
6. Save them in your user env vars:
   - `NOTION_OAUTH_CLIENT_ID`
   - `NOTION_OAUTH_CLIENT_SECRET`

## 7. Trello — API key (action item becomes a card)

**What it enables:** the "Create Trello card" action — action items become cards in a list.

**Cost / multi-user:** free. Trello uses the app's API key + a token authorised by the
user (Atlassian's own authorisation flow, no review).

**Tutorial (≈5 min):**

1. Logged into Trello, go to <https://trello.com/power-ups/admin> → **New** (create a
   "NORA" Power-Up — it is the administrative container, no need to publish it).
2. On the Power-Up page → **API key** → **Generate a new API key**.
3. Under "Allowed origins", add `https://nora.systems`.
4. Copy the **API key** (and the **Secret**, if displayed).
5. Save them in your user env vars:
   - `TRELLO_API_KEY`
   - `TRELLO_API_SECRET` (if there is one)

## 8. Todoist — OAuth (action item becomes a task)

**What it enables:** the "Create Todoist task" action — with the due date coming from the
action item (`dueDate`).

**Cost / multi-user:** free, no review.

**Tutorial (≈5 min):**

1. Go to <https://developer.todoist.com/appconsole.html> → **Create a new app**.
2. Name: `NORA` · OAuth redirect URL:
   `https://api.nora.systems/integrations/todoist/oauth/callback`
3. Copy the **Client ID** and **Client Secret**.
4. Save them in your user env vars:
   - `TODOIST_OAUTH_CLIENT_ID`
   - `TODOIST_OAUTH_CLIENT_SECRET`

## 9. Linear — OAuth (action item becomes a product issue)

**What it enables:** the "Create Linear issue" action — for the startup/product audience.

**Cost / multi-user:** free, no review.

**Tutorial (≈5 min):**

1. In Linear: **Settings** → **API** → **OAuth applications** → **New OAuth application**.
2. Name: `NORA` · Callback URL: `https://api.nora.systems/integrations/linear/oauth/callback`
3. Copy the **Client ID** and **Client Secret**.
4. Save them in your user env vars:
   - `LINEAR_OAUTH_CLIENT_ID`
   - `LINEAR_OAUTH_CLIENT_SECRET`

## 10. Microsoft Outlook / Teams / Calendar (Graph) — possible, medium effort

**What it enables:** parity with Google for Microsoft corporate users: send
e-mail via Outlook, create a Calendar event, post to Teams.

**Cost / multi-user:** free (multi-tenant App Registration in Entra ID). The end user
authorises via normal OAuth. *However*: corporate tenants usually require
admin consent, and Microsoft recommends "publisher verification" for multi-tenant
apps — without it an "unverified" warning appears (similar to Google Testing).

**Status: shipped.** `OutlookSendEmailAction` and `MicrosoftCalendarCreateEventAction` are in
`infrastructure/integration/actions/`, and `microsoft` is a registered provider since V026. This
section used to say "leave it for after the pitch — Google already covers the e-mail/calendar story
in the demo", which was a reasonable call in June and became advice against building something that
already exists. The steps below are still what you follow to register or re-register the app:

1. <https://portal.azure.com> → **Microsoft Entra ID** → **App registrations** → **New registration**.
2. Supported account types: **Accounts in any organizational directory and personal
   Microsoft accounts** (multi-tenant + personal).
3. Redirect URI (Web): `https://api.nora.systems/integrations/microsoft/oauth/callback`
4. **Certificates & secrets** → **New client secret** → copy the **Value** right away (it disappears afterwards).
5. **API permissions** (delegated, Microsoft Graph): `Mail.Send`, `Calendars.ReadWrite`,
   `offline_access`, `openid`, `email`.
6. Env vars: `MS_OAUTH_CLIENT_ID` · `MS_OAUTH_CLIENT_SECRET`

## Outside the catalogue (and why)

- **WhatsApp (Meta Cloud API):** requires Meta Business verification — weeks, it is not
  "anyone can connect". Unfeasible pre-pitch.
- **Jira:** Atlassian's OAuth 2.0 (3LO) works for free, but the app has to go through
  approval for multi-user production; dev mode limits the users. Same vibe as
  Google Testing — possible, but with no advantage over GitHub/Linear for the demo.
- **Zoom / Google Meet (import recording):** free APIs exist, but they are a *source* of
  transcription (another epic), not a Flows action.
- **Google Sheets ("log meetings in a spreadsheet"):** technically it is just adding the
  `spreadsheets` scope to the existing Google OAuth — but a new scope = the consent screen again
  on every reconnection and one more reason for a future review. Evaluate post-pitch.

## Security reminders (agreed process)

- Save an env var on Windows: PowerShell →
  `[Environment]::SetEnvironmentVariable('NOME', 'valor', 'User')` (or Settings →
  environment variables). **Never** paste the credential into the chat, into a commit or into a
  repo file.
- Propagate it to GitHub Secrets **always via `gh secret set NOME --body ...`**
  (never a pipe — the PowerShell BOM corrupts it) and to the host's SOPS-encrypted `secrets.env`.
  It used to say "and to Bicep/the app"; Bicep went with Azure (ADR 0034/0036), and the path to the
  running container is now `infra/host/secrets.env` plus the compose file that reads it.
- Every new integration follows the ADR 0031 pattern: HMAC state, AES-GCM encrypted token in
  the database, server-side refresh.

## History

Created 2026-06-12 as a catalogue plus tutorials, with the credential flow going through local env vars.

Updated 2026-08-17: the catalogue turned out to have been **fully executed** — thirteen actions and
nine OAuth providers shipped — while the document still read as a plan, still dated its state to
June, and still recommended deferring Microsoft, which had shipped. The state table was re-measured
from the actions directory and the migration `CHECK` constraints, the ranking was relabelled as the
order that was followed, and the two dead references to Bicep were replaced with the path that
actually carries a secret to the host today. The credential tutorials were left alone: they are the
part that is still true and still needed.
