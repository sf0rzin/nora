# Padrões de Engenharia — NORA

> Guia operacional para humanos e agentes de IA programando a NORA.
> Define convenções, estrutura, padrões e ferramentas. Atualizado pra refletir o **estado real do código** (Sub-fase 1.10) — não promessas.

---

## 1. Princípios de Engenharia

1. **Vertical slice antes de expansão.** Entregar um fluxo completo pequeno > abrir várias frentes incompletas.
2. **Multi-tenant desde o primeiro commit.** Qualquer dado de cliente nasce com `tenant_id`. Não existe atalho.
3. **Autorização no backend.** Filtro no frontend é UX; segurança real está em `AuthorizationService` + `PolicyEvaluator`.
4. **Contrato antes da implementação.** Quando frontend, backend e worker interagem, o contrato vem primeiro (OpenAPI + JSON Schema + exemplos).
5. **IA com saída estruturada.** LLM nunca retorna texto livre para a aplicação; sempre JSON Schema strict validado por Pydantic (ADR 0003).
6. **Produto horizontal.** Zero regra hardcoded para TOTVS. Tenant configura seu próprio contexto.
7. **Segurança por padrão.** PII é redigido antes de qualquer chamada LLM externa (ADR 0012).
8. **Documentação viva.** Decisão durável → `docs/adr/`. Detalhe transitório → issue/PR/vault privado.

---

## 2. Stack confirmada

| Camada | Stack | Padrão |
|---|---|---|
| **Web** | Next.js 14 + TypeScript 5 + React 18 + Tailwind CSS 3 (cru — **sem shadcn**, sem MUI, sem Chakra) | App Router, RSC quando fizer sentido, client components só para interação |
| **Backend** | Java 21 + Spring Boot 3.3 + JPA + Flyway | DDD em camadas (domain/application/infrastructure/api), REST + OpenAPI, Bean Validation |
| **Worker NLP** | Python 3.12 + FastAPI + Pydantic 2 + OpenAI SDK 1.50 | Pipelines pequenos, schemas explícitos, prompts versionados em `prompts/{version}.md` |
| **Banco** | Postgres 16 + Flyway | Migrations versionadas `V###__nome.sql`, `tenant_id` em toda tabela tenant-bound |
| **IA** | LLM agnóstico via env vars (default OpenAI `gpt-4o-mini`; Azure OpenAI em Enterprise) | JSON Schema strict, temperatura baixa, logs sem PII. ADR 0004. |
| **Search/RAG** | Azure AI Search (desligado no MVP, `enableSearch=false`) | Stub local aceitável; interface `Retriever` estável desde já |
| **Auth** | JWT (JJWT 0.12) + refresh tokens stateful (V011); cookies HttpOnly | SSO Entra ID/SAML pós-MVP |
| **Desktop** | Tauri 2 + Rust + sidecar Python | Captura áudio nativa; ADR 0008. Escopo de outro arquiteto. |
| **Infra** | Azure (Container Apps + Postgres Flexible + KV + Storage + AI Search opt) + Bicep | IaC declarativa; SP OIDC via GitHub Actions |
| **CI/CD** | GitHub Actions: `ci.yml` + `build-images.yml` + `deploy-infra.yml` | Push GHCR; deploy automatizado para `dev` |

### Decisão de Escopo MVP

Foco no slice **Web + Backend + Worker NLP**. Desktop, SSO, upload de áudio/vídeo, MCPs completos e Salesforce nativo entram pós-MVP.

---

## 3. Estrutura de pastas

```text
nora/
├── apps/
│   ├── web/                    # Next.js (Tailwind cru)
│   └── desktop/                # Tauri 2 (outro arquiteto)
├── services/
│   ├── api/                    # Spring Boot backend
│   └── nlp-worker/             # FastAPI worker NLP/LLM
├── packages/
│   ├── nlp-baseline/           # TF-IDF PT-BR reaproveitável (ADR 0010)
│   └── shared-contracts/       # placeholder, pouco usado no MVP
├── infra/
│   ├── bicep/                  # Infra Azure (main.bicep + 9 módulos)
│   └── docker/                 # Compose local, Dockerfiles auxiliares
├── data/
│   ├── synthetic/              # 12 transcripts + 3 contextos (versionados)
│   └── samples/                # pequenos exemplos
├── notebooks/                  # Entregas Data Science FIAP
├── docs/
│   ├── product/                # vision, backlog (status real), roadmap, glossary
│   ├── engineering/            # architecture, standards (este doc), data-model, data-model-oracle
│   ├── operations/             # azure-deploy (runbook + 8 pegadinhas), production-readiness-gaps
│   ├── challenge/              # FIAP Challenge 2026 (personas, casos de uso, README, fiap-challenge-2026)
│   ├── security/               # em construção (Sub-fase 1.12 — threat model, LGPD operacional)
│   ├── api/                    # OpenAPI + JSON Schemas LLM + exemplos
│   └── adr/                    # 18 ADRs (0001-0018) + README
├── scripts/                    # automação local
├── .github/                    # workflows + templates
├── CLAUDE.md                   # contexto para Claude Code
└── README.md
```

