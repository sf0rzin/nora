# NORA

> Negotiation Observability & Revenue Assistant — plataforma SaaS de inteligência conversacional para reuniões corporativas.

NORA transforma transcrições de reuniões em **resumos, decisões, tarefas, riscos e oportunidades** usando o **contexto da empresa cliente** (produtos, ICP, playbook, concorrentes).

Construído como **produto comercial real** atendendo a parceria **FIAP Challenge 2026 × TOTVS**.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE) [![Status: Deployed in Azure](https://img.shields.io/badge/Status-Deployed_in_Azure-success.svg)](#estado-atual)

---

## Estado atual (2026-05-14)

NORA está **deployado em Azure** com 14 recursos provisionados via Bicep IaC:

```
Web:    https://nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io
API:    https://nora-api-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io
Health: https://nora-api-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io/actuator/health
```

Sub-fases 1.0 a 1.10 entregues. Próxima: **1.11 Demo Polish** (Customer Confidence persistência mínima + UX interna editorial + seed sintético TOTVS + roteiro de demo). Ver [`docs/product/roadmap.md`](docs/product/roadmap.md).

---

## Documentação

Estrutura completa em `docs/`:

```
docs/
├── product/            # Visão de produto, backlog, roadmap, glossário
├── engineering/        # Arquitetura, padrões, data model (Postgres + Oracle)
├── operations/         # Runbook deploy Azure, production-readiness gaps
├── security/           # (em construção) Threat model, checklists LGPD/OWASP
├── challenge/          # Material acadêmico FIAP Challenge 2026
├── api/                # OpenAPI + schemas LLM + exemplos
└── adr/                # 18 ADRs com decisões arquiteturais imutáveis
```

**Ordem de leitura sugerida (~30min):**

1. [`docs/product/vision.md`](docs/product/vision.md) — produto e fronteiras
2. [`docs/product/roadmap.md`](docs/product/roadmap.md) — histórico + futuro
3. [`docs/engineering/architecture.md`](docs/engineering/architecture.md) — fluxos + stack rationale
4. [`docs/adr/README.md`](docs/adr/README.md) — índice das decisões arquiteturais
5. [`docs/product/glossary.md`](docs/product/glossary.md) — termos NORA

**Para operadores:**

- [`docs/operations/azure-deploy.md`](docs/operations/azure-deploy.md) — runbook + 8 pegadinhas Azure for Students
- [`docs/operations/production-readiness-gaps.md`](docs/operations/production-readiness-gaps.md) — gaps dev → prod

**Para AI agents (Claude Code, Copilot, etc.):**

- [`CLAUDE.md`](CLAUDE.md) — contexto + non-negotiables + read-first
- [`.github/copilot-instructions.md`](.github/copilot-instructions.md)
- [`.claude/skills/arquiteto-nora/SKILL.md`](.claude/skills/arquiteto-nora/SKILL.md) — skill polimórfica do papel "Arquiteto NORA"

---

## Estrutura do monorepo

```
apps/web              # Next.js 14 + TypeScript + Tailwind cru (sem shadcn — ADR 0013)
apps/desktop          # Tauri 2 + Rust + Python sidecar (Azure Speech)
services/api          # Spring Boot 3 + Java 21 + DDD + Postgres + Flyway
services/nlp-worker   # FastAPI + Pydantic + PII Shield + LLM agnóstico
packages/nlp-baseline # TF-IDF baseline interpretável (ADR 0010)
infra/bicep           # Bicep IaC pra Azure Container Apps + Postgres + KV + Speech
data/synthetic        # Dataset sintético pra testes (12 transcripts + contextos)
docs/                 # Documentação canônica (ver acima)
.github/              # CI workflows (ci.yml, build-images.yml, deploy-infra.yml)
.claude/              # Skills + agents config pra Claude Code
```

---

## Stack

| Camada | Tecnologia |
|---|---|
| Frontend | Next.js 14.2 + TypeScript 5.6 + Tailwind 3.4 (sem shadcn — ADR 0013) |
| Backend | Java 21 + Spring Boot 3.3.5 + DDD + JPA + Flyway |
| Database | Postgres 16 (Flexible Server B1ms em Azure; modelo Oracle espelhado em `docs/engineering/data-model-oracle.md` para FIAP DB) |
| NLP Worker | Python 3.12 + FastAPI 0.115 + Pydantic 2.9 + OpenAI SDK 1.50 |
| Desktop | Tauri 2 + Rust + sidecar Python (Azure Speech) |
| Cloud | Azure (Container Apps + Postgres Flexible + Key Vault + Speech + Search opcional) |
| CI/CD | GitHub Actions (`ci.yml`, `build-images.yml`, `deploy-infra.yml` OIDC sem secrets) |
| LLM | OpenAI `gpt-4o-mini` default; provider agnóstico via ADR 0004 (suporta Azure OpenAI, Groq, Ollama, etc.) |

---

## Pré-requisitos

- Java 21
- Node.js 20 + npm
- Python 3.12
- Docker / Docker Compose
- Make

---

## Setup Rápido

```bash
# 1. Clonar e criar arquivos de ambiente
git clone <repo-url> nora && cd nora
make env

# 2. Subir infra local (Postgres + Adminer)
make db-up

# 3. Em terminais separados, rodar cada serviço
make api-dev      # backend em http://localhost:8080
make worker-dev   # worker em  http://localhost:8001
make web-dev      # web em     http://localhost:3000
```

Veja todos os comandos:

```bash
make help
```

---

## Como Contribuir

NORA é projeto **operado por Anthony Sforzin (PO) + múltiplos arquitetos Claude**. Colaboração externa é bem-vinda dentro do escopo declarado (ver `docs/product/roadmap.md`).

1. Leia o ADR relacionado em `docs/adr/` antes de propor mudança arquitetural
2. Branches: `feat/sub-X.Y-<slug>` (sub-fase do roadmap), `feat/usZZ-<slug>` (user story), `fix/<slug>` (hotfix), `docs/<slug>` (doc-only), `chore/<slug>` (limpeza)
3. PRs apontam pra `main`. CI deve passar antes de mergear
4. ADRs aceitos são **imutáveis** — pra mudar decisão, crie ADR sucessor
5. Não comite secrets — use `.env.example` pra nomes

---

## Segurança

Reporte vulnerabilidades via e-mail (não issue público). Detalhes em [`SECURITY.md`](SECURITY.md).

---

## Licença

[GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) — ver ADR 0017.

Anthony Sforzin mantém copyright. Dual-licensing comercial disponível mediante contato.
