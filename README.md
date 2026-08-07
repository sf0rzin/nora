<p align="center">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="docs/assets/nora-logo-dark.svg">
    <img alt="NORA" src="docs/assets/nora-logo-light.svg" width="260">
  </picture>
</p>

<p align="center">
  <strong>Negotiation Observability &amp; Revenue Assistant</strong><br>
  Conversational-intelligence SaaS platform for meetings.
</p>

<p align="center">
  <a href="LICENSE"><img alt="License: AGPL v3" src="https://img.shields.io/badge/License-AGPL_v3-15171a.svg"></a>
  <a href="#current-state"><img alt="Status: Migrating to self-hosted" src="https://img.shields.io/badge/Status-Migrating_to_self--hosted-b26a00.svg"></a>
  <img alt="Java 21" src="https://img.shields.io/badge/Java-21-007396.svg">
  <img alt="Spring Boot 3.3" src="https://img.shields.io/badge/Spring_Boot-3.3-6db33f.svg">
  <img alt="Next.js 14" src="https://img.shields.io/badge/Next.js-14-000000.svg">
  <img alt="Python 3.12" src="https://img.shields.io/badge/Python-3.12-3776ab.svg">
</p>

---

NORA turns meeting transcripts into **summaries, decisions, tasks, risks and
opportunities**, using the **client company's context** (products, ICP, playbook,
competitors). It is built as a **real commercial product**, also serving the
**FIAP Challenge 2026 × TOTVS** partnership.

## Current state

**NORA is being migrated off Azure to a self-hosted Proxmox VM**
([ADR 0034](docs/adr/0034-migracao-azure-para-proxmox.md)).

The Azure deployment is **down**: `nora.systems` and `api.nora.systems` return 522, and the
Container App FQDN does not connect either — the most likely cause is the *Azure for
Students* subscription being deactivated. The public URLs below are therefore **not
serving** until the DNS cutover described in
[`azure-decommission.md`](docs/operations/azure-decommission.md) is done.

The application itself is unchanged: the Web + API + NLP Worker vertical is functional and
runs from the same GHCR images. What changes is the substrate — a single Debian VM with
Docker Compose, Cloudflare Tunnel as the only ingress, and secrets in SOPS + age.

```
Application:  https://nora.systems          (pending DNS cutover)
API:        https://api.nora.systems        (pending DNS cutover)
Health:     https://api.nora.systems/actuator/health
```

The per-sub-phase breakdown and the delivery history live in the
[roadmap](docs/product/roadmap.md); the per-user-story status, in the
[backlog](docs/product/backlog.md).

The **Core** app is **chat-first**: the user talks to NORA about meetings, action
items and projects. The chat has **streaming responses** and **semantic search (RAG) via
embeddings** over the meetings themselves, with a provider-agnostic LLM and embeddings
provider (see [ADR 0004](docs/adr/0004-llm-provider-strategy.md)) and 100% server-side keys in a
BFF. Besides the chat, there is a chronological inbox, the meeting detail (summary, decisions, action
items, risks and opportunities), a Productivity Score and Customer Confidence.

NORA also has an **operator console (control plane)** for the model
catalog and AI telemetry — see [ADRs 0022–0025](docs/adr/README.md). The real
TOTVS transcripts are processed by a Data Science pipeline in
[`notebooks/`](notebooks/).

## Documentation

The canonical documentation lives in `docs/`:

```
docs/
├── product/       # Vision, backlog (real status), roadmap, glossary
├── engineering/   # Architecture, standards, data model (Postgres + Oracle), contracts
├── operations/    # Deploy and operations runbooks (Proxmox, Azure decommission, RLS cutover, control plane)
├── challenge/     # FIAP Challenge 2026 academic material
├── api/           # HTTP contracts + LLM output schemas
└── adr/           # Architectural decisions (index and count in adr/README.md)
```

**Suggested reading order:**

1. [Product vision](docs/product/vision.md) — what NORA is and its boundaries
2. [Roadmap](docs/product/roadmap.md) — delivery history and upcoming sub-phases
3. [Architecture](docs/engineering/architecture.md) — end-to-end flows and stack rationale
4. [ADR index](docs/adr/README.md) — architectural decisions (source of truth)
5. [Glossary](docs/product/glossary.md) — NORA's canonical terms

**For operators:** [Proxmox deploy runbook](docs/operations/proxmox-deploy.md) ·
[Azure decommission](docs/operations/azure-decommission.md) ·
[production-readiness gaps](docs/operations/production-readiness-gaps.md).
The [Azure deploy runbook](docs/operations/azure-deploy.md) is kept as **history**.

**For AI agents (Claude Code, Copilot):** [`CLAUDE.md`](CLAUDE.md) ·
[`.github/copilot-instructions.md`](.github/copilot-instructions.md).

## Architecture

