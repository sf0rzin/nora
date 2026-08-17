# Product Vision — NORA

> Product reference document. Source of truth for **What** NORA is, for **whom**, and **why**.
>
> For the **Agile Methodology with Squad Framework** course (Sprint 1+2, FIAP Challenge 2026 × TOTVS) this doc serves as the main Vision Statement.

## 1. Vision Statement

> *Geoffrey Moore format — the market standard for product vision in Agile*

---

**FOR** professionals and teams who take part in work meetings and need to turn conversations into concrete actions,

**WHO** lose critical intelligence generated in those meetings because the knowledge stays trapped in individual memory, in disconnected notes or in under-filled CRMs — resulting in lost opportunities, misaligned projects and undetected churn,

**NORA** is a conversational intelligence SaaS platform with two complementary plans — **Core** and **Enterprise** — and three integrated surfaces: web, desktop and API/MCP,

**THAT** automatically processes meeting transcripts and delivers, in seconds, structured summaries, action items and, for commercial teams, advanced signals of opportunity, risk and customer confidence per conversation — with context calibrated to the customer's own business, native integration via the **Model Context Protocol (MCP)**, an IAM model with users, groups and policies, and LGPD-by-design handling of personal data,

**UNLIKE** generic transcription tools (Otter.ai, Fireflies) or international Sales Intelligence platforms (Gong, Clari) that use generic knowledge, do not speak Portuguese natively and do not respect Brazilian data protection legislation,

**NORA** is the only conversational intelligence platform that learns each company's vocabulary, products and context — making any organisation capable of extracting real intelligence from its own meetings, regardless of sector or ecosystem. It starts as a personal copilot (Core, freemium) and evolves into a complete revenue engine for teams (Enterprise), with natural bottom-up adoption *(Product-Led Growth)*.

---

**How to read the statement above.** It is positioning, kept in the Geoffrey Moore form the course
deliverable asks for. It is not the inventory of what is built. Two of its clauses need naming here
so the rest of this document is not read as their proof:

- **MCP** is *declared scope, not a shipped surface.* NORA is decided to become an MCP **server**
  ([ADR 0041](../adr/0041-nora-as-mcp-server.md)) and no MCP code has ever existed in this
  repository. What ships today for reaching the tools a user already has is OAuth integrations
  ([ADR 0031](../adr/0031-oauth-integrations-token-storage.md)) — a different protocol in the
  opposite direction. §7 draws that distinction in full.
- **LGPD by design** describes how the system was built, not a certification and not a signed
  contract. Its exact reach — what is redacted, where, and what is deliberately not — is stated in
  §4 and §5 and in [ADR 0040](../adr/0040-pii-scope-analysis-transcription-subprocessor.md). Read
  those, not this line.

The **Is / Is Not** and **Does / Does Not** tables (§4 and §5) are the commitments. This section is
the pitch.

## 2. The Platform: Core and Enterprise

NORA is a platform with two plans that share the same AI engine and infrastructure, but serve distinct profiles and needs.

The box below lists **what each plan has**. It used to list what each plan was meant to have, which
is a different document.

```
┌─────────────────────────────────────────────────────────────────────┐
│                        NORA  PLATFORM                               │
├──────────────────────────────────┬──────────────────────────────────┤
│          NORA  CORE              │        NORA  ENTERPRISE          │
│      Individual professional     │         Teams and companies      │
├──────────────────────────────────┼──────────────────────────────────┤
│ • Automatic meeting summaries    │ • Everything in Core, plus       │
│ • Action item detection          │ • Company Context (products,     │
│ • Productivity Score, opt-in     │   ICP, competitors, glossary)    │
│ • Chat over your own meetings,   │   injected into every analysis   │
│   grounded by semantic search    │ • Customer Confidence per call:  │
│ • Project tracking (by tag)      │   score, band, buying signals,   │
│ • PII Shield over text (LGPD)    │   objections, per-account trend  │
│ • NORA Flows: trigger →          │ • AWS-style IAM: Root + Users +  │
│   condition → action, on a       │   Groups + Policies, with        │
│   canvas                         │   immutable policy versioning    │
│ • OAuth integrations with nine   │ • Isolated multi-tenancy:        │
│   external tools (outbound)      │   tenant filter + Postgres RLS   │
│ • Freemium / individual plan     │                                  │
├──────────────────────────────────┴──────────────────────────────────┤
│  SURFACES: Web SaaS · Desktop (Windows) · REST API                  │
│            MCP server: declared scope, not built (ADR 0041)         │
├─────────────────────────────────────────────────────────────────────┤
│  GO-TO-MARKET: Product-Led Growth                                   │
│  An individual adopts Core (freemium) → shows it to the company →   │
│  the company buys Enterprise                                        │
└─────────────────────────────────────────────────────────────────────┘
```

