# Glossary — NORA

> Canonical vocabulary of the NORA project. Terms in alphabetical order. Each entry includes a definition, scope (where it appears) and a reference (ADR / file / PR) when applicable.
>
> Use this doc when:
> - You are new to the project (human or Claude) and run into an unknown term
> - You are writing a doc/ADR/spec and need the canonical term
> - You are discussing with Stratfy (PO) / the Architects and want to align vocabulary

---

## A

**Account Health Score** — Score aggregated per **account** (not per meeting), expressing the temporal health of the relationship with an Enterprise customer/lead. Scale 0-100 with bands `AT_RISK` / `WATCH` / `HEALTHY` / `STRONG`. Computed from Customer Confidence + risks + opportunities. ADR 0006 accepted; implementation postponed via ADR 0014 (defer post-MVP). Reactivation: post-pilot with 3+ tenants having >10 meetings per account.

**Action Item** — Task automatically extracted from a meeting by the LLM. Fields: `title`, `assignee` (optional), `dueDate` (optional), `priority` (`LOW`/`MEDIUM`/`HIGH`), `sourceQuote` (verbatim quote from the source). Shown in the `tasks/` panel and in the meeting detail. Editable by the user (US24).

**ADR** — Architecture Decision Record. A durable technical decision + context + alternatives considered. **Immutable** once accepted — a successor creates a new ADR, it does not edit the old one. Lean MADR format (Status / Date / Deciders / Context / Decision / Consequences / Alternatives). In `docs/adr/`. Index: `docs/adr/README.md`.

**AUTH_FILTER_HARD_CAP** — An in-memory cap constant that used to limit how many meetings were loaded before IAM filtering. **Removed**: the scan now happens in batches (paginated scan) in `MeetingService.listAllForAuthFilter`, eliminating the debt of empty pages and truncated `totalItems` for tenants with many meetings.

## B

**Band** — Categorical classification of scores into discrete buckets. Standardized bands:
- **Productivity Score** and **Customer Confidence**: `LOW` / `MEDIUM` / `HIGH`
- **Account Health**: `AT_RISK` / `WATCH` / `HEALTHY` / `STRONG`

**BlackHole** — macOS virtual audio driver used by the NORA Desktop to capture system audio (pre-ScreenCaptureKit workaround). Added in PR #37. Contradicts earlier documentation that said "Does not support macOS in the MVP".

**Bucket4j** — Java library (version 8.10.1) used for rate limiting in the backend. Used mainly in `SpeechController` to prevent abuse of the Speech Token Broker (which costs Azure money).

## C

**Container Apps** — Azure service used to host `nora-api-dev`, `nora-worker-dev` and `nora-web-dev`. Single environment `nora-cae-dev`. The worker is internal-only (no inbound ingress exposed).

**Conditions** — In an IAM Policy, optional rules that restrict when an Allow/Deny statement applies. AWS-style format: `{ "stringEquals": { "nora:Department": "sales" } }`. PolicyEvaluator supports `StringEquals`, `StringIn`, `StringLike`, `DateGreaterThan` and `DateLessThan`. Unsupported operators result in `false` (fail-closed).

**Coverage** — (1) In **Productivity Score**, the status of each `expectedOutcome` declared by the user: `ADDRESSED` (fully covered) / `PARTIAL` (partially covered) / `MISSED` (not covered). Each with textual evidence (`sourceQuote`). (2) In **testing**, the percentage of code exercised by the tests — NLP worker 87%, Spring backend 67%, web 0% (no runner).

**Customer Confidence** — Score 0-100 of the **customer's/lead's confidence in the tenant's NORA** (not our confidence in the customer). Per meeting. Combines sentiment + buying signals (`buyingSignals`) + objections (`objections`) + trend relative to the last meeting of the same account. Band `LOW`/`MEDIUM`/`HIGH`. Trend `IMPROVING`/`STABLE`/`DECLINING`. ADR 0006 accepted. **Delivered full-stack (PR #148)**: field emitted by the worker, persisted in the backend and rendered in the web app, with an authoritative per-account trend.

## D

**DDD** — Domain-Driven Design. Architectural pattern used in the Spring backend. Layers:
- **domain**: pure rules (entities, value objects). Does not depend on Spring, DB, HTTP, external SDKs
- **application**: orchestration (services). Coordinates domain + infra
- **infrastructure**: external adapters (JPA repositories, HTTP clients, message senders)
- **api**: HTTP/REST (controllers). Thin — only HTTP↔application translation

