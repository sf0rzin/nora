# 0014 — Defer post-MVP commercial gate

- Status: aceito
- Data: 2026-05-14
- Decisores: Stratfy (PO), Tech Lead, Arquiteto Design

## Contexto

O backlog declara 57 stories (US01-US51 numeradas + 6 condicionais). Status real após Sub-fase 1.9 (deploy real Azure, 2026-05-13):

- **28 DONE**
- **5 PARTIAL**
- **10 MISSING** (5 dos quais marcados W = Won't Have v1 no backlog original)
- 9 categorizadas Should/Could/Won't

Velocidade de 10 sub-fases em ~10 dias agentic é insustentável por mais 10 sub-fases sem queima ou perda de foco. A revisão do Arquiteto Design no audit pré-Sub-fase 1.10 explicitou o risco:

> "Pare de adicionar US. Backlog declara v1 fechada. Próximas sub-fases entregam só dentro do v1 declarado."

3 planos do PO (memory `user_career.md`):
- **Plano A** (TOTVS contrata): ~70% maduro, ~2 semanas pra demo-ready
- **Plano B** (SaaS comercial): ~25%, exige co-founder de negócio
- **Plano C** (LinkedIn/portfolio): já maduro

Pasta `mcp/{calendar,tasks,crm}/` committed vazia projeta "incompleto" pra qualquer reviewer técnico que clone o repo.

## Decisão

Declarar a **v1 do backlog fechada após Sub-fase 1.12 (Production Hardening)**. Lista de US explicitamente **deferidas** (não MISSING permanente, reativáveis com critério):

| US | Title | Defer até | Quem decide reativar |
|---|---|---|---|
| US05 | SSO Entra ID / SAML | Demo Plano A fechada OU 100 tenants Plano B | Stratfy (decisão comercial) |
| US08 | Upload áudio/vídeo (Azure Speech upload) | Idem | Idem |
| US15 | Busca semântica embeddings (AI Search) | Modelo de custo bater OU pitch FIAP justificar | Tech Lead (custo: AI Search Basic ~R$400/mês) |
| US21 | Painel de tendências (Could) | Pós-demo Plano A | Arquiteto Design (valor UX) |
| US25 | Export tarefas CSV/MD (Should) | Sub-fase 1.13+ | Tech Lead |
| US33 | Métricas de uso do tenant (Should) | Plano B onboarding | Tech Lead |
| US34 | Export relatório consolidado (Should) | Sub-fase 1.13+ | Tech Lead |
| US41 | Templates de policy (Should) | Sub-fase 1.13+ | Tech Lead |
| US43 | Simulador de policy (Should) | Sub-fase 1.13+ | Tech Lead |
| US44 | Permission boundaries (Could) | Pós-demo Plano A | Tech Lead |
| US47 | MCP project state (Won't) | Plano B integração | Stratfy |
| US50 | Account Health Score agregado | Sub-fase 1.13+ (depois de US48-49 base estável via ADR 0015) | Tech Lead |
| US51 | Alerta de mudança de banda | Sub-fase 1.13+ | Tech Lead |

**US48-49 (Customer Confidence base) tratadas separadamente em ADR 0015** — implementação mínima na 1.11.

Pasta `mcp/{calendar,tasks,crm}/` removida da raiz do monorepo (ou movida pra `archive/mcp-future/` no `.gitignore`) — sinal visual de "incompleto" eliminado.

## Consequências

**Positivas:**
- Backlog para de crescer durante a janela crítica pré-pitch (até 12/06)
- Roadmap.md fica realista e priorizável
- Stratfy e arquitetos focam em **polir o que existe** vs adicionar features que ninguém vê no MVP
- Reviewers técnicos veem produto coerente, não "100 promessas inacabadas"
- Plano C content tem foco (8 pegadinhas Azure, IAM AWS-style, PII BR, etc.) sem se diluir em "também tenho Y e Z"

**Negativas:**
- Stratfy pode sentir tentação de "só essa eu adiciono" — disciplina precisa ser ativa
- Se demo Plano A não fechar, lista de US deferidas precisa ser revisitada com clareza sobre quais reativar
- Risco de "scope creep silencioso" — fixes que viram features. Mitigação: PR review pergunta "isso está no escopo da sub-fase declarada?"

## Alternativas Consideradas

1. **Continuar expandindo backlog** — rejeitado pela razão do contexto (velocidade insustentável + fadiga da equipe + sinal visual "incompleto")
2. **Deletar US deferidas do backlog** — rejeitado por perder rastreabilidade. Histórico vale.
3. **Marcar W (Won't Have v1) em todas as deferidas** — meio caminho. Escolhemos **"Defer formal"** explícito porque é **reativável** com critério; W sugere "pra sempre".

## Plano de Aplicação

1. **`docs/product/backlog.md`** atualizado: status real + marca explícita "DEFERRED — reativa quando X" nas 13 US listadas
2. **`docs/product/roadmap.md`** descreve Sub-fase 1.11 e 1.12 com escopo declarado; sub-fases 1.13+ ficam abertas pendentes de tração
3. **Skill `arquiteto-nora`** já tem anti-padrão "Aceitar scope creep ('já que tô mexendo aqui, vou adicionar Y') — escopo declarado é escopo executado" — referência explícita a este ADR

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-14 | Stratfy (PO) | Aprovado em bloco após revisão do Arquiteto Design + recomendação Tech Lead. Critério reativação documentado por US |
