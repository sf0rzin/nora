# NORA

[![CI](https://github.com/sf0rzin/nora/actions/workflows/ci.yml/badge.svg?branch=main)](https://github.com/sf0rzin/nora/actions/workflows/ci.yml)

Conversation intelligence for meetings: NORA turns a transcript into what the meeting actually produced.

## What it does

You give NORA a meeting transcript. It returns a summary, the decisions that were made, the action items with their owners, and the risks and opportunities it found. The analysis runs against the customer's own context — their products, their ideal customer profile, their competitors — so the output reads like someone who knows the account wrote it, rather than a generic summariser.

Two derived measures sit on top of that: a Productivity Score for how well a meeting met its stated goal, and a Customer Confidence signal tracked across meetings for an account. Both are defined in the [glossary](docs/product/glossary.md).

The web application is chat-first. You ask NORA about your meetings, action items and accounts, and it answers with streaming responses and semantic search over the meetings themselves. Alongside the chat there is a chronological inbox and a per-meeting detail view, a visual builder for automations that fire when an analysis completes, and a separate operator console for the model catalogue and AI cost telemetry.

Personally identifiable information never reaches the language model in the clear. A redaction gate in the NLP worker replaces names, e-mail addresses, phone numbers, CPF, CNPJ and card numbers with placeholders before any provider call, and the model's output is validated against a strict JSON schema.

## Current state

The stack runs on a single self-hosted bare-metal Ubuntu host under Docker Compose, with Cloudflare Tunnel as the only ingress (no inbound port besides SSH) and secrets encrypted with SOPS and age. Azure is gone — there is no subscription, no export and nothing to decommission. ADR 0034 is the decision to leave it, and ADR 0036 corrects the substrate: it is one physical machine, not a VM on a hypervisor. Both are in the [ADR index](docs/adr/README.md).

Web, API and NLP worker are a working vertical slice, and the desktop client captures audio and transcribes it on-device.

Two things to know before reading the code as production-ready. Postgres row-level security is written but **not enforced** — the policies are complete, covering every tenant-owned table, but the application still connects as the table owner and the enforcement flag defaults to off, so tenant isolation currently rests entirely on the application-layer `tenant_id` filter rather than on the two layers the ADRs describe. And `apps/web` has no test suite at all.

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
apps/desktop               Tauri 2 + Rust: audio capture and on-device Whisper transcription
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
| Web and admin | Next.js 16.3 · TypeScript 5.6 · Tailwind CSS 3.4, no component library |
| Backend | Java 21 · Spring Boot 3.5 · Spring Security · JPA · Flyway |
| Database | Postgres 16. Self-hosted runs the `pgvector/pgvector:pg16` image with the extension available but not created; local development runs plain `postgres:16-alpine` |
| NLP worker | Python 3.12 · FastAPI · Pydantic 2 · provider-agnostic LLM and embeddings client |
| Desktop | Tauri 2 · Rust · `whisper-rs` for on-device speech-to-text |
| Hosting | Self-hosted: one bare-metal Ubuntu host, no hypervisor, Docker Compose, Cloudflare Tunnel, Caddy, SOPS + age |
| Observability | OpenTelemetry Collector · Prometheus · Loki · Alloy · Grafana |
| CI/CD | GitHub Actions. Deployment is pull-based: the host fetches an immutable release pointer |
| Model | OpenAI `gpt-4o-mini` by default; the client is provider-agnostic |

## Running it locally

You need Java 21, Maven, Node.js 22, Python 3.12, Docker with Compose, and Make. There is no Maven wrapper in the repository, so `mvn` has to be on your PATH — CI gets it from `setup-java`, which is why nothing here fails without it.

```bash
git clone https://github.com/sf0rzin/nora.git && cd nora
make env
make db-up
```

`make env` creates `.env.local` at the root and for the API, worker, web and desktop, from their `.env.example` files. `make db-up` starts Postgres and Adminer from `infra/docker/docker-compose.yml`; it needs `.env.local` to exist, so run `make env` first.

One default worth knowing about immediately: `apps/web/.env.local` starts with `NEXT_PUBLIC_USE_MOCKS=true`, so the web application renders fixtures and never calls the backend. That makes the UI work before anything else is running, and it also means you can follow every step here, see a working application, and not be looking at your API. Set it to `false` to exercise the real one.

Then run each service in its own terminal:

```bash
make api-dev
make worker-dev
make web-dev
```

The backend serves on 8080, the worker on 8001, the web application on 3000, and Adminer on 8090.

`make dev` starts all three in the background instead, with logs under `.logs/`; `make dev-stop` stops them without touching the database. `make help` lists every target.

No external credential is needed to bring the stack up. The worker ships with `USE_LLM_STUB=true`, so it answers analysis requests from a local stub at no cost. For real analysis, set `LLM_API_KEY` in `services/nlp-worker/.env.local` and turn the stub off; that file documents how to point the same client at OpenAI, Azure OpenAI, Groq, OpenRouter or a local Ollama.

`apps/admin` has no Make target. Run `npm install && npm run dev` inside `apps/admin`; it serves on port 3002 and falls back to mock data unless you set `NORA_ADMIN_USE_MOCKS=false`. It needs no `.env` file, which is why `make env` does not create one for it.

For tests, `make api-test` runs the backend suite and `make worker-test` the worker's. `make test` runs both. There is no web test suite.

## Documentation

Start with the [product vision](docs/product/vision.md), then the [architecture](docs/engineering/architecture.md) for how the pieces fit together and why, then the [ADR index](docs/adr/README.md), which is the source of truth for every architectural decision. The [backlog](docs/product/backlog.md) records the real per-story status and the [roadmap](docs/product/roadmap.md) records what shipped when.

For operating it: the [deployment runbook](docs/operations/host-deploy.md) is the current one, and [production-readiness-gaps.md](docs/operations/production-readiness-gaps.md) is an honest list of what is not ready.

`docs/challenge/` holds the FIAP NEXT Challenge 2026 material. `CLAUDE.md` is the context file for AI coding agents.

## Security

Report vulnerabilities by e-mail rather than in a public issue. Details and expected timelines are in [SECURITY.md](SECURITY.md).

## License

[GNU Affero General Public License v3.0](LICENSE). Commercial licensing is available on request.

This is a single-maintainer repository, and issue creation is restricted, so there is no contribution process to point you at. If you have found a bug or want to use this, e-mail is the way in.
