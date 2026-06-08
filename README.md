---
title: "NORA — Negotiation Observability & Revenue Assistant"
owner: Equipe Stratfy
status: approved
version: 2.0
last_reviewed: 2026-06-06
---

# NORA

> Negotiation Observability & Revenue Assistant — plataforma SaaS de inteligência
> conversacional para reuniões corporativas.

NORA transforma transcrições de reuniões em **resumos, decisões, tarefas, riscos e
oportunidades**, usando o **contexto da empresa cliente** (produtos, ICP, playbook,
concorrentes). É construída como **produto comercial real**, atendendo também à parceria
**FIAP Challenge 2026 × TOTVS**.

[![License: AGPL v3](https://img.shields.io/badge/License-AGPL_v3-blue.svg)](LICENSE)
[![Status: Deployed in Azure](https://img.shields.io/badge/Status-Deployed_in_Azure-success.svg)](#estado-atual)

---

## Estado atual

NORA está **implantada em Azure** (via Bicep IaC), com a vertical Web + API + Worker NLP
+ Desktop funcional. O detalhamento por sub-fase e o histórico de entregas vivem no
[roadmap](docs/product/roadmap.md); o status por user story, no
[backlog](docs/product/backlog.md).

```
Web:    https://nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io
API:    https://nora-api-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io
Health: https://nora-api-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io/actuator/health
```

O app **Core** é **chat-first**: o usuário conversa com a NORA sobre reuniões, action
items e projetos. O chat tem **resposta em streaming** e **busca semântica (RAG) por
embeddings** sobre as próprias reuniões, com provider de LLM e de embeddings agnóstico
(ver [ADR 0004](docs/adr/0004-llm-provider-strategy.md)) e chaves 100% server-side num
BFF. Além do chat, há inbox cronológico, detalhe da reunião (resumo, decisões, action
items, riscos e oportunidades), Productivity Score e Customer Confidence.

O NORA conta ainda com um **console de operador (control plane)** para catálogo de
modelos e telemetria de IA — ver [ADRs 0022–0025](docs/adr/README.md). As transcrições
reais da TOTVS são processadas por um pipeline de Data Science em
[`notebooks/`](notebooks/).

---

## Documentação

A documentação canônica vive em `docs/`:

```
docs/
├── product/       # Visão, backlog (status real), roadmap, glossário
├── engineering/   # Arquitetura, padrões, modelo de dados (Postgres + Oracle), contratos
├── operations/    # Runbooks de deploy e operação (Azure, RLS cutover, control plane)
├── challenge/     # Material acadêmico FIAP Challenge 2026
├── api/           # Contratos HTTP + schemas de saída do LLM
└── adr/           # Decisões arquiteturais (índice e contagem em adr/README.md)
```

**Ordem de leitura sugerida:**

1. [Visão do produto](docs/product/vision.md) — o que NORA é e suas fronteiras
2. [Roadmap](docs/product/roadmap.md) — histórico de entregas e próximas sub-fases
3. [Arquitetura](docs/engineering/architecture.md) — fluxos end-to-end e racional da stack
4. [Índice de ADRs](docs/adr/README.md) — decisões arquiteturais (fonte de verdade)
5. [Glossário](docs/product/glossary.md) — termos canônicos da NORA

**Para operadores:** [runbook de deploy Azure](docs/operations/azure-deploy.md) ·
[gaps de prontidão para produção](docs/operations/production-readiness-gaps.md).

**Para agentes de IA (Claude Code, Copilot):** [`CLAUDE.md`](CLAUDE.md) ·
[`.github/copilot-instructions.md`](.github/copilot-instructions.md).

---

## Estrutura do monorepo

```
apps/web              # Next.js 14 + TypeScript + Tailwind sem shadcn (ADR 0013) — app Core chat-first
apps/admin            # Console de operador (control plane): catálogo de modelos + telemetria
apps/desktop          # Tauri 2 + Rust + sidecar Python (captura de áudio via Azure Speech)
services/api          # Spring Boot 3 + Java 21 + DDD + Postgres + Flyway
services/nlp-worker   # FastAPI + Pydantic + PII Shield + cliente de LLM/embeddings agnóstico
packages/nlp-baseline # TF-IDF interpretável em PT-BR (ADR 0010)
packages/shared-contracts # Contratos compartilhados (códigos de erro, tipos de PII, status)
infra/bicep           # Infra como código (Azure Container Apps, Postgres, Key Vault, Speech)
data/                 # Datasets sintéticos e amostras para testes
notebooks/            # Pipeline de Data Science das transcrições TOTVS (parser + TF-IDF + EDA)
docs/                 # Documentação canônica (ver acima)
.github/              # Workflows de CI/CD e templates
```

---

## Stack

| Camada | Tecnologia |
|---|---|
| Frontend | Next.js 14.2 + TypeScript 5.6 + Tailwind 3.4 (sem shadcn — ADR 0013) |
| Backend | Java 21 + Spring Boot 3.3 + DDD + JPA + Flyway |
| Banco de dados | Postgres 16 (Azure Flexible Server). Modelo Oracle espelhado para a disciplina FIAP em [`data-model-oracle.md`](docs/engineering/data-model-oracle.md) |
| Worker NLP | Python 3.12 + FastAPI + Pydantic 2 + cliente de LLM/embeddings agnóstico |
| Desktop | Tauri 2 + Rust + sidecar Python (Azure Speech) |
| Cloud | Azure (Container Apps + Postgres Flexible + Key Vault + Speech) |
| CI/CD | GitHub Actions (`ci.yml`, `build-images.yml`, `deploy-infra.yml` com OIDC, sem secrets) |
| LLM | OpenAI `gpt-4o-mini` como padrão; provider agnóstico (ADR 0004). Embeddings via Gemini/OpenAI |

> As versões exatas e onde verificá-las estão em
> [`docs/engineering/architecture.md`](docs/engineering/architecture.md).

---

## Pré-requisitos

- Java 21
- Node.js 20 + npm
- Python 3.12
- Docker / Docker Compose
- Make

## Setup rápido

```bash
# 1. Clonar e criar arquivos de ambiente
git clone <repo-url> nora && cd nora
make env

# 2. Subir a infraestrutura local (Postgres + Adminer)
make db-up

# 3. Em terminais separados, rodar cada serviço
make api-dev      # backend em http://localhost:8080
make worker-dev   # worker em  http://localhost:8001
make web-dev      # web em     http://localhost:3000
```

Para ver todos os comandos: `make help`.

---

## Como contribuir

NORA é operada pela **equipe Stratfy (PO) + múltiplos arquitetos Claude**. Colaboração
externa é bem-vinda dentro do escopo declarado no [roadmap](docs/product/roadmap.md).

1. Leia o ADR relacionado em [`docs/adr/`](docs/adr/README.md) antes de propor mudança arquitetural.
2. Branches: `feat/sub-X.Y-<slug>`, `feat/usZZ-<slug>`, `fix/<slug>`, `docs/<slug>`, `chore/<slug>`.
3. PRs apontam para `main`. A CI deve passar antes do merge.
4. ADRs aceitos são **imutáveis** — para mudar uma decisão, crie um ADR sucessor.
5. Não faça commit de secrets — use `.env.example` para os nomes das variáveis.

---

## Segurança

Reporte vulnerabilidades por e-mail (não em issue público). Detalhes em
[`SECURITY.md`](SECURITY.md).

## Licença

[GNU Affero General Public License v3.0](LICENSE) (AGPL-3.0) — ver
[ADR 0017](docs/adr/0017-license-agpl-3.md). A equipe Stratfy mantém o copyright.
Licenciamento comercial (dual-licensing) disponível mediante contato.

---

## Histórico do documento

| Versão | Data | Autor | Mudança |
|---|---|---|---|
| 2.0 | 2026-06-06 | Equipe Stratfy | Reescrita como documento-modelo do novo padrão de docs: front-matter, tom profissional, idioma consistente, fonte única de verdade (links em vez de contagens), e reconciliação com o estado real (RAG/embeddings, app admin/control plane, LGPD operacional) |
| 1.x | até 2026-05-28 | Equipe Stratfy | Versões anteriores |