**The split is commercial framing, and the code does not enforce it.** `tenants.plan` exists
(`V001__create_tenants.sql:12`, values `FREE` / `PRO` / `ENTERPRISE`) and is read back by
`GET /tenant`, but no endpoint, guard or component gates a feature on it — the web shell renders a
hardcoded `Core` badge (`apps/web/src/components/core/app-shell.tsx:347`). Every tenant can reach
everything the tenant's IAM policies allow. Entitlement enforcement would be new work, and
[ADR 0038](../adr/0038-post-pitch-scope-realignment.md) §1 declares a destination with no paying
tenant to enforce it against. The columns above describe **who each capability is for**, not a
licence check.

### What this box used to promise, and what closed it

Kept rather than deleted, because the traceability of a withdrawn promise is worth more than a
tidy box — the same argument [ADR 0014](../adr/0014-defer-post-mvp-commercial-gate.md) made and
[ADR 0038](../adr/0038-post-pitch-scope-realignment.md) §Alternatives 2 upheld.

| Removed from the box | Status | Closed by |
|---|---|---|
| **Account Health Score over time** (US50) and its band alert (US51) | **WONT** — closed scope, not deferred. It aggregates across accounts over time and there is no history to aggregate; `account_health_snapshots` was foreseen in the data model and never migrated | ADR 0038 §4 |
| **SSO (Entra ID / SAML 2.0)** (US05) | **WONT.** Its reactivation criterion was "the first paying Enterprise tenant requires it"; ADR 0038 §1 says there will be none. It is also the largest build on the old deferred list, buying a second login path for an IAM that has one | ADR 0038 §4 |
| **Enterprise SLA + BR support**, and the **Enterprise DPA** that used to be counted with it | **WONT.** All three are contractual instruments between a supplier and a customer. There is no customer, so signing or publishing any of them would be theatre — and their absence therefore stops being a gap. The DPA's death is also why ADR 0040 §5 can state plainly that no data processing agreement governs the transcription provider | ADR 0038 §4 |
| **Commercial Next Best Action** | **Never built, and never tracked.** No code in the web app, the API or the worker ever produced it, and no user story covers it. It was a box item with nothing behind it — see §5, where it now sits in the *Does Not* column | — (removed here) |
| **Configurable Competitive Radar** | **Not a feature.** What exists is real but smaller: the tenant lists its competitors in the Company Context, and the analysis marks an objection with the competitor named in it (`competitor` on each objection). There is no radar, no aggregation and no alerting | — (restated at true size) |
| **Team analytics & dashboards** | **Not built as described.** `/dashboard` is a chronological, filterable meeting list (US16), not team analytics. The trends panel is US21 — reactivated by ADR 0038 §5 as open scope, still MISSING | ADR 0038 §5 |
| **Integration over MCP** (Calendar/Outlook, Linear/Jira, GitHub) | **Delivered by a different protocol.** Nine OAuth integrations ship today (ADR 0031); MCP is the inbound path and is unbuilt (ADR 0041). See §7 | ADR 0031 · ADR 0041 |

## 3. Where the current state is written down

This section used to be a dated snapshot of the deployment, and it is not one any more. The reason
is worth stating, because deleting a section from a product document is the kind of edit a reader
should be able to audit.

The snapshot was headed `2026-06-06`, carried coverage numbers measured on `2026-05-13`, and had
acquired a disclaimer on top explaining that its infrastructure half had been false since
`2026-08-10`. That is three dates and one apology inside a section whose whole purpose was to say
"this is true now". Every durable fact in it is maintained somewhere else, by someone who has a
reason to keep it correct:

| To know | Read | Why there |
|---|---|---|
| The stack, verified against the manifests | [`AGENTS.md`](../../AGENTS.md) §Stack and §Current scope | Each row names the file it was read out of, so it fails loudly when a manifest moves |
| What is deployed, and on what | [`README.md`](../../README.md) §Current state, [ADR 0036](../adr/0036-substrate-is-a-single-bare-metal-host.md) | The substrate is an architectural decision, and decisions belong in ADRs |
| Per-story delivery status | [`docs/product/backlog.md`](backlog.md) | It is the artefact that carries DONE / PARTIAL / MISSING per user story. **Caveat, true on 2026-08-17:** it has not yet been reconciled against ADR 0038, so it still shows US05, US50 and US51 as "deferred as a block via ADR 0014". This document follows the accepted ADR; where the two disagree, the ADR is the authority and the backlog is the stale one |
| The schema, and how far the migrations go | [`docs/engineering/data-model.md`](../engineering/data-model.md) | Canonical, and mirrored for the Oracle deliverable |
| What was decided and when | [`docs/adr/README.md`](../adr/README.md) | The canonical ADR index |
| What happened, in order | [`docs/product/roadmap.md`](roadmap.md) §1 History | History belongs in the history section, and stays there |

**The Azure record is not lost by this deletion.** The Azure deployment was real, and it is recorded
where a reader looks for it: [ADR 0034](../adr/0034-azure-to-proxmox-migration.md) (the exit),
[ADR 0036](../adr/0036-substrate-is-a-single-bare-metal-host.md) (the correction to the substrate)
and the roadmap's history section. What is gone from here is a copy of it that had already drifted
twice.

**On test coverage, since the old section quoted numbers.** The old snapshot's figures — worker 87%,
backend 67%, web 0% — were measured on `2026-05-13` and went three months unrepeated. CI now
measures them on every run: **worker 92.4% statement over 863 tests; backend 77.1-77.3% instruction
and 61.5-61.6% branch over 578 tests** (2026-08-17). The backend is quoted as a range because the
figure jitters between runs of the same commit, which is worth knowing before anyone treats a
one-decimal move as a regression. `apps/web` is still unmeasured — Playwright e2e only, no coverage
instrumentation — so its old "0%" was never a measurement either. Current numbers come from
`scripts/report-coverage.sh`; read the last CI run rather than this paragraph. What is actually enforced in CI is
narrower and is verifiable: a JaCoCo rule over the single class `PolicyEvaluator` at instruction
>= 90% / branch >= 75% (`services/api/pom.xml`), and `pytest --cov=nora_nlp.services.pii_shield
--cov-fail-under=90` over that one module (`.github/workflows/ci.yml:152-154`). `apps/web` has no
test runner, so nothing about it is measured at all. ADR 0018's ">85% sustained" is an aspiration,
not a gate.

What §4 and §5 below describe is the product. They are the part of this document that states
commitments, and they are the part to keep true.

## 4. Is — Is Not table

> *Defines what the product IS and what it must NEVER be confused with.*

|  | **Is** | **Is Not** |
|---|---|---|
| **Nature** | A **conversational intelligence platform** with two plans (Core and Enterprise) and three surfaces: **Web**, **Desktop (Windows)** and a **REST API**. A fourth — an MCP server — is declared scope and unbuilt (ADR 0041) | A CRM, an ERP or a replacement for any management system. Not, today, an MCP server: nothing external can query NORA over MCP |
| **Core** | A **personal meeting copilot** for the individual professional — organises projects, creates tasks, records decisions | A notes app, a generic recorder or a replacement for Notion/Linear |
| **Enterprise** | A **commercial intelligence engine** configured with the company's own context and products — for any sector | A tool exclusive to the TOTVS ecosystem or to any other specific vendor |
| **Context** | A platform that **learns the customer's vocabulary**: each company configures its products, competitors and terms | A generic AI that uses hardcoded knowledge of a single market |
| **Desktop** | A **real-time app** (Tauri 2 / Rust) that captures system audio and analyses the meeting while it happens, on **Windows** | A videoconference plugin or a browser extension. Not cross-platform: macOS and Linux left the supported scope (ADR 0038 §2) |
| **IAM** | A **granular access control** system in the style of **AWS IAM** (Root + Users + Groups + Policies created by the tenant itself) | A system where everyone in the company sees every transcript from every department |
| **AI** | An **analysis, structuring and recommendation** engine that amplifies the human. Output via strict JSON Schema | An AI that makes commercial decisions autonomously without human review |
| **Integration** | An **open** platform in two directions, with only one of them built. **Outbound (built):** data *leaves* NORA for the tools the user already uses, over OAuth integrations driven by Flows (ADR 0030/0031). **Inbound (decided, unbuilt):** an external client asks NORA questions over MCP (ADR 0041) | A closed system that requires replacing the existing tools. Not an MCP integration, in either direction, until ADR 0041 is implemented — the protocol on the shipped path is OAuth |
| **Data** | An **LGPD-by-design** system: personal data in **text** is detected and redacted before the analysis LLM (ADR 0012), and structured PII is redacted in the BFF on the chat and search paths (ADR 0033) | A platform that stores or shares third-party conversation data. **Not a guarantee that covers audio**: once the cloud transcription of ADR 0039 lands, raw audio reaches a declared external subprocessor before any redaction exists (ADR 0040). The scope of the promise is text, and it is stated in §5 |
| **Model** | A SaaS with an **individual freemium** (Core) evolving into **paid enterprise** via PLG | A product that requires a corporate purchase as its entry point |

