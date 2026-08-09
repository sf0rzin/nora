---
title: "CLAUDE.md — NORA (contexto principal do projeto)"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# CLAUDE.md — NORA

This file is the main project context for Claude Code and similar AI coding agents. Read it before making code changes.

## Project

NORA (Negotiation Observability & Revenue Assistant) is a SaaS conversational intelligence platform for meetings.

**Core promise:** transform meeting transcripts into summaries, decisions, action items and business intelligence using the customer's own company/product context.

**Primary goal:** strong FIAP Challenge 2026 / NEXT 2026 project that doubles as a production-ready commercial SaaS.

## Read First (in this order)

> **NOTA:** estrutura `docs/` foi reorganizada na Sub-fase 1.10 (2026-05-14). Caminhos antigos (`docs/PROJECT.md`, `docs/development-standards.md`, etc.) **não existem mais**.

1. **`docs/product/vision.md`** — produto e fronteiras
2. **`docs/product/roadmap.md`** — backlog priorizado + histórico de sub-fases + futuro
3. **`docs/engineering/architecture.md`** — fluxos end-to-end + stack rationale + DDD layers
4. **`docs/engineering/standards.md`** — convenções de código e PR
5. **`docs/adr/`** — decisões arquiteturais durables (ver `docs/adr/README.md` para o índice canônico de ADRs)
6. **`docs/product/glossary.md`** — termos NORA (Productivity Score, Customer Confidence, etc.)

Para contexto operacional (deploy self-hosted, runbooks):

7. **`docs/operations/proxmox-deploy.md`** — runbook de deploy na VM Proxmox + as 9 armadilhas do self-hosted (**substitui o `azure-deploy.md`**)
8. **`docs/operations/azure-decommission.md`** — ordem segura de desligamento da Azure (resgate dos dados → DNS → delete do RG)
9. **`docs/operations/production-readiness-gaps.md`** — gaps de prod-readiness (os ancorados em Azure foram parcialmente substituídos pelo ADR 0034)
10. **`docs/operations/azure-deploy.md`** — **histórico.** Runbook da era Azure + as 8 armadilhas do Azure for Students. Não operar por ele

Para contexto acadêmico (FIAP Challenge):

11. **`docs/challenge/fiap-challenge-2026.md`** — contexto FIAP, rubrica, deadlines
12. **`docs/challenge/personas-e-mapa-de-empatia.md`** — 3 personas + mapa de empatia
13. **`docs/challenge/diagrama-casos-de-uso.md`** — UML casos de uso

## Operating multiple architects

NORA is operated by the **Stratfy team (PO) + multiple Claude instances** running the `arquiteto-nora` skill. Each architect has a declared specialization (Tech Lead, Design, etc.) and a dedicated folder in the Obsidian vault.

Cross-architect coordination happens **async via the Obsidian vault** at `Claude/50-coordenacao-arquitetos/`. The Stratfy team (PO) is always CC.

See `Claude/50-coordenacao-arquitetos/00-papeis.md` (Obsidian vault) for current roles and `Claude/50-coordenacao-arquitetos/CURRENT-STATE.md` for active PRs / blockers.

## Current scope

NORA is **migrating off Azure to a self-hosted Proxmox VM** (ADR 0034, 2026-08-07).
Production on Azure went **down** (522 on `nora.systems` / `api.nora.systems`; the Azure
for Students subscription was most likely deactivated). Rescuing the Postgres data is the
top priority — see `docs/operations/azure-decommission.md`. Stack:

- **Web + Backend + NLP Worker + Desktop** vertical slice all functional
- **Backend** is Spring Boot 3 (Java 21) + Postgres 16 (`pgvector/pgvector:pg16` container) + Flyway, with **IAM AWS-style** (Root + Users + Groups + Policies) and **multi-tenancy** via `tenant_id` filter (ADR 0002) + RLS (ADR 0026/0028, **three** roles: `nora_app`, `nora_telemetry`, admin/owner)
- **NLP Worker** is FastAPI (Python 3.12) with **PII Shield** (PERSON_NAME + EMAIL + CPF + CNPJ + PHONE + CREDIT_CARD per ADR 0012) and **JSON Schema strict** LLM output (ADR 0003) via **provider-agnostic client** (ADR 0004, default OpenAI `gpt-4o-mini`)
- **Web** is Next.js 14 + TypeScript + **Tailwind cru, no shadcn** (ADR 0013) with editorial palette OKLCH + Inter + Instrument Serif fonts
- **Desktop** is Tauri 2 + Rust with **Whisper STT running on-device** (ADR 0035 — the Python sidecar and the Azure Speech token broker are both removed) — operated by a separate collaborator
- **Infra** is `infra/proxmox/docker-compose.yml` (compose project `nora`) on a single Debian VM: **Cloudflare Tunnel as the only ingress** (no inbound port), Caddy routing by Host, secrets in **SOPS + age**, observability via OTel Collector + Prometheus + Loki + Grafana. **Deploy is PULL** (`deploy-proxmox.yml` publishes an immutable release pointer; the host pulls) — never push, because the repo is public (ADR 0017)
- `infra/bicep/` is **legacy** — the Azure infra it describes is being torn down

For up-to-date status of each backlog story, see `docs/product/backlog.md` (DONE / PARTIAL / MISSING per US).

## Stack (versões verificadas)

