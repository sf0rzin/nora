# NORA

[![CI](https://github.com/sf0rzin/nora/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sf0rzin/nora/actions/workflows/ci.yml)

Conversation intelligence for meetings: NORA turns a transcript into what the meeting actually produced.

## What it does

You give NORA a meeting transcript. It returns a summary, the decisions that were made, the action items with their owners, and the risks and opportunities it found. The analysis runs against the customer's own context — their products, their ideal customer profile, their competitors — so the output reads like someone who knows the account wrote it, rather than a generic summariser.

Two derived measures sit on top of that: a Productivity Score for how well a meeting met its stated goal, and a Customer Confidence signal tracked across meetings for an account. Both are defined in the [glossary](docs/product/glossary.md).

The web application is chat-first. You ask NORA about your meetings, action items and accounts, and it answers with streaming responses and semantic search over the meetings themselves. Alongside the chat there is a chronological inbox and a per-meeting detail view, a visual builder for automations that fire when an analysis completes, and a separate operator console for the model catalogue and AI cost telemetry.

Personally identifiable information never reaches the language model in the clear. A redaction gate in the NLP worker replaces names, e-mail addresses, phone numbers, CPF, CNPJ and card numbers with placeholders before any provider call, and the model's output is validated against a strict JSON schema.

## Current state

The stack runs on a single Azure Linux VM under Docker Compose, with Cloudflare Tunnel as the only ingress (no inbound port besides SSH) and secrets encrypted with SOPS and age. Two earlier ADRs described this differently and both are corrected by [ADR 0051](docs/adr/0051-the-substrate-is-an-azure-vm.md): ADR 0034's exit from Azure was real for the managed services — Container Apps, Key Vault, App Insights and the Bicep IaC are gone and stay gone — but the sentence "there is no subscription" was never true of Azure itself; and ADR 0036's bare-metal claim failed its own test when `systemd-detect-virt` returned `microsoft` on the machine serving `nora.systems`. What survives from both is everything that actually matters to the code: one machine, one Compose file, pull-based deploy, tunnel-only ingress.

Web, API and NLP worker are a working vertical slice, and the desktop client captures audio and transcribes it on-device.

Postgres row-level security **is enforced on the deployed stack** since 2026-08-10: the API connects as `nora_app`, which is `NOBYPASSRLS` and owns nothing, so the policies apply to it and the database refuses a cross-tenant read even if a query forgets its `tenant_id` predicate. The operator console reads through a separate `BYPASSRLS` role for its cross-tenant aggregate. The API refuses to start if that cutover is only half applied — including when the connection still bypasses RLS, which it checks by asking the database rather than trusting a flag.

It is **off by default in the repository** (`NORA_RLS_ENFORCE` defaults to `false`), so a local `make dev` still connects as the owner and the application-layer filter is the only control there. Identity and IAM tables are exempt by design (ADR 0028): login resolves a user by global e-mail before any tenant exists, and RLS with no tenant context would fail that closed.

One thing to know before reading the code as production-ready: the front ends are tested very unevenly. `apps/web` has a Vitest unit suite and three Playwright e2e specs (security headers, route protection, CSP violations); until 2026-08-23 the unit suite covered pure `src/lib` modules only and **no page or component had a test at all**, and most screens still have none, so its whole-app coverage is far below the backend's. `apps/admin` had no test of any kind until the same date.

**This section publishes no coverage percentage, deliberately.** Four documents in this repository once carried four different web figures and three different counts of gated modules, none of them matching `vitest.config.mts`, and every one of them had been correct when it was written. Every CI run measures all four surfaces and prints them — `scripts/report-coverage.sh` — so the last run is the answer and a number copied out of it here is a number that starts decaying immediately. ADR 0042 explains what is gated and what is only reported; the gated list itself lives in the two `vitest.config.mts` files, in `pom.xml` and in the worker's `--cov-fail-under`, and nowhere else.

## Architecture

```
                 ┌──────────────┐       ┌──────────────┐
   Browser   ──▶ │   Web (BFF)  │ ────▶ │     API      │ ──▶ Postgres 16
                 │   Next.js    │       │ Spring Boot  │
                 └──────────────┘       └──────┬───────┘
                                               │ internal HTTP
                                        ┌──────▼───────┐
                                        │  NLP Worker  │ ──▶ LLM/embeddings provider
                                        │   FastAPI    │     (PII redaction at the last gate)
                                        └──────────────┘
```

- **Web** is a backend-for-frontend: provider keys stay server-side and the session is an httpOnly cookie.
- **API** is layered — `domain`, `application`, `infrastructure`, `api` — with multi-tenancy on `tenant_id`, AWS-style IAM (users, groups, policies), and deny-by-default authorization: a handler that declares no permission is refused rather than allowed.
- **Worker** redacts before it calls a provider, and validates what comes back against a JSON schema.

## Repository layout

```
apps/web                   Next.js web application: chat, meetings, flows
apps/admin                 Operator console: model catalogue and AI telemetry
apps/desktop               Tauri 2 + Rust: audio capture and streaming cloud transcription
services/api               Spring Boot backend: domain, IAM, multi-tenancy, Flyway migrations
services/nlp-worker        FastAPI worker: PII redaction, prompting, schema-validated output
packages/nlp-baseline      Interpretable pt-BR TF-IDF baseline
packages/shared-contracts  Error codes, PII types and status values shared across services
infra/docker               Local development stack: Postgres + Adminer
infra/host                 Self-hosted stack: compose, Caddy, cloudflared, observability, secrets
data/                      Synthetic transcripts and samples used by tests
notebooks/                 Data-science pipeline over the meeting transcripts
scripts/                   Repository checks and development helpers
docs/                      Documentation, see below
.github/                   CI/CD workflows and templates
```

## Stack

| Layer | What it is |
|---|---|
| Web and admin | Next.js 16.3 · TypeScript 5.6 · Tailwind CSS 3.4, no component library and no shadcn — that is a decision, ADR 0013, with OKLCH design tokens on top of it |
| Backend | Java 21 · Spring Boot 3.5 · Spring Security · JPA · Flyway |
| Database | Postgres 16. Self-hosted runs the `pgvector/pgvector:pg16` image with the extension available but not created; local development runs plain `postgres:16-alpine` |
| NLP worker | Python 3.12 · FastAPI · Pydantic 2 · provider-agnostic LLM and embeddings client |
| Desktop | Tauri 2 · Rust · streaming speech-to-text over a WebSocket, on a session credential minted by the API (ADR 0039/0045). The provider key never reaches the client |
| Hosting | One Azure Ubuntu VM (ADR 0051), Docker Compose, Cloudflare Tunnel, Caddy, SOPS + age |
| Observability | OpenTelemetry Collector · Prometheus · Loki · Alloy · Grafana |
| CI/CD | GitHub Actions. Deployment is pull-based — nothing pushes to the host. The host's timer follows the published release pointer since 2026-08-23 (`deploy.sh --if-changed --follow-release`), and refuses a pointer whose immutable sibling tag is missing; `deploy.sh --tag sha-<short>` is still there for a deliberate roll-back or roll-forward |
| Model | OpenAI `gpt-4o-mini` by default; the client is provider-agnostic |

## Running it locally

You need Java 21, Maven, Node.js 22, Python 3.12, Docker with Compose, and Make. There is no Maven wrapper in the repository, so `mvn` has to be on your PATH — CI gets it from `setup-java`, which is why nothing here fails without it.

```bash
git clone https://github.com/sf0rzin/nora.git && cd nora
make env
make db-up
```

`make env` creates `.env.local` at the root and for the API, worker, web and desktop, from their `.env.example` files. `make db-up` starts Postgres and Adminer from `infra/docker/docker-compose.yml`; it needs `.env.local` to exist, so run `make env` first.

One default worth knowing about immediately: `apps/web/.env.local` starts with `NEXT_PUBLIC_USE_MOCKS=false`, so the web application calls the backend and the quickstart needs it running. That default was `true` until 2026-08-23, and it did not do what it promised: `USE_MOCKS` is read by exactly two functions in `src/lib/api/client.ts`, so the dashboard and the meeting detail rendered fixtures while eight other screens failed against an API that was not there. Set it to `true` deliberately when you want to look at those two screens with no backend at all.

Then run each service in its own terminal:

```bash
make api-dev
make worker-dev
make web-dev
```

The backend serves on 8080, the worker on 8001, the web application on 3000, and Adminer on 8090.

`make dev` starts all three in the background instead, with logs under `.logs/`; `make dev-stop` stops them without touching the database. `make help` lists every target.

No external credential is needed to bring the stack up. The worker ships with `USE_LLM_STUB=true`, so it answers analysis requests from a local stub at no cost. For real analysis, set `LLM_API_KEY` in `services/nlp-worker/.env.local` and turn the stub off; that file documents how to point the same client at OpenAI, Azure OpenAI, Groq, OpenRouter or a local Ollama.

`make admin-dev` starts the operator console; it installs its dependencies first, the same way `make web-dev` does. It serves on port 3002 and its default is the production shape: the real data layer with Cloudflare Access JWT validation on, which on a machine with no `CF_ACCESS_*` set means every page answers 403 naming the two missing variables. Run `NORA_ADMIN_USE_MOCKS=true make admin-dev` for the mock data. The variable used to default the other way, and the point of the change is that forgetting it can no longer serve fabricated data with the identity gate off. It needs no `.env` file, which is why `make env` does not create one for it, and it is deliberately not part of `make dev` — it is a separate concern from the product slice.

For tests, `make api-test` runs the backend suite through `mvn verify`, so the JaCoCo gate runs with it; `make worker-test` runs the worker's; `make web-test` and `make admin-test` run the two Next.js suites with coverage, which is what applies their per-module floors; and `make desktop-test` runs the Tauri crate's `cargo test` plus the TypeScript tests on Node's own runner. `make test` runs all five. The Playwright e2e specs are not in it — they need a production build and a browser download, so run them with `npm run test:e2e` inside `apps/web`.

## Documentation

Start with the [product vision](docs/product/vision.md), then the [architecture](docs/engineering/architecture.md) for how the pieces fit together and why, then the [ADR index](docs/adr/README.md), which is the source of truth for every architectural decision and links to each one. The [backlog](docs/product/backlog.md) records the real per-story status and the [roadmap](docs/product/roadmap.md) records what shipped when.

Four decisions are the ones most likely to be got wrong by reading older records: [ADR 0040](docs/adr/0040-pii-scope-analysis-transcription-subprocessor.md) scopes the PII promise to analysis and names transcription as an external subprocessor, [ADR 0043](docs/adr/0043-pii-address-coverage-and-a-decreasing-corpus-target.md) turns the PII corpus into a decreasing target with a date, [ADR 0046](docs/adr/0046-finish-the-declared-scope.md) is the scope decision in force, and [ADR 0050](docs/adr/0050-the-landing-page-states-what-the-code-does.md) is why the public page describes less than it used to.

For operating it: the [deployment runbook](docs/operations/host-deploy.md) is the current one, and [production-readiness-gaps.md](docs/operations/production-readiness-gaps.md) is an honest list of what is not ready.

`docs/challenge/` holds the FIAP NEXT Challenge 2026 material. `AGENTS.md` is the context file for AI coding agents.

## Security

Report vulnerabilities by e-mail rather than in a public issue. Details and expected timelines are in [SECURITY.md](SECURITY.md).

## License

[GNU Affero General Public License v3.0](LICENSE). Commercial licensing is available on request.

This is a single-maintainer repository, and issue creation is restricted, so there is no contribution process to point you at. If you have found a bug or want to use this, e-mail is the way in.
