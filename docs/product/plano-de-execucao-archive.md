# Plano de Execução — NORA (ARQUIVADO / HISTÓRICO)

> **⚠️ DOCUMENTO DESCONTINUADO em 2026-05-14.**
>
> Este arquivo é mantido apenas como **referência histórica**. Descrevia divisão hipotética semana-a-semana entre dois desenvolvedores — modelo que **não corresponde ao fluxo real**. Hoje a equipe **Stratfy** executa o NORA via Claude Code com subagentes paralelos em worktrees.
>
> **Fonte de verdade atual:** [`docs/product/roadmap.md`](roadmap.md) (histórico das 11 Sub-fases 1.0-1.10 + próximas 1.11+).
>
> As referências abaixo a "Anthony" e "Colega" são as designações originais do plano hipotético; reflitam-se como "Stratfy" e "colaborador externo" no contexto atual.

---

> Passo a passo para começar o desenvolvimento e dividir trabalho entre Anthony e um colega usando IA.

---

## 1. Objetivo Imediato

Construir uma vertical slice forte do MVP:

1. Usuário entra no sistema.
2. Configura ou usa um tenant de demo.
3. Faz upload de uma transcrição textual.
4. A NORA processa com contexto do tenant.
5. O usuário vê resumo, decisões, tarefas, riscos/oportunidades e histórico em dashboard.
6. Usuário Enterprise só vê reuniões do próprio escopo.

Esse fluxo é pequeno o bastante para duas pessoas, mas forte o suficiente para parecer produto real.

---

## 2. Preparação no GitHub

1. Criar um GitHub Project com colunas: `Backlog`, `Ready`, `In Progress`, `Review`, `Done`.
2. Criar labels: `frontend`, `backend`, `worker`, `database`, `docs`, `security`, `mvp`, `academic`.
3. Transformar as Must Have do `docs/backlog-mvp.md` em issues.
4. Cada issue deve conter: Story ID, critério de aceite, arquivos esperados, comando de teste.
5. Ativar branch protection na `main` quando o código começar: PR obrigatório e pelo menos uma revisão.

---

## 3. Como Trabalhar em Paralelo

A chave é contrato primeiro. Antes de frontend e backend programarem ao mesmo tempo, definam payloads e endpoints.

### Contrato Inicial

Criar exemplos em `docs/api/examples/`:

- `meeting-upload-request.json`
- `meeting-analysis-response.json`
- `tenant-context-request.json`
- `scoped-meetings-response.json`

Com isso, frontend usa mock e backend/worker implementam sem bloquear a UI.

---

## 4. Divisão Recomendada

### Anthony — Trilha Backend/Arquitetura

Responsável por fundação técnica, segurança e domínio.

Primeiras tarefas:

1. Criar estrutura `services/api` com Spring Boot 3 + Java 21.
2. Criar entidades iniciais: `Tenant`, `User`, `Role`, `Meeting`, `Transcript`, `MeetingAnalysis`, `ActionItem`.
3. Configurar Postgres local e Flyway.
4. Implementar `tenant_id` em todas as entidades tenant-bound.
5. Criar auth e-mail/senha + JWT.
6. Criar endpoints de upload textual e listagem de reuniões.
7. Criar autorização mínima: Admin vê tudo; usuário vê apenas escopo permitido.
8. Integrar backend com worker por HTTP inicialmente.

IA indicada:

- Opus para modelagem de domínio, segurança, multi-tenancy e revisão.
- Sonnet para endpoints, DTOs, testes e migrations.

### Colega — Trilha Web/Worker/Dados

Responsável por experiência de uso, worker NLP e dataset de demo.

Primeiras tarefas:

1. Criar estrutura `apps/web` com Next.js + TypeScript + Tailwind + shadcn/ui.
2. Montar layout operacional: login, dashboard, detalhe da reunião, lista de tarefas.
3. Usar mocks baseados em `docs/api/examples` enquanto backend não estiver pronto.
4. Criar `services/nlp-worker` com FastAPI + Pydantic.
5. Criar fixtures de transcrições sintéticas em `data/synthetic`.
6. Implementar pipeline inicial: limpeza, PII regex, TF-IDF, resposta mockada validada por schema.
7. Evoluir para chamada LLM estruturada.

