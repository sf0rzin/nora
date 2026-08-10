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

**THAT** automatically processes meeting transcripts and delivers, in seconds, structured summaries, action items and, for commercial teams, advanced signals of opportunity, risk and account health — with context calibrated to the customer's own business, native integration via the **Model Context Protocol (MCP)**, an IAM model with users, groups and policies, and LGPD compliance,

**UNLIKE** generic transcription tools (Otter.ai, Fireflies) or international Sales Intelligence platforms (Gong, Clari) that use generic knowledge, do not speak Portuguese natively and do not respect Brazilian data protection legislation,

**NORA** is the only conversational intelligence platform that learns each company's vocabulary, products and context — making any organisation capable of extracting real intelligence from its own meetings, regardless of sector or ecosystem. It starts as a personal copilot (Core, freemium) and evolves into a complete revenue engine for teams (Enterprise), with natural bottom-up adoption *(Product-Led Growth)*.

## 2. The Platform: Core and Enterprise

NORA is a platform with two plans that share the same AI engine and infrastructure, but serve distinct profiles and needs.

```
┌─────────────────────────────────────────────────────────────────────┐
│                        NORA  PLATFORM                               │
├──────────────────────────────────┬──────────────────────────────────┤
│          NORA  CORE              │        NORA  ENTERPRISE          │
│      Individual professional     │         Teams and companies      │
├──────────────────────────────────┼──────────────────────────────────┤
│ • Automatic meeting summaries    │ • Everything in Core, plus       │
│ • Action item detection          │ • Product Context via RAG        │
│ • Productivity Score, opt-in     │ • Customer Confidence (per call) │
│ • Project tracking               │ • Account Health Score over time │
│ • Personal PII Shield (LGPD)     │ • Configurable Competitive Radar │
│ • Integration over MCP:          │ • Commercial Next Best Action    │
│   · Google Calendar / Outlook    │ • AWS-style IAM: Root + Users +  │
│   · Linear / Jira                │   Groups + Policies              │
│   · GitHub                       │ • Team analytics & dashboards    │
│ • Freemium / individual plan     │ • SSO (Entra ID / SAML 2.0 —     │
│                                  │   post-MVP)                      │
│                                  │ • Isolated multi-tenancy         │
│                                  │ • Enterprise SLA + BR support    │
├──────────────────────────────────┴──────────────────────────────────┤
│  SURFACES: Web SaaS · Desktop (Tauri, real-time) · API / MCPs       │
├─────────────────────────────────────────────────────────────────────┤
│  GO-TO-MARKET: Product-Led Growth                                   │
│  An individual adopts Core (freemium) → shows it to the company →   │
│  the company buys Enterprise                                        │
└─────────────────────────────────────────────────────────────────────┘
```

## 3. Current State (2026-06-06)

NORA is no longer in the scaffolding phase nor in pure Sprint 1+2 documentation. **It is deployed on Azure** and operational end-to-end in the MVP's central flows:

- Web in dev production: <https://nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io>
- 14 Azure resources provisioned in `rg-nora-dev` (centralus): Container Apps Env, 3 Container Apps (web + api + worker), Postgres Flexible, Key Vault, Storage Account, App Insights, Log Analytics, Azure Speech, 3 User-Assigned Identities (api/worker/web), and federated credentials on the SP `sp-nora-github-deploy`
- Pipeline `build-images.yml` publishing 3 real images to GHCR (`ghcr.io/sf0rzin/nora-{api,worker,web}`); deploy via `deploy-infra.yml` with OIDC
- AWS-style IAM operational: Users + Groups + Policies + audit log with immutable policy versioning. PolicyEvaluator supports `StringEquals`, `StringIn`, `StringLike`, `DateGreaterThan` and `DateLessThan` (operators outside the list and missing attributes result in `Deny`, fail-closed)
- Productivity Score full-stack (ADR 0005): Spring backend + NLP worker + web 3 components (`MeetingGoalForm`, `MeetingProductivitySection`, `ProductivityScoreCard`)
- PII Shield expanded: beyond email/CPF/CNPJ/phone/credit card, it covers **PERSON_NAME (BR)** with a list of ~270 Brazilian names + a negative list (ADR 0012)
- Agnostic LLM pipeline (ADR 0004): default OpenAI `gpt-4o-mini`, strict schema via `response_format=json_schema`
- Coverage: NLP worker 87% (54 tests), Spring backend 67% (174 tests), Next.js web 0% (no runner — debt for 1.12)
- **Chat-first Core app (2026-05-28)**: the Core's web surface became conversational — **AI Chat with streaming** (OpenAI via ADR 0004, server-side key in a `/api/chat` BFF, workspace context injected), with Home (inbox), meeting detail, Action items, Projects and Integrations (MCP). IAM/tenant context (Enterprise) were removed from the Core nav.
- **TOTVS transcripts**: the Data Science pipeline in `notebooks/totvs_transcricoes_eda.py` processes the real export (dedicated parser for the malformed format + cleanup of `[LOCUTOR N]` + TF-IDF reusing `nlp_baseline` + language×NPS correlation for risk/opportunity signals)