| Componente | Versão |
|---|---|
| Java | 21 |
| Spring Boot | 3.3.5 |
| Postgres | 16 (`pgvector/pgvector:pg16`; extensão pgvector **disponível mas não criada** — ver ADR 0034 §escopo excluído) |
| Flyway | herdada de Spring Boot 3.3.5 |
| Python (worker) | >= 3.12 |
| FastAPI | >= 0.115 |
| Pydantic | >= 2.9 |
| OpenAI SDK | >= 1.50 |
| Next.js | 14.2.15 |
| TypeScript | ^5.6 |
| Tailwind CSS | ^3.4 |
| Tauri (desktop) | 2 (STT on-device via `whisper-rs` — ADR 0035) |
| Orquestração | Docker Compose (projeto `nora`, `infra/proxmox/docker-compose.yml`) |
| Ingress | Cloudflare Tunnel (`cloudflared`) + Caddy 2.8 |
| Segredos | SOPS + age (`secrets.env.sops`; chave privada só no host) |
| Observabilidade | OTel Collector 0.115 + Prometheus 3.1 (30d) + Loki 3.3 + Alloy 1.7 + Grafana 11.5 |
| Bicep | **legado** — `infra/bicep/` descreve a infra Azure em desligamento |

Ver `docs/engineering/architecture.md` §1 para a tabela completa com onde verificar cada versão.

## Non-Negotiables (regras invioláveis)

- **Tenant isolation**: `tenant_id` em toda tabela tenant-owned. Filter em backend, nunca só frontend. ADR 0002
- **PII redaction**: PII nunca chega bruto na LLM. PIIShield no worker é último gate. ADR 0012
- **JSON Schema strict** em saída LLM: `response_format=json_schema` (ADR 0003). Pydantic validation no worker
- **LLM provider agnóstico** (ADR 0004): default OpenAI direto, Azure OpenAI futuro
- **DDD layers no backend**: `domain` não conhece Spring/HTTP/SDK. `application` orquestra. `infrastructure` adapta. `api` é fino
- **Sem TOTVS hardcoded** no código de produto. Tenant context é configurável
- **ADRs são imutáveis** uma vez aceitos. Decisão obsoleta? Cria ADR sucessor (ver `docs/adr/README.md`)
- **Defer scope creep**: ADR 0014 declara v1 fechada. 13 US deferidas explicitamente (+ US48/US49 endereçadas via ADR 0015) — sem adicionar novo escopo até pitch FIAP (15/06/2026)
- **Tests**: áreas críticas (IAM, Auth, PII) >85% coverage sustained (ADR 0018)
- **Não comitar secrets**. Use `.env.example` para nomes de variáveis

## Como trabalhamos

- **Implementar uma sub-fase ou story por branch.** Naming: `feat/sub-X.Y-<slug>` ou `feat/usZZ-<slug>` ou `fix/<slug>` ou `docs/<slug>`
- **Mensagens de commit em inglês** — subject e corpo —, mantendo Conventional Commits: `type(scope): subject (#PR)`. Vale para humanos e agentes. Discussão, issues e PR descriptions seguem livres em português; a regra é só o texto do commit. O histórico anterior a 2026-08-09 é misto e fica como está — não reescrever
- **Referenciar IDs** (US##, Sub-fase 1.X, ADR NNNN, PR #) em commits e PR descriptions
- **Antes de editar**, inspecionar os padrões existentes no módulo alvo (Grep/Glob)
- **Após editar**, rodar o menor comando de verificação relevante (`mvn test`, `pytest`, `npm run typecheck`, `docker compose -f infra/proxmox/docker-compose.yml config`) e reportar passou/falhou
- **Atualizar docs** quando código diverge: doc é parte do produto, não acessório
- **Obsidian vault** é obrigatório para mudanças não-triviais (ver skill `arquiteto-nora`)

## AI Collaboration Pattern (subagentes)

Para tarefas grandes, divida em fatias implementáveis paralelas. Use a skill `arquiteto-nora` para:

1. **Entender** (ler `MEMORY.md` + `CURRENT-STATE.md` + docs relevantes)
2. **Decidir** (apresentar 1-3 abordagens + recomendar)
3. **Quebrar** em fatias despacháveis (independentes ou sequenciais declaradas)
4. **Pedir autorização** à Stratfy (PO) antes de dispatchar subagent que escreve código
5. **Dispatchar** com brief autocontido (`Agent` tool)
6. **Revisar** o diff (não confiar no resumo)
7. **Documentar** no Obsidian + atualizar memory + sugerir ADR se decisão durável faltou registro

Use **modelos Opus** para arquitetura, modelo de dados, revisão de segurança e refactors. Use **modelos Sonnet** ou subagentes para implementação focada, testes, componentes de UI e fluxos CRUD mecânicos.

## Histórico de mudanças deste arquivo

| Data | Mudança |
|---|---|
| 2026-08-07 | Migração Azure → Proxmox (ADR 0034) e STT local (ADR 0035): "Current scope", tabela de Stack e ponteiros de `docs/operations/` atualizados. `azure-deploy.md` passa a ser histórico; `proxmox-deploy.md` e `azure-decommission.md` entram no lugar |
| 1.0 / 2026-06-06 | Arquiteto NORA (Tech Lead): Reconciliação doc × código + padronização (auditoria pré-apresentação) |
| 2026-05-14 | Reescrito durante Sub-fase 1.10 (Docs Refresh): nova estrutura `docs/` em subpastas (product/engineering/operations/challenge/security), referências atualizadas, ADRs novos linkados, estrutura multi-arquiteto documentada |
| (anterior) 2026-05-02+ | Versão original criada com scaffolding inicial |