**Deny-first eval** — IAM evaluation strategy where an explicit `Deny` wins over an applicable `Allow`. Full order: (1) Root bypass → (2) explicit Deny → (3) applicable Allow → (4) default Deny.

## E

**Effect** — In an IAM Policy, the statement field with value `Allow` or `Deny`. `Deny` wins over `Allow` (see Deny-first eval).

**expectedOutcome** — The user's declaration of what they expected the meeting to resolve. Part of `MeetingGoal`. A list of short strings (e.g., "Decidir se vamos comprar Protheus", "Alinhar pricing com cliente X"). Productivity Score measures the coverage of each one.

## F

**Federated Credential** — Azure OIDC mechanism to authenticate GitHub Actions without storing a client secret. The Service Principal `sp-nora-github-deploy` has 3 separate federated credentials: (main) / (pull_request) / (environment:dev). Lesson: you need one fed cred per (branch, environment) pair.

**Flyway** — Tool for versioned SQL migrations. Migrations in `services/api/src/main/resources/db/migration/V001__*.sql` up to V021 (canonical source: `docs/engineering/data-model.md`). Each migration is immutable once merged into main.

## G

**GHCR** — GitHub Container Registry. Stores the Docker images `ghcr.io/sf0rzin/nora-{api,worker,web}:{latest, sha-XXXXXXX, ref}`. Images are Public (a manual step in the GHCR settings). Built via `build-images.yml`.

## I

**IAM AWS-style** — NORA's IAM model inspired by AWS IAM: **Root** + **Users** + **Groups** + **Policies** with Effect/Action/Resource[/Condition]. **No hardcoded role hierarchy** — the tenant creates its own groups. ADR 0007.

**iam_policy_versions** — Table (migration V006) that keeps **immutable versioning** of policies. Every policy change creates a new version; the old version remains as history. It has `is_template` planned but not yet in V006 (US41 MISSING).

## J

**JJWT** — Java library (version 0.12.6) used to issue and validate JWTs in the backend. Configured in `services/api/pom.xml`.

**JSON Schema strict** — Mandatory validation format for the LLM output. We pass `response_format={"type": "json_schema", ...}` to OpenAI/Azure OpenAI; the provider guarantees the response validates against the schema. Without it, parsing can break. ADR 0003. Canonical schema in `docs/api/llm-schemas/meeting-analysis-v1.schema.json`.

## K

**Key Vault** — Azure service used to store secrets (JWT_SECRET, OPENAI_API_KEY, Postgres ConnectionString, Speech key). Container Apps accesses it via **Key Vault references** with a User-Assigned Identity (UAI). Name in dev: `nora-kv-dev-wgl3a3`. Soft-delete blocks recreation for 7 days (Azure for Students pitfall).

## L

**Live Analysis** — Real-time analysis during a meeting (Desktop captures audio + Python sidecar transcribes + NLP worker returns highlights). Endpoint `POST /meetings/live-analyze`. Schema `LiveHighlightsV1`. PR #65.

**LLM** — Large Language Model. In NORA, the default is OpenAI `gpt-4o-mini` (configurable per tenant via env, provider-agnostic per ADR 0004). In Enterprise it can be Azure OpenAI. Output mandatorily via JSON Schema strict (ADR 0003).

## M

**MeetingGoal** — **Opt-in** user input for computing the Productivity Score. Fields:
- `purpose` (short string) — the meeting's declared purpose
- `expectedOutcomes` (list) — what needed to be resolved/decided
- `projectStateSnapshot` (optional) — project state for context

Submitted via `PUT /meetings/{id}/goal`. ADR 0005.

**MoSCoW** — Prioritization acronym: **M**ust have / **S**hould have / **C**ould have / **W**on't have (v1). Used in the backlog (`docs/product/backlog.md`). 31 Must, 14 Should, 5 Could, 7 Won't in the original MVP.

**Multi-tenancy** — Data isolation between NORA clients (tenants). **In the MVP**: `tenant_id` in every tenant-owned table + a filter in the application layer (Spring). **Defense in depth**: Postgres RLS (schema in V016; full RLS + auth-aware scope in V019/V020) + composite isolation FK (V015). ADR 0002.

## N

**NlpWorker / Worker NLP** — Python FastAPI service in `services/nlp-worker/` that processes transcripts. Pipeline:
1. PII Shield redacts PII
2. TF-IDF baseline extracts important terms (interpretable)
3. LLM analyzes with the prompt + tenant context + strict schema
4. Pydantic validation of the output