The durable decisions are documented in the ADRs (canonical index: `docs/adr/README.md`). **Customer Confidence was delivered full-stack** in **PR #148 (2026-05-21)** via ADR 0015: the worker emits the `customerConfidence` block, the backend persists it in the pipeline with an authoritative trend per account, `GET /meetings/{id}` returns the block and the `CustomerConfidenceCard` appears in the meeting detail — resolving the landing page's narrative debt. **Aggregated** Account Health (US50-51) remains deferred (ADR 0014). Semantic search / RAG (US15) was delivered (PR #206): migration V021 (`meeting_embeddings`), `EmbeddingService` + `HttpEmbeddingClient` with provider-agnostic embeddings (Gemini/OpenAI) via pgvector, and the Core chat consumes `/meetings/search` as RAG context. Operational LGPD was also delivered (ADR 0029): `DELETE /privacy/meetings/{id}` (right to be forgotten) + scheduled `RetentionSweeper`. Meanwhile a post-1.10 hardening wave (#114–#138) delivered RLS (V016) — completed by full RLS and auth-aware scope (V019/V020) —, soft-delete (V013), refresh-token rotation (V014) and the composite isolation FK (V015). The migration schema goes up to **V021** (canonical source: `docs/engineering/data-model.md`).

To understand the previous state (Sprint 1+2 documentation) consult the document history at the end of this file and `docs/product/roadmap.md`.

## 4. Is — Is Not table

> *Defines what the product IS and what it must NEVER be confused with.*

|  | **Is** | **Is Not** |
|---|---|---|
| **Nature** | A **conversational intelligence platform** with two plans (Core and Enterprise) and three surfaces (Web, Desktop, API/MCP) | A CRM, an ERP or a replacement for any management system |
| **Core** | A **personal meeting copilot** for the individual professional — organises projects, creates tasks, records decisions | A notes app, a generic recorder or a replacement for Notion/Linear |
| **Enterprise** | A **commercial intelligence engine** configured with the company's own context and products — for any sector | A tool exclusive to the TOTVS ecosystem or to any other specific vendor |
| **Context** | A platform that **learns the customer's vocabulary**: each company configures its products, competitors and terms | A generic AI that uses hardcoded knowledge of a single market |
| **Desktop** | A **real-time app** (Tauri 2 / Rust + Python sidecar) that captures and analyses the meeting while it happens, cross-platform | A videoconference plugin or a browser extension |
| **IAM** | A **granular access control** system in the style of **AWS IAM** (Root + Users + Groups + Policies created by the tenant itself) | A system where everyone in the company sees every transcript from every department |
| **AI** | An **analysis, structuring and recommendation** engine that amplifies the human. Output via strict JSON Schema | An AI that makes commercial decisions autonomously without human review |
| **Integration** | A platform that is **open via MCP**: data *leaves* NORA for the tools the user already uses | A closed system that requires replacing the existing tools |
| **Data** | An **LGPD-first** system: PII is detected and redacted before any external LLM, with explicit consent | A platform that stores or shares third-party conversation data |
| **Model** | A SaaS with an **individual freemium** (Core) evolving into **paid enterprise** via PLG | A product that requires a corporate purchase as its entry point |

## 5. Does — Does Not table

> *Defines the product's behaviour, features and limits. Updated 2026-05-14 — reflects reality after the real Azure deploy (Sub-phase 1.9).*

