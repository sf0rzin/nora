# Engineering Standards — NORA

> Operational guide for humans and AI agents programming NORA.
> Defines conventions, structure, patterns and tooling. Updated to reflect the **actual state of the code** — not promises.
> Every version, path and folder below was re-read from the file it names on **2026-08-17**, at commit `23cefeb`. A claim here that a manifest contradicts is a bug in this document, not a fact about the project.

## 1. Engineering Principles

1. **Vertical slice before expansion.** Delivering one small complete flow > opening several incomplete fronts.
2. **Multi-tenant from the first commit.** Any customer data is born with a `tenant_id`. There is no shortcut.
3. **Authorization in the backend.** Filtering in the frontend is UX; real security lives in `AuthorizationService` + `PolicyEvaluator`.
4. **Contract before implementation.** When frontend, backend and worker interact, the contract comes first (OpenAPI + JSON Schema + examples).
5. **AI with structured output.** The LLM never returns free text to the application; always strict JSON Schema validated by Pydantic (ADR 0003).
6. **Horizontal product.** Zero hardcoded rules for TOTVS. Each tenant configures its own context.
7. **Security by default, scoped to text and analysis.** PII does not reach the **analysis** LLM raw; the worker's `PIIShield` is the last gate before analysis (ADR 0012, ADR 0040). Two exposures are declared at the same volume as the promise: PERSON_NAME on the chat path is accepted residue until the worker routing lands (ADR 0033), and transcription is an external subprocessor that receives raw audio before any redaction can exist (ADR 0039, ADR 0040 §3). This document used to claim coverage over every byte the product touches; that claim was withdrawn deliberately. Do not restore it. No control was weakened — ADR 0040 supersedes neither ADR 0012 nor ADR 0033.
8. **Living documentation.** Durable decision → `docs/adr/`. Transient detail → issue/PR/private vault.

## 2. Confirmed stack

The version column is the pin in the manifest named beside it, not a round number. `docs/engineering/architecture.md` §1 carries the same readings row by row with the rationale for each choice; `AGENTS.md` §Stack carries the short form. If the three ever disagree, the manifest wins and all three are wrong.