IA indicada:

- Sonnet para UI, mocks, fixtures e implementação do worker.
- Opus para revisão dos prompts, schema de análise e qualidade do dataset sintético.

---

## 5. Ordem de Execução nas Primeiras 2 Semanas

### Dia 1 — Setup e Contratos

- Criar estrutura de pastas.
- Criar `.env.example` por app/service.
- Criar exemplos JSON em `docs/api/examples`.
- Criar issues no GitHub a partir das Must Have.
- Definir primeiro contrato `POST /meetings/upload` e `GET /meetings/{id}`.

### Dias 2–4 — Fundação

- Anthony: Spring Boot + Postgres + Flyway + entidades base.
- Colega: Next.js + layout + páginas mockadas.
- Colega: FastAPI worker com endpoint `/analyze` retornando JSON fake validado.

### Dias 5–7 — Primeira Vertical Slice

- Upload textual no backend.
- Backend chama worker.
- Persistência de análise.
- Dashboard consome API real.
- Detalhe da reunião mostra resumo/tarefas.

### Semana 2 — Inteligência e Enterprise

- Configuração de contexto do tenant.
- Worker usa contexto no prompt.
- PII Shield inicial.
- RBAC/ABAC mínimo.
- Dataset sintético comercial forte.
- Polimento visual da demo.

---

## 6. Issues MVP Iniciais

Criar estas issues primeiro:

| Issue | Responsável | Descrição |
|---|---|---|
| `SETUP-01` | Anthony | Criar estrutura `services/api` e banco local |
| `SETUP-02` | Colega | Criar estrutura `apps/web` e design base |
| `SETUP-03` | Colega | Criar estrutura `services/nlp-worker` |
| `CONTRACT-01` | Ambos | Definir payloads JSON entre Web, API e Worker |
| `US01-US04` | Anthony | Auth Core com e-mail/senha/JWT |
| `US07` | Anthony | Upload de transcrição textual |
| `US11-US12` | Colega | Pipeline de resumo e tarefas no worker |
| `US16-US18` | Colega | Dashboard, detalhe e busca básica |
| `US30` | Anthony | Configurar contexto do tenant |
| `US19-US20-US36` | Anthony | Escopo Enterprise e controle de acesso mínimo |

---

## 7. Como Usar IA com GitHub

1. Cada issue vira uma branch.
2. O responsável pede para a IA ler `CLAUDE.md`, `docs/development-standards.md` e a issue.
3. A IA implementa apenas a issue.
4. O responsável roda testes locais.
5. Abre PR com resumo e evidências.
6. O outro integrante revisa com apoio de IA em modo code review.
7. Merge só depois de passar CI e revisão humana.

Prompt de review recomendado:

```text
Revise este PR do projeto NORA com foco em bugs, segurança, multi-tenancy,
quebra de contrato e falta de testes. Priorize achados objetivos.
```

---

## 8. Estratégia de Demo

Criar 3 tenants/dados sintéticos:

1. `TOTVS Demo` com produtos Protheus, RM e Fluig.
2. `Fintech Demo` com produtos próprios para provar horizontalidade.
3. `Startup Demo` para fluxo Core individual.

Criar 6 transcrições sintéticas:

- 2 reuniões Core de produto/engenharia.
- 2 reuniões comerciais com sinal de upsell.
- 1 reunião com menção a concorrente.
- 1 reunião com risco de churn.

O pitch deve mostrar que o contexto muda a análise: a mesma IA entende vocabulários diferentes conforme o tenant.

---

## 9. Checklist Antes de Começar Código

- [ ] Criar issues GitHub das Must Have.
- [ ] Criar estrutura de pastas alvo.
- [ ] Criar contratos JSON iniciais.
- [ ] Definir `.env.example` dos serviços.
- [ ] Decidir comandos oficiais de dev/test.
- [ ] Criar primeiro dataset sintético.
- [ ] Abrir branches separadas para Anthony e colega.

---

## 10. Regra de Ouro

Se uma tarefa mexe em contrato entre camadas, os dois param e alinham o contrato antes de continuar. Isso evita que IA gere duas partes tecnicamente boas, mas incompatíveis entre si.