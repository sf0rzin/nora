# 0041 — NORA as an MCP server (the inbound path)

- Status: accepted
- Date: 2026-08-16
- Related: ADR 0031 (OAuth integrations — the **outbound** path, which is what actually shipped),
  ADR 0002 (tenant filter) and ADR 0007 (AWS-style IAM) — the two this decision must not perforate,
  ADR 0028 (RLS scope), ADR 0020 (token rotation precedent), ADR 0038 (the realignment that opens
  this and closes US47), ADR 0014 (the deferral this reverses for US27)

## Context

MCP has been in the product's definition since version 0.2 of the vision, dated 2026-05-01
(`docs/product/vision.md:216`). It is not a footnote there. It is in the one-line definition of what
NORA *is* (`:17`, `:19`), in the surfaces line ("Web SaaS · Desktop · API / MCPs", `:50`), in the
boundaries table (`:97`, `:112`, `:113`, `:117`), and it has a section of its own — §7, "Why MCPs
change the game" (`:148-166`) — with a diagram of NORA reaching Calendar, Linear/Jira, GitHub and
Salesforce.

Line 166 of that same file admits the truth:

> In the current MVP no MCP server is implemented. MCPs remain a roadmap concept (post-MVP
> commercial).

The backlog carries four stories for it — US27 (Claude MCP), US28 (Google Calendar MCP), US29 (task
manager MCPs), US47 (MCP project state) — all marked **W**, all **MISSING**, all deferred as a block
by ADR 0014. Fifteen months of vision documents and not one line of MCP code has ever existed in
this repository.

**What did get built is the opposite direction.** ADR 0031's OAuth integrations are NORA writing
*into* Gmail, Calendar, Slack, GitHub, Notion, Todoist, Linear, Microsoft, Telegram and Trello,
through `IntegrationsController.java`, `infrastructure/integration/` and migrations V024–V026,
driven by the Flows engine of ADR 0030. Read literally, the diagram in vision §7 — NORA's arrows
pointing out at other tools — is a picture of ADR 0031 with the wrong protocol name written on the
arrows.

That is the distinction this ADR turns on:

- **Outbound** — NORA acts on other systems. Built. It is OAuth, not MCP, and it works.
- **Inbound** — an external client asks NORA questions. Not built. Nothing has ever existed.

MCP is the inbound one. The promise that has been on the box since 0.2 is the one nobody has
started.

## Decision

**NORA exposes an MCP server. External MCP clients — Claude Desktop, IDEs, coding and research
agents — read meetings, tasks, semantic search and Customer Confidence from it.**

Four things are fixed here; the rest belongs to the implementation.

### 1. It lives inside `services/api`, as a new inbound adapter

Not a separate service, not a sidecar process.

The reason is authorization. The tenant filter (ADR 0002), `PolicyEvaluator`
(`services/api/src/main/java/br/com/nora/api/domain/iam/PolicyEvaluator.java`, ADR 0007) and the RLS
context propagation (`TenantRlsAspect`, ADR 0028) all live on that path already. A separate process
has exactly two options and both are worse: reimplement authorization, which duplicates the one
thing in this system that must never diverge between two copies; or call NORA's own REST API as a
client, which is a proxy with an extra hop, an extra credential and an extra place for the tenant
scope to be dropped.

The MCP server is an inbound adapter in the sense the DDD layering already uses — thin, in `api`,
translating a protocol into the same application services the controllers call.

### 2. Every tool call resolves a real tenant and a real IAM principal, and goes through `PolicyEvaluator`

No MCP-specific permission vocabulary. A tool that reads meetings evaluates `meeting:read`; a tool
that reads tasks evaluates `task:read` — the same actions, against the same policies, as the web
surface.

The invariant, stated so it can be tested: **an MCP client can never see more than the user it acts
for can see in the web application.** This is what makes the decision compatible with ADR 0002 and
ADR 0007 rather than a hole beside them, and it is the acceptance criterion the implementation
inherits.

