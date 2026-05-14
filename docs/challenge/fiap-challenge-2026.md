# FIAP Challenge 2026 — NORA × Parceria TOTVS

## Contexto

NORA é o projeto desenvolvido por **Anthony Sforzin** (matriculado no curso de Engenharia de Software na FIAP) no contexto do **FIAP Challenge 2026**, em parceria com a TOTVS S.A.

**Diferencial deste projeto:** NORA é construído como **produto comercial real**, não apenas como entrega acadêmica. A rubrica FIAP é tratada como **um dos múltiplos compromissos** que o produto cumpre — paralelo à possibilidade de entrega comercial via TOTVS (Plano A do PO) e à eventual operação como SaaS independente (Plano B).

Esta página documenta:

1. Como NORA atende a rubrica do FIAP Challenge 2026
2. Deadlines e entregas alvo
3. Onde cada peça da rubrica está documentada no repositório

## Rubrica acadêmica

> **NOTA:** itens marcados `?? não conferido` precisam ser validados contra a rubrica oficial publicada pela FIAP. Documento foi escrito com base em entregas históricas FIAP Challenge anteriores; pode necessitar ajustes específicos pra edição 2026.

### Entregas acadêmicas esperadas

| Item da rubrica | Onde está no NORA |
|---|---|
| **Personas e mapa de empatia** | [`personas-e-mapa-de-empatia.md`](personas-e-mapa-de-empatia.md) — 3 personas (Lucas Almeida, Camila Souza, Rafael Costa) |
| **Diagrama de casos de uso (UML)** | [`diagrama-casos-de-uso.md`](diagrama-casos-de-uso.md) — mermaid com 20+ casos de uso |
| **Backlog priorizado (MoSCoW)** | [`../product/backlog.md`](../product/backlog.md) — US01-US51 com status real DONE/PARTIAL/MISSING |
| **Modelo de dados relacional (Postgres)** | [`../engineering/data-model.md`](../engineering/data-model.md) — 24+ tabelas, 12 migrations Flyway aplicadas |
| **Modelo de dados Oracle (entrega DB)** | [`../engineering/data-model-oracle.md`](../engineering/data-model-oracle.md) — DDL Oracle 19c+ equivalente ao schema Postgres |
| **Arquitetura técnica (diagramas, fluxos)** | [`../engineering/architecture.md`](../engineering/architecture.md) — DDD layers, IAM flow, RAG pipeline, multi-tenancy |
| **Decisões arquiteturais documentadas** | [`../adr/`](../adr/) — 18 ADRs (decisões durables com contexto + alternativas) |
| **Validação técnica (testes)** | Test coverage real medido (worker 87%, backend 67%) — ver ADR 0018 |
| **Demonstração funcional (deploy)** | NORA deployado em Azure: `https://nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io` |
| **Pitch / apresentação final** | Sub-fase 1.11 cria roteiro de demo de 15-20min |

### Diferenciais técnicos (acima do mínimo de rubrica)

NORA entrega elementos que vão além da rubrica acadêmica típica:

- **IAM AWS-style** (ADR 0007) — modelo de autorização Root + Users + Groups + Policies com Effect/Action/Resource/Condition, próprio. Versionamento de policies + audit trail
- **PII Shield BR-aware** (ADR 0012) — redaction de EMAIL, CPF, CNPJ, PHONE, CREDIT_CARD, PERSON_NAME (lista BR ~270 nomes) antes de chamadas LLM. Compliance LGPD por design
- **Provider LLM agnóstico** (ADR 0004) — abstração que permite trocar OpenAI direto → Azure OpenAI → Whisper local sem mudar pipeline
- **JSON Schema strict obrigatório** (ADR 0003) — saída LLM validada server-side, sem free-form text cross-service
- **Multi-tenancy** (ADR 0002) — filtro de aplicação no MVP, RLS Postgres planejada pra prod
- **Productivity Score opt-in** (ADR 0005) — análise de produtividade da reunião contra objetivo declarado, com disclaimer obrigatório "indicador da reunião, não dos participantes"
- **Customer Confidence schema** (ADR 0006) — score por reunião com buying signals + objeções (implementação mínima na Sub-fase 1.11)
- **Deploy Azure production-grade** — 8 pegadinhas do Azure for Students catalogadas + workflow OIDC sem secrets, 14 recursos provisionados via Bicep IaC
- **Test coverage rigoroso** (ADR 0018) — áreas críticas (IAM, Auth, PII) sustentadas >85%
- **License AGPL-3.0** (ADR 0017) — proteção contra clone-and-compete

## Deadlines

| Marco | Data | Status |
|---|---|---|
| Entrega de modelagem de dados (Oracle) | ?? não conferido | Material em `../engineering/data-model-oracle.md` |
| Apresentação parcial (sprint review) | ?? não conferido | — |
| **Pitch FIAP / NEXT 2026** | **2026-06-12** | **Sub-fase 1.11 (Demo Polish) entrega o material** |
| Entrega final FIAP | ?? não conferido | Sub-fases 1.11 + 1.12 cobrem |

## Equipe

- **Anthony Sforzin (sys0xFF)** — desenvolvimento solo + arquitetura + PO
- **Gabriel Maciel (@pollotherunner)** — colaborador externo no Desktop app (Tauri + sidecar Python)
- **Múltiplos Claude rodando skill `arquiteto-nora`** — assistentes técnicos (Tech Lead, Arquiteto Design) operando sob direção do Anthony

> Para detalhes sobre a divisão e coordenação multi-arquiteto Claude, ver `Claude/50-coordenacao-arquitetos/00-papeis.md` no vault Obsidian privado do PO.

## Por que NORA é mais que um trabalho acadêmico

Anthony tem **3 planos de carreira** explicitamente declarados:

- **Plano A** — TOTVS contrata vendo NORA na demo (parceria FIAP × TOTVS, NORA passa de portfolio a oferta concreta de emprego)
- **Plano B** — SaaS comercial (longo prazo, com co-founder de negócio)
- **Plano C** — LinkedIn / portfolio técnico (material já existe agora, pronto pra publicação)

A rubrica FIAP é a **camada acadêmica visível**; o produto comercial roda em paralelo como código real, deployado, monetizável.

## Próximos passos pré-pitch (12/06)

- **Sub-fase 1.11 — Demo Polish Plano A** (2-3 semanas agentic): polir UX interna (dashboard, meeting detail, tasks, settings) + implementar Customer Confidence persistência mínima (ADR 0015) + corrigir AUTH_FILTER_HARD_CAP + adicionar PolicyEvaluator operators (stringIn, stringLike) + seed sintético TOTVS realista + roteiro de demo gravado
- **Sub-fase 1.12 — Production Hardening** (se sobrar tempo pré-pitch): rg-nora-prod separado, monitoring alerts, LGPD operational, secrets rotation. **Pode ficar pós-pitch sem prejuízo da demo.**

## Histórico

| Data | Mudança |
|---|---|
| 2026-05-14 | Doc criado na Sub-fase 1.10 (Docs Refresh) consolidando o framing FIAP × TOTVS |
