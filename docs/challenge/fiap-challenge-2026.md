---
title: "FIAP Challenge 2026 — NORA × Parceria TOTVS"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.1
last_reviewed: 2026-06-06
---

# FIAP Challenge 2026 — NORA × Parceria TOTVS

## Contexto

NORA é o projeto desenvolvido pela equipe **Stratfy** (matriculada no curso de Engenharia de Software na FIAP) no contexto do **FIAP Challenge 2026**, em parceria com a TOTVS S.A.

**Diferencial deste projeto:** NORA é construído como **produto comercial real**, não apenas como entrega acadêmica. A rubrica FIAP é tratada como **um dos múltiplos compromissos** que o produto cumpre — paralelo à possibilidade de entrega comercial via TOTVS (Plano A da Stratfy) e à eventual operação como SaaS independente (Plano B).

Esta página documenta:

1. Como NORA atende a rubrica do FIAP Challenge 2026
2. Deadlines e entregas alvo
3. Onde cada peça da rubrica está documentada no repositório

## Rubrica acadêmica

> **NOTA:** itens marcados `?? não conferido` precisam ser validados contra a rubrica oficial publicada pela FIAP. Documento foi escrito com base em entregas históricas FIAP Challenge anteriores; pode necessitar ajustes específicos para a edição 2026.

### Entregas acadêmicas esperadas

| Item da rubrica | Onde está no NORA |
|---|---|
| **Personas e mapa de empatia** | [`personas-e-mapa-de-empatia.md`](personas-e-mapa-de-empatia.md) — 3 personas (Lucas Almeida, Camila Souza, Rafael Costa) |
| **Diagrama de casos de uso (UML)** | [`diagrama-casos-de-uso.md`](diagrama-casos-de-uso.md) — mermaid com 20+ casos de uso |
| **Backlog priorizado (MoSCoW)** | [`../product/backlog.md`](../product/backlog.md) — US01-US51 com status real DONE/PARTIAL/MISSING |
| **Modelo de dados relacional (Postgres)** | [`../engineering/data-model.md`](../engineering/data-model.md) — schema relacional + migrations Flyway aplicadas (fonte canônica do conjunto de migrations) |
| **Modelo de dados Oracle (entrega DB)** | [`../engineering/data-model-oracle.md`](../engineering/data-model-oracle.md) — DDL Oracle 19c+ equivalente ao schema Postgres |
| **Arquitetura técnica (diagramas, fluxos)** | [`../engineering/architecture.md`](../engineering/architecture.md) — DDD layers, IAM flow, RAG pipeline, multi-tenancy |
| **Decisões arquiteturais documentadas** | [`../adr/README.md`](../adr/README.md) — índice canônico de ADRs (decisões durables com contexto + alternativas) |
| **Validação técnica (testes)** | Test coverage real medido (worker 87%, backend 67%) — ver ADR 0018 |
| **Demonstração funcional (deploy)** | NORA deployado em Azure: `https://nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io` |
| **Pitch / apresentação final** | Sub-fase 1.11 cria roteiro de demo de 15-20min |

### Diferenciais técnicos (acima do mínimo de rubrica)

NORA entrega elementos que vão além da rubrica acadêmica típica:

- **IAM AWS-style** (ADR 0007) — modelo de autorização Root + Users + Groups + Policies com Effect/Action/Resource/Condition, próprio. Versionamento de policies + audit trail
- **PII Shield BR-aware** (ADR 0012) — redaction de EMAIL, CPF, CNPJ, PHONE, CREDIT_CARD, PERSON_NAME (lista BR ~270 nomes) antes de chamadas LLM. Compliance LGPD por design
- **Provider LLM agnóstico** (ADR 0004) — abstração que permite trocar OpenAI direto → Azure OpenAI → Whisper local sem mudar pipeline
- **JSON Schema strict obrigatório** (ADR 0003) — saída LLM validada server-side, sem free-form text cross-service
- **Multi-tenancy** (ADR 0002) — filtro de aplicação no MVP + RLS Postgres com schema entregue e scope auth-aware; resta o cutover/enforcement operacional em prod (ADR 0026/0028)
- **Productivity Score opt-in** (ADR 0005) — análise de produtividade da reunião contra objetivo declarado, com disclaimer obrigatório "indicador da reunião, não dos participantes"
- **Customer Confidence** (ADR 0006) — score por reunião com buying signals + objeções, entregue full-stack com trend autoritativo por conta (PR #148)
- **Deploy Azure production-grade** — 8 armadilhas do Azure for Students catalogadas + workflow OIDC sem secrets, 14 recursos provisionados via Bicep IaC
- **Test coverage rigoroso** (ADR 0018) — áreas críticas (IAM, Auth, PII) sustentadas >85%
- **License AGPL-3.0** (ADR 0017) — proteção contra clone-and-compete

## Deadlines

| Marco | Data | Status |
|---|---|---|
| Entrega de modelagem de dados (Oracle) | ?? não conferido | Material em `../engineering/data-model-oracle.md` |
| Apresentação parcial (sprint review) | ?? não conferido | — |
| **Pitch FIAP / NEXT 2026** | **2026-06-15** | **Sub-fase 1.11 (Demo Polish) entrega o material** |
| Entrega final FIAP | ?? não conferido | Sub-fases 1.11 + 1.12 cobrem |

## Equipe Stratfy

- **Stratfy** — equipe responsável pelo NORA (PO + arquitetura técnica + operação). Coordenação via GitHub organização/handle `sys0xFF`. Membros listados como contribuidores no git history.
- **Gabriel Maciel (@pollotherunner)** — colaborador externo no Desktop app (Tauri + sidecar Python). Escopo isolado, fora do core SaaS.
- **Múltiplos Claude rodando skill `arquiteto-nora`** — assistentes técnicos (Tech Lead, Arquiteto Design) operando sob direção da equipe Stratfy.

> Para detalhes sobre a divisão e coordenação multi-arquiteto Claude, ver `Claude/50-coordenacao-arquitetos/00-papeis.md` no vault Obsidian privado da equipe.

## Por que NORA é mais que um trabalho acadêmico

A Stratfy trabalha com **3 cenários estratégicos para NORA pós-pitch**:

- **Plano A** — TOTVS contrata vendo NORA na demo (parceria FIAP × TOTVS, NORA passa de portfolio a oferta concreta de contratação/parceria institucional)
- **Plano B** — SaaS comercial operado pela própria Stratfy (longo prazo, com co-founder de negócio se necessário)
- **Plano C** — Portfolio técnico / posicionamento profissional dos membros (material já existe agora, pronto para publicação)

A rubrica FIAP é a **camada acadêmica visível**; o produto comercial roda em paralelo como código real, deployado, monetizável.

## Próximos passos pré-pitch (15/06)

- **Sub-fase 1.11 — Demo Polish Plano A** (2-3 semanas agentic): polir UX interna (dashboard, meeting detail, tasks, settings) + seed sintético TOTVS realista + roteiro de demo gravado. Itens antes listados aqui já foram entregues: Customer Confidence full-stack (PR #148), remoção do AUTH_FILTER_HARD_CAP (scan em lotes em `MeetingService.listAllForAuthFilter`) e operadores do PolicyEvaluator (StringEquals, StringIn, StringLike, DateGreaterThan, DateLessThan, fail-closed)
- **Sub-fase 1.12 — Production Hardening** (se sobrar tempo pré-pitch): rg-nora-prod separado, monitoring alerts, secrets rotation, cutover/enforcement de RLS em prod. LGPD operacional já entregue (ADR 0029: `DELETE /privacy/meetings/{id}` + RetentionSweeper agendado). **Pode ficar pós-pitch sem prejuízo da demo.**

## Histórico

| Data | Mudança |
|---|---|
| 2026-05-14 | Doc criado na Sub-fase 1.10 (Docs Refresh) consolidando o framing FIAP × TOTVS |
| 2026-06-06 | Arquiteto NORA (Tech Lead) — Reconciliação doc x código + padronização (auditoria pré-apresentação) |