### 3. Authentication: a tenant-scoped MCP token, hashed at rest

A user authenticated in the web application mints a token for an MCP client. It is stored **only as
a SHA-256 hash** — the pattern every other one-time or long-lived credential in this system already
follows (`V018__hash_invitation_token.sql` aligned invitations with `email_verification_tokens`,
`password_reset_tokens` and `refresh_tokens` from V003/V011). It is presented as an
`Authorization: Bearer` header, it is revocable, and at the edge it is exchanged for the same
authenticated principal the JWT filter produces — which is what makes §2 free rather than a second
implementation.

**This is deliberately not a full OAuth 2.1 authorization server**, which is what the MCP
specification's authorization section asks of a remote server. The reason is written out rather than
glossed: NORA is an OAuth *client* today (ADR 0031), not an authorization server. Becoming one —
authorization endpoint, token endpoint, PKCE, dynamic client registration, consent UI, metadata
discovery — is a substantial build, and ADR 0038 has just declared a destination that does not
justify it.

The cost of the deviation, named so nobody is surprised by it: **MCP clients that only speak the
spec's OAuth flow will not connect without a manually pasted token.** Reopen trigger: a client that
matters cannot connect, or ADR 0038's single reactivation trigger fires (NORA acquires a user who is
not the maintainer).

### 4. The first cut is read-only

Tools expose queries, not mutations. Three reasons:

- Writes already have a path. Flows (ADR 0030) plus OAuth integrations (ADR 0031) are how NORA acts
  on the world, and they were built for exactly that.
- A leaked read token cannot damage a tenant. It exposes what that user could already read, which
  bounds the blast radius of the simplification in §3.
- The read surface is where the vision's promise actually lives. "What did we decide about the
  Contoso renewal?" asked from inside an IDE is the demonstration; creating a task from an agent is
  not.

Write tools are a second cut, and they need a decision nobody has made yet: which IAM actions an
agent may exercise unattended, and on whose authority. That decision is not made here.

## Effect on the backlog

This section is binding on `docs/product/backlog.md`. The ADR decides; the backlog records.

| Story | What happens |
|---|---|
| **US27 — "Claude MCP"** | **Reframed and reactivated.** The title becomes *NORA as an MCP server*, because "Claude MCP" describes a client, not the thing being built. It was **W** / MISSING / deferred by ADR 0014; ADR 0038 does not defer it and this ADR opens it. Status stays MISSING — nothing is built yet — and the priority moves off **W**, since W means "not in v1" and this is now declared scope |
| **US28 — "Google Calendar MCP"** | The underlying promise — meeting output reaching the user's calendar — was met **by a different mechanism**: OAuth integrations (ADR 0031, migrations V024–V026), not MCP. The story must be renamed to name the real mechanism and must **not** be counted as MCP scope. This ADR deliberately assigns it **no status**: the honest status depends on which write actions actually exist in `infrastructure/integration/actions/` and `application/workflow/actions/`, and that is a question for the code, not for this ADR |
| **US29 — "Task manager MCPs (Linear/Jira/Notion)"** | Same treatment as US28, same reason, same refusal to assign a status here |
| **US47 — "MCP project state (pull Jira/Linear/Azure DevOps)"** | **Dies — WONT.** It is inbound in name only. What it actually asks for is NORA *pulling* state out of three external trackers, which is ADR 0031's outbound lane, tripled, with three more OAuth apps to register and three more schemas to normalise. It is precisely the shape of scope this realignment cuts, and the MCP server decided here needs none of it |

## Why this is the only decision in the realignment that adds scope

Every other decision of the 2026-08 realignment subtracts: platforms, a tier's promises, a local STT
engine, a desktop UI, an operations block. This one adds an L-sized build to a solo maintainer's
plate. That asymmetry was raised explicitly and accepted, for two reasons.

