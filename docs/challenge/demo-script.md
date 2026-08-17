# NORA — demo script

The single script for demonstrating NORA live: what to show, in what order, how long each part
takes, and what to do when a part fails in front of an audience.

**This file is the only place in the repository that states how long the demo is.** The roadmap
and `fiap-challenge-2026.md` used to carry two different numbers — 8-10 minutes in one, 15-20 in
the other — for a script that did not exist. They now link here instead of repeating a duration.

## Declared length

**10 minutes for the core, blocks 1 to 6. Blocks 7 to 9 are optional and take the full run to
18 minutes.**

The reason it is written as blocks rather than as a single fixed number: **nobody knows how long
the slot is.** The pitch of 2026-06-15 has happened; the FIAP milestones after it, the final
delivery included, are still unconfirmed in [`fiap-challenge-2026.md`](fiap-challenge-2026.md),
and the length of any remaining presentation is unknown with them. So every block below carries
its own time, the narrative is complete at the end of block 6, and cutting from the end shortens
the demo without breaking it. Ten minutes is what the roadmap had committed to; the optional
blocks cover the case where the slot turns out to be closer to twenty.

| Block | Minutes | Cumulative | |
|---|---|---|---|
| 1. Setup and framing | 1:00 | 1:00 | core |
| 2. Core — upload and analysis | 2:00 | 3:00 | core |
| 3. Core — the company context is what makes it different | 1:30 | 4:30 | core |
| 4. Enterprise — IAM and scoped visibility | 2:30 | 7:00 | core |
| 5. Customer Confidence and the account trend | 2:00 | 9:00 | core |
| 6. Close | 1:00 | 10:00 | core |
| 7. Flows | 3:00 | 13:00 | optional |
| 8. Chat over the workspace | 2:30 | 15:30 | optional |
| 9. Operator console and cost telemetry | 2:30 | 18:00 | optional |

## Before you start

### Which mode you are running in

This is the single most consequential preparation decision, and it changes what you can honestly
show.

| | **Live-LLM mode** | **Offline mode** |
|---|---|---|
| Worker | `USE_LLM_STUB=false` + `LLM_API_KEY` | `USE_LLM_STUB=true` |
| Cost | real, per analysed meeting | zero |
| Summary, decisions, action items, risks, opportunities | yes | yes, from the deterministic heuristics in `stub_analyzer.py` |
| **Customer Confidence (block 5)** | yes | **no — see below** |
| **Chat (block 8)** | yes, with a key in `apps/web` too | **no — the chat has no stub** |

Two measured facts behind that table, both worth knowing before you promise anything on stage:

- **Customer Confidence needs a real analyser.** The stub emits `customerConfidence` with
  `accountName` always null, and `CustomerConfidenceService.persist` is a documented no-op when
  `accountName` is null or blank. No account is created, no assessment is written, no trend is
  computed, and the card in the meeting detail simply does not render. Block 5 is impossible in
  offline mode; it is not a matter of it looking worse.
- **`USE_LLM_STUB` does not cover the chat.** It is a worker setting. The chat runs in the web
  BFF (`apps/web/src/app/api/chat/route.ts`), which answers 503 when no key resolves for the
  configured provider. There is no stub path there at all.

Semantic search has a third, independent credential: `NORA_EMBEDDING_PROVIDER` (default `gemini`).
Without a credential for it the embedding index stays empty, `GET /meetings/search` returns
nothing, and the chat falls back to recent meetings instead of retrieved ones — so the citations
that make block 8 worth showing will not appear.

**Configuring the credential later does not fix the meetings already seeded**, because indexing
only happens at the end of an analysis. Run the backfill once, after the seed and with the
credential in place, or block 8 will have nothing to cite:

```bash
curl -H "X-Internal-Token: $NORA_PLATFORM_ADMIN_TOKEN" \
  http://localhost:8080/admin/platform/embeddings/backfill          # what it would do
curl -X POST -H "X-Internal-Token: $NORA_PLATFORM_ADMIN_TOKEN" \
  -H 'Content-Type: application/json' -d '{"tenantId":"<uuid>"}' \
  http://localhost:8080/admin/platform/embeddings/backfill          # do it
```

### Seeding