| Layer | Stack | Pattern |
|---|---|---|
| **Web** | Next.js **16.3.0** + TypeScript ^5.6.3 + React 18.3.1 + Tailwind CSS ^3.4.13 (raw — **no shadcn**, no MUI, no Chakra) — `apps/web/package.json` | App Router, RSC when it makes sense, client components only for interaction. Also the BFF: provider keys stay server-side |
| **Operator console** | Next.js **16.3.0**, same four front-end pins as `apps/web` — `apps/admin/package.json` | Model catalog + AI cost telemetry (ADR 0023/0024/0025). Fail-closed: without both `CF_ACCESS_*` set it serves 403, it does not fall back to mock data |
| **Backend** | Java 21 + Spring Boot **3.5.16** + JPA + Flyway — `services/api/pom.xml` (parent, `java.version`) | Layered DDD (domain/application/infrastructure/api), REST + OpenAPI, Bean Validation |
| **NLP Worker** | Python >=3.12 + FastAPI >=0.115 + Pydantic >=2.9 + OpenAI SDK >=1.50 — `services/nlp-worker/pyproject.toml` | Small pipelines, explicit schemas, prompts versioned in `prompts/{version}.md`. The analysis routes require `X-Internal-Token` and fail closed when it is unconfigured (`security.py`); the health probes stay open |
| **Database** | Postgres 16 + Flyway | Versioned migrations `V###__name.sql`, `tenant_id` on every tenant-bound table |
| **AI** | Any endpoint speaking OpenAI's Chat Completions API, selected by `LLM_BASE_URL` / `LLM_API_KEY` / `LLM_MODEL`; default is OpenAI direct on `gpt-4o-mini` (`services/nlp-worker/src/nora_nlp/settings.py`) | Strict JSON Schema, low temperature, logs without PII. ADR 0004. Azure OpenAI is one compatible endpoint among several, **not a tier**: Azure left the project entirely with ADR 0034/0036. The control plane's per-service binding (ADR 0024) is the runtime override, and the worker does not read it |
| **Search/RAG** | Provider-agnostic HTTP embedding client (Gemini/OpenAI) + cosine similarity computed in Java | Semantic search delivered (PR #206, V021 `meeting_embeddings`); Core chat consumes `/meetings/search` as RAG context. **`pgvector` is not in use.** V021 stores the vector as a JSON array in a `TEXT` column and `EmbeddingService.cosine` scores it in Java; the container image is `pgvector/pgvector:pg16` and the extension is deliberately never created (ADR 0034 §excluded scope). Adequate for tens or hundreds of meetings per tenant — a scale ceiling, not a feature |
| **Auth** | JWT (JJWT 0.13.0) + stateful refresh tokens (V011); HttpOnly cookies | Corporate SSO (Entra ID / SAML, US05) is **WONT** — closed by ADR 0038 §4, not deferred and not waiting on anything |
| **Desktop** | Tauri 2 + Rust, **Windows-only** (ADR 0038 §2) | System-wide audio capture via WASAPI loopback; ADR 0008. Surfaces are capture + overlay + dock — the local React UI was deleted and the `main` window loads the remote web app. **Transcription is the provider's realtime API** (ADR 0039, contract in ADR 0045): one WebSocket per track on a short-lived credential from `POST /stt/sessions`, with the provider key held by the API. The on-device engine, the Python sidecar and the `stt-azure` feature are all gone, so the crate has no `[features]` section and no C++ toolchain requirement |
| **Infra** | Self-hosted: single bare-metal Ubuntu host, no hypervisor, Docker Compose (ADR 0034/0036) | Cloudflare Tunnel ingress, SOPS + age secrets, pull-based deploy |
| **CI/CD** | GitHub Actions: `ci.yml` + `build-images.yml` + `deploy-host.yml` (plus the Cloudflare, RLS-cutover, secrets-seed, supply-chain and desktop-release workflows) | Push to GHCR; the host pulls, but rolling forward is still manual — see AGENTS.md |

### Scope is declared, not open

The May-2026 version of this section read: *"Focus on the Web + Backend + NLP Worker slice. Desktop, SSO, audio/video upload, full MCPs and native Salesforce come post-MVP."* Four of its five clauses have since been settled elsewhere, so the sentence is retired rather than edited:

- The slice is no longer three surfaces. **Web + Backend + Worker + Desktop are all functional**, plus `apps/admin` as a fifth.
- **SSO is WONT** (ADR 0038 §4), not post-MVP.
- **MCP is built** — ADR 0041's inbound adapter inside `services/api`, read-only in its first cut (`api/mcp/`, `POST /mcp`, migration V029). "Full MCPs" as the old sentence meant it — NORA calling out to other people's MCP servers — is not on any list and is not planned; the outbound direction is OAuth (ADR 0031).
- **"Native Salesforce" was never a tracked item** in the backlog and is not one now. The nine OAuth providers that did ship are in ADR 0031; Salesforce is not among them.
- **Audio/video upload is the one clause still true.** `POST /meetings` takes a transcript file, and `POST /meetings/split-preview` accepts `.txt` only (`MeetingsController.requireTxtFile`). Audio enters through the desktop capture path, never through an HTTP upload.

What is closed, what came back into scope and what is a declared deferral are enumerated once, in **ADR 0038 §4/§5/§6**, and summarised in `AGENTS.md`. Anything in none of those three lists needs a successor ADR before it is built. This document does not keep a fourth copy.

## 3. Folder structure

```text
nora/
├── apps/
│   ├── web/                    # Next.js (raw Tailwind); also the BFF
│   ├── admin/                  # operator console / control plane (ADR 0022-0025)
│   └── desktop/                # Tauri 2 + Rust, Windows-only (ADR 0038 §2)
├── services/
│   ├── api/                    # Spring Boot backend
│   └── nlp-worker/             # FastAPI worker NLP/LLM
├── packages/
│   ├── nlp-baseline/           # reusable PT-BR TF-IDF (ADR 0010)
│   └── shared-contracts/       # shared contracts (error-codes, pii-types, processing-status)
├── infra/
│   ├── host/                   # self-hosted stack: compose, Caddy, cloudflared, observability, secrets
│   └── docker/                 # local Compose, auxiliary Dockerfiles
├── data/
│   ├── synthetic/              # 12 transcripts (some with .srt/.vtt variants) + 3 tenant contexts
│   └── samples/                # small examples
├── notebooks/                  # FIAP Data Science deliverables
├── docs/
│   ├── product/                # vision, backlog (real status), roadmap, glossary, possible-integrations
│   ├── engineering/            # architecture, standards (this doc), data-model, data-model-oracle, contracts/
│   ├── operations/             # host-deploy (runbook + self-hosting pitfalls), control-plane and RLS-cutover
│   │                           #   runbooks, environment-secrets, cloudflare-access, ssh-over-tunnel,
│   │                           #   web-custom-domain, production-readiness-gaps (historical)
│   ├── challenge/              # FIAP Challenge 2026 (personas, use cases, README, fiap-challenge-2026)
│   ├── api/                    # OpenAPI + LLM JSON Schemas + examples
│   ├── assets/                 # logo SVGs used by the docs
│   └── adr/                    # ADRs (canonical index in docs/adr/README.md)
├── scripts/                    # guards and local automation
├── .github/                    # workflows + templates + CODEOWNERS
├── AGENTS.md                   # context for AI coding agents
├── SECURITY.md                 # vulnerability reporting
├── Makefile                    # local dev entry points
├── LICENSE                     # AGPL-3.0 (ADR 0017)
└── README.md
```

**Notes about the real structure:**

- **There is no `apps/web/src/features/`** (the previous version of the doc foresaw one). The frontend uses `src/components/` + `src/app/` (App Router), with four thin groupings under `components/` rather than a feature tree — see §8.
- **`packages/shared-contracts/`** contains the real shared contracts (`error-codes.md`, `pii-types.json`, `processing-status.json`, `README.md`); full HTTP contracts live in `docs/api/`.
- **There is no `docs/security/` directory, and there never was one.** Until this revision the tree above listed it, holding "threat model, operational LGPD". Neither file exists anywhere in the repository: the folder was planned in the Sub-phase 1.10 docs refresh (`AGENTS.md` change history, 2026-05-14) and never created, and `git log` records no deletion. Operational LGPD is a decision, **ADR 0029**, not a document; there is no written threat model. Two other files still cite the path as if it existed — `docs/adr/0016` (accepted, therefore immutable) and `docs/operations/production-readiness-gaps.md`, which lists it as future work, which is honest.
- **MCP is not a top-level folder, and will not become one.** ADR 0041 put the MCP server **inside** `services/api` as an inbound adapter — not a separate process and not an `mcp/` package — authenticated by a hashed, tenant-scoped token, resolving every tool call through the same `PolicyEvaluator` as the web app, read-only in its first cut. **It is built**: `api/mcp/` (protocol, tool catalogue, the five reads), `api/controllers/McpController.java` and `McpTokensController.java`, `application/mcp/`, `infrastructure/security/McpSecurityConfig.java`, migration V029. The opposite direction — NORA acting **outbound** on nine external providers through OAuth integrations (ADR 0031) — is a different protocol, and conflating the two is what made a delivered subsystem look unstarted. The wording that used to sit here, "deferred post-MVP via ADR 0014 … conditional on the first paying tenant", was doubly stale: ADR 0038 supersedes ADR 0014, and ADR 0038 §1 says there will be no paying tenant.

## 4. Where to store each piece of information

| Information | Location |
|---|---|
| Product vision (Is/Is Not, Does/Does Not, Geoffrey Moore) | `docs/product/vision.md` |
| Prioritized backlog (MoSCoW + real status DONE/PARTIAL/MISSING) | `docs/product/backlog.md` |
| Delivery history (sub-phases 1.0–1.11, then by theme) + what is ahead | `docs/product/roadmap.md` |
| NORA glossary (canonical terms: Productivity Score, Customer Confidence, IAM Policy, etc.) | `docs/product/glossary.md` |
| Technical architecture (DDD layers, end-to-end flows, stack rationale) | `docs/engineering/architecture.md` |
| Data Science pipeline for the TOTVS transcripts (EDA + TF-IDF + LLM) | `notebooks/totvs_transcricoes_eda.py` |
| Technical standards (this doc) | `docs/engineering/standards.md` |
| Postgres data model | `docs/engineering/data-model.md` |
| Oracle data model (FIAP DB deliverable) | `docs/engineering/data-model-oracle.md` |
| Self-hosted deploy runbook + self-hosting pitfalls | `docs/operations/host-deploy.md` |
| Control-plane operation, RLS enforce cutover, secrets, Access, SSH over the tunnel | `docs/operations/` (one runbook per topic) |
| Control-plane HTTP contract between the API and the operator console | `docs/engineering/contracts/platform-control-plane.md` |
| Production-readiness gaps — **historical**, written against the Azure deployment that no longer exists (ADR 0034/0036) | `docs/operations/production-readiness-gaps.md` |
| FIAP Challenge 2026 academic material (personas, use cases, rubric) | `docs/challenge/` |
| Context an AI coding agent reads first (non-negotiables, scope table, stack) | `AGENTS.md` |
| Durable architectural decisions (canonical index) | `docs/adr/NNNN-<slug>.md` (index in `docs/adr/README.md`) |
| HTTP contracts | `docs/api/openapi.yaml` — hand-written, complete, and compared to the controllers in CI by `scripts/check-openapi-coverage.sh`. springdoc is on the classpath for cross-checking, not as the source |
| Payload examples | `docs/api/examples/*.json` |
| LLM schemas | `docs/api/llm-schemas/*.schema.json` |
| Worker prompts | `services/nlp-worker/src/nora_nlp/prompts/{version}.md` |
| Pydantic schemas | `services/nlp-worker/src/nora_nlp/models.py` |
| Synthetic data | `data/synthetic/` |
| Academic notebooks | `notebooks/` |
| Example environment variables | `.env.example` in each app/service |
| Real secrets | **Never in Git.** `.env.local` in dev; `secrets.env.sops` (SOPS + age) in prod |

## 5. Backend — Java/Spring Boot

### Organization

```text
services/api/src/main/java/br/com/nora/api/
├── NoraApiApplication.java
├── domain/                # POJOs/records, pure logic; ZERO Spring dependency
│   ├── analysis/          # MeetingAnalysis + children
│   ├── chat/              # chat session and message
│   ├── customer/          # Customer Confidence (ADR 0006/0015)
│   ├── event/             # domain events consumed by the workflow engine (ADR 0030)
│   ├── iam/               # IamPolicy, PolicyEvaluator, PolicyStatement
│   ├── identity/          # User, Email value object, PasswordPolicy, RefreshToken
│   ├── integration/       # OAuth connection model (ADR 0031)
│   ├── meeting/           # Meeting, Participant, ParticipantMatcher/Identity, ProcessingStatus, Transcript
│   │   └── productivity/  # MeetingGoal, ProductivityAssessment (ADR 0005)
│   ├── platform/          # control-plane model (ADR 0022-0024)
│   ├── tenant/            # Tenant
│   └── workflow/          # Flows definition model (ADR 0030) + schedule state (ADR 0047)
├── application/           # use cases, services, ports
│   ├── analysis/          # AnalysisService
│   ├── chat/ customer/ embedding/ integration/ platform/ privacy/ task/ tenant/ workflow/
│   ├── iam/               # AuthorizationService, IamService
│   ├── identity/          # AuthService
│   ├── meeting/           # MeetingService, MeetingGoalService, TranscriptSplitService
│   └── ports/             # interfaces (UserRepository, MeetingRepository, NlpWorkerClient, ...)
├── infrastructure/        # adapters: JPA, JJWT, HTTP
│   ├── persistence/       # one package per aggregate: entities + repository adapters
│   ├── security/          # JjwtJwtIssuer, JwtAuthenticationFilter, TenantRlsAspect
│   ├── nlp/               # HttpNlpWorkerClient (implements the NlpWorkerClient port)
│   ├── embedding/         # HttpEmbeddingClient
│   ├── integration/       # OAuth clients + `actions/` (the outbound write actions)
│   ├── events/            # post-commit event bus (ADR 0030)
│   ├── platform/          # 2nd datasource, operator security, cost telemetry (ADR 0022-0025)
│   └── audit/ config/ email/ observability/
└── api/                   # controllers, DTOs, exception handlers
    ├── controllers/       # 21 of them: AuthController, MeetingsController, IamController, ...
    ├── dto/               # request/response records
    ├── security/          # CurrentUser, AuthCookies
    └── exception/         # GlobalExceptionHandler
```

The tree above is the shape, not the inventory. Two corrections it carries over the previous revision, because both used to send readers to a path that does not exist: `productivity/` is nested **under** `domain/meeting/`, not beside it, and the worker adapter is `infrastructure/nlp/HttpNlpWorkerClient.java`, not `infrastructure/analysis/WorkerHttpClient`.

### Inviolable rules

- `domain/` does **not** import anything from Spring, JPA, HTTP, the database or an external SDK.
- `application/` depends on **ports** (interfaces) declared in `application/ports/`.
- `infrastructure/` **implements** the ports (adapters).
- `api/` contains only controllers, DTOs and mappers. **No business rule in a controller.**
- Tenant-bound queries **always** filter by `tenant_id` before `id`.
- **Every controller handler declares an authorization decision, and the framework enforces that it does.** Annotate it with `@RequiresPermission(action, resource)` — the `RequiresPermissionInterceptor` resolves the principal, builds the resource ARN and calls `AuthorizationService` before the method — or with `@AuthorizationNotRequired` carrying the reason. **A handler that declares neither is denied**, so a new endpoint cannot ship without an authorization decision.
  - The deliberate exception: endpoints whose Allow depends on resource **attributes** authorize explicitly in the body with `authz.require(..., attributes)` inside the transaction, and carry `@AuthorizationNotRequired` stating why. The interceptor runs before the resource is loaded, and a condition over a missing attribute silently drops a **Deny**. Do not "tidy" one of those into the annotation.
  - Listings use `anyAllow()` as the pre-gate plus `AuthorizationService#filterAllowed` per item.

### API patterns

- REST described by OpenAPI. **The spec is hand-written** (`docs/api/openapi.yaml`) and `scripts/check-openapi-coverage.sh` compares it against the controllers **in both directions** — a route the spec omits fails, and so does a spec path with no controller behind it. It runs in its own `openapi-coverage` CI job, inside `ci-gate`. springdoc is on the classpath for cross-checking, not as the source: do not replace the file with a generated dump, because the hand-written descriptions are part of the deliverable.
- JSON in `camelCase` on the public API.
- Standardized errors — the shape is the `ErrorResponse` record, mirrored in `docs/api/examples/error.example.json`. All five fields are always present; `details` is empty except on validation failures, where it carries `{field, issue}` pairs:

```json
{
  "code": "MEETING_NOT_FOUND",
  "message": "Meeting not found or outside user scope.",
  "traceId": "01HXYZABCDEFGHIJKMNPQRSTUV",
  "timestamp": "2026-05-02T14:55:01Z",
  "details": []
}
```

- Default pagination: `page`, `size`, `sort`.
- Upload/processing operations return `processingStatus` (`PENDING`/`PROCESSING`/`COMPLETED`/`FAILED`).
- **The operator surface is not the tenant API.** `/admin/platform/**` and `/internal/platform/**` carry no IAM principal at all — `PlatformAdminController` is annotated `@AuthorizationNotRequired(reason = "Control plane: token chain @Order(2), no IAM principal.")` and is gated by Cloudflare Access at the edge plus the console's own fail-closed assertion check (ADR 0023/0025). Do not reach for `authz.require(...)` there; there is no tenant to scope to.

### Testing patterns

- Domain: pure JUnit tests, no Spring container. Example: `PolicyEvaluatorTest`.
- Application: tests with mocked ports.
- Infrastructure: integration tests with Testcontainers (real Postgres).
- API: `@SpringBootTest` or `@WebMvcTest`. Minimum coverage includes denied authorization paths (403/404).

## 6. Database

### Conventions

- Tables in plural `snake_case`: `tenants`, `meetings`, `meeting_analyses`.
- Columns in `snake_case`.
- Primary keys: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()` (`pgcrypto` extension).
- Standard auditable fields:

```sql
id uuid primary key default gen_random_uuid(),
tenant_id uuid not null references tenants(id),
created_at timestamptz not null default now(),
updated_at timestamptz not null default now()
```

- Tenant-bound data **always** has `tenant_id` + a composite index for the search scope.
- `email` uses `CITEXT` (case-insensitive) — `citext` extension (V002:6).

### Multi-tenancy

- Mandatory backend filter + isolation tests (`IamScopingIntegrationTest`). This is the control that always applies.
- Postgres RLS on top of it — **schema delivered in V016**, **full RLS + auth-aware scope in V019/V020** (`tenant_isolation` + `TenantRlsAspect`). Defense in depth over the app filter (ADR 0002).
- **Enforcement state, which differs by environment and is easy to state wrongly:** RLS is **enforced on the deployed stack** since 2026-08-10 — the API connects as `nora_app` (NOBYPASSRLS), the operator aggregate reads through `nora_telemetry` (BYPASSRLS), and `RlsEnforceTelemetryGuard` refuses to boot on a half-applied cutover. It is **off by default in the repository**, so locally the application filter is the only control. Identity and IAM tables are exempt **by design** (V020, ADR 0028): login resolves a user by global e-mail before any tenant exists. Turning the repository default on is a declared deferral (ADR 0038 §6), not a missing cutover.
- Never fetch a tenant-bound entity by `id` alone; always `(tenant_id, id)`.
- Out-of-scope access returns `403` or `404` depending on the enumeration risk.

### Migrations

- Flyway in the backend.
- Naming: `V001__create_tenants.sql`, `V002__create_users_and_roles.sql`, etc.
- **A migration is never edited after being applied** — always create a new version (forward-only).
  - **One migration has broken this rule, on purpose, and it is documented in its own header.** `V027__composite_fk_iam_user_attachments.sql` was edited after being applied, because the failure it fixes happens *while V027 runs*, so a follow-up migration would never get the chance to execute. The price is a Flyway checksum mismatch: any database that ran the earlier body needs one `flyway repair` (or a rebuild) before the API will boot. Cite this as the exception it is — not as permission.
- See `docs/engineering/data-model.md` for the full migration map (**V001–V032**; highlights since V017: V017 Customer Confidence, V018 invitation token hash, V019/V020 full RLS + auth-aware scope, V021 `meeting_embeddings`, V022 chat sessions, V023 Flows, V024–V026 OAuth integration connections and the provider CHECK growing to nine, V027 the composite IAM FK, V028 the company-context history, V029 the inbound MCP credential, V030 the trends panel's completion timestamp, V032 the run state of the scheduled Flows trigger). Single source of truth for the schema. The control plane has its own separate line under `services/api/src/main/resources/db/platform/` (ADR 0022).

## 7. NLP Worker — Python/FastAPI

### Organization

```text
services/nlp-worker/src/nora_nlp/
├── main.py                # FastAPI app
├── security.py            # X-Internal-Token check, fail-closed when unconfigured
├── routers/
│   ├── analyze.py         # POST /analyze, /split, /analyze-live — all token-gated
│   └── health.py          # /healthz, /readyz — open on purpose (compose healthcheck)
├── services/
│   ├── pii_shield.py      # deterministic redaction before the LLM
│   ├── baseline.py        # TF-IDF from nlp-baseline
│   ├── llm_analyzer.py    # real LLM pipeline
│   ├── stub_analyzer.py   # deterministic for CI
│   ├── live_analyzer.py   # live incremental analysis
│   ├── stub_live_analyzer.py
│   ├── split_analyzer.py  # multi-meeting transcript split
│   ├── stub_split_analyzer.py
│   └── prompt_utils.py    # loads and renders the versioned prompt files
├── clients/
│   └── llm.py             # adapter OpenAI-compatible (ADR 0004)
├── prompts/               # README.md + meeting-analysis-v1, live-highlights-v1,
│                          #   meeting-split-v1, pii-shield-v1
├── models.py              # MeetingAnalysisV1, AnalyzeRequest/Response, etc.
└── settings.py            # pydantic-settings
```

### Analysis pipeline

1. Receive transcript + metadata (language, format) + tenant_context.
2. **PII Shield** — BR regex (EMAIL/CPF/CNPJ/PHONE/CREDIT_CARD/PERSON_NAME). See `services/pii_shield.py`.
3. Normalize text, generate a TF-IDF baseline for interpretability.
4. Retrieve vector context through the provider-agnostic embedding client, scoring cosine similarity in Java over the tenant's rows (semantic search delivered — PR #206, V021). **Not `pgvector`**: the vector is a JSON array in a `TEXT` column and the extension is never created — see the Search/RAG row in §2.
5. Call the LLM with a versioned prompt and strict `response_format=json_schema`.
6. Validate with Pydantic (`MeetingAnalysisV1`).
7. Return structured JSON to the backend.

### Structured output

Canonical schema in `docs/api/llm-schemas/meeting-analysis-v1.schema.json`. It includes:

- `summary` (markdown)
- `decisions[]`
- `actionItems[]` (title, assignee, dueDate, priority, sourceQuote)
- `risks[]` (severity, category, sourceQuote)
- `opportunities[]` (estimatedValue, category, sourceQuote)
- `topics[]`, `sentimentOverall`
- `productivity` (optional, ADR 0005)
- `customerConfidence` (optional, ADR 0006/0015 — persisted full-stack since #148; emitted only in conversations with a customer/lead)
- `baselineTerms[]` (TF-IDF)
- `piiRedactionApplied`

### Rules

- Prompt **versioned** in `prompts/{version}.md` with `## SYSTEM` and `## USER` sections.
- Every output schema has a test with a valid and an invalid payload (`tests/`).
- Low temperature (0 to 0.3) for analysis.
- Never log the raw transcript with PII.
- An LLM failure produces a controlled error, **not an exposed stack trace**.

## 8. Frontend — Next.js (raw Tailwind)

### Organization

```text
apps/web/src/
├── app/
│   ├── auth/              # login, signup, and (card)/ — invites, password, verify-email
│   ├── (app)/             # chat, dashboard, flows, integrations, meetings, projects,
│   │                      #   settings, tasks
│   ├── api/               # route handlers — today only api/chat (the BFF chat path)
│   ├── globals.css        # tokens + utilities (imported by the root layout)
│   └── layout.tsx, page.tsx, error.tsx, loading.tsx, not-found.tsx, healthz/
├── components/            # no features/ subdivision; four thin groupings + flat files
│   ├── auth/              # auth-screen.tsx + auth.css
│   ├── brand/             # nora-logo.tsx, shader-orb.tsx
│   ├── core/              # app-shell, sidebar sessions, avatar, command palette,
│   │                      #   desktop-update-button
│   ├── landing/           # public landing sections
│   └── *.tsx              # meeting-*, productivity-*, customer-confidence-card,
│                          #   policy-editor (Monaco) + policy-form-editor (the form
│                          #   half of the same document), invitation-card,
│                          #   markdown-content
├── lib/
│   ├── api/               # client.ts + types.ts (typed fetch)
│   ├── iam/policy-document.ts # policy document <-> form model, both ways (US42)
│   ├── pii/redact.ts      # structured PII redaction in the BFF (ADR 0033)
│   ├── report/markdown.ts # printable meeting report
│   ├── auth.ts, chat-sessions-sync.ts, password-policy.ts, strings.ts, utils.ts
├── fixtures/              # pt-BR API response fixtures used when mocks are on
├── middleware.ts
└── styles/
    ├── tokens.css         # OKLCH palette + typography
    └── components.css     # shared component utilities
```

**There is no shadcn-style `components/ui/`.** Base components are written by hand using Tailwind classes directly.

Two paths in the previous revision of this tree did not exist: `components/ui-primitives/` and `styles/globals.css`. `globals.css` lives under `app/`, and there is no primitives package — the hand-written base elements are inline in the components that use them.

### Rules

- Strict TypeScript (`strict: true`).
- **Validation:**
  - Simple forms: HTML5 constraints plus hand-written checks. **Neither `zod` nor `react-hook-form` is a dependency of `apps/web`.** The previous revision named both — one as "declared but barely used", the other as the fallback for complex forms. `react-hook-form` was never in `apps/web/package.json`; `zod` was, in the original scaffolding, and was dropped in PR #85. Reaching for either is a decision to argue for, not a convention to follow.
  - The backend does the canonical validation (Bean Validation + the worker's JSON Schema). The frontend is UX, not the source of truth.
- Domain components in `components/` — there is no `features/`.
- Global state only when necessary; prefer local state/server data via direct fetch.
- Do not duplicate authorization rules in the frontend as a source of truth. **Conditional rendering ≠ authorization.**
- Enterprise UI must be dense, clear, operational. No landing-page feel in the dashboard.

### Mocks are opt-in, not the default

**This inverted, and the previous revision documented the old behaviour.** `apps/web/src/lib/api/client.ts` reads `const USE_MOCKS = process.env.NEXT_PUBLIC_USE_MOCKS === 'true'`, so a build that does not set the variable goes against the real API at `NEXT_PUBLIC_API_BASE_URL` (default `http://localhost:8080`). It used to default to `true`, which meant a production build that forgot the variable served hardcoded fixtures; failing fast on a misconfigured API URL is the better failure. To develop against fixtures, set `NEXT_PUBLIC_USE_MOCKS=true` explicitly.

## 9. Testing

| Layer | Minimum tests |
|---|---|
| **Backend** | Pure domain unit tests, integration with Postgres via Testcontainers, integration tests for tenant/scope authorization (`IamScopingIntegrationTest`), WireMock to stub the worker |
| **Worker** | Pipeline unit tests, schema validation (jsonschema), synthetic transcript fixtures in `data/synthetic/` |
| **Frontend** | Two suites, both run by the `web` job. Vitest unit tests (`apps/web/src/**/*.test.ts`) over eight `src/lib` modules — the shared `request()` in `client.ts`, the Markdown report builder, the task-list CSV/Markdown exporter, the BFF PII redaction, the password policy, the trends date/axis helpers and the IAM policy-document conversion; **no page or component test** (ADR 0042). Playwright e2e (`apps/web/e2e/`) against `next start`: security headers, route protection, CSP violations |
| **Operator console** (`apps/admin`) | Lint, typecheck and build in CI. **No tests at all** |
| **Desktop** | `cargo test --locked --all-targets` plus the doc-tests, run by the `desktop-rust` job on `windows-latest`. Owned via CODEOWNERS by `@pollotherunner` (ADR 0027) |
| **Contracts** | Valid JSON examples in `docs/api/examples/` for worker↔API payloads |

### Test coverage targets (audit §12, ADR 0018)

ADR 0018 is accepted and immutable; the targets below are its, unchanged. The **measured** column is not — it is re-taken on every CI run and the figures here are the ones that run last reported.

| Area | Sustained target (ADR 0018) | Measured 2026-08-17 |
|---|---|---|
| **Critical areas** (IAM, Auth, PII, LLM analyzer) | **> 85%** | IAM packages 90.9% instr · Auth/identity packages 93.8% instr · PII shield 96.6% stmt · `llm_analyzer.py` 84.7% stmt |
| Other backend areas | > 60% | overall backend 77.1-77.3% instruction / 78.0-78.1% line (see the repeatability note below) |
| NLP Worker | > 85% | **92.4%** statement over `nora_nlp` |
| **Backend branch coverage** | > 70% | **61.5-61.6%** — still short of the target, by about 8.5 points |
| Web Next.js | ADR 0018's per-page table (auth pages > 50%, dashboard/meeting-detail/tasks > 40%, shared components > 60%) — **not enforced, not met** | **9.54%** statement whole-app; the five gated `src/lib` modules at 96.6% (`redact.ts`), 97.7% (`markdown.ts`), 100% (`tasks-export.ts`), 100% (`password-policy.ts`) and 89.9% (`iam/policy-document.ts`); `client.ts` 32.5%, reported and not gated — it drops every time a wrapper is added, which is why it is not |
| Desktop client | out of scope here (maintained by @pollotherunner) | not measured |

**Where these come from.** `scripts/report-coverage.sh` runs in the `api`, `worker` and `web` CI jobs and prints the figures to the job log and to the run summary page. It reads the report the test run just wrote (`target/site/jacoco/jacoco.csv`, `.coverage`, `apps/web/coverage/coverage-summary.json`) rather than measuring anything itself, so the same command on a workstation gives the same number. Read the current figures there; the date above is when this table was last copied from a run, not a promise about today.

**Three of these are gates. The rest are reports.** `mvn verify` fails on the JaCoCo rule over `PolicyEvaluator` (instruction >= 90%, branch >= 75%); the worker job fails on `--cov-fail-under=90` over `pii_shield`; the web job fails on the per-module `coverage.thresholds` in `apps/web/vitest.config.mts` over `redact.ts`, `markdown.ts`, `tasks-export.ts`, `usage-report.ts` and `password-policy.ts`. Nothing fails on any other row, including the branch-coverage row that misses its target and the 7.7% whole-app web row. The web numbers are **floors deliberately set below the measured rate** (ADR 0042): they exist to catch a regression, not to certify a level, and raising coverage means raising the floor in the same pull request.

**The overall backend figure is not repeatable, and that is measured, not assumed.** Three CI runs of the same branch reported 77.1%, 77.2% and 77.3% instruction (32,083 / 32,128 / 32,138 covered of a constant 41,596), with branch at 61.5-61.6% and line at 78.0-78.1%. Something in the integration suite takes a slightly different path from run to run. The per-area rows above did **not** move: IAM, Auth and `PolicyEvaluator` came out byte-identical all three times, which is the useful half of this finding — the numbers ADR 0018 actually cares about are stable, and the aggregate is the noisy one. Consequence for anyone quoting these: a backend total is good to about ±0.1 point, so a decision that turns on a tenth of a percent is a decision resting on nothing.

**Counters are not interchangeable.** JaCoCo's *instruction* coverage is what the backend gate counts and what the backend figures above use; coverage.py reports *statement* coverage for the worker. A line percentage and an instruction percentage are different numbers for the same code — quoting one under the other's name is how "67%" survived three months without anyone being able to say what it measured.

> **Superseded caveat (2026-05-21), kept as the record of what went wrong:** the previous figures — worker 87% (54 tests), backend 67% line / 53% branch (174 tests), all measured 2026-05-13 — carried the instruction "**re-measure** before quoting them in the pitch" and were quoted for three months without it. The instruction was correct and unenforceable; a CI step replaced it. Also now stale in that caveat: the worker *does* declare `pytest-cov`.

### Definition of done

A story is only DONE when:

- The code is implemented.
- Relevant tests were added/updated (coverage in critical areas reaches the targets).
- Local verification was executed (`mvn test`, `pytest`, `npm run build`).
- No secret or sensitive data in the commit.
- Documentation adjusted if a contract, architecture or scope changed.
- The PR was reviewed by another person **or by AI in review mode** + human validation.

## 10. Git, Issues and PRs

### Branches

- `main`: always stable (deploys are triggered from here).
- `feat/us11-meeting-summary`, `feat/sub-1.10-docs-refresh`, `fix/tenant-scope-leak`, `docs/standards-refresh`.

### Commits

Conventional Commits, **subject and body in English** — for humans and agents alike. Discussion, issues and PR descriptions may be in Portuguese; the commit text may not.

- `feat(api): add meeting upload endpoint`
- `feat(worker): extract action items from transcript`
- `fix(api): enforce tenant scope on meeting lookup`
- `docs(engineering): refresh standards.md to match current code`
- `chore(infra): pin the GHCR image tag the host pulls`

**Write the PR title in English too.** `main` squashes, and the squash takes the commit subject from the **PR title**, so a Portuguese title lands a Portuguese commit on `main`. It has already happened once (`3696e55`), and rewriting `main` to fix it is not available — the branch is `non_fast_forward`. **`scripts/check-pr-title.sh` now checks it in CI**, as the `pr-title` job inside `ci-gate`: Portuguese, Conventional Commits shape, and a 120-character ceiling taken from this repository's own p99 rather than the usual 72, which is the median here. `scripts/check-language.sh` cannot cover this and never could — it scans file names and file contents, and a commit message is neither.

Do not add AI-attribution or co-author trailers. They were removed from this repository on purpose.

### PRs

Each PR contains:

- An English title (see above).
- The related story/issue (or Sub-phase X.Y if it is a structural task).
- What changed (2-3 line summary).
- How it was tested — and if a toolchain is not available locally, say so and let CI answer rather than claiming a command you did not run.
- Security/multi-tenant risks (even if it is "none, visual change").
- Screenshots when it is UI.

`ci-gate` is the aggregating job the `main` ruleset requires; a new CI job blocks nothing until it is in that job's `needs`.

## 11. Use of AI in the project

### Before asking for code

Provide the agent with:

- The backlog Story ID (or Sub-phase).
- Relevant anchored files (`path:line` where applicable).
- The scope of what may change.
- The acceptance criteria.
- The expected test command (`mvn test`, `pytest -q`, etc.).

### Recommended prompt

```text
You are working in the NORA project. Read AGENTS.md and docs/engineering/standards.md.
Implement US## from docs/product/backlog.md only in service X.
Do not change scope outside that story.
Add tests and explain how to validate.
Anchor every technical claim in path:line or an ADR.
```

### Model split

- **Opus models**: architecture, security review, data modeling, critical refactors, contract design, ADRs.
- **Sonnet models**: focused implementation, tests, UI components, CRUD, fixtures, localized documentation.

## 12. ADRs as reference

Durable architectural decisions live in `docs/adr/NNNN-<slug>.md`. Every new feature that makes a hard-to-reverse decision (database, framework, tenancy model, AI format) **must** create an ADR.

**The index lives in exactly one place: [`docs/adr/README.md`](../adr/README.md).** It is the single source of truth for the number, the title and — the part that rots fastest — the status.

This section used to carry a "convenience summary" table of its own. It has been removed rather than refreshed, because a second copy of an index is only ever a source of wrong answers: the copy stopped at **0029** while the repository reached **0041**, and two of the statuses it did list had gone stale in the meantime (0014 is superseded by 0038, and 0009's successor 0035 is itself superseded by 0039). Anyone reading the table for a status would have got a confidently wrong one. A duplicate that has to be maintained by hand, next to the canonical file it duplicates, does not earn its keep.

The three ADRs this document leans on most, if you need a starting point: **0038** (what is in scope and what is closed), **0028** (RLS enforcement, §6 above) and **0018** (coverage targets, §9 above).

**Accepted ADRs are immutable.** A decision that goes stale gets a successor ADR and a `Status: superseded by NNNN` line on the original — never an edit to its body. The procedure is in `docs/adr/README.md`. That immutability is why several accepted ADRs still describe things this document says are gone (a three-platform desktop, Azure, a `docs/security/` folder): they are accurate for their date, and the reduction is recorded in the successor.

When to create an ADR:

- A decision that is hard to reverse (database, framework, tenancy model, AI format).
- A decision that will surprise whoever arrives later.
- A decision taken after discarding at least **one real alternative**.

## 13. Relevant technical notes (post-initial-MVP updates)

### PII Shield with PERSON_NAME (ADR 0012)

The current version covers EMAIL, PHONE, CPF, CNPJ, CREDIT_CARD, PERSON_NAME (heuristics + a BR list of ~270 names + a negative list of ~90 terms) **and ADDRESS** — seven of the eight values in `packages/shared-contracts/pii-types.json`. ADDRESS was declared debt from ADR 0012 until ADR 0043 implemented it as a deterministic street-type recogniser; it is not NER, so an address with a lower-cased or purely numeric name stays out of scope, and that limit is written in the contract's `x-notes` rather than left to be discovered.

The shield is the only module in the repository that measures its own failure rate. `services/nlp-worker/tests/test_pii_corpus.py` runs a corpus of ~5,900 cases and gates **two** rates — leak and false redaction — because driving the first to zero by redacting more is the failure mode a single number cannot see. Since ADR 0043 each rate carries three constants rather than one: a ceiling (nothing gets worse), a ratchet (the ceiling must be re-tightened when the rate improves, so it cannot bank slack) and a dated goal (which fails the build once the date passes unmet). Anyone changing `pii_shield.py` should read that file's header before the module's.

Implementation: `services/nlp-worker/src/nora_nlp/services/pii_shield.py`. It is the last gate **before analysis** — see the scoping in §1, principle 7, and ADR 0040 for the two exposures that sit outside it.

### Stateful refresh tokens (Sub-phase 1.3 / PR #59)

Login issues two HttpOnly cookies:

- `nora_access` — JWT (15 min default), `SameSite=Lax`, `Path=/`.
- `nora_refresh` — opaque (30 days default), `SameSite=Strict`, `Path=/auth`, so it never leaks on general traffic. Persisted as a SHA-256 hash in `refresh_tokens` (V011). Rotation and reuse detection are ADR 0020 (V014).

`POST /auth/refresh` rotates the access token; `POST /auth/logout` revokes the refresh token from the cookie.

### Productivity Score & Customer Confidence

- **Productivity Score (ADR 0005, Sub-phase 1.8)**: persisted (V012). Opt-in per meeting via `MeetingGoal`. The UI renders `ProductivityScoreCard` only when `productivity` is present.
- **Customer Confidence (ADR 0006/0015)**: **implemented full-stack** in **#148** (2026-05-21). The worker emits the block; the backend persists it (V017) with an authoritative per-account trend (`CustomerConfidenceService`); `GET /meetings/{id}` returns `customerConfidence`; the `CustomerConfidenceCard` UI is in MeetingDetail. **Aggregated Account Health (US50/US51) is WONT** — ADR 0038 §4 closed it, along with the `account_health_snapshots` table that was never migrated. It is not deferred and it is not waiting on ADR 0014, which ADR 0038 supersedes.

### Web CI: aligned on `npm` (resolved 2026-05-21)

The `web` job in `.github/workflows/ci.yml` uses **`npm ci`** (npm cache via `package-lock.json`), consistent with `apps/web/Dockerfile` (`npm ci` → deployed image), the `Makefile` and the committed `package-lock.json`. `apps/web` is an **npm** project (there is no `pnpm-lock.yaml` nor a `packageManager` field).

History: until 2026-05-21 the job used `pnpm install --no-frozen-lockfile`, which **ignored** `package-lock.json` and resolved its own dependency tree — that is, CI validated something potentially different from the artifact the Dockerfile builds and deploys. The previous doc falsely claimed that "PR #73 unified" it on npm. Fixed by aligning CI to npm.

## 14. Expected quality

The bar is high: clean, modular, testable code that is ready to evolve. Using AI speeds up implementation but **does not replace** architecture, clear contracts and human review. NORA must look like a real product from the very first working demo.

## Change history of this document

| Date | Change |
|---|---|
| 2026-08-17 | **The MCP server shipped (US27, ADR 0041)**, so the two places that described it as decided-and-unbuilt — the scope bullet in §2 and the folder-structure note in §3 — now say what is in the tree and where. The `mcp/` folder still does not exist, and the note says why that is the design rather than an omission. |
| 2026-08-17 | **Reconciled against the code and against ADRs 0038-0041.** The "Confirmed stack" table was re-read from the manifests: Next.js was **14**, the pin is **16.3.0**; Spring Boot was **3.3**, the parent is **3.5.16**; JJWT, React, TypeScript and Tailwind were unversioned or stale. **`pgvector` was named as the Search/RAG mechanism in two places** (the stack table and the worker pipeline) and is not in use — V021 stores a JSON array in a `TEXT` column and `EmbeddingService.cosine` scores it in Java. Four scope claims were repointed: SSO is **WONT** (ADR 0038 §4), MCP is **active scope with nothing built** (ADR 0041) rather than "deferred via ADR 0014", aggregated Account Health is **WONT**, and the whole "MVP Scope Decision" paragraph was retired in favour of ADR 0038 §4/§5/§6. **`docs/security/` was listed in the folder tree and has never existed** — no such directory, no threat model, no `lgpd-operations.md`; `git log` records no deletion, so it was planned in Sub-phase 1.10 and never created. Three more paths in the trees did not resolve: `components/ui-primitives/`, `styles/globals.css` and `infrastructure/analysis/WorkerHttpClient`, plus `domain/productivity/`, which is nested under `domain/meeting/`. **Mock data inverted from default to opt-in** and this document still documented the old behaviour. `zod` and `react-hook-form` were presented as available and are not dependencies. The §12 ADR summary table was **deleted, not refreshed** — it stopped at 0029 while the repository reached 0041, and duplicating a canonical index by hand only ever produces wrong statuses. The RLS paragraph now states the enforcement split (enforced on the deployed stack since 2026-08-10, off by default in the repository). The desktop, worker and web trees, the migration map (V021 → V027, with V027's documented immutability exception), the error-response shape and the PII scope were brought to the current code, and the authorization rule now records what the framework enforces: every handler declares `@RequiresPermission` or `@AuthorizationNotRequired`, or the interceptor denies it. **§9's coverage figures are not this revision's work** — C10 replaced them with measured numbers hours earlier and this branch rebased onto that rather than restating it; what was added there is the `apps/admin` and Desktop rows of the minimum-tests table. |
