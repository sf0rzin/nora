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
5. **`docs/adr/`** — decisões arquiteturais durables (ver `docs/adr/README.md` pra índice)
6. **`docs/product/glossary.md`** — termos NORA (Productivity Score, Customer Confidence, etc.)

Pra contexto operacional (deploy Azure, runbooks):

7. **`docs/operations/azure-deploy.md`** — runbook + 8 pegadinhas do Azure for Students
8. **`docs/operations/production-readiness-gaps.md`** — gaps pra promover dev → prod

Pra contexto acadêmico (FIAP Challenge):

9. **`docs/challenge/fiap-challenge-2026.md`** — contexto FIAP, rubrica, deadlines
10. **`docs/challenge/personas-e-mapa-de-empatia.md`** — 3 personas + mapa de empatia
11. **`docs/challenge/diagrama-casos-de-uso.md`** — UML casos de uso

## Operating multiple architects

NORA is operated by **Anthony (PO) + multiple Claude instances** running the `arquiteto-nora` skill. Each architect has a declared specialization (Tech Lead, Design, etc.) and a dedicated folder in the Obsidian vault.

Cross-architect coordination happens **async via the Obsidian vault** at `Claude/50-coordenacao-arquitetos/`. Anthony is always CC.

See `Claude/50-coordenacao-arquitetos/00-papeis.md` (Obsidian vault) for current roles and `Claude/50-coordenacao-arquitetos/CURRENT-STATE.md` for active PRs / blockers.

## Current scope

NORA is **deployed in Azure** as of 2026-05-13. Stack:

- **Web + Backend + NLP Worker + Desktop** vertical slice all functional
- **Backend** is Spring Boot 3 (Java 21) + Postgres Flexible Server + Flyway, with **IAM AWS-style** (Root + Users + Groups + Policies) and **multi-tenancy** via `tenant_id` filter (ADR 0002)
- **NLP Worker** is FastAPI (Python 3.12) with **PII Shield** (PERSON_NAME + EMAIL + CPF + CNPJ + PHONE + CREDIT_CARD per ADR 0012) and **JSON Schema strict** LLM output (ADR 0003) via **provider-agnostic client** (ADR 0004, default OpenAI `gpt-4o-mini`)
- **Web** is Next.js 14 + TypeScript + **Tailwind cru, no shadcn** (ADR 0013) with editorial palette OKLCH + Inter + Instrument Serif fonts
- **Desktop** is Tauri 2 + Rust + Python sidecar for Azure Speech (ADR 0008 + ADR 0009 Token Broker) — operated by a separate collaborator
- **Infra** is Bicep IaC in `infra/bicep/` deployed via `deploy-infra.yml` GitHub Actions workflow with **OIDC federated credentials** (no client secrets)

For up-to-date status of each backlog story, see `docs/product/backlog.md` (DONE / PARTIAL / MISSING per US).

## Stack (versões verificadas)

| Componente | Versão |
|---|---|
| Java | 21 |
| Spring Boot | 3.3.5 |
| Postgres | 16 (Flexible Server B1ms Burstable) |
| Flyway | herdada de Spring Boot 3.3.5 |
| Python (worker) | >= 3.12 |
| FastAPI | >= 0.115 |
| Pydantic | >= 2.9 |
| OpenAI SDK | >= 1.50 |
| Next.js | 14.2.15 |
| TypeScript | ^5.6 |
| Tailwind CSS | ^3.4 |
| Tauri (desktop) | 2 |
| Bicep | builder padrão `az bicep build` |

Ver `docs/engineering/architecture.md` §1 pra tabela completa com onde verificar cada versão.

## Non-Negotiables (regras invioláveis)

- **Tenant isolation**: `tenant_id` em toda tabela tenant-owned. Filter em backend, nunca só frontend. ADR 0002
- **PII redaction**: PII nunca chega bruto na LLM. PIIShield no worker é último gate. ADR 0012
- **JSON Schema strict** em saída LLM: `response_format=json_schema` (ADR 0003). Pydantic validation no worker
- **LLM provider agnóstico** (ADR 0004): default OpenAI direto, Azure OpenAI futuro
- **DDD layers no backend**: `domain` não conhece Spring/HTTP/SDK. `application` orquestra. `infrastructure` adapta. `api` é fino
- **Sem TOTVS hardcoded** no código de produto. Tenant context é configurável
- **ADRs são imutáveis** uma vez aceitos. Decisão obsoleta? Cria ADR sucessor (ver `docs/adr/README.md`)
- **Defer scope creep**: ADR 0014 declara v1 fechada. 14 US deferidas explicitamente — sem adicionar novo escopo até pitch FIAP (12/06/2026)
- **Tests**: áreas críticas (IAM, Auth, PII) >85% coverage sustained (ADR 0018)
- **Não comitar secrets**. Use `.env.example` pra nomes de variáveis

## How To Work

- **Implementar uma sub-fase ou story por branch.** Naming: `feat/sub-X.Y-<slug>` ou `feat/usZZ-<slug>` ou `fix/<slug>` ou `docs/<slug>`
- **Referenciar IDs** (US##, Sub-fase 1.X, ADR NNNN, PR #) em commits e PR descriptions
- **Antes de editar**, inspect existing patterns no módulo alvo (Grep/Glob)
- **Após editar**, rodar o menor comando de verificação relevante (`mvn test`, `pytest`, `npm run typecheck`, `az bicep build`) e reportar passou/falhou
- **Atualizar docs** quando código diverge: doc é parte do produto, não acessório
- **Obsidian vault** é obrigatório pra mudanças não-triviais (ver skill `arquiteto-nora`)

## AI Collaboration Pattern (subagentes)

Pra tarefas grandes, divida em fatias implementáveis paralelas. Use a skill `arquiteto-nora` pra:

1. **Entender** (ler `MEMORY.md` + `CURRENT-STATE.md` + docs relevantes)
2. **Decidir** (apresentar 1-3 abordagens + recomendar)
3. **Quebrar** em fatias despacháveis (independentes ou sequenciais declaradas)
4. **Pedir autorização** ao Anthony antes de dispatchar subagent que escreve código
5. **Dispatchar** com brief autocontido (`Agent` tool)
6. **Revisar** o diff (não confiar no resumo)
7. **Documentar** no Obsidian + atualizar memory + sugerir ADR se decisão durável faltou registro

Use **Opus-style models** para architecture, data model, security review e refactors. Use **Sonnet-style** ou subagentes pra focused implementation, tests, UI components, CRUD flows mecânicos.

## Histórico de mudanças deste arquivo

| Data | Mudança |
|---|---|
| 2026-05-14 | Reescrito durante Sub-fase 1.10 (Docs Refresh): nova estrutura `docs/` em subpastas (product/engineering/operations/challenge/security), referências atualizadas, ADRs novos linkados, estrutura multi-arquiteto documentada |
| (anterior) 2026-05-02+ | Versão original criada com scaffolding inicial |