## 5. Does — Does Not table

> *Defines the product's behaviour, features and limits. Reconciled 2026-08-17 against the code and
> against ADR 0038 through ADR 0041. A row belongs in **Does** only if there is code behind it; a
> decided-but-unbuilt capability belongs in **Does Not**, naming the ADR that decided it.*

|  | **Does** | **Does Not** |
|---|---|---|
| **Input** | Accepts transcripts in **text** (`.txt`, `.vtt`, `.srt`). Desktop captures system audio in **real time** (Windows via WASAPI loopback) | Does not accept upload of archived audio or video (`.mp3`, `.mp4`) — US08, Won't Have v1, and it stays unbuilt (ADR 0040 §6). There is **no batch transcription roadmap item**: the Azure Speech batch path this row used to promise died with the Azure subscription (ADR 0034/0036) |
| **PII Shield** | **Automatically detects and redacts, in text**, CPF, CNPJ, confidential monetary values, email, phone, credit card and **Brazilian personal names (PERSON_NAME)** with `[[TIPO_N]]` placeholders, before submission to the analysis LLM (ADR 0012). On the chat and semantic-search paths, structured PII is redacted in the BFF before the chat and embedding providers (ADR 0033) | **The promise is scoped to text and analysis** (ADR 0040). Does not cover address PII (ADDRESS) — declared, still-owed debt. **PERSON_NAME on the chat path is accepted residue** until worker routing lands (ADR 0033). **Audio is not redacted and cannot be**: redaction needs text, and text is what the transcriber produces — see the **Transcription** row |
| **Transcription** | Today the Desktop transcribes **on the user's own machine** (Whisper via `whisper-rs`, ADR 0035): no audio leaves the device, and only text is uploaded | **This is changing, and the change is decided.** ADR 0039 moves transcription to OpenAI's streaming API, reached with an ephemeral session credential minted by the backend; the audio then goes **desktop → provider directly**, never through NORA's infrastructure. It is **not built yet**. Once it is, the transcription provider is a **declared external subprocessor** receiving raw audio before any redaction exists (ADR 0040 §3), there is **no data processing agreement** with it (ADR 0040 §5), and the honest name for the resulting position is a **demonstration posture, not a compliance posture** |
| **Core — Summary** | **Generates a structured summary**: context, decisions taken, next steps | Does not rewrite or creatively edit the content — it always preserves the original intent. The "within 30 seconds" this row used to state is the acceptance criterion of the user story (`backlog.md`), not a measured latency: nothing in the repository records an observed p95 for analysis |
| **Core — Action Items** | **Detects and categorises** explicit and implicit **tasks** with the confidence level displayed, priority `LOW`/`MEDIUM`/`HIGH` and a textual quote of the source (`sourceQuote`) | Does not guarantee capture of 100% of the items — it is input for human review. Does not assign automatically without an explicit name |
| **Core — Productivity Score** | **Opt-in per meeting**: the user declares an objective + expected outcomes; NORA measures coverage (`ADDRESSED`/`PARTIAL`/`MISSED`) and assigns a score of 0–100 with a `LOW`/`MEDIUM`/`HIGH` band (ADR 0005) | Does not calculate without opt-in — privacy by design. Does not use external benchmarks — only the answer key declared by the user themselves |
| **Core — Projects** | **Groups meetings by tag** into projects, client-side, with no manual filling and no separate backend | It is not a project manager. It sends nothing to Jira or Linear by itself — that is the **Integrations** row, and it is Flows that triggers it |
| **Core — Flows** | **Automations on a canvas** (ADR 0030/0032): a trigger, optional conditions, and actions on connected tools. All three real triggers are offered — `meeting.analysis_completed`, `action_item.created`, `meeting.risk_detected` | Does not schedule. `schedule.cron` exists in the backend enum and is **rejected** at save time rather than accepted and silently never fired |
| **Core — Integrations** | **Connects nine external tools over OAuth and equivalents** — Google, Microsoft, Slack, GitHub, Notion, Todoist, Linear, Telegram, Trello — with tokens encrypted at rest (AES-GCM, ADR 0031). This is the **outbound** path: data leaves NORA for the tools the user already has | **This is not MCP**, despite fifteen months of this document calling it that. Each integration is optional and independent. The inbound path — an external client querying NORA — is the MCP server of ADR 0041, and it is unbuilt. See §7 |
| **Enterprise — Company Context** | **Learns the customer's business**: the admin configures products, ICP, competitors, glossary and objection handling; the context is injected into every analysis and into the chat's answers | Does not use generic or hardcoded knowledge from any vendor — the context is always the tenant's |
| **Enterprise — Semantic search / RAG** | **Delivered** (US15, PR #206): one embedding per meeting (migration `V021`), `EmbeddingService` + `HttpEmbeddingClient` with provider-agnostic embeddings (Gemini/OpenAI), exposed as `GET /meetings/search` and consumed by the chat as RAG context | **Does not use `pgvector`, despite the container image being `pgvector/pgvector:pg16`.** The extension is never created; the vector is a JSON array in a `TEXT` column and cosine similarity is computed in Java. Adequate for tens or hundreds of meetings per tenant, and it is a scale ceiling, not a feature |
| **Enterprise — Customer Confidence** | **Delivered full-stack** (ADR 0015, PR #148): the worker emits a 0–100 score + band + buying signals + objections + `accountName`; the backend recomputes the trend per account authoritatively and persists it; `GET /meetings/{id}` returns the block; `CustomerConfidenceCard` UI in the meeting detail. Objections carry the `competitor` named in them, when one is named | Does not score internal meetings — the block is emitted only for conversations with a customer, lead or prospect, and is `null` otherwise. Does not invent signals: every buying signal and objection must carry a literal `quote` from the transcript |
| **Enterprise — Account Health** | — | **Closed scope, not deferred** (ADR 0038 §4). The aggregated score per account over time (US50) and the band-change alert (US51) are **WONT**: they aggregate over history that does not exist. `account_health_snapshots` was foreseen in ADR 0006 and never migrated; it stops being debt. The per-meeting signal that *is* built is the Customer Confidence row above |
| **Enterprise — Next Action** | — | **Never built, and never tracked.** "Recommends the Next Best Action in the next 48–72h" sat in this column as delivered behaviour with no implementation in the web app, the API or the worker, and no user story anywhere in the backlog. It is stated as absent rather than quietly deleted, because it was read as a commitment for as long as it stood here |
| **IAM — Model** | **Granular AWS-style IAM**: Root + Users + Groups + Policies (Effect/Action/Resource/Condition) created by the tenant itself. Immutable policy versioning + audit log. (ADR 0007) | Does not impose a fixed role hierarchy (no predefined Manager/Analyst/Viewer) |
| **IAM — Conditions** | **AWS-style Conditions** on attributes defined by the tenant: `Department`, `Project`, `Account` etc. `PolicyEvaluator` supports `StringEquals`, `StringIn`, `StringLike`, `DateGreaterThan`, `DateLessThan`, and the listing endpoints evaluate them per item against the meeting's `attributes` (`V007`/`V008`) | Operators outside that list, and attributes missing from the context, result in `Deny` (fail-closed). **No shipped UI writes those attributes**: neither the web upload nor the desktop sends an `attributes` map, so attribute-based conditions today require calling `POST /meetings` directly. The evaluator is real; the way to populate its input is not in the product yet — see §6 |
| **Desktop** | **Tauri 2 app** (Rust) with system audio capture, **Windows only, via WASAPI loopback**, plus a live overlay and a dock | It is not a videoconference plugin. It does not run on macOS, on Linux or on mobile (ADR 0038 §2): the macOS (BlackHole) and Linux (PulseAudio) capture paths existed in the code but had never been exercised, and were removed rather than kept as a promise. It no longer ships a local UI of its own — the app's screens are the web surface |
| **Multi-tenancy** | **Isolation per organisation** via `tenant_id` in every tenant-owned table + application filter (ADR 0002), a **composite isolation FK** (V015, extended to IAM attachments in V027) as defence in depth, and **Postgres RLS** (V016, completed by V019/V020), **enforced on the deployed stack since 2026-08-10** — the API connects as `nora_app` (NOBYPASSRLS) and `RlsEnforceTelemetryGuard` refuses to boot on a half-applied cutover (ADR 0028) | RLS is **off by default in the repository**, where the application filter is the only control; flipping that default is a declared deferral (ADR 0038 §6g), because it would make every local checkout provision three database roles before the app boots. Identity and IAM tables are exempt by design (ADR 0028) — login resolves a user by global e-mail before any tenant exists. Does not offer on-premises installation |
| **Compliance** | **Operational LGPD** (ADR 0029): **right to be forgotten delivered** — `DELETE /privacy/meetings/{id}`, a physical hard delete that cascades to the raw transcript — plus an **opt-in** `RetentionSweeper` | **Retention is off unless switched on**: `NORA_PRIVACY_RETENTION_DAYS=0` means disabled, not "delete immediately", and it is the shipped default. The window is **global**, not per tenant. Erasure is **per meeting, not per data subject**; there is no tenant-wide deletion and no portability export (ADR 0038 §6h). Does not capture a consent record — the only opt-in the product implements is per-meeting, for the Productivity Score. Does not carry out DPIAs |

## 6. IAM — Enterprise Access Control

NORA Enterprise implements an **AWS IAM-style** model: the tenant creates its own **groups** and its own **policies**. No hierarchical role is imposed by the product. See ADR 0007.

```
Company (Tenant)
├── Root user           — tenant owner; full bypass; cannot be removed
├── Users               — invited by the Root or by anyone holding IAM permission
├── Groups              — created freely ("Sales-SP", "Auditors", etc.)
│   └── ⇄ Policies
├── Users ⇄ Groups       (N:N)
├── Users ⇄ Policies     (N:N)
└── Policies            — JSON document: Effect / Action / Resource [/ Condition]
```

**Real example:** the tenant admin creates a "Design Department" group and attaches a policy that allows `meeting:read` and `analysis:read` only on resources with the condition `nora:Department = "design"`.
→ The group's members manage design meetings and **never see** sales transcripts.

**The mechanism behind that example is built.** Meetings carry a free-form key/value `attributes` map (`V007`, GIN-indexed in `V008`), the upload contract accepts it (`MeetingUploadMetadata`), and the listing endpoints evaluate conditions per item against exactly those attributes (`MeetingsController` → `AuthorizationService.filterAllowed(..., Meeting::attributes)`). A meeting with no attribute fails an attribute-based condition, which is the fail-closed direction.

**What is missing is a producer.** No shipped surface sets `attributes` on a meeting. The web client sends title, language, format, timestamps, participants and tags (`apps/web/src/lib/api/client.ts:228-236`); the desktop sends the same minus the language choice (`apps/desktop/src/lib/meetings.ts:96-105`). Neither sends `attributes`. So the condition in the example above works, and today it can only be exercised by a caller talking to `POST /meetings` directly. **Attribute-based conditions are a capability of the API, not yet a feature of the product**, and that distinction is the honest reading of the "Real example".

**Effect on Company Context — none today.** There is **one** context record per tenant: `tenant_contexts.tenant_id` is `UNIQUE` (`V005:15`). Per-department sub-catalogues, selected by the conditions of whoever triggered the analysis, were described here for over a year and were never built. If they are wanted, they are a schema change, not a configuration.

**Effect on Desktop — none today.** A meeting captured by the desktop is not born tagged with `Department` or `Project`; nothing in the capture path attaches an attribute. Making capture attribute-aware is open work, not delivered behaviour.

**Evaluation order:** Root bypass → explicit Deny → applicable Allow → Default Deny.

## 7. Two directions: OAuth outbound, MCP inbound

This section used to describe a single thing called "MCP" and attribute to it work that a different
protocol had actually delivered. There are two directions, they are not the same feature, and only
one of them is built.

### Outbound — NORA acts on other systems. Built.

This is the direction that eliminates double data entry, and it is real today. It is **OAuth
integrations** ([ADR 0031](../adr/0031-oauth-integrations-token-storage.md)) driven by the Flows
engine ([ADR 0030](../adr/0030-flows-event-bus-workflow-engine.md)), not MCP:

```
Meeting analysed in NORA
         │
         │  a Flow fires on the event  (meeting.analysis_completed · action_item.created
         │                              · meeting.risk_detected)
         ▼
   condition?  ──no──▶  stop
         │yes
         ▼
   OAuth ──▶ Google · Microsoft · Slack · GitHub · Notion · Todoist · Linear
             Telegram · Trello
             (tokens encrypted at rest, AES-GCM; migrations V024–V026)
```

The user transcribes once, and the Flow distributes the result to the tools they already have.

### Inbound — an external client asks NORA questions. Decided, not built.

This is what **MCP** is for, and it is the promise this document has carried since version 0.2
(2026-05-01) with no line of code ever written behind it.
[ADR 0041](../adr/0041-nora-as-mcp-server.md) settles the design:

- **NORA is the MCP *server***, not a client of other servers. External MCP clients — Claude
  Desktop, IDEs, coding and research agents — read meetings, tasks, semantic search and Customer
  Confidence from it.
- It lives **inside `services/api`** as an inbound adapter, so that every tool call goes through the
  same tenant filter (ADR 0002) and the same `PolicyEvaluator` (ADR 0007) the web surface uses. The
  invariant, stated so it can be tested: *an MCP client can never see more than the user it acts
  for can see in the web application.*
- **Authentication is a tenant-scoped token, stored only as a SHA-256 hash.** This deliberately
  falls short of the MCP specification's OAuth 2.1 authorization server, with the cost named:
  clients that speak only that flow will not connect without a manually pasted token.
- **The first cut is read-only.** Writes already have a path — that is the outbound direction above.

> **No MCP server is implemented today.** Nothing in this repository speaks MCP, in either
> direction. What changed in August 2026 is that it stopped being a vague post-MVP concept and
> became declared, designed scope with an accepted ADR behind it (ADR 0041). Until that build
> lands, every use of the word "MCP" in this document refers to a decision, not to a capability.

Why this one promise was kept when the same realignment closed SSO, aggregated Account Health, the
DPA and the SLA: MCP is in the one-line definition of what NORA *is*, so deleting it would change
what the product claims to be rather than tidy a document. That reasoning, including the case
against it, is written out in ADR 0041.

## 8. Value Proposition per Plan

**NORA Core:**
> *"Leave any meeting knowing exactly what was decided, what you need to do and how that connects to your projects — without writing a single line."*

**NORA Enterprise:**
> *"Configure NORA with your business's vocabulary and turn every customer meeting into actionable intelligence: see opportunities before the competitor, detect churn weeks in advance and know exactly what the next step is — in your sector, with your products, in your language."*

## 9. Personas

Three reference personas guide product decisions:

- **Lucas** — Individual professional, Core user. Manages multiple projects, wastes time going back over meetings to remember what was decided
- **Camila** — Enterprise admin. Configures the tenant, defines who sees what via IAM, configures the company's context
- **Rafael** — Enterprise AE. A salesperson who needs to read opportunity and risk signals in every conversation with a customer

Full details, empathy maps, pains and gains: `docs/challenge/personas-and-empathy-map.md`.

## 10. Market Context

**Global Sales Intelligence market** (Gong, Clari, Chorus): products in English, cost > US$100/user/month, generic knowledge (without the customer's vocabulary), without native LGPD compliance.

**Generic transcription tools** (Otter.ai, Fireflies, Granola): they capture text, do not extract intelligence and do not integrate in a structured way.

**NORA fills two vacuums simultaneously:**
1. A Brazilian platform, in Portuguese, built LGPD-first, with PLG adoption.
2. The only platform that learns the context of any company — not just those listed in its fixed database.

The first of those two is a **narrower** claim than it was. The privacy differentiator against the
competitors named above rests on the text pipeline, and ADR 0040 records that the guarantee does not
extend to audio once cloud transcription lands, that no data processing agreement exists with any
transcription provider, and that the resulting position is a demonstration posture rather than a
compliance posture. Stated here rather than only in the ADR, because this is the section where the
comparison is made.

**Initial market: the TOTVS ecosystem** (first vertical customer reference for go-to-market). The
FIAP × TOTVS pitch was **held on 2026-06-15**.

**Estimated impact in Enterprise (unvalidated hypotheses — no pilot has ever run, and ADR 0038 §1 declares no user base to run one with):**
- ↓ 40% in post-meeting CRM filling time.
- ↑ Churn detection 30–60 days in advance.
- ↑ Rate of upsell captured vs. identified (today estimated at < 20%).

## 11. What comes next

This section used to summarise sub-phases 1.11 to 1.13 against a production Azure resource group, a
DR runbook that does not exist and a decision tree hanging off the outcome of a pitch that has since
happened. All three are gone.

Two documents own the answer now, and this one only points at them, so that the plan cannot drift
in three places at once:

- [`docs/product/roadmap.md`](roadmap.md) — the sub-phase history and what is ahead. **Caveat, true
  on 2026-08-17:** its §2 has not been reconciled against ADR 0038 either, and still plans against
  `rg-nora-prod`, Azure Monitor and that same missing DR runbook. Read §1 (history) as accurate and
  §2 (what is ahead) as pending.
- [ADR 0038](../adr/0038-post-pitch-scope-realignment.md) — the scope decision underneath it: what
  is closed (§4), what came back into scope (§5) and what is a declared deferral with a written
  reason (§6). Where the roadmap and the ADR disagree, the ADR is the authority.

The three open builds this document is responsible for naming, because they are the ones it makes
promises about: the **MCP server** ([ADR 0041](../adr/0041-nora-as-mcp-server.md)), the move of
transcription to **cloud STT** ([ADR 0039](../adr/0039-cloud-stt-openai-ephemeral-token.md)), and
**ADDRESS** coverage in the PII Shield. None of the three is built; each is decided.

## Document History

| Version | Date | Description |
|---|---|---|
| 0.1 | 2026-05-01 | Initial creation — Vision Statement + Is/Is Not and Does/Does Not tables |
| 0.2 | 2026-05-01 | Expansion into a platform with two plans: Core + Enterprise + MCPs + PLG |
| 0.3 | 2026-05-02 | Horizontal platform (any company, not just TOTVS) + real-time Desktop (Tauri) + IAM RBAC+ABAC + three surfaces + Product Context via RAG |
| 0.4 | 2026-05-02 | MVP alignment: SSO and Desktop as post-MVP; audio with temporary TTL-based storage |
| **1.0** | **2026-05-14** | **Rewrite after the real Azure deploy (Sub-phase 1.9). Corrects drift: Desktop supports macOS via BlackHole (PR #37 — it is no longer "Does not support macOS in the MVP"). Adds the "Current State" section with real endpoints + operational IAM + Productivity Score full-stack + test coverage. Adds the "Upcoming Sub-phases" section with a link to the roadmap. Replaces the previous doc `docs/visao-do-produto.md` (moved to `docs/product/vision.md`).** |
| 1.1 | 2026-06-06 | Doc x code reconciliation + standardisation |
| **1.2** | **2026-08-17** | **Reconciliation against the August 2026 realignment (ADR 0038–0041).** Plan box rewritten to what each plan has, with a table recording what it used to promise and what closed it: SSO (US05), aggregated Account Health (US50/US51) and the Enterprise SLA/DPA are **WONT** by ADR 0038 §4; "Commercial Next Best Action" is removed as never built and never tracked; "Competitive Radar" and "Team analytics" are restated at their true size. Records that the Core/Enterprise split is commercial framing that the code does not enforce. §3 "Current State" replaced: the dated Azure snapshot is deleted in favour of pointers to the documents that maintain each fact, with the reason written out. §4/§5 reconciled — Desktop is Windows-only, transcription is on-device **today** with ADR 0039 decided and unbuilt, the PII promise is scoped to text and analysis and the transcription provider is a declared subprocessor (ADR 0040), RAG does **not** use pgvector, RLS is enforced on the deployed stack and deferred only as the repository default, retention `0` means OFF. §6: per-department Company Context and attribute-tagged desktop capture removed as never built, and the "Real example" qualified — the condition evaluator is real, but no shipped UI writes the meeting `attributes` it reads. §7 rewritten around the two directions — OAuth outbound shipped (ADR 0031), MCP inbound decided and unbuilt (ADR 0041). §11 replaced by pointers to the roadmap and ADR 0038 |
