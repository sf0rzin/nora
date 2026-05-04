# Padrões de Desenvolvimento — NORA

> Guia operacional para humanos e agentes de IA que vão programar a NORA.

Este documento define como o projeto deve ser desenvolvido, onde guardar cada informação e quais padrões devem ser seguidos para manter o código coeso mesmo com duas pessoas trabalhando em paralelo com IA.

---

## 1. Princípios de Engenharia

1. **Vertical slice antes de expansão**: entregar um fluxo completo pequeno é mais importante do que abrir várias frentes incompletas.
2. **Multi-tenant desde o primeiro commit**: qualquer dado de cliente/empresa nasce com `tenant_id`.
3. **Autorização no backend**: filtro no frontend é UX; segurança real é no backend e no banco.
4. **Contrato antes da implementação**: quando frontend, backend e worker interagem, o contrato vem primeiro (OpenAPI + JSON de exemplo).
5. **IA com saída estruturada**: LLM nunca retorna texto livre para a aplicação; sempre retorna JSON validado por schema.
6. **Produto horizontal**: nada de regra hardcoded para TOTVS. TOTVS é caso de uso inicial, não dependência do sistema.
7. **Segurança por padrão**: PII é detectado/redigido antes de qualquer chamada para LLM externo.
8. **Documentação viva**: decisão durável vai para docs; detalhe transitório vai para issue/PR.

---

## 2. Stack Recomendada

| Camada | Stack | Padrão |
|---|---|---|
| Web | Next.js + TypeScript + Tailwind + shadcn/ui | App Router, Server Components quando fizer sentido, client components só para interação |
| Backend | Java 21 + Spring Boot 3 | DDD em camadas, REST + OpenAPI, validação com Bean Validation |
| NLP Worker | Python 3.12 + FastAPI + Pydantic | Pipelines pequenos, schemas explícitos, prompts versionados |
| Banco | Postgres 16 + Flyway | Migrations versionadas, `tenant_id`, índices planejados |
| IA | Provider LLM agnóstico (default OpenAI `gpt-4o-mini`; Azure OpenAI em Enterprise) | JSON schema, temperatura baixa, logs sem PII. Ver ADR 0004. |
| RAG/Search | Azure AI Search | Stub local permitido no MVP; interface estável desde cedo |
| Auth | JWT/OAuth2 próprio no MVP; Entra ID pós-MVP | SSO não bloqueia o primeiro produto |
| Infra | Azure + GitHub Actions | IaC com Bicep quando começar deploy real |

### Decisão de Escopo

Para o MVP, o foco é **Web + Backend + Worker NLP**. Desktop, SSO, upload de áudio/vídeo, MCP completo e Salesforce entram depois que a vertical slice textual estiver estável.

---

## 3. Estrutura de Pastas

Estrutura alvo do repositório:

```text
TOTVS_Nora/
├── apps/
│   ├── web/                    # Next.js
│   └── desktop/                # Tauri pós-MVP
├── services/
│   ├── api/                    # Spring Boot backend
│   └── nlp-worker/             # FastAPI worker NLP/LLM
├── packages/
│   └── shared-contracts/       # Tipos/contratos gerados ou compartilhados
├── mcp/
│   ├── calendar/               # MCP pós-MVP
│   ├── tasks/
│   └── crm/
├── infra/
│   ├── bicep/                  # Infra Azure
│   └── docker/                 # Compose local, Dockerfiles auxiliares
├── data/
│   ├── synthetic/              # Dados sintéticos versionáveis
│   └── samples/                # Exemplos pequenos para testes/demo
├── notebooks/                  # Entregas Data Science / Colab
├── docs/
│   ├── adr/                    # Architecture Decision Records
│   ├── api/                    # OpenAPI, exemplos e contratos
│   └── *.md                    # Produto, Agile, padrões
├── scripts/                    # Automação local
├── .github/                    # CI, instruções Copilot, templates futuros
├── CLAUDE.md                   # Contexto principal para Claude
└── README.md
```

---

## 4. Onde Guardar Cada Informação

