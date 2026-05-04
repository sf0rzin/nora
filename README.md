# NORA

> Negotiation Observability & Revenue Assistant — plataforma SaaS de inteligência conversacional para reuniões corporativas.

NORA transforma transcrições de reuniões em **resumos, decisões, tarefas, riscos e oportunidades** usando o **contexto da empresa cliente** (produtos, ICP, playbook, concorrentes). Projeto FIAP Challenge 2026 / NEXT 2026.

---

## Documentação

A documentação canônica está em `docs/`. Leia nesta ordem:

1. [docs/PROJECT.md](docs/PROJECT.md) — visão e arquitetura
2. [docs/visao-do-produto.md](docs/visao-do-produto.md) — visão Agile
3. [docs/personas-e-mapa-de-empatia.md](docs/personas-e-mapa-de-empatia.md)
4. [docs/diagrama-casos-de-uso.md](docs/diagrama-casos-de-uso.md)
5. [docs/backlog-mvp.md](docs/backlog-mvp.md)
6. [docs/development-standards.md](docs/development-standards.md)
7. [docs/data-model.md](docs/data-model.md)
8. [docs/plano-de-execucao.md](docs/plano-de-execucao.md)
9. [docs/adr/](docs/adr/) — decisões arquiteturais
10. [docs/api/](docs/api/) — OpenAPI, exemplos JSON e schemas LLM

Contexto para agentes de IA:

- [CLAUDE.md](CLAUDE.md)
- [.github/copilot-instructions.md](.github/copilot-instructions.md)

---

## Estrutura

```
apps/web              # Next.js + TypeScript + Tailwind + shadcn/ui
apps/desktop          # Tauri (pós-MVP)
services/api          # Spring Boot 3 + Java 21 (DDD)
services/nlp-worker   # FastAPI + Pydantic + LLM provider agnóstico (default OpenAI)
packages/shared-contracts
mcp/{calendar,tasks,crm}   # MCP servers (pós-MVP)
infra/{bicep,docker}
data/synthetic        # Fixtures e demo data
docs/                 # Toda a documentação canônica
.github/              # CI, instruções de IA, templates
```

---

## Pré-requisitos

- Java 21
- Node.js 20 + pnpm 9
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

## MVP

Foco do MVP: **Web + Backend + Worker NLP** com upload de transcrição em texto, contexto por tenant, análise estruturada (resumo, decisões, ações, riscos, oportunidades), dashboard, detalhe da reunião e controle de acesso Enterprise mínimo.

Pós-MVP: Desktop (Tauri), SSO, áudio/vídeo, MCPs completos, Salesforce/HubSpot, custom roles.

---

## Como Contribuir

1. Cada issue vira uma branch (`feat/usXX-...`, `fix/...`, `chore/...`).
2. Antes de codar, leia `docs/development-standards.md` e o ADR relacionado se houver.
3. Abra PR usando o template padrão.
4. CI precisa passar e pelo menos 1 revisão humana.

---

## Status

Discovery, padrões e fundação técnica concluídos. Iniciando vertical slice do MVP.
