---
title: "Diagnóstico de documentação"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# Diagnóstico de documentação

> Avaliação de **profissionalismo e consistência** de 27 documentos (`README`,
> `CLAUDE.md`, `SECURITY.md`, todo `docs/`). O padrão proposto para corrigir o que está
> aqui descrito está no documento [04 — Guia de estilo e templates](04-guia-de-estilo-e-templates.md).

## Conclusão em uma frase

A documentação do NORA **tem conteúdo de alta qualidade** (arquitetura e ADRs nota
4–5), mas sofre de **drift por congelamento** (os docs pararam em ~2026-05-14/05-28
enquanto a engenharia avançou ~58 PRs e 8 ADRs) e de **inconsistência de tom**
(gírias, emoji-como-status, mistura PT/EN). O problema não é escrever melhor — é
**reconciliar com o código** e **padronizar**.

## Pontuação por documento

> Escala 1–5 (5 = nível produto/enterprise polido).

| Documento | Nota | Principal problema |
|---|---|---|
| `docs/adr/README.md` | 5 | — (é a única fonte correta sobre contagem de ADRs) |
| `docs/engineering/architecture.md` | 5 | Âncoras de migration defasadas (V001–V012) |
| `docs/engineering/data-model.md` | 5 | Declara V001–V017; real é V021 |
| `docs/engineering/contracts/platform-control-plane.md` | 5 | "Congelado" descrevendo Easy Auth, superado por ADR 0025 |
| `docs/product/vision.md` | 5 | "21 ADRs"; RAG/LGPD como futuro (já entregues) |
| `docs/api/README.md` | 5 | Índice de contratos não lista chat/RAG, privacy, control plane |
| `docs/challenge/README.md` | 5 | Histórico congelado (padrão do conjunto) |
| ADRs amostrados (0001, 0014, 0029) | 5 | 0029 é o exemplar de qualidade |
| `README.md` | 4 | "21 ADRs"; estado atual sem RAG/control plane/LGPD |
| `CLAUDE.md` | 4 | Lista de ADRs congelada; cabeçalhos EN em doc PT |
| `SECURITY.md` | 4 | E-mail de contato errado; tooling de repo público em repo privado |
| `docs/product/backlog.md` | 4 | US15 marcada MISSING (RAG já entregue); totais congelados |
| `docs/product/roadmap.md` | 4 | "21 ADRs / V017 / #148"; real 29 / V021 / #206. Emoji + "gambiarra" |
| `docs/engineering/standards.md` | 4 | Tabela de ADRs duplicada e parada em 0021 |
| `docs/product/glossary.md` | 4 | Várias entradas obsoletas (Customer Confidence, Conditions, RAG) |
| `docs/operations/production-readiness-gaps.md` | 4 | Gap 5 (LGPD) "pendente" (já feito); cross-ref errado a ADR 0019 |
| `docs/api/llm-schemas/README.md` | 4 | "Sem markdown nos campos" contradiz `summary (markdown)` |
| `docs/challenge/fiap-challenge-2026.md` | 4 | "21 ADRs / 17 migrations"; escopo 1.11 como futuro (já entregue) |
| `docs/operations/azure-deploy.md` | 3 | "8 pegadinhas" + emoji como título canônico; tom de notas pessoais |
| (demais runbooks operations) | 4 | Emoji como status; conflito Easy Auth × Cloudflare entre docs |

## Os três problemas estruturais

### A. Drift doc × código (o mais grave)

Itens **confirmados contra o código** que os docs ainda descrevem errado:

| Afirmação nos docs | Realidade no código |
|---|---|
| "21 ADRs (0001–0021)" — em README, roadmap, vision, standards, fiap-challenge | **29 ADRs** (`docs/adr/0001..0029`). Só `adr/README.md` está certo. |
| "Migrations V001–V017" (ou V012/V016) | **V021** (`V018` hash invitation token, `V019`/`V020` RLS completa/scope auth-aware, `V021` meeting_embeddings) |
| US15 "Busca semântica — MISSING / pós-MVP" | **Entregue**: `EmbeddingService.java`, `HttpEmbeddingClient.java`, `RagSearchIntegrationTest.java`, `V021`, PR #206 (Chat RAG) |
| LGPD operacional "pendente / débito 1.12" | **Implementado**: ADR 0029, `DELETE /privacy/meetings/{id}`, `RetentionSweeper`, `PrivacyFlowIntegrationTest` |
| Customer Confidence "PARTIAL / persistência adiada" (glossary) | **DONE full-stack** (#148) |
| `PolicyEvaluator` "só StringEquals" (glossary) | Expandido: `StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` |
| `packages/shared-contracts` "só `.gitkeep`" | 4 arquivos reais |
| Estrutura com `mcp/{calendar,tasks,crm}` | Pasta `mcp/` **não existe** |
| Datas "Estado atual": README 05-28, vision 05-21, backlog 05-14, roadmap 05-23 | Repo já em **PR #206**; nenhuma reflete o HEAD |

Faltam em quase todos os docs de produto: **control plane / console de modelos**
(ADR 0022–0025), **RLS cutover** (ADR 0026/0028) e **LGPD operacional** (ADR 0029).

### B. Tom e formatação inconsistentes

- **Gírias** em docs voltados a stakeholders: "pegadinhas" (README, CLAUDE, standards, azure-deploy), "shipou" (backlog), "nukar" (production-readiness), "gambiarra" (roadmap), "tô mexendo aqui" (ADR 0014), "pra/pro" onipresentes.
- **Emoji como status semântico** (✅ ⬜ ⚠️ 🚨) em roadmap, azure-deploy, rls-cutover, cloudflare-access — inadequado para due-diligence/banca e frágil em *diff*.
- **Mistura PT/EN sem política**: cabeçalhos alternam idioma ("How To Work", "Non-Negotiables", "Responsible disclosure", "Definition of Done") em corpo PT-BR.

### C. Múltiplas fontes de verdade que divergem

- A **tabela de ADRs** existe em `adr/README.md` (canônica, 0001–0029) **e** em `standards.md §12` (0001–0021, defasada).
- **Status por feature** aparece em `backlog.md`, `vision.md §5` e `roadmap.md` ao mesmo tempo — e divergem.
- **Contagem de migrations** repetida em data-model, glossary, roadmap, fiap — todas defasadas.
- **Referências cruzadas erradas**: `production-readiness-gaps` cita "ADR 0019" para LGPD (ADR 0019 é tenant isolation; LGPD é ADR 0029).
- **Conflito entre docs operacionais**: `control-plane-runbook` e `cloudflare-access` adotam Cloudflare (ADR 0025), mas o contrato "congelado" `platform-control-plane.md` ainda descreve Easy Auth — sem nota de supersessão.

## Plano de profissionalização (faseado)

Para não virar um PR gigante na véspera da apresentação, recomenda-se faseamento:

**Fase 1 — Quick wins de credibilidade (antes de 15/06)** — alto impacto, baixo risco:
1. Corrigir as 9 afirmações de *drift* da tabela A nos 4 docs mais visíveis (README, vision, backlog, roadmap). A maioria é trocar um número ou mover uma linha de "futuro" para "entregue".
2. Corrigir o e-mail de segurança no `SECURITY.md`.
3. Adotar **fonte única de verdade**: nos docs vivos, substituir a contagem de ADRs/migrations por um link para o índice canônico, em vez de recopiar o número.

**Fase 2 — Padronização (depois de 15/06)** — aplicar o [guia de estilo (04)](04-guia-de-estilo-e-templates.md):
1. Front-matter YAML em todos os docs (owner, status, version, last_reviewed).
2. Política de idioma + remoção de gírias e emoji-como-status.
3. Templates de ADR e runbook aplicados retroativamente.
4. Linter de docs no CI (front-matter obrigatório, links válidos, homóglifos).

**Fase 3 — Processo (contínuo)**:
- Checklist no `PULL_REQUEST_TEMPLATE`: *"docs vivos reconciliados nesta PR?"*.
- Reconciliação doc × código a cada sub-fase que mude status, migration ou ADR.

## Documento-modelo

Conforme decidido, **o `README.md` foi reescrito nesta branch** como gabarito visual do
novo padrão: aplica front-matter, tom profissional, política de idioma e — crucialmente —
o princípio de **fonte única de verdade** (linka o índice de ADRs em vez de cravar uma
contagem que envelhece). Compare via `git diff main -- README.md`.

A escolha do README como modelo se deve a ele ser a porta de entrada do repositório (o
que avaliador/recrutador vê primeiro). Se preferir um ADR como gabarito alternativo, o
ADR 0029 já serve como exemplar do padrão MADR.

## Sobre idioma (PT-BR × inglês)

Recomendação para **agora**: manter **PT-BR**, padronizado. Profissionalismo vem de
consistência, não de idioma; a banca FIAP/TOTVS é brasileira; o produto é LGPD/PT-BR
nativo. Uma eventual "americanização" do GitHub (portfólio internacional) deve ser um
projeto deliberado **pós-apresentação**, idealmente no padrão *bilingual front door*
(README em inglês como porta de entrada, docs profundos em PT-BR). O guia de estilo (04)
já inclui uma política de idioma preparada para essa transição.