|  | **Does** | **Does Not** |
|---|---|---|
| **Input** | Accepts transcripts in **text** (`.txt`, `.vtt`, `.srt`) — MVP. Desktop captures system audio in **real time** (Windows via WASAPI; macOS via BlackHole; Linux via PulseAudio) | Does not accept upload of archived audio/video in the MVP (`.mp3`, `.mp4`) — it is in US08 (Won't Have v1). Roadmap: post-MVP via Azure Speech batch |
| **PII Shield** | **Automatically detects and redacts** CPF, CNPJ, confidential monetary values, email, phone, credit card and **Brazilian personal names (PERSON_NAME)** before any submission to the external AI, with `[[TIPO_N]]` placeholders (ADR 0012) | Does not cover address PII (ADDRESS) in the MVP — debt catalogued for post-MVP. Does not retain the original audio after extraction; when necessary, it uses temporary storage with a short TTL |
| **Core — Summary** | **Generates a structured summary**: context, decisions taken, next steps, within 30 seconds | Does not rewrite or creatively edit the content — it always preserves the original intent |
| **Core — Action Items** | **Detects and categorises** explicit and implicit **tasks** with the confidence level displayed, priority `LOW`/`MEDIUM`/`HIGH` and a textual quote of the source (`sourceQuote`) | Does not guarantee capture of 100% of the items — it is input for human review. Does not assign automatically without an explicit name |
| **Core — Productivity Score** | **Opt-in per meeting**: the user declares an objective + expected outcomes; NORA measures coverage (`ADDRESSED`/`PARTIAL`/`MISSED`) and assigns a score of 0–100 with a `LOW`/`MEDIUM`/`HIGH` band (ADR 0005) | Does not calculate without opt-in — privacy by design. Does not use external benchmarks — only the answer key declared by the user themselves |
| **Core — Projects** | **Maintains project traceability** over time without manual filling; uses tenant attributes or meeting tags | It is not a project manager — it sends data to Jira/Linear via MCP (post-MVP) |
| **Core — MCPs** | **Integrates via MCP** with Calendar/Outlook, Linear/Jira, GitHub and Salesforce/HubSpot — all post-MVP | Does not require use of all the integrations — each MCP is optional and independent |
| **Enterprise — Product Context** | **Learns the customer's business**: the admin configures a catalogue of products, competitors, glossary and stakeholders; the AI uses this context via RAG/injection in every analysis | Does not use generic or hardcoded knowledge from any vendor — the context is always the tenant's. Semantic search / RAG (US15) was delivered (PR #206) with provider-agnostic embeddings (Gemini/OpenAI) via pgvector + HTTP embedding client (not Azure AI Search) |
| **Enterprise — Customer Confidence** | **Delivered full-stack** (ADR 0015, PR #148): the worker emits a 0–100 score + band + trend + buying signals + objections + `accountName`; the backend persists with an authoritative trend per account; `GET /meetings/{id}` returns the block; `CustomerConfidenceCard` UI in the meeting detail | **Aggregated** Account Health (temporal score per account + alerts, US50-51) remains deferred (ADR 0014) — it requires pilot volume |
| **Enterprise — Account Health** | Schema foreseen (ADR 0006): bands `AT_RISK` / `WATCH` / `HEALTHY` / `STRONG`, aggregated per account, with a trend | **Not implemented** — postponed via ADR 0014 (defer post-MVP commercial gate) |
| **Enterprise — Next Action** | **Recommends the Next Best Action** in the next 48–72h based on the pattern of the conversation | Does not automatically create tasks in the CRM — it sends them via MCP or webhook (post-MVP) |
| **IAM — Model** | **Granular AWS-style IAM**: Root + Users + Groups + Policies (Effect/Action/Resource/Condition) created by the tenant itself. Immutable policy versioning + audit log. (ADR 0007) | Does not impose a fixed role hierarchy (no predefined Manager/Analyst/Viewer) |
| **IAM — Conditions** | **AWS-style Conditions** on attributes defined by the tenant: `Department`, `Project`, `Account` etc. PolicyEvaluator supports `StringEquals`, `StringIn`, `StringLike`, `DateGreaterThan`, `DateLessThan` | Operators outside that list (and attributes missing from the context) result in `Deny` (fail-closed) |
| **Desktop** | **Tauri 2 app** (Rust + Python sidecar) with system audio capture. **Windows via WASAPI** (official v1) · **macOS via BlackHole** (virtual audio driver; native ScreenCaptureKit is in debt as a nice-to-have) · **Linux via PulseAudio** | It is not a videoconference plugin. It does not run on mobile in the MVP |
| **Multi-tenancy** | **Isolation per organisation** via `tenant_id` in every table + application filter (ADR 0002), with **Postgres RLS** (schema in V016, full RLS + auth-aware scope in V019/V020) and a **composite isolation FK (V015)** as defence in depth. Reproducible Bicep IaC | What remains is the operational RLS cutover/enforcement in prod (runbook in ADR 0026/0028); the schema is already delivered. Does not offer on-premises installation in the MVP |
| **Compliance** | **LGPD by design and operational** (ADR 0029): consent, audited record and **right to be forgotten delivered** — `DELETE /privacy/meetings/{id}` + scheduled `RetentionSweeper` | Does not carry out DPIAs automatically — a manual action by the customer's DPO |

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

**Effect on Product Context:** each department can have its own sub-catalogue. The AI chooses the correct sub-catalogue based on the conditions applicable to the user who triggered the analysis.

**Effect on Desktop:** the transcript is born tagged with the relevant attributes (`Department`, `Project`, `Participants`) from the moment of capture, applying the logged-in user's policies.

**Evaluation order:** Root bypass → explicit Deny → applicable Allow → Default Deny.

## 7. Why MCPs change the game

The **Model Context Protocol (MCP)** is an open standard that lets NORA connect to external tools in a secure and standardised way — without fragile integrations or maintenance of ad-hoc webhooks. For the user, it means NORA "talks" to the tools they already use:

```
Meeting transcribed in NORA
         │
         ├─── MCP → Google Calendar / Outlook  → Writes the summary onto the calendar event
         │
         ├─── MCP → Linear / Jira              → Opens issues from the detected action items
         │
         ├─── MCP → GitHub                     → Links the technical discussion to the PR/issue named
         │
         └─── MCP → Salesforce / HubSpot / TOTVS CRM → Pushes the opportunity with structured context
```

This eliminates the main friction in adopting productivity tools: double data entry. The user transcribes once — NORA distributes it wherever it needs to go.

> In the current MVP no MCP server is implemented. MCPs remain a roadmap concept (post-MVP commercial).

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
1. A Brazilian, LGPD-compliant platform, in Portuguese, with PLG adoption.
2. The only platform that learns the context of any company — not just those listed in its fixed database.

**Initial market: the TOTVS ecosystem** (first vertical customer reference for go-to-market — FIAP pitch 15/06/2026).

**Estimated impact in Enterprise (hypotheses to validate):**
- ↓ 40% in post-meeting CRM filling time.
- ↑ Churn detection 30–60 days in advance.
- ↑ Rate of upsell captured vs. identified (today estimated at < 20%).

## 11. Upcoming Sub-phases

Details in `docs/product/roadmap.md`. Summary:

- **1.11 — Demo Polish Plan A** (in progress): Customer Confidence (#148), AUTH_FILTER_HARD_CAP removed (batched scanning in `MeetingService.listAllForAuthFilter`) and `PolicyEvaluator` expansion (`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan`) delivered; what remains is polished internal UX + synthetic dataset + demo script
- **1.12 — Production Hardening**: dedicated production RG (`rg-nora-prod`) + operational cutover/enforcement of Postgres RLS in prod + monitoring alerts + DR runbook + secrets rotation + test coverage targets (ADR 0018 to be created). Operational LGPD has already been delivered (ADR 0029)
- **1.13+** — Post-TOTVS pitch (15/06+): depends on the outcome of Plan A. Scenarios: pitch dossier / due diligence (Plan A) · Plan C content + Plan B commercial pivot

## Document History

| Version | Date | Description |
|---|---|---|
| 0.1 | 2026-05-01 | Initial creation — Vision Statement + Is/Is Not and Does/Does Not tables |
| 0.2 | 2026-05-01 | Expansion into a platform with two plans: Core + Enterprise + MCPs + PLG |
| 0.3 | 2026-05-02 | Horizontal platform (any company, not just TOTVS) + real-time Desktop (Tauri) + IAM RBAC+ABAC + three surfaces + Product Context via RAG |
| 0.4 | 2026-05-02 | MVP alignment: SSO and Desktop as post-MVP; audio with temporary TTL-based storage |
| **1.0** | **2026-05-14** | **Rewrite after the real Azure deploy (Sub-phase 1.9). Corrects drift: Desktop supports macOS via BlackHole (PR #37 — it is no longer "Does not support macOS in the MVP"). Adds the "Current State" section with real endpoints + operational IAM + Productivity Score full-stack + test coverage. Adds the "Upcoming Sub-phases" section with a link to the roadmap. Replaces the previous doc `docs/visao-do-produto.md` (moved to `docs/product/vision.md`).** |
| 1.1 | 2026-06-06 | Doc x code reconciliation + standardisation |