Internal-only — only the Spring backend talks to it. Hosted in `nora-worker-dev` (internal Container App).

**Negative list** — List of terms that must **not** be redacted by the PII Shield even though they look like names (e.g., "Apolo" looks like a proper name but is a mythological/common reference in the technical context; proper names of team members that appear in commits and comments likewise). ~80 terms catalogued to reduce false positives. ADR 0012.

## O

**OIDC** — OpenID Connect. Used by the Azure Service Principal (`sp-nora-github-deploy`) to authenticate GitHub Actions without a client secret. Configured via federated credentials in the app registration.

**Outcome** — See `expectedOutcome`.

## P

**packages/nlp-baseline** — Local Python package in `packages/nlp-baseline/` with 3 TF-IDF modules (preprocessing, vectorizer, top_terms). Used by the NLP worker **before** the LLM to extract important terms in an interpretable way. ADR 0010.

**PII** — Personally Identifiable Information. Categories covered by NORA's PII Shield: email, CPF, CNPJ, phone, credit card, PERSON_NAME (BR). Not covered in the MVP: ADDRESS (catalogued debt). ADR 0012.

**PII Shield** — System in the NLP worker that detects and redacts PII **before** sending text to an external LLM. Replaces it with `[[TIPO_N]]` placeholders (e.g., `[[EMAIL_1]]`, `[[CPF_2]]`). After the LLM, the backend can unredact if authorized. ADR 0012. Implementation in `services/nlp-worker/src/.../pii_shield.py` (95% coverage).

**PolicyEvaluator** — Spring component in `services/api/src/main/java/.../PolicyEvaluator.java` that receives a set of policies + context (user, action, resource, attributes) and returns `Allow` / `Deny`. Implements Deny-first eval. Supported operators: `StringEquals`, `StringIn`, `StringLike`, `DateGreaterThan`, `DateLessThan` (unsupported operators result in `false`, fail-closed). Coverage 95.8% instr / 84% branches.

**Productivity Score** — Score 0-100 of the **meeting against the objective declared** by the user themselves (not an external benchmark). **Opt-in** per meeting — without a `MeetingGoal` it is not computed. Band `LOW`/`MEDIUM`/`HIGH`. ADR 0005. Full-stack implementation: dedicated migration (see `docs/engineering/data-model.md`) + worker (model + stub + LLM) + Spring backend + web (`MeetingGoalForm`, `MeetingProductivitySection`, `ProductivityScoreCard`).

## R