**It is the oldest unkept promise in the product, and the most visible one in a portfolio.** MCP has
been on the box since 2026-05-01. A NORA that a technical reviewer can attach to their own Claude
Desktop and query demonstrates the entire product in a single move — which is exactly what ADR 0038
§1 says the project is for.

**The honest alternative costs more than building it.** MCP is not decoration in the vision; it is
in the one-line definition of what NORA is (`vision.md:17`). Removing it there does not tidy a
document, it changes what the product claims to be — and it would be the second time in this
realignment that a promise is deleted rather than kept. There is a limit to how much of a product's
identity can be cut before what remains stops being the thing that was pitched.

## Consequences

**Positive**

- The vision's oldest promise stops being a paragraph that contradicts itself two lines later.
- The inbound/outbound distinction gets written down. Without it, US27–US29 and US47 look like four
  attempts at the same feature, and E6 ("MCP Integrations") looks unstarted when most of what it
  described has in fact shipped under another protocol.
- Authorization gains a second consumer, which is the cheapest way to find out whether
  `PolicyEvaluator` is genuinely the single gate or merely the gate the controllers happen to use.

**Negative / debts**

- It is the one net addition in a realignment built on subtraction, and it competes for the same
  single pair of hands as the FIAP artefacts.
- §3 knowingly deviates from the MCP specification's authorization model. That is a documented
  interoperability limit, not a bug to be reported later.
- A read-only server will invite the immediate question "why can't it create the task?" — §4 is the
  answer, and it will need repeating.
- `docs/product/vision.md` §7 and its diagram describe MCP doing what ADR 0031 actually does. That
  section needs rewriting around the two directions, and until it is, it misattributes shipped work
  to an unbuilt protocol.

## Alternatives Considered

1. **Delete the MCP promise from the vision instead of building it.** The move consistent with the
   rest of the realignment, and by far the cheaper one: an edit to a handful of lines versus an
   L-sized build. Rejected for the reasons in §Why this is the only decision that adds scope — the
   promise is load-bearing in the product's definition, and it is the item where deletion costs more
   than delivery.
2. **A separate MCP process that calls NORA's REST API.** The conventional shape, and it would keep
   the Java service untouched. Rejected: an extra network hop, an extra credential to hold, and a
   second place where the tenant scope could be dropped — for no gain that §1's in-process adapter
   does not already provide.
3. **Build a full OAuth 2.1 authorization server first, per the MCP specification.** The
   interoperable answer, and the correct one for a product with users. Rejected as scope, with the
   deviation and its cost recorded in §3 and a written reopen trigger. Deferring it silently would
   have been the failure mode; deferring it in writing is the decision.
4. **Ship write tools in the first cut.** More impressive in a demonstration: an agent that files
   the action item it just read. Rejected: unattended writes require deciding which IAM actions an
   agent may exercise on a user's behalf, and that decision has not been made. A read-only server is
   useful on day one and does not foreclose it.
5. **Reuse the existing session JWT as the MCP credential.** Zero new persistence and zero new code
   at the edge. Rejected: the access token is short-lived, cookie-shaped, tied to a browser session
   and refreshed by the rotation family of ADR 0020. An MCP client sits in a configuration file for
   weeks. Forcing the JWT into that role means either lengthening its lifetime — which weakens it
   everywhere else — or handing a desktop client a refresh-token family it has no business holding.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-16 | sys0xFF | Created and accepted as a design decision; construction is separate work. Records that MCP has been promised since vision 0.2 (2026-05-01) with no code ever written, and that ADR 0031's OAuth integrations are the outbound path while MCP is the inbound one. Fixes the in-process adapter, `PolicyEvaluator` on every tool call, a hashed tenant-scoped bearer token in place of a full OAuth 2.1 authorization server, and a read-only first cut. Reactivates and reframes US27, removes US28/US29 from MCP scope, and kills US47 |