```
                 ┌──────────────┐       ┌──────────────┐
   Browser   ──▶ │   Web (BFF)  │ ────▶ │     API      │ ──▶ Postgres 16
                 │  Next.js 14  │       │ Spring Boot  │
                 └──────────────┘       └──────┬───────┘
                                               │ internal HTTP
                                        ┌──────▼───────┐
                                        │  NLP Worker  │ ──▶ LLM/embeddings provider
                                        │   FastAPI    │     (PII Shield at the last gate)
                                        └──────────────┘
```

- **Web (BFF):** keeps the keys server-side; session via httpOnly cookies.
- **API:** DDD (domain / application / infrastructure / api), multi-tenancy by
  `tenant_id`, AWS-style IAM (Root + Users + Groups + Policies).
- **Worker:** redacts PII before any LLM call and validates the output with
  strict JSON Schema + Pydantic.

## Monorepo structure

```
apps/web                   # Next.js 14 + TypeScript + Tailwind without shadcn (ADR 0013) — chat-first Core app
apps/admin                 # Operator console (control plane): model catalog + telemetry
apps/desktop               # Tauri 2 + Rust — audio capture + on-device Whisper STT (ADR 0035)
services/api               # Spring Boot 3 + Java 21 + DDD + Postgres + Flyway
services/nlp-worker        # FastAPI + Pydantic + PII Shield + provider-agnostic LLM/embeddings client
packages/nlp-baseline      # Interpretable PT-BR TF-IDF (ADR 0010)
packages/shared-contracts  # Shared contracts (error codes, PII types, status)
infra/proxmox              # Self-hosted stack: docker-compose, Caddy, cloudflared, observability, SOPS secrets
infra/bicep                # LEGACY — Azure IaC (Container Apps, Postgres, Key Vault, Speech), being decommissioned
data/                      # Synthetic datasets and samples for tests
notebooks/                 # Data Science pipeline for the TOTVS transcripts (parser + TF-IDF + EDA)
docs/                      # Canonical documentation (see above)
.github/                   # CI/CD workflows and templates
```

## Stack

| Layer | Technology |
|---|---|
| Frontend | Next.js 14.2 + TypeScript 5.6 + Tailwind 3.4 (no shadcn — ADR 0013) |
| Backend | Java 21 + Spring Boot 3.3 + DDD + JPA + Flyway |
| Database | Postgres 16 (`pgvector/pgvector:pg16` container). Oracle model mirrored for the FIAP course in [`data-model-oracle.md`](docs/engineering/data-model-oracle.md) |
| NLP Worker | Python 3.12 + FastAPI + Pydantic 2 + provider-agnostic LLM/embeddings client |
| Desktop | Tauri 2 + Rust with on-device Whisper STT ([ADR 0035](docs/adr/0035-stt-local-whisper-no-cliente.md)) |
| Cloud | **Self-hosted** — single Debian VM on Proxmox + Docker Compose, Cloudflare Tunnel as the only ingress, SOPS + age for secrets ([ADR 0034](docs/adr/0034-migracao-azure-para-proxmox.md)). Previously Azure Container Apps |
| Observability | OTel Collector + Prometheus + Loki + Alloy + Grafana (self-hosted) |
| CI/CD | GitHub Actions (`ci.yml`, `build-images.yml`, `deploy-proxmox.yml` — **pull-based**: the host pulls an immutable release pointer, no runner ever touches it) |
| LLM | OpenAI `gpt-4o-mini` as the default; provider-agnostic (ADR 0004). Embeddings via Gemini/OpenAI |

> The exact versions and where to verify them are in
> [`docs/engineering/architecture.md`](docs/engineering/architecture.md).

## Prerequisites

- Java 21
- Node.js 20 + npm
- Python 3.12
- Docker / Docker Compose
- Make

## Quick setup

```bash
# 1. Clone and create the environment files
git clone <repo-url> nora && cd nora
make env

# 2. Bring up the local infrastructure (Postgres + Adminer)
make db-up

# 3. In separate terminals, run each service
make api-dev      # backend at http://localhost:8080
make worker-dev   # worker at  http://localhost:8001
make web-dev      # web at     http://localhost:3000
```

To see all commands: `make help`.

## How to contribute

NORA is operated by the **Stratfy team (PO) + multiple Claude architects**. External
collaboration is welcome within the scope declared in the [roadmap](docs/product/roadmap.md).

1. Read the related ADR in [`docs/adr/`](docs/adr/README.md) before proposing an architectural change.
2. Branches: `feat/sub-X.Y-<slug>`, `feat/usZZ-<slug>`, `fix/<slug>`, `docs/<slug>`, `chore/<slug>`.
3. Commits follow [Conventional Commits](https://www.conventionalcommits.org/); PRs target `main` and are integrated by squash.
4. CI must pass before merge.
5. Accepted ADRs are **immutable** — to change a decision, create a successor ADR.
6. Don't commit secrets — use `.env.example` for the variable names.

## Security

Report vulnerabilities by email (not in a public issue). Details in
[`SECURITY.md`](SECURITY.md).

## License

[GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) — see
[ADR 0017](docs/adr/0017-license-agpl-3.md). The Stratfy team holds the copyright.
Commercial licensing (dual-licensing) available on request.