**RAG** — Retrieval-Augmented Generation. AI pattern where the LLM prompt is enriched with relevant documents retrieved from a knowledge base. In NORA, used to bring **tenant context** (products, glossary, competitors, stakeholders) into the prompt, plus semantic search over meetings. **Delivered (US15, PR #206)**: provider-agnostic embeddings (Gemini/OpenAI) persisted via pgvector (migration V021) and retrieved by an HTTP embedding client (`EmbeddingService.java`, `HttpEmbeddingClient.java`). The Core chat consumes `/meetings/search` as RAG context. (It does not use Azure AI Search.)

**Refresh token** — **Long-lived** token (30 days, stateful UUID) used to renew the access JWT (15min). Persisted in `iam_refresh_tokens` (migration V011). httpOnly cookie `nora_refresh`. Short access + long refresh = a balanced security pattern.

**rg-nora-dev** — Azure development Resource Group. Subscription `Azure for Students`. Region `centralus`. Contains 14 resources. Estimated cost R$110-180/month.

**rg-nora-prod** — Azure production Resource Group. **Does not exist yet** — creation planned for Sub-phase 1.12 (Production Hardening). Fully isolated from dev.

**RLS** — Row-Level Security. Postgres feature that filters rows by a SQL policy. **Schema delivered in V016** (`tenant_isolation` on 10 tables; predicate `tenant_id = nora.current_tenant_id()`, reading the GUC `nora.current_tenant_id` set by the `TenantRlsAspect`), with **full RLS + auth-aware scope in V019/V020** and a cutover runbook (ADR 0026/0028). What remains is the **operational cutover/enforcement in prod** (role `nora_app` `NOBYPASSRLS` + flag `nora.security.rls.enforce`), not the schema. Defense in depth for the app filter (ADR 0002). (Note: the real GUC is `nora.current_tenant_id`, not `app.tenant_id` as ADR 0002 sketched.)

## S

**Schema strict** — See JSON Schema strict.

**Service Principal** — Azure application identity used to authenticate pipelines/scripts. NORA uses `sp-nora-github-deploy` (appId `3f8b27f6-...`) with the roles `Contributor` + `Role Based Access Control Administrator` on `rg-nora-dev`. Authentication via OIDC (federated credentials).

**Soft-delete** — Default behavior of Key Vault and Cognitive Services (Azure Speech) where deleted resources remain recoverable for 7 days. Blocks recreation with the same name. Fix: `az keyvault purge` / `az cognitiveservices account purge`. Azure for Students pitfalls #4 and #5.

**Speech Token Broker** — Backend endpoint (`SpeechController`) that issues an ephemeral Azure Speech token (~9 minutes) for the Desktop. **Does not expose the Speech key** — only the token. Bucket4j rate limit. ADR 0009.

**Sub-phase** — NORA's unit of work. Numbering `X.Y` (e.g., `1.10`). Each sub-phase = 1+ merged PRs + a coherent, verifiable delivery. A sub-phase closes when the scope is delivered, not when a timer runs out. Full roadmap in `docs/product/roadmap.md`.

## T

**Tenant** — Client/organization that uses NORA. Full isolation guaranteed by `tenant_id` in all tenant-owned tables. Each tenant has its own Users, Meetings, Tasks, IAM Policies, Tenant Context, Refresh Tokens, Audit Events.

**Tenant Context** — Per-tenant configuration that teaches NORA the "company vocabulary". Fields: company name, products, glossary, competitors, stakeholders. Injected into the LLM prompt in every analysis. Edited via `TenantContextController`. Migration V005. US31 (version history) still MISSING.

**TF-IDF baseline** — Term Frequency × Inverse Document Frequency. Classic NLP algorithm that extracts important terms from a document by comparing local frequency vs the corpus. The `packages/nlp-baseline/` package extracts relevant terms pre-LLM (interpretable and cheap). ADR 0010.

## U

**UAI** — User-Assigned Identity. Type of Azure managed identity that is **pre-created** (vs SystemAssigned, which is created with the resource). NORA uses two UAIs (`nora-uai-deploy` and `nora-uai-app`) to resolve the role assignment + KV reference cycle in Container Apps. Without it, ACA tries to access KV before the role assignment has propagated.

**Unredact** — Operation of reverting the PII Shield's `[[TIPO_N]]` placeholders back to the original values. Done by the backend after the LLM response, only if authorized by the request context.

## V

**V001 - V021** — Current Flyway migrations (canonical source: `docs/engineering/data-model.md`). Each one idempotent and immutable, sequentially numbered. Highlights: V013 = soft-delete, V014 = refresh-token rotation, V015 = composite isolation FK, V016 = Row-Level Security (schema), V018 = invitation token hash, V019/V020 = full RLS + auth-aware scope, V021 = `meeting_embeddings` (pgvector). Customer Confidence is persisted (see the **Customer Confidence** entry).

## W

**Wildcard** — In an IAM Policy, `*` in Resource or Action matches anything. Examples:
- `nora:tenant:acme:meeting/*` allows any meeting of the `acme` tenant
- `meeting:*` allows any meeting action (read, write, update, reprocess, analyze:live)
- `*` allows everything (typical use in the Root policy)

Implemented in PolicyEvaluator with glob-style matching.

**WireMock** — Java library (standalone version 3.9.1) used in the backend's integration tests to mock external HTTP responses (NLP worker, Azure Speech, OpenAI). Allows testing without a network.

**Worker NLP** — See NlpWorker.

---

## Document History

| Version | Date | Description |
|---|---|---|
| 1.0 | 2026-05-14 | **Initial creation**. Canonical glossary covering product terms (Customer Confidence, Productivity Score, Account Health, MoSCoW, Tenant), architecture (DDD, RAG, JSON Schema strict, Multi-tenancy, RLS), IAM (IAM AWS-style, Effect, Conditions, Wildcard, Deny-first eval, PolicyEvaluator), Azure infra (Container Apps, Key Vault, UAI, OIDC, Soft-delete, Service Principal, rg-nora-dev), implementation (NlpWorker, PII Shield, TF-IDF baseline, packages/nlp-baseline, Speech Token Broker, Refresh token), and process (ADR, Sub-phase, Flyway/V001-V012, BlackHole, AUTH_FILTER_HARD_CAP). 50+ terms in total |
| 1.1 | 2026-06-06 | NORA Architect (Tech Lead) — Doc x code reconciliation + standardization (pre-presentation audit) |