**Notas sobre a estrutura real:**

- **Não existe `apps/web/src/features/`** (a versão anterior do doc previa). O frontend usa `src/components/` flat + `src/app/` (App Router).
- **`packages/shared-contracts/`** é praticamente um placeholder no MVP (só `.gitkeep`); contratos vivem em `docs/api/`.
- **MCPs (calendar, tasks, crm)** foram deferidos pós-MVP via ADR 0014 (defer commercial gate). Pastas vazias removidas do monorepo na Sub-fase 1.10. ADR 0001 (monorepo) menciona estrutura prevista; reativação condicional ao primeiro tenant pagante pedir integração.

---

## 4. Onde guardar cada informação

| Informação | Local |
|---|---|
| Visão de produto (É/Não É, Faz/Não Faz, Geoffrey Moore) | `docs/product/vision.md` |
| Backlog priorizado (MoSCoW + status real DONE/PARTIAL/MISSING) | `docs/product/backlog.md` |
| Roadmap vivo (histórico sub-fases 1.0–1.10 + futuro 1.11+) | `docs/product/roadmap.md` |
| Glossário NORA (termos canônicos: Productivity Score, Customer Confidence, IAM Policy, etc.) | `docs/product/glossary.md` |
| Plano histórico (descontinuado — só referência) | `docs/product/plano-de-execucao-archive.md` |
| Arquitetura técnica (DDD layers, fluxos end-to-end, stack rationale) | `docs/engineering/architecture.md` |
| Padrões técnicos (este doc) | `docs/engineering/standards.md` |
| Modelo de dados Postgres | `docs/engineering/data-model.md` |
| Modelo de dados Oracle (entrega FIAP DB) | `docs/engineering/data-model-oracle.md` |
| Runbook deploy Azure + 8 pegadinhas (Sub-fase 1.9) | `docs/operations/azure-deploy.md` |
| Production-readiness gaps (alvo Sub-fase 1.12) | `docs/operations/production-readiness-gaps.md` |
| Material acadêmico FIAP Challenge 2026 (personas, casos de uso, rubrica) | `docs/challenge/` |
| Decisões arquiteturais duráveis (18 ADRs) | `docs/adr/NNNN-titulo.md` (índice em `docs/adr/README.md`) |
| Contratos HTTP | `docs/api/openapi.yaml` (a gerar) ou via springdoc-openapi |
| Exemplos de payload | `docs/api/examples/*.json` |
| Schemas LLM | `docs/api/llm-schemas/*.schema.json` |
| Prompts do worker | `services/nlp-worker/src/nora_nlp/prompts/{version}.md` |
| Schemas Pydantic | `services/nlp-worker/src/nora_nlp/models.py` |
| Dados sintéticos | `data/synthetic/` |
| Notebooks acadêmicos | `notebooks/` |
| Variáveis de ambiente exemplo | `.env.example` em cada app/service |
| Segredos reais | **Nunca no Git.** `.env.local` em dev; Azure Key Vault em prod |

---

## 5. Backend — Java/Spring Boot

### Organização

```text
services/api/src/main/java/br/com/nora/api/
├── NoraApiApplication.java
├── domain/                # POJOs/records, lógica pura; ZERO dependência Spring
│   ├── iam/               # IamPolicy, PolicyEvaluator, PolicyStatement
│   ├── meeting/           # Meeting, Participant, ProcessingStatus
│   ├── analysis/          # MeetingAnalysis + filhos
│   ├── identity/          # User, Email value object, Password
│   ├── tenant/            # Tenant
│   └── productivity/      # MeetingGoal, ProductivityAssessment
├── application/           # casos de uso, services, portas
│   ├── identity/          # AuthService
│   ├── iam/               # AuthorizationService, IamService
│   ├── meeting/           # MeetingService
│   ├── analysis/          # AnalysisService
│   ├── productivity/      # ProductivityService
│   ├── speech/            # SpeechTokenService
│   └── ports/             # interfaces (UserRepository, MeetingRepository, ...)
├── infrastructure/        # adapters: JPA, JJWT, HTTP, Azure
│   ├── persistence/jpa/   # entities + adapters dos repositories
│   ├── security/          # JjwtJwtIssuer, JwtAuthenticationFilter
│   ├── speech/            # AzureSpeechTokenBroker
│   └── analysis/          # WorkerHttpClient
└── api/                   # controllers, DTOs, exception handlers
    ├── controllers/       # AuthController, MeetingsController, IamController, ...
    ├── dto/               # request/response records
    ├── security/          # CurrentUser, AuthCookies
    └── exception/         # GlobalExceptionHandler
```

### Regras invioláveis

- `domain/` **não** importa nada de Spring, JPA, HTTP, banco ou SDK externo.
- `application/` depende de **portas** (interfaces) declaradas em `application/ports/`.
- `infrastructure/` **implementa** as portas (adapters).
- `api/` contém apenas controllers, DTOs e mappers. **Nenhuma regra de negócio em controller.**
- Queries tenant-bound **sempre** filtram por `tenant_id` antes de `id`.
- Toda chamada com risco de autorização passa por `AuthorizationService.require(...)` ou `requireAnyAllow(...)`.

### Padrões de API

- REST com OpenAPI (auto-gerada por springdoc).
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
- Operações de upload/processamento retornam `processingStatus` (`PENDING`/`PROCESSING`/`COMPLETED`/`FAILED`).
- Endpoints administrativos **sempre** validam autorização via `authz.require(action, resource)`.

### Padrões de teste

- Domain: testes JUnit puros, sem container Spring. Exemplo: `PolicyEvaluatorTest`.
- Application: testes com mocks das portas.
- Infrastructure: integration tests com Testcontainers (Postgres real).
- API: `@SpringBootTest` ou `@WebMvcTest`. Cobertura mínima inclui caminhos de autorização negados (403/404).

---

## 6. Banco de dados

### Convenções

- Tabelas em `snake_case` no plural: `tenants`, `meetings`, `meeting_analyses`.
- Colunas em `snake_case`.
- Chaves primárias: `id UUID PRIMARY KEY DEFAULT gen_random_uuid()` (extensão `pgcrypto`).
- Campos auditáveis padrão:

```sql
id uuid primary key default gen_random_uuid(),
tenant_id uuid not null references tenants(id),
created_at timestamptz not null default now(),
updated_at timestamptz not null default now()
```

- Dados tenant-bound **sempre** têm `tenant_id` + índice composto por escopo de busca.
- `email` usa `CITEXT` (case-insensitive) — extensão `citext` (V002:6).

### Multi-tenancy

- MVP: filtro obrigatório no backend + testes de isolamento (`IamScopingIntegrationTest`).
- Produção: Postgres RLS — **schema entregue em V016** (`tenant_isolation` + `TenantRlsAspect`); enforcement opt-in (role `nora_app` `NOBYPASSRLS` + flag `nora.security.rls.enforce`). Defesa em profundidade do filtro de app (ADR 0002).
- Nunca buscar entidade tenant-bound só por `id`; sempre `(tenant_id, id)`.
- Acesso fora do escopo retorna `403` ou `404` conforme risco de enumeração.

### Migrations

- Flyway no backend.
- Nome: `V001__create_tenants.sql`, `V002__create_users_and_roles.sql`, etc.
- **Migration nunca é editada depois de aplicada** — sempre criar nova versão (forward-only).
- Ver `docs/engineering/data-model.md` para mapa completo V001–V016 (V013 soft-delete, V014 refresh rotation, V015 composite FK, V016 RLS).

---

## 7. NLP Worker — Python/FastAPI

### Organização

```text
services/nlp-worker/src/nora_nlp/
├── main.py                # FastAPI app
├── routers/
│   ├── analyze.py         # POST /analyze, /live-analyze
│   └── health.py
├── services/
│   ├── pii_shield.py      # redação determinística antes do LLM
│   ├── baseline.py        # TF-IDF do nlp-baseline
│   ├── llm_analyzer.py    # pipeline LLM real
│   ├── stub_analyzer.py   # determinístico para CI
│   ├── live_analyzer.py   # análise incremental ao vivo
│   └── stub_live_analyzer.py
├── clients/
│   └── llm.py             # adapter OpenAI-compatible (ADR 0004)
├── prompts/
│   └── meeting-analysis-v1.md
├── models.py              # MeetingAnalysisV1, AnalyzeRequest/Response, etc.
└── settings.py            # pydantic-settings
```

### Pipeline de análise

1. Receber transcript + metadados (idioma, formato) + tenant_context.
2. **PII Shield** — regex BR (EMAIL/CPF/CNPJ/PHONE/CREDIT_CARD/PERSON_NAME). Ver `services/pii_shield.py`.
3. Normalizar texto, gerar baseline TF-IDF para interpretabilidade.
4. (Pós-MVP) recuperar contexto vetorial via Azure AI Search.
5. Chamar LLM com prompt versionado e `response_format=json_schema` strict.
6. Validar com Pydantic (`MeetingAnalysisV1`).
7. Retornar JSON estruturado ao backend.

### Saída estruturada

Schema canônico em `docs/api/llm-schemas/meeting-analysis-v1.schema.json`. Inclui:

- `summary` (markdown)
- `decisions[]`
- `actionItems[]` (title, assignee, dueDate, priority, sourceQuote)
- `risks[]` (severity, category, sourceQuote)
- `opportunities[]` (estimatedValue, category, sourceQuote)
- `topics[]`, `sentimentOverall`
- `productivity` (opcional, ADR 0005)
- `customerConfidence` (opcional, ADR 0006 — schema existe, persistência pendente)
- `baselineTerms[]` (TF-IDF)
- `piiRedactionApplied`

### Regras

- Prompt **versionado** em `prompts/{version}.md` com seções `## SYSTEM` e `## USER`.
- Todo schema de saída tem teste com payload válido e inválido (`tests/`).
- Temperatura baixa (0 a 0.3) para análise.
- Nunca logar transcript bruto com PII.
- Falha de LLM gera erro controlado, **não stack trace exposto**.

---

## 8. Frontend — Next.js (Tailwind cru)

### Organização

```text
apps/web/src/
├── app/
│   ├── (auth)/            # login, signup, reset
│   ├── (app)/             # dashboard, meetings, tasks, settings
│   └── api/               # route handlers (poucos)
├── components/            # flat — sem subdivisão features/
│   ├── ui-primitives/     # button, input, dialog (escritos à mão)
│   ├── meeting-*          # cards e formulários de reunião
│   ├── productivity-*     # ProductivityScoreCard, MeetingGoalForm
│   ├── iam-*              # GroupList, PolicyEditor (Monaco)
│   └── nora-logo.tsx
├── lib/
│   ├── api/               # client.ts + types.ts (typed fetch)
│   ├── auth/              # client-side helpers
│   └── utils.ts
└── styles/
    ├── globals.css        # tokens + utilities
    └── tokens.css         # paleta OKLCH + tipografia
```

**Não existe `components/ui/` à la shadcn.** Componentes-base são escritos à mão usando classes Tailwind diretamente.

### Regras

- TypeScript estrito (`strict: true`).
- **Validação:**
  - Forms simples: HTML5 + `react-hook-form` quando necessário.
  - Backend faz validação canônica (Bean Validation + JSON Schema do worker). Frontend é UX, não fonte da verdade.
  - **Zod está declarado como dep mas é pouco usado no MVP.** Quando justificado (schemas complexos, união discriminada), tudo bem. Não é obrigatório.
- Componentes de domínio em `components/` flat — não há `features/`.
- Estado global só quando necessário; preferir estado local/server data via fetch direto.
- Não duplicar regras de autorização no frontend como fonte de verdade. **Renderização condicional ≠ autorização.**
- UI Enterprise deve ser densa, clara, operacional. Nada de landing page no dashboard.

### Mocks como default em dev

`apps/web/src/lib/api/client.ts` usa `NEXT_PUBLIC_USE_MOCKS=true` por default (também no CI). Para apontar para backend real, setar `NEXT_PUBLIC_USE_MOCKS=false` e `NEXT_PUBLIC_API_BASE_URL=http://localhost:8080`.

---

## 9. Testes

| Camada | Testes mínimos |
|---|---|
| **Backend** | Unitários de domínio (puros), integração com Postgres via Testcontainers, integration tests de autorização tenant/scope (`IamScopingIntegrationTest`), WireMock para stubar worker |
| **Worker** | Unitários de pipeline, validação de schemas (jsonschema), fixtures de transcrições sintéticas em `data/synthetic/` |
| **Frontend** | (TBD — sem runner declarado no `package.json`). Sub-fase 1.11+ pode adicionar Vitest |
| **Contratos** | Exemplos JSON válidos em `docs/api/examples/` para payloads worker↔API |

### Test coverage targets (audit §12, propostos em ADR futuro)

| Área | Target sustained |
|---|---|
| **Áreas críticas** (IAM, Auth, PII, LLM analyzer) | **> 85%** |
| Demais áreas backend | > 60% |
| Worker NLP | > 85% (atual: 87%) |
| **Branch coverage backend** | > 70% (atual: 53%) |
| Web Next.js | TBD (sem runner ainda; meta após Sub-fase 1.11+) |
| Desktop sidecar Python | fora do escopo (outro arquiteto) |

**Estado atual (2026-05-13):**

- Worker NLP: **87%** linha (54 tests).
- Backend Spring: **67%** linha / **53%** branch (174 tests). Áreas críticas já passam de 90% (`InvitationService` 98.1%, `PolicyEvaluator` 95.8%, `AuthService` 93.2%, `AuthorizationService` 89.9%).
- Web: 0% (sem runner).

> **Caveat (2026-05-21):** números medidos em 2026-05-13, **antes** da onda de hardening #114–#138 (RLS aspect, token rotation, RS256/JWKS, composite FK, auth audit) que tocou áreas críticas de Auth/IAM. **Re-medir** (`mvn verify` + `pytest`) antes de citar no pitch; o worker ainda não declara `pytest-cov` (adicionar — ADR 0018).

### Definition of Done

Uma story só é DONE quando:

- Código implementado.
- Testes relevantes adicionados/atualizados (cobertura nas áreas críticas atinge targets).
- Verificação local executada (`mvn test`, `pytest`, `npm run build`).
- Sem segredo ou dado sensível no commit.
- Documentação ajustada se mudou contrato, arquitetura ou escopo.
- PR revisado por outra pessoa **ou por IA em modo review** + validação humana.

---

## 10. Git, Issues e PRs

### Branches

- `main`: sempre estável (deploys disparam daqui).
- `feat/us11-meeting-summary`, `feat/sub-1.10-docs-refresh`, `fix/tenant-scope-leak`, `docs/standards-refresh`.

### Commits

Conventional Commits:

- `feat(api): add meeting upload endpoint`
- `feat(worker): extract action items from transcript`
- `fix(api): enforce tenant scope on meeting lookup`
- `docs(engineering): refresh standards.md to match current code`
- `chore(infra): bump Container App image to sha-abc123`

### PRs

Cada PR contém:

- Story/issue relacionada (ou Sub-fase X.Y se for tarefa estrutural).
- O que mudou (resumo de 2-3 linhas).
- Como foi testado.
- Riscos de segurança/multi-tenant (mesmo que seja "nenhum, mudança visual").
- Screenshots quando for UI.

---

## 11. Uso de IA no projeto

### Antes de pedir código

Forneça ao agente:

- Story ID do backlog (ou Sub-fase).
- Arquivos relevantes ancorados (`path:linha` quando aplicável).
- Escopo do que pode mudar.
- Critério de aceite.
- Comando de teste esperado (`mvn test`, `pytest -q`, etc.).

### Prompt recomendado

```text
Você está no projeto NORA. Leia CLAUDE.md e docs/engineering/standards.md.
Implemente a US## do docs/product/backlog.md apenas no service X.
Não altere escopo fora dessa story.
Adicione testes e explique como validar.
Ancore cada afirmação técnica em path:linha ou ADR.
```

### Divisão de modelos

- **Opus 4.7**: arquitetura, revisão de segurança, modelagem de dados, refatorações críticas, desenho de contratos, ADRs.
- **Opus 4.6 / Sonnet**: implementação focada, testes, componentes de UI, CRUD, fixtures, documentação localizada.

---

## 12. ADRs como referência

Decisões arquiteturais duráveis ficam em `docs/adr/NNNN-titulo.md`. Toda nova feature que tome decisão de difícil reversão (banco, framework, modelo de tenancy, formato de IA) **deve** criar ADR.

ADRs (estado em 2026-05-19, pós-Sub-fase 1.10):

| ID | Decisão | Status |
|---|---|---|
| 0001 | Monorepo com pastas por aplicação/serviço | aceito |
| 0002 | Multi-tenancy: filtro de app no MVP, RLS em produção | aceito |
| 0003 | Saída do LLM via JSON Schema strict obrigatório | aceito |
| 0004 | Estratégia de provider LLM (agnóstica) | aceito |
| 0005 | Productivity Score (opt-in por reunião) | aceito |
| 0006 | Customer Confidence + Account Health | aceito (persistência: ADR 0015) |
| 0007 | IAM AWS-style (Root + Users + Groups + Policies) | aceito |
| 0008 | Desktop com Tauri 2 + sidecar Python | aceito |
| 0009 | Speech Token Broker (Azure Speech credenciais) | aceito |
| 0010 | Package `nlp-baseline` para TF-IDF PT-BR | aceito |
| 0011 | Invite flow + corporate domain | aceito |
| 0012 | PII PERSON_NAME (BR no MVP, NER pós) | aceito |
| 0013 | Frontend CSS strategy (Tailwind cru, sem shadcn) | proposto (Design refina) |
| 0014 | Defer post-MVP scope (US deferidas explicitamente) | aceito |
| 0015 | Customer Confidence persistência mínima na Sub-fase 1.11 | aceito |
| 0016 | Production-readiness backlog (Sub-fase 1.12) | proposto |
| 0017 | License AGPL-3.0 | aceito |
| 0018 | Test coverage targets por área crítica | aceito |

Quando criar ADR:

- Decisão difícil de reverter (banco, framework, modelo de tenancy, formato de IA).
- Decisão que vai surpreender quem chegar depois.
- Decisão tomada após descartar pelo menos **uma alternativa real**.

---

## 13. Notas técnicas relevantes (atualizações pós-MVP inicial)

### PII Shield com PERSON_NAME (ADR 0012)

A versão atual cobre EMAIL, PHONE, CPF, CNPJ, CREDIT_CARD **e** PERSON_NAME (heurísticas + lista BR de ~270 nomes + negative list de ~80 termos). ADDRESS está fora de escopo MVP.

Implementação: `services/nlp-worker/src/nora_nlp/services/pii_shield.py`.

### Refresh tokens stateful (Sub-fase 1.3 / PR #59)

Login emite dois cookies HttpOnly:

- `nora_access` — JWT (15 min), `SameSite=Lax`, `Path=/`.
- `nora_refresh` — UUID opaco (30 dias), `SameSite=Strict`, `Path=/auth`. Persistido como SHA-256 hash em `refresh_tokens` (V011).

`POST /auth/refresh` rotaciona o access; `POST /auth/logout` revoga o refresh do cookie.

### Productivity Score & Customer Confidence

- **Productivity Score (ADR 0005, Sub-fase 1.8)**: persistido (V012). Opt-in por reunião via `MeetingGoal`. UI renderiza `ProductivityScoreCard` apenas quando `productivity` está presente.
- **Customer Confidence (ADR 0006)**: **schema LLM completo** em `meeting-analysis-v1.schema.json:117-167`, mas **sem tabelas Postgres** e sem endpoint. Implementação mínima formalizada em **ADR 0015** (aceito 2026-05-14) — migration V013 + endpoint READ planejados pra Sub-fase 1.11.

### Speech Token Broker (ADR 0009)

Desktop chama `POST /speech/token` (autenticado JWT) e recebe token efêmero (~9 min) emitido pelo backend usando `AZURE_SPEECH_KEY` do Key Vault. Desktop **nunca** vê a key. Rate limit 6 tokens/min/user (Bucket4j).

### CI web: alinhado em `npm` (resolvido 2026-05-21)

O job `web` do `.github/workflows/ci.yml` usa **`npm ci`** (cache npm via `package-lock.json`), consistente com o `apps/web/Dockerfile` (`npm ci` → imagem deployada), o `Makefile` e o `package-lock.json` commitado. `apps/web` é projeto **npm** (não há `pnpm-lock.yaml` nem campo `packageManager`).

Histórico: até 2026-05-21 o job usava `pnpm install --no-frozen-lockfile`, que **ignorava** o `package-lock.json` e resolvia uma árvore de dependências própria — ou seja, o CI validava algo potencialmente diferente do artifact que o Dockerfile builda e deploya. A doc anterior afirmava falsamente que "PR #73 unificou" para npm. Corrigido alinhando o CI ao npm.

---

## 14. Qualidade esperada

A régua é alta: código limpo, modular, testável e pronto para evoluir. Uso de IA acelera implementação mas **não substitui** arquitetura, contratos claros e revisão humana. A NORA deve parecer um produto real desde a primeira demo funcional.
