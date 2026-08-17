# Engineering Standards — NORA

> Operational guide for humans and AI agents programming NORA.
> Defines conventions, structure, patterns and tooling. Updated to reflect the **actual state of the code** — not promises.

## 1. Engineering Principles

1. **Vertical slice before expansion.** Delivering one small complete flow > opening several incomplete fronts.
2. **Multi-tenant from the first commit.** Any customer data is born with a `tenant_id`. There is no shortcut.
3. **Authorization in the backend.** Filtering in the frontend is UX; real security lives in `AuthorizationService` + `PolicyEvaluator`.
4. **Contract before implementation.** When frontend, backend and worker interact, the contract comes first (OpenAPI + JSON Schema + examples).
5. **AI with structured output.** The LLM never returns free text to the application; always strict JSON Schema validated by Pydantic (ADR 0003).
6. **Horizontal product.** Zero hardcoded rules for TOTVS. Each tenant configures its own context.
7. **Security by default.** PII is redacted before any external LLM call (ADR 0012).
8. **Living documentation.** Durable decision → `docs/adr/`. Transient detail → issue/PR/private vault.

## 2. Confirmed stack

| Layer | Stack | Pattern |
|---|---|---|
| **Web** | Next.js 14 + TypeScript 5 + React 18 + Tailwind CSS 3 (raw — **no shadcn**, no MUI, no Chakra) | App Router, RSC when it makes sense, client components only for interaction |
| **Backend** | Java 21 + Spring Boot 3.3 + JPA + Flyway | Layered DDD (domain/application/infrastructure/api), REST + OpenAPI, Bean Validation |
| **NLP Worker** | Python 3.12 + FastAPI + Pydantic 2 + OpenAI SDK 1.50 | Small pipelines, explicit schemas, prompts versioned in `prompts/{version}.md` |
| **Database** | Postgres 16 + Flyway | Versioned migrations `V###__name.sql`, `tenant_id` on every tenant-bound table |
| **AI** | LLM-agnostic via env vars (default OpenAI `gpt-4o-mini`; Azure OpenAI in Enterprise) | Strict JSON Schema, low temperature, logs without PII. ADR 0004. |
| **Search/RAG** | pgvector + provider-agnostic HTTP embedding client (Gemini/OpenAI) | Semantic search delivered (PR #206, V021 `meeting_embeddings`); Core chat consumes `/meetings/search` as RAG context |
| **Auth** | JWT (JJWT 0.12) + stateful refresh tokens (V011); HttpOnly cookies | SSO Entra ID/SAML post-MVP |
| **Desktop** | Tauri 2 + Rust | Native audio capture; ADR 0008. On-device Whisper is the only transcription path (ADR 0035). The Python sidecar and the `stt-azure` feature were deleted, so there is no second backend to keep compiling. |
| **Infra** | Self-hosted: single bare-metal Ubuntu host, no hypervisor, Docker Compose (ADR 0034/0036) | Cloudflare Tunnel ingress, SOPS + age secrets, pull-based deploy |
| **CI/CD** | GitHub Actions: `ci.yml` + `build-images.yml` + `deploy-host.yml` | Push to GHCR; the host pulls, but rolling forward is still manual — see AGENTS.md |

### MVP Scope Decision

Focus on the **Web + Backend + NLP Worker** slice. Desktop, SSO, audio/video upload, full MCPs and native Salesforce come post-MVP.

## 3. Folder structure

```text
nora/
├── apps/
│   ├── web/                    # Next.js (raw Tailwind)
│   ├── admin/                  # operator console / control plane (ADR 0022-0025)
│   └── desktop/                # Tauri 2 (@pollotherunner)
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
│   ├── synthetic/              # 12 transcripts + 3 contexts (versioned)
│   └── samples/                # small examples
├── notebooks/                  # FIAP Data Science deliverables
├── docs/
│   ├── product/                # vision, backlog (real status), roadmap, glossary
│   ├── engineering/            # architecture, standards (this doc), data-model, data-model-oracle
│   ├── operations/             # host-deploy (runbook + self-hosting pitfalls), production-readiness-gaps
│   ├── challenge/              # FIAP Challenge 2026 (personas, use cases, README, fiap-challenge-2026)
│   ├── security/               # threat model, operational LGPD (delivered — ADR 0029)
│   ├── api/                    # OpenAPI + LLM JSON Schemas + examples
│   └── adr/                    # ADRs (canonical index in docs/adr/README.md)
├── scripts/                    # local automation
├── .github/                    # workflows + templates
├── AGENTS.md                   # context for AI coding agents
└── README.md
```

**Notes about the real structure:**

- **There is no `apps/web/src/features/`** (the previous version of the doc foresaw one). The frontend uses a flat `src/components/` + `src/app/` (App Router).
- **`packages/shared-contracts/`** contains the real shared contracts (`error-codes.md`, `pii-types.json`, `processing-status.json`, `README.md`); full HTTP contracts live in `docs/api/`.
- **MCPs (calendar, tasks, crm)** remain deferred post-MVP via ADR 0014 (defer commercial gate) as a roadmap concept. There is no `mcp/` folder in the monorepo. ADR 0001 (monorepo) mentions the foreseen structure; reactivation is conditional on the first paying tenant asking for an integration.

## 4. Where to store each piece of information

| Information | Location |
|---|---|
| Product vision (Is/Is Not, Does/Does Not, Geoffrey Moore) | `docs/product/vision.md` |
| Prioritized backlog (MoSCoW + real status DONE/PARTIAL/MISSING) | `docs/product/backlog.md` |
| Living roadmap (history of sub-phases 1.0–1.10 + future 1.11+) | `docs/product/roadmap.md` |
| NORA glossary (canonical terms: Productivity Score, Customer Confidence, IAM Policy, etc.) | `docs/product/glossary.md` |
| Technical architecture (DDD layers, end-to-end flows, stack rationale) | `docs/engineering/architecture.md` |
| Data Science pipeline for the TOTVS transcripts (EDA + TF-IDF + LLM) | `notebooks/totvs_transcricoes_eda.py` |
| Technical standards (this doc) | `docs/engineering/standards.md` |
| Postgres data model | `docs/engineering/data-model.md` |
| Oracle data model (FIAP DB deliverable) | `docs/engineering/data-model-oracle.md` |
| Self-hosted deploy runbook + self-hosting pitfalls | `docs/operations/host-deploy.md` |
| Production-readiness gaps (target Sub-phase 1.12) | `docs/operations/production-readiness-gaps.md` |
| FIAP Challenge 2026 academic material (personas, use cases, rubric) | `docs/challenge/` |
| Durable architectural decisions (canonical index) | `docs/adr/NNNN-titulo.md` (index in `docs/adr/README.md`) |
| HTTP contracts | `docs/api/openapi.yaml` (to be generated) or via springdoc-openapi |
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
│   ├── iam/               # IamPolicy, PolicyEvaluator, PolicyStatement
│   ├── meeting/           # Meeting, Participant, ProcessingStatus
│   ├── analysis/          # MeetingAnalysis + children
│   ├── identity/          # User, Email value object, Password
│   ├── tenant/            # Tenant
│   └── productivity/      # MeetingGoal, ProductivityAssessment
├── application/           # use cases, services, ports
│   ├── identity/          # AuthService
│   ├── iam/               # AuthorizationService, IamService
│   ├── meeting/           # MeetingService
│   ├── analysis/          # AnalysisService
│   ├── productivity/      # ProductivityService
│   └── ports/             # interfaces (UserRepository, MeetingRepository, ...)
├── infrastructure/        # adapters: JPA, JJWT, HTTP
│   ├── persistence/jpa/   # entities + repository adapters
│   ├── security/          # JjwtJwtIssuer, JwtAuthenticationFilter
│   └── analysis/          # WorkerHttpClient
└── api/                   # controllers, DTOs, exception handlers
    ├── controllers/       # AuthController, MeetingsController, IamController, ...
    ├── dto/               # request/response records
    ├── security/          # CurrentUser, AuthCookies
    └── exception/         # GlobalExceptionHandler
```

### Inviolable rules

- `domain/` does **not** import anything from Spring, JPA, HTTP, the database or an external SDK.
- `application/` depends on **ports** (interfaces) declared in `application/ports/`.
- `infrastructure/` **implements** the ports (adapters).
- `api/` contains only controllers, DTOs and mappers. **No business rule in a controller.**
- Tenant-bound queries **always** filter by `tenant_id` before `id`.
- Every call with an authorization risk goes through `AuthorizationService.require(...)` or `requireAnyAllow(...)`.

### API patterns

- REST with OpenAPI (auto-generated by springdoc).
- JSON in `camelCase` on the public API.
- Standardized errors:

```json
{
  "code": "MEETING_NOT_FOUND",
  "message": "Meeting not found or outside user scope.",
  "traceId": "..."
}
```

- Default pagination: `page`, `size`, `sort`.
- Upload/processing operations return `processingStatus` (`PENDING`/`PROCESSING`/`COMPLETED`/`FAILED`).
- Administrative endpoints **always** validate authorization via `authz.require(action, resource)`.

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

- MVP: mandatory backend filter + isolation tests (`IamScopingIntegrationTest`).
- Production: Postgres RLS — **schema delivered in V016**, **full RLS + auth-aware scope in V019/V020** (`tenant_isolation` + `TenantRlsAspect`); what remains is the operational cutover/enforcement in prod (runbook in ADR 0026/0028), not the schema. Defense in depth over the app filter (ADR 0002).
- Never fetch a tenant-bound entity by `id` alone; always `(tenant_id, id)`.
- Out-of-scope access returns `403` or `404` depending on the enumeration risk.

### Migrations

- Flyway in the backend.
- Naming: `V001__create_tenants.sql`, `V002__create_users_and_roles.sql`, etc.
- **A migration is never edited after being applied** — always create a new version (forward-only).
- See `docs/engineering/data-model.md` for the full migration map (through V021: V018 invitation token hash, V019/V020 full RLS + auth-aware scope, V021 `meeting_embeddings`). Single source of truth for the schema.

## 7. NLP Worker — Python/FastAPI

### Organization

```text
services/nlp-worker/src/nora_nlp/
├── main.py                # FastAPI app
├── routers/
│   ├── analyze.py         # POST /analyze, /live-analyze
│   └── health.py
├── services/
│   ├── pii_shield.py      # deterministic redaction before the LLM
│   ├── baseline.py        # TF-IDF from nlp-baseline
│   ├── llm_analyzer.py    # real LLM pipeline
│   ├── stub_analyzer.py   # deterministic for CI
│   ├── live_analyzer.py   # live incremental analysis
│   └── stub_live_analyzer.py
├── clients/
│   └── llm.py             # adapter OpenAI-compatible (ADR 0004)
├── prompts/
│   └── meeting-analysis-v1.md
├── models.py              # MeetingAnalysisV1, AnalyzeRequest/Response, etc.
└── settings.py            # pydantic-settings
```

### Analysis pipeline

1. Receive transcript + metadata (language, format) + tenant_context.
2. **PII Shield** — BR regex (EMAIL/CPF/CNPJ/PHONE/CREDIT_CARD/PERSON_NAME). See `services/pii_shield.py`.
3. Normalize text, generate a TF-IDF baseline for interpretability.
4. Retrieve vector context via pgvector + provider-agnostic embedding client (semantic search delivered — PR #206, V021).
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
│   ├── (auth)/            # login, signup, reset
│   ├── (app)/             # dashboard, meetings, tasks, settings
│   └── api/               # route handlers (few)
├── components/            # flat — no features/ subdivision
│   ├── ui-primitives/     # button, input, dialog (hand-written)
│   ├── meeting-*          # meeting cards and forms
│   ├── productivity-*     # ProductivityScoreCard, MeetingGoalForm
│   ├── iam-*              # GroupList, PolicyEditor (Monaco)
│   └── nora-logo.tsx
├── lib/
│   ├── api/               # client.ts + types.ts (typed fetch)
│   ├── auth/              # client-side helpers
│   └── utils.ts
└── styles/
    ├── globals.css        # tokens + utilities
    └── tokens.css         # OKLCH palette + typography
```

**There is no shadcn-style `components/ui/`.** Base components are written by hand using Tailwind classes directly.

### Rules

- Strict TypeScript (`strict: true`).
- **Validation:**
  - Simple forms: HTML5 + `react-hook-form` when necessary.
  - The backend does the canonical validation (Bean Validation + the worker's JSON Schema). The frontend is UX, not the source of truth.
  - **Zod is declared as a dependency but is barely used in the MVP.** When justified (complex schemas, discriminated unions), that's fine. It is not mandatory.
- Domain components in a flat `components/` — there is no `features/`.
- Global state only when necessary; prefer local state/server data via direct fetch.
- Do not duplicate authorization rules in the frontend as a source of truth. **Conditional rendering ≠ authorization.**
- Enterprise UI must be dense, clear, operational. No landing-page feel in the dashboard.

### Mocks as the default in dev

`apps/web/src/lib/api/client.ts` uses `NEXT_PUBLIC_USE_MOCKS=true` by default (also in CI). To point at the real backend, set `NEXT_PUBLIC_USE_MOCKS=false` and `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`.

## 9. Testing

| Layer | Minimum tests |
|---|---|
| **Backend** | Pure domain unit tests, integration with Postgres via Testcontainers, integration tests for tenant/scope authorization (`IamScopingIntegrationTest`), WireMock to stub the worker |
| **Worker** | Pipeline unit tests, schema validation (jsonschema), synthetic transcript fixtures in `data/synthetic/` |
| **Frontend** | Playwright e2e (`apps/web/e2e/`): security headers, route protection, CSP violations, run by the `web` job against `next start`. No unit runner — Vitest is decided and not yet built |
| **Contracts** | Valid JSON examples in `docs/api/examples/` for worker↔API payloads |

### Test coverage targets (audit §12, ADR 0018)

ADR 0018 is accepted and immutable; the targets below are its, unchanged. The **measured** column is not — it is re-taken on every CI run and the figures here are the ones that run last reported.

| Area | Sustained target (ADR 0018) | Measured 2026-08-17 |
|---|---|---|
| **Critical areas** (IAM, Auth, PII, LLM analyzer) | **> 85%** | IAM packages 90.9% instr · Auth/identity packages 93.8% instr · PII shield 96.6% stmt · `llm_analyzer.py` 84.7% stmt |
| Other backend areas | > 60% | overall backend 77.1-77.3% instruction / 78.0-78.1% line (see the repeatability note below) |
| NLP Worker | > 85% | **92.4%** statement over `nora_nlp` |
| **Backend branch coverage** | > 70% | **61.5-61.6%** — still short of the target, by about 8.5 points |
| Web Next.js | TBD | unmeasured — Playwright e2e exists, coverage instrumentation does not |
| Desktop client | out of scope here (maintained by @pollotherunner) | not measured |

**Where these come from.** `scripts/report-coverage.sh` runs in the `api` and `worker` CI jobs and prints the figures to the job log and to the run summary page. It reads the report the test run just wrote (`target/site/jacoco/jacoco.csv`, `.coverage`) rather than measuring anything itself, so the same command on a workstation gives the same number. Read the current figures there; the date above is when this table was last copied from a run, not a promise about today.

**Two of these are gates. The rest are reports.** `mvn verify` fails on the JaCoCo rule over `PolicyEvaluator` (instruction >= 90%, branch >= 75%) and the worker job fails on `--cov-fail-under=90` over `pii_shield`. Nothing fails on any other row, including the branch-coverage row that misses its target.

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

Conventional Commits:

- `feat(api): add meeting upload endpoint`
- `feat(worker): extract action items from transcript`
- `fix(api): enforce tenant scope on meeting lookup`
- `docs(engineering): refresh standards.md to match current code`
- `chore(infra): bump Container App image to sha-abc123`

### PRs

Each PR contains:

- The related story/issue (or Sub-phase X.Y if it is a structural task).
- What changed (2-3 line summary).
- How it was tested.
- Security/multi-tenant risks (even if it is "none, visual change").
- Screenshots when it is UI.

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

Durable architectural decisions live in `docs/adr/NNNN-titulo.md`. Every new feature that makes a hard-to-reverse decision (database, framework, tenancy model, AI format) **must** create an ADR.

The canonical and always up-to-date index of the ADRs is in **`docs/adr/README.md`** (single source of truth); the table below is a convenience summary:

| ID | Decision | Status |
|---|---|---|
| 0001 | Monorepo with folders per application/service | accepted |
| 0002 | Multi-tenancy: app filter in the MVP, RLS in production | accepted |
| 0003 | LLM output via mandatory strict JSON Schema | accepted |
| 0004 | LLM provider strategy (agnostic) | accepted |
| 0005 | Productivity Score (opt-in per meeting) | accepted |
| 0006 | Customer Confidence + Account Health | accepted (persistence: ADR 0015) |
| 0007 | AWS-style IAM (Root + Users + Groups + Policies) | accepted |
| 0008 | Desktop with Tauri 2 + Python sidecar | accepted |
| 0009 | Speech Token Broker (Azure Speech credentials) | superseded by 0035 |
| 0010 | `nlp-baseline` package for PT-BR TF-IDF | accepted |
| 0011 | Invite flow + corporate domain | accepted |
| 0012 | PII PERSON_NAME (BR in the MVP, NER later) | accepted |
| 0013 | Frontend CSS strategy (raw Tailwind, no shadcn) | proposed (Design to refine) |
| 0014 | Defer post-MVP scope (explicitly deferred USs) | accepted |
| 0015 | Customer Confidence minimal persistence in Sub-phase 1.11 | accepted |
| 0016 | Production-readiness backlog (Sub-phase 1.12) | proposed |
| 0017 | License AGPL-3.0 | accepted |
| 0018 | Test coverage targets per critical area | accepted |
| 0019 | Tenant isolation defense-in-depth (RLS + composite FK) | accepted |
| 0020 | Refresh-token rotation + reuse detection | accepted |
| 0021 | Soft-delete on tenant-owned entities | accepted |
| 0022 | Separate platform database + 2nd datasource (control plane) | accepted |
| 0023 | Operator identity (platform admin) separate from per-tenant IAM | accepted (Easy Auth replaced by ADR 0025) |
| 0024 | Dynamic model catalog + router by modality | accepted |
| 0025 | Operator identity v2: Cloudflare Tunnel + Access | accepted |
| 0026 | Full RLS, versioned role provisioning and cutover | partially superseded by 0028 |
| 0027 | Branch protection on `main` + mandatory CI gate | accepted |
| 0028 | Auth-aware RLS enforcement: scope by data and cutover | accepted |
| 0029 | Operational LGPD: right to be forgotten + retention | accepted |

When to create an ADR:

- A decision that is hard to reverse (database, framework, tenancy model, AI format).
- A decision that will surprise whoever arrives later.
- A decision taken after discarding at least **one real alternative**.

## 13. Relevant technical notes (post-initial-MVP updates)

### PII Shield with PERSON_NAME (ADR 0012)

The current version covers EMAIL, PHONE, CPF, CNPJ, CREDIT_CARD **and** PERSON_NAME (heuristics + a BR list of ~270 names + a negative list of ~80 terms). ADDRESS is out of MVP scope.

Implementation: `services/nlp-worker/src/nora_nlp/services/pii_shield.py`.

### Stateful refresh tokens (Sub-phase 1.3 / PR #59)

Login issues two HttpOnly cookies:

- `nora_access` — JWT (15 min), `SameSite=Lax`, `Path=/`.
- `nora_refresh` — opaque UUID (30 days), `SameSite=Strict`, `Path=/auth`. Persisted as a SHA-256 hash in `refresh_tokens` (V011).

`POST /auth/refresh` rotates the access token; `POST /auth/logout` revokes the refresh token from the cookie.

### Productivity Score & Customer Confidence

- **Productivity Score (ADR 0005, Sub-phase 1.8)**: persisted (V012). Opt-in per meeting via `MeetingGoal`. The UI renders `ProductivityScoreCard` only when `productivity` is present.
- **Customer Confidence (ADR 0006/0015)**: **implemented full-stack** in **#148** (2026-05-21). The worker emits the block; the backend persists it (V017) with an authoritative per-account trend (`CustomerConfidenceService`); `GET /meetings/{id}` returns `customerConfidence`; the `CustomerConfidenceCard` UI is in MeetingDetail. Aggregated Account Health (US50-51) remains deferred (ADR 0014).

### Web CI: aligned on `npm` (resolved 2026-05-21)

The `web` job in `.github/workflows/ci.yml` uses **`npm ci`** (npm cache via `package-lock.json`), consistent with `apps/web/Dockerfile` (`npm ci` → deployed image), the `Makefile` and the committed `package-lock.json`. `apps/web` is an **npm** project (there is no `pnpm-lock.yaml` nor a `packageManager` field).

History: until 2026-05-21 the job used `pnpm install --no-frozen-lockfile`, which **ignored** `package-lock.json` and resolved its own dependency tree — that is, CI validated something potentially different from the artifact the Dockerfile builds and deploys. The previous doc falsely claimed that "PR #73 unified" it on npm. Fixed by aligning CI to npm.

## 14. Expected quality

The bar is high: clean, modular, testable code that is ready to evolve. Using AI speeds up implementation but **does not replace** architecture, clear contracts and human review. NORA must look like a real product from the very first working demo.