```bash
make db-reset
make dev
API_BASE=http://localhost:8080 make seed-demo
```

`scripts/seed-demo.sh` creates both workspaces, the company contexts, the IAM group, the scoped
policy, the invitation for Rafael and every meeting used below — over the HTTP API, the same way
the web app would. Read its header before pointing it anywhere that is not localhost: it creates
tenants and root users that no endpoint can delete.

It prints, at the end, the generated password and the ids of everything it created. **Keep that
output open in a second window during the demo.** It is where you look up a meeting id when you
need to reach one directly.

Seed at least an hour before the demo, not five minutes before. With a real provider each analysis
is a network round trip, and the seed waits for one before starting the next so the Customer
Confidence trend chains in narrative order.

### The one preparation step the seed cannot do for you

The seed cannot create Rafael. Accepting an invitation needs the raw token, which
`InvitationService` never persists (it stores only the SHA-256), no endpoint returns, and
`LogEmailSender` deliberately suppresses when it prints the dev e-mail. The token exists in the
invitation e-mail and nowhere else, so completing Rafael requires the API to be running with
`RESEND_API_KEY` set and a mailbox you can read.

If you can do that, re-run the seed with `NORA_SEED_RAFAEL_TOKEN=<token>` against a fresh
database and block 4 becomes a two-account demonstration. If you cannot, block 4 has a plan B
below that still shows the policy doing real work.

### Checklist

- [ ] `make dev` up; `GET /actuator/health` answers 200
- [ ] Seed run, output saved
- [ ] Mode decided and written down, so you do not promise block 5 in offline mode
- [ ] Two browser profiles open, one signed in as Camila and one as Rafael (or, without Rafael,
      one signed in as Camila with `/settings/iam` already loaded)
- [ ] A meeting detail page already open in a background tab, as the fallback for block 2
- [ ] Screen recording of the full run, made the day before — the universal plan B

## The blocks

### Block 1 — Setup and framing · 1:00

Open `/dashboard` signed in as Lucas.

Say what NORA is in one sentence: it turns a meeting transcript into a summary, decisions, action
items and commercial signals, **using the customer's own company context** rather than generic
world knowledge. That last clause is the product; everything after this is evidence for it.

Point at the seeded meetings in the list. Two meetings, already analysed, with task counts and
risk counts visible on the cards.

> **Plan B** — if the dashboard is empty, the seed did not run or the API is not up. Do not
> debug live. Switch to the recording and narrate over it.

### Block 2 — Core: upload and analysis · 2:00

Go to `/meetings/upload`. Upload
`data/synthetic/meetings/11-solo-roadmap-concorrente.txt` — a transcript the seed deliberately
leaves out, so there is something genuinely new to process.

While it processes, say what is happening: the file goes to the API, the API calls the NLP worker,
the **PII Shield redacts before any LLM call** (ADR 0012), the model returns JSON validated
against a strict schema (ADR 0003), and the result is persisted.

Open the meeting when it completes. Show the summary, then the decisions, then the action items.
Go to `/tasks`, mark one as done, and show that the change persists.

> **Plan B, in order of preference.** (a) If the analysis is slow, keep talking — the pipeline
> explanation above fills about a minute. (b) If it is still processing after that, switch to a
> meeting the seed already analysed; the upload has already demonstrated acceptance and the
> queued state. (c) If the upload itself fails, open the pre-loaded background tab from the
> checklist. Never re-upload the same file live: the second copy will sit next to the first in
> the list for the rest of the demo.
>
> If a meeting is stuck in `PROCESSING`, that is a known state with a known handler —
> `StuckAnalysisSweeper` moves it to `FAILED` after 30 minutes so it can be retried. Do not wait
> for it on stage; move on.

### Block 3 — Core: the company context is what makes it different · 1:30

Still signed in as Lucas, open `/settings/context` and show the loaded context: the company, the
products with their differentiators, the competitors, the objection-handling notes. This is the
`solo-launch` context — a small software company.

Then go back to the analysis and point at where that context shows up. The context travels with
every analysis request, so the topics carry product and competitor names that came from the
configuration rather than from the transcript alone — the deterministic stub does that by
construction, and a real provider does it through the prompt.

Say the part that matters commercially: **this is configuration, not code.** The workspace you are
about to open in block 4 runs the same build against a completely different vocabulary — a
logistics ERP vendor, with its own modules, competitors and objection playbook — and the only
difference between the two is one JSON document sent to `PUT /tenant/context`. There is no
vendor-specific branch anywhere in the analyser; that is a standing engineering rule for this
repository, and `data/synthetic/README.md` records how the demonstration data respects it.

> **Plan B** — the context page reads from the API. If it fails, show the two payloads side by
> side in an editor: `data/synthetic/tenants/solo-launch.context.json` and
> `data/synthetic/tenants/meridian-erp.context.json`. They are exactly what the seed sent, so
> nothing is being faked — and side by side they make the point better than the page does.

### Block 4 — Enterprise: IAM and scoped visibility · 2:30

Sign in as Camila (root of the Enterprise workspace) and open `/settings/iam`.

Show, in this order:

1. The group `Comercial Sul`.
2. The policy `meetings-read-region-sul` in the JSON editor. Read the condition out loud:
   `StringEquals` on `region = sul`. Explain that the key is not reserved — it is an attribute the
   uploader attached to the meeting, and the evaluator resolves conditions against those
   attributes.
3. The pending invitation for Rafael, carrying that group.

Then show the effect. Camila, as root, sees all five meetings — root bypasses policy evaluation
entirely, by design (ADR 0007). Rafael sees three: the ones tagged `region=sul`.

Two details worth saying out loud, because they are the difference between a demo and a claim:

- The meeting with **no** `region` attribute at all is invisible to Rafael too. A condition key
  missing from the context makes the statement not match — the evaluator is fail-closed, so
  nobody had to write a Deny for it.
- Rafael opening a `region=sudeste` meeting **by direct URL** gets 403. The filter is in the
  backend, not in the frontend.

> **Plan B — and read this one before the demo, because it is the likeliest to be needed.**
> If Rafael was never created (see "the one preparation step the seed cannot do for you"), you
> cannot show the second account. What you can still show, and what is worth showing:
> the policy document, the group, the attachment, the pending invitation, and the IAM audit trail
> recording every one of those changes. Then say plainly that the scoped read is verified by
> `IamScopingIntegrationTest` in the backend suite rather than demonstrated here today. Naming an
> automated test is a better answer than mime-ing a second user.
>
> A second, weaker option: `scripts/seed-demo.sh` prints exactly what Rafael can and cannot see
> when it is run with the token. Showing that saved output is honest as long as you say it is
> recorded output and not a live call.

### Block 5 — Customer Confidence and the account trend · 2:00

**Live-LLM mode only.** In offline mode, skip to block 6 and say why — an audience that hears
"this needs a real model and I ran it offline to keep the demo deterministic" loses nothing; an
audience that watches you click a card that does not exist loses the rest of the demo.

Stay signed in as Camila — she is root and sees all three regardless of the policy, so this block
does not depend on Rafael existing. Open the three `Central Log Transportes` meetings in date
order. They are one account across three conversations: a discovery, a hard negotiation where a
competitor undercuts on price, and the closing.

For each one, show the Customer Confidence card: the score, the band, the quoted buying signals
and the quoted objections. Every signal carries the sentence it came from — the model is not
asked to be believed, it is asked to cite.

Then the point of the whole block: **the trend is computed by the backend, not by the model.**
`CustomerConfidenceService.computeTrend` compares the new score with the previous assessment for
the same account and applies a ±5 dead band. The model's own guess at a trend is discarded. Say
why that matters: a number a model can produce differently on two runs is not something to base an
account review on.

What NORA deliberately does **not** do here: there is no aggregated account health score and no
band-change alert. Those were closed as scope by ADR 0038, not postponed quietly. Per-meeting
confidence with a server-computed trend is the whole feature.

> **Plan B** — if the three meetings did not chain into one account, the cause is almost always
> that the model wrote the customer's name differently in two of them, and the get-or-create by
> lowercase name produced two accounts. The seed prints the account name it found for each
> meeting, so you will know before the demo rather than during it. In that case show a single
> meeting's card and describe the trend rule instead of showing it.

### Block 6 — Close · 1:00

One screen, back on the dashboard. Three sentences:

- What was shown: a transcript in, structured commercial output out, scoped by a real
  authorization model, with the customer's own context in the loop.
- What it is built on: a monorepo with a Spring backend in DDD layers, a Python NLP worker, a
  Next.js product, a Windows capture client, and a documented architectural decision for every
  choice that was hard.
- Where the boundaries are: say one thing NORA does not do. Choosing something real —
  no aggregated account health, no SSO, transcription still mid-migration — reads as confidence,
  and the audience will find the boundary anyway.

## Optional blocks

### Block 7 — Flows · 3:00

Open `/flows` in the Enterprise workspace. The seed leaves one flow there —
`meeting.risk_detected` wired to an e-mail — so the canvas is not empty. Open it and show the
trigger catalogue: three triggers, all three dispatched by the same analysis round —
`meeting.analysis_completed`, `action_item.created`, `meeting.risk_detected`.

Say the honest part: `schedule.cron` appears nowhere, because nothing dispatches it, and the API
refuses to save a flow that uses it in an `ACTIVE` state. A flow that could never run should not be
storable as if it could.

Use the flow's **test run** rather than a real trigger: a real trigger means uploading a meeting
and waiting for a whole analysis to complete on stage. Know what the button does before you press
it — the side panel says so in the UI: it executes the flow for real against your last analysed
meeting, side effects included. The seeded flow sends to an address on the seed's own domain,
which defaults to an RFC 2606 reserved name and therefore cannot reach a person. If you point it
somewhere else before the demo, that e-mail is really sent.

> **Plan B** — if the test run fails, show `/flows/{id}` execution history instead. A failed
> action fails its own run and is logged without failing the analysis that triggered it, which is
> itself worth pointing at.

### Block 8 — Chat over the workspace · 2:30

**Needs an LLM key in `apps/web`, and — for citations — an embedding credential too.**

In the Enterprise workspace, open `/chat` and ask something that spans meetings: what the
objections against the competitor were across the account, or what is still open with
`Central Log Transportes`. Show that the answer cites the meetings it used.

> **Plan B** — a 503 here means no key resolved for the configured provider. There is no stub;
> do not try to fix it live. Skip the block. If the answer arrives without citations, the
> embedding index is empty and the chat fell back to recent meetings — say so rather than letting
> the audience assume retrieval happened.

### Block 9 — Operator console and cost telemetry · 2:30

The operator console is a separate app on port 3002 and is **not** started by `make dev`.

It is fail-closed: with no `CF_ACCESS_*` configured, every page answers 403 naming the two missing
variables. For a local demo, start it with `NORA_ADMIN_USE_MOCKS=true make admin-dev` and **say
that the data on screen is mock data**. Presenting the mock as production telemetry is the exact
failure the fail-closed default was introduced to prevent.

Show the model catalogue and the AI cost telemetry, and explain the control plane: which model
serves which service is runtime configuration, not a redeploy.

## What this demo does not include, and why

- **Live desktop capture and transcription.** The migration of ADR 0039 has landed (contract in
  ADR 0045), so the reason for keeping this off the script has changed and is now smaller: the
  190 MB first-run download and the 4-core/8 GB floor are gone, and the client streams to the
  provider on a credential the backend mints. What is left is that it is Windows-only, has never
  been validated in a real Windows/Teams environment (backlog US09), and needs a working network
  plus a configured provider credential on the API — three ways for a live demo to fail on
  somebody else's laptop.
  If the desktop does appear: **describe it accurately.** The audio goes from the machine straight
  to the transcription provider and does not pass through NORA's infrastructure, which is also why
  the cost figures in the operator console are estimates. Do not say the audio stays on the device
  — that was the previous design (ADR 0035) and it is no longer true.
- **Audio file upload (US08).** Not built. Text transcripts only.
- **SSO.** Closed as scope by ADR 0038, not pending.
- **OAuth integrations authenticating live.** They need real provider credentials and a callback
  that resolves. Show the integrations page if you want, but do not start an OAuth dance on stage.

## History

| Date | Change |
|---|---|
| 2026-08-17 | Created. First demo script in the repository; resolves the 8-10 vs 15-20 minute disagreement by declaring the length here and once. Pairs with `scripts/seed-demo.sh` and the Meridian tenant in `data/synthetic/` |