| Informação | Local |
|---|---|
| Visão, arquitetura macro, roadmap | `docs/PROJECT.md` |
| Visão Agile, É/Não É, Faz/Não Faz | `docs/visao-do-produto.md` |
| Personas e mapa de empatia | `docs/personas-e-mapa-de-empatia.md` |
| Backlog, user stories, critérios | `docs/backlog-mvp.md` |
| Casos de uso | `docs/diagrama-casos-de-uso.md` |
| Padrões técnicos e convenções | `docs/development-standards.md` |
| Plano de execução e divisão de tarefas | `docs/plano-de-execucao.md` |
| Decisões arquiteturais duráveis | `docs/adr/NNNN-titulo.md` |
| Contratos HTTP | `docs/api/openapi.yaml` ou gerado pelo backend |
| Exemplos de payload | `docs/api/examples/*.json` |
| Prompts do worker | `services/nlp-worker/src/nora_nlp/prompts/` |
| Schemas de saída da IA | `services/nlp-worker/src/nora_nlp/schemas/` |
| Dados sintéticos para demo | `data/synthetic/` |
| Notebooks acadêmicos | `notebooks/` |
| Variáveis de ambiente de exemplo | `.env.example` em cada app/service |
| Segredos reais | Nunca no Git; usar `.env.local` e depois Azure Key Vault |

---

## 5. Backend — Java/Spring Boot

### Organização

Dentro de `services/api`:

```text
src/main/java/br/com/nora/api/
├── NoraApiApplication.java
├── domain/
│   ├── tenant/
│   ├── identity/
│   ├── meeting/
│   ├── analysis/
│   └── accesscontrol/
├── application/
│   ├── commands/
│   ├── queries/
│   ├── services/
│   └── ports/
├── infrastructure/
│   ├── persistence/
│   ├── security/
│   ├── messaging/
│   └── clients/
└── api/
    ├── controllers/
    ├── dto/
    └── exception/
```

### Regras

- `domain` não conhece Spring, JPA, HTTP, banco ou SDK externo.
- `application` orquestra casos de uso e depende de portas/interfaces.
- `infrastructure` implementa persistência, clients externos e segurança.
- `api` contém controllers, DTOs e mappers.
- Controller nunca contém regra de negócio.
- Queries tenant-bound sempre exigem `tenant_id` e usuário autenticado.

### Padrões de API

- REST com OpenAPI.
- JSON em `camelCase` na API pública.
- Erros padronizados:

```json
{
  "code": "MEETING_NOT_FOUND",
  "message": "Meeting not found or outside user scope.",
  "traceId": "..."
}
```

- Paginação padrão: `page`, `size`, `sort`.
- Operações de upload/processamento devem retornar `processingStatus`.
- Endpoints administrativos sempre validam role e tenant.

---

## 6. Banco de Dados

### Convenções

- Tabelas em `snake_case` no plural: `tenants`, `meetings`, `meeting_analyses`.
- Colunas em `snake_case`.
- Chaves primárias: `id UUID`.
- Campos auditáveis padrão:

```sql
id uuid primary key,
tenant_id uuid not null,
created_at timestamptz not null,
updated_at timestamptz not null
```

- Dados globais, como `system_plans`, podem não ter `tenant_id`.
- Dados tenant-bound sempre têm `tenant_id` e índice composto por escopo de busca.

### Multi-tenancy

- MVP: filtro obrigatório no backend + testes de isolamento.
- Produção: Postgres Row-Level Security habilitado nas tabelas tenant-bound.
- Nunca buscar entidade tenant-bound só por `id`; sempre `tenant_id + id`.
- Toda tentativa de acesso fora do escopo retorna `403` ou `404` conforme risco de enumeração.

### Migrations

- Usar Flyway no backend.
- Nome: `V001__create_tenants.sql`, `V002__create_users.sql`.
- Migration nunca deve ser editada depois de aplicada; criar nova versão.

---

## 7. NLP Worker — Python/FastAPI

### Organização

Dentro de `services/nlp-worker`:

```text
src/nora_nlp/
├── main.py
├── api/
├── pipeline/
├── schemas/
├── prompts/
├── services/
├── clients/
└── tests/
```

### Pipeline MVP

1. Receber transcrição textual e metadados do tenant.
2. Redigir PII por regex BR (CPF, CNPJ, e-mail, telefone; Presidio depois).
3. Normalizar texto.
4. Gerar baseline TF-IDF para entrega acadêmica e interpretabilidade.
5. Recuperar contexto do tenant (stub local no MVP, Azure AI Search depois).
6. Chamar LLM com prompt versionado e schema de saída.
7. Validar resposta com Pydantic.
8. Retornar JSON estruturado ao backend.

### Saída Estruturada

Exemplo de contrato lógico:

```json
{
  "summary": "...",
  "decisions": ["..."],
  "actionItems": [
    {
      "title": "...",
      "owner": "...",
      "dueDate": null,
      "confidence": 0.86
    }
  ],
  "risks": [],
  "opportunities": [],
  "competitorMentions": [],
  "nextBestActions": []
}
```

### Regras

- Prompt não fica hardcoded dentro de função grande; usar arquivos em `prompts/`.
- Todo schema de saída tem teste com payload válido e inválido.
- Temperatura baixa para análise (`0` a `0.3`).
- Não logar transcrição bruta com PII.
- Falha de LLM deve produzir erro controlado, não stack trace exposto.

---

## 8. Frontend — Next.js

### Organização

Dentro de `apps/web`:

```text
src/
├── app/
│   ├── (auth)/
│   ├── (dashboard)/
│   └── api/
├── components/
│   ├── ui/                 # shadcn/ui
│   └── layout/
├── features/
│   ├── meetings/
│   ├── tasks/
│   ├── tenant-context/
│   └── access-control/
├── lib/
│   ├── api/
│   ├── auth/
│   └── validation/
└── styles/
```

### Regras

- Usar TypeScript estrito.
- Validar forms com Zod.
- Componentes de domínio ficam em `features/<feature>`.
- `components/ui` é só base visual reutilizável.
- Estado global só quando necessário; preferir estado local/server data.
- Não duplicar regras de autorização no frontend como fonte de verdade.
- UI Enterprise deve ser densa, clara e operacional; nada de landing page no dashboard.

---

## 9. Testes

| Camada | Testes mínimos |
|---|---|
| Backend | Unitários de domínio, integração com banco, testes de autorização tenant/scope |
| Worker | Unitários de pipeline, validação de schemas, fixtures de transcrições sintéticas |
| Frontend | Componentes críticos, fluxos com Playwright quando a UI existir |
| Contratos | Exemplos JSON válidos para payloads entre API e worker |

### Definition of Done

Uma story só é considerada pronta quando:

- Código implementado.
- Testes relevantes adicionados/atualizados.
- Verificação local executada.
- Sem segredo ou dado sensível no commit.
- Documentação ajustada se mudou contrato, arquitetura ou escopo.
- PR revisado por outra pessoa ou por IA em modo review + validação humana.

---

## 10. Git, Issues e PRs

### Branches

- `main`: sempre estável.
- `feat/us11-meeting-summary`
- `feat/us30-tenant-context`
- `fix/tenant-scope-leak`
- `docs/development-standards`

### Commits

Usar Conventional Commits:

- `feat(api): add meeting upload endpoint`
- `feat(worker): extract action items from transcript`
- `fix(api): enforce tenant scope on meeting lookup`
- `docs: align MVP backlog personas`

### PRs

Cada PR deve conter:

- Story/issue relacionada.
- O que mudou.
- Como foi testado.
- Riscos de segurança/multi-tenant.
- Screenshots quando for UI.

---

## 11. Uso de IA no Projeto

### Antes de pedir código

Sempre forneça ao agente:

- Story ID do backlog.
- Arquivos relevantes.
- Escopo do que pode mudar.
- Critério de aceite.
- Comando de teste esperado.

### Prompt recomendado

```text
Você está no projeto NORA. Leia CLAUDE.md e docs/development-standards.md.
Implemente a US11 do docs/backlog-mvp.md apenas no service X.
Não altere escopo fora dessa story.
Adicione testes e explique como validar.
```

### Divisão de modelos

- Opus: arquitetura, revisão de segurança, modelagem de dados, refatorações críticas, desenho de contratos.
- Sonnet: implementação focada, testes, componentes de UI, CRUD, fixtures, documentação localizada.

---

## 12. Ordem Técnica Recomendada

1. Scaffold monorepo e apps vazios.
2. Backend com tenants/users/auth simples.
3. Banco com migrations iniciais.
4. Worker com endpoint local de análise mockada.
5. Contrato API ↔ worker.
6. Upload textual e fila/status de processamento.
7. Worker real com PII + TF-IDF + LLM estruturado.
8. Web dashboard + detalhe da reunião.
9. Enterprise context configuration.
10. RBAC/ABAC mínimo.
11. Polimento de demo e dataset sintético.

---

## 13. Qualidade Esperada

A régua é alta: código limpo, modular, testável e pronto para evoluir. O uso de IA permite acelerar implementação, mas não substitui arquitetura, contratos claros e revisão humana. A NORA deve parecer um produto real desde a primeira demo funcional.