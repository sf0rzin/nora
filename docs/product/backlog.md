---
title: "Backlog — NORA"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.1
last_reviewed: 2026-06-06
---

# Backlog — NORA

> Backlog vivo do MVP, mantido em formato MoSCoW com **status real por user story** (DONE / PARTIAL / MISSING). Atualizado 2026-05-14, base no audit `2026-05-13-audit-pre-subfase-1.10.md` §2 e §3.
>
> Documento substitui `docs/backlog-mvp.md` (movido para cá). Fonte de verdade dos status: PRs mergeados em `main`, migrations `services/api/src/main/resources/db/migration/V*.sql`, audit retro-ativo.
>
> **Reconciliado 2026-05-21 (pós-PR #148):** Customer Confidence (US48-49) passou de PARTIAL → **DONE** full-stack. O audit `2026-05-13` (e a reconciliação doc×código de 2026-05-21 que o precedeu no mesmo dia) foram escritos **antes** do #148 mergear; este doc reflete o estado pós-merge.
>
> Para entender o histórico de execução das sub-fases que entregaram cada status, ver `docs/product/roadmap.md`.

---

## 1. Épicos

| ID | Épico | Tier | Descrição |
|---|---|---|---|
| **E1** | Identidade & Acesso | Core + Enterprise | Cadastro, login, recuperação de senha, convites, SSO pós-MVP, IAM/RBAC |
| **E2** | Ingestão de Reuniões | Core + Enterprise | Upload textual no MVP; áudio e captura ao vivo no roadmap |
| **E3** | Processamento IA | Core + Enterprise | Transcrição, NLP, resumo, extração de tarefas, embeddings |
| **E4** | Dashboard & Insights | Core + Enterprise | Visualização de reuniões, busca, filtros, histórico |
| **E5** | Gestão de Tarefas | Core | Tarefas extraídas, status, atribuição, exportação |
| **E6** | Integrações MCP | Core | Conexão com Claude MCP, Google Calendar, task managers |
| **E7** | Administração Enterprise | Enterprise | Configuração de tenant, contexto da empresa, gestão de usuários |
| **E8** | IAM Enterprise (estilo AWS) | Enterprise | Root user, Users, Groups e Policies (Effect/Action/Resource[/Condition]) gerenciados pelo próprio tenant |
| **E9** | Produtividade da Reunião | Core + Enterprise | Avaliação opt-in: usuário declara objetivo e outcomes esperados; NORA mede cobertura e atribui Productivity Score |
| **E10** | Customer Confidence & Account Health | Enterprise | Confiança do cliente/lead na empresa avaliada por reunião; Account Health Score agregado temporal |

---

## 2. Priorização MoSCoW + Status Real

> **M** = Must Have · **S** = Should Have · **C** = Could Have · **W** = Won't Have (v1)
>
> **Status:**
> - **DONE** = implementado, mergeado, no fluxo (com ou sem débito menor)
> - **PARTIAL** = pedaço entregue, faltam pontos
> - **MISSING** = não implementado

---

### E1 — Identidade & Acesso

| ID | Título | MoSCoW | Status | Evidência | Débito conhecido |
|---|---|---|---|---|---|
| US01 | Criar conta com e-mail e senha | M | DONE | `AuthController.signup` (`services/api/.../AuthController.java:63-75`) · PR #4 | — |
| US02 | E-mail de verificação pós-cadastro | M | DONE | `AuthController.verifyEmail` (linha 77-80) · migration V003 · PR #4 | Entrega de e-mail depende de adapter Resend/log (configurável por env) |
| US03 | Login e-mail/senha | M | DONE | `AuthController.login` (linha 83-108) · PRs #4 + #59 | — |
| US04 | Redefinir senha via link | M | DONE | `AuthController.requestPasswordReset` + `confirmPasswordReset` (linha 143-161) · PRs #4, #47 | — |
| US05 | SSO corporativo (Google/Entra ID/SAML) | **W** | MISSING | Marcado W no backlog original | Pós-MVP. Deferido em bloco via ADR 0014 |
| US06 | Convite ao tenant por e-mail corporativo | M | DONE | `InvitationController` · migration V010 · ADR 0011 · PR #55 | — |

### E2 — Ingestão de Reuniões

| ID | Título | MoSCoW | Status | Evidência | Débito conhecido |
|---|---|---|---|---|---|
| US07 | Upload de transcrição (`.txt`, `.vtt`, `.srt`) | M | DONE | `MeetingsController.upload` (linha 98-136) · migration V004 · PR #5 | — |
| US08 | Upload de áudio/vídeo (`.mp3`, `.mp4`) | **W** | MISSING | `ALLOWED_FORMATS = {TXT,VTT,SRT}` em `MeetingsController.java:66` | Pós-MVP. Deferido em bloco via ADR 0014 |
| US09 | Captura ao vivo no Desktop | **W** (declarado W no backlog, mas **implementado**) | DONE | `apps/desktop/src-tauri/.../system_audio.rs`, `audio_capture.rs`, `stt_sidecar.rs` · PRs #8, #65 · ADRs 0008 + 0009 | Validação em ambiente Windows/Teams real ainda pendente. macOS via BlackHole funciona; ScreenCaptureKit nativo é nice-to-have (escopo do colaborador) |
| US10 | Nomear e categorizar reunião no upload | S | DONE | `MeetingUploadMetadata` aceita `title` e `tags` (`MeetingsController.java:107,120`) | — |

### E3 — Processamento IA

| ID | Título | MoSCoW | Status | Evidência | Débito conhecido |
|---|---|---|---|---|---|
| US11 | Resumo automático da reunião | M | DONE | `services/nlp-worker/src/.../llm_analyzer.py` + `stub_analyzer.py` · schema canônico em `docs/api/llm-schemas/meeting-analysis-v1.schema.json` · PRs #7, #32 | — |
| US12 | Tarefas e decisões extraídas | M | DONE | `actionItems` + `decisions` em `MeetingAnalysisV1` · endpoint `/tasks` | — |
| US13 | Identificar participantes mencionados | S | **PARTIAL** | `Participant` model em `services/nlp-worker/src/.../models.py:104-109` · migration V004 | Sem dedup nem matching de participantes entre reuniões |
| US14 | Contexto da empresa injetado no LLM | M | DONE | `TenantContextController` · migration V005 · injetado no prompt | — |
| US15 | Busca semântica por embeddings | S | DONE | Embeddings provider-agnóstico (Gemini/OpenAI) via pgvector + HTTP embedding client: `EmbeddingService.java` · `HttpEmbeddingClient.java` · migration V021 (`meeting_embeddings`) · `RagSearchIntegrationTest.java` · `GET /meetings/search` consumido pelo chat Core como contexto RAG · **PR #206** | — |

### E4 — Dashboard & Insights

| ID | Título | MoSCoW | Status | Evidência | Débito conhecido |
|---|---|---|---|---|---|
| US16 | Painel cronológico de reuniões | M | DONE | `MeetingsController.list` · `apps/web/src/app/(app)/dashboard/page.tsx` | — |
| US17 | Detalhe de uma reunião | M | DONE | `MeetingsController.get` · `apps/web/src/app/(app)/meetings/[id]/page.tsx` | — |
| US18 | Busca por palavra-chave/período | M | DONE | `list` aceita `search`, `from`, `to` (`MeetingsController.java:140-145`) | — |
| US19 | Visibilidade escopo-restrita por IAM | M | DONE | `AuthorizationService.isAllowed` + `IamScopingIntegrationTest` · PR #35 | `PolicyEvaluator` suporta `StringEquals`/`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` (Sub-fase 1.11c) |
| US20 | Root vê tudo do tenant | M | DONE | Bypass em `AuthorizationService` · `PolicyEvaluator.java:14` | — |
| US21 | Painel de tendências (temas + carga tarefas) | C | MISSING | Sem endpoint nem componente | Deferido em bloco via ADR 0014. Reativar quando US15 ligada (depende de embeddings/análise temporal) |

### E5 — Gestão de Tarefas

| ID | Título | MoSCoW | Status | Evidência | Débito conhecido |
|---|---|---|---|---|---|
| US22 | Lista consolidada de tarefas | M | DONE | `TasksController.list` · `apps/web/src/app/(app)/tasks/page.tsx` | — |
| US23 | Marcar tarefa como concluída | M | DONE | `TasksController.update` (linha 53-77) | — |
| US24 | Editar texto de tarefa | S | DONE | `update` aceita `title` | — |
| US25 | Exportar tarefas em CSV/MD | S | MISSING | Sem endpoint | Deferido em bloco via ADR 0014. Reativar quando feedback de pilots indicar uso fora do app |
| US26 | Data limite em tarefa | C | **PARTIAL** | Coluna `due_date` em migration V005:82 | UI de seleção de data não inspecionada (PARTIAL conservador) |

### E6 — Integrações MCP

| ID | Título | MoSCoW | Status | Evidência | Débito conhecido |
|---|---|---|---|---|---|
| US27 | MCP Claude | **W** | MISSING | Sem código. MCPs seguem como conceito de roadmap (sem pasta dedicada no repo) | Pós-MVP. Deferido em bloco via ADR 0014 |
| US28 | MCP Google Calendar | **W** | MISSING | — | Pós-MVP. Deferido em bloco via ADR 0014 |
| US29 | MCP task managers (Linear/Jira/Notion) | **W** | MISSING | — | Pós-MVP. Deferido em bloco via ADR 0014 |

### E7 — Administração Enterprise

| ID | Título | MoSCoW | Status | Evidência | Débito conhecido |
|---|---|---|---|---|---|
| US30 | Configurar contexto da empresa | M | DONE | `TenantContextController` · migration V005 · PR #33 | — |
| US31 | Histórico de versões do contexto da empresa | S | MISSING | V005 só tem `created_at`/`updated_at`. `data-model.md` previu coluna `version` mas migration não inclui | Débito: migration V014 trivial (S). Deferido em bloco via ADR 0014. Reativar antes de prod GA (compliance LGPD precisa) |
| US32 | Domínio corporativo do tenant | M | DONE | `TenantController.updateDomain` · migration V009 · ADR 0011 · PR #55 | — |
| US33 | Métricas de uso do tenant | S | MISSING | Sem endpoint | Deferido em bloco via ADR 0014. Reativar quando 5+ tenants ativos em pilot pagar para ver ROI |
| US34 | Export relatório consolidado do período | S | MISSING | Sem endpoint | Deferido em bloco via ADR 0014. Reativar quando US33 entregue (dependência de agregações) |

### E8 — IAM Enterprise (estilo AWS)

| ID | Título | MoSCoW | Status | Evidência | Débito conhecido |
|---|---|---|---|---|---|
| US35 | Criar grupos IAM | M | DONE | `IamController.createGroup` · migration V006 · PR #35 | — |
| US36 | Criar e versionar policies JSON | M | DONE | `createPolicy`/`updatePolicy` · tabela `iam_policy_versions` em V006 | Conditions: `StringEquals`/`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` (Sub-fase 1.11c) |
| US37 | Anexar/desanexar policies a grupos e users | M | DONE | `attachToGroup`/`attachToUser` etc | — |
| US38 | Adicionar/remover users de grupos | M | DONE | `addMember`/`removeMember` | — |
| US39 | HTTP 403 claro fora do escopo | M | DONE | `GlobalExceptionHandler` | Detalhe da mensagem de erro estável não conferido em detalhe |
| US40 | Audit log IAM | M | DONE | `IamController.listAudit` · tabela `iam_audit_events` em V006 | Audit log de auth (login/logout/refresh) ausente — pattern atual é IAM-only |
| US41 | Templates de policy | S | MISSING | Sem endpoint. Coluna `is_template` não em V006 | Deferido em bloco via ADR 0014. Reativar quando >3 tenants pedirem onboarding rápido |
| US42 | Editor visual de policy (form-based) | S | **PARTIAL** | Monaco JSON em `apps/web/src/components/.../policy-editor.tsx` (PR #55). É JSON com syntax highlighting + schema validation, não form-based | Reativar form-based quando US43 (simulator) estiver online — usabilidade aumenta junto |
| US43 | Simulador de policy ("pode user X fazer Y em Z?") | S | MISSING | Sem endpoint | Deferido em bloco via ADR 0014. Reativar antes do primeiro pilot pago — sem isso, debug de policies é cego |
| US44 | Permission boundaries | C | MISSING | Sem código | Deferido em bloco via ADR 0014. Reativar quando hierarquia organizacional + delegação de IAM virar necessidade (provavelmente Pilot+1) |

### E9 — Produtividade da Reunião

| ID | Título | MoSCoW | Status | Evidência | Débito conhecido |
|---|---|---|---|---|---|
| US45 | Productivity Score opt-in (declarar objetivo) | M | DONE | `MeetingsController.putGoal/deleteGoal` (linha 258-292) · migration V012 · ADR 0005 · PR #67 | — |
| US46 | Productivity Score 0-100 + coverage por outcome | M | DONE | `ProductivityAssessment` model + schema + UI components (`MeetingProductivitySection`, `ProductivityScoreCard`) | — |
| US47 | MCP project state (pull Jira/Linear/Azure DevOps) | **W** | MISSING | — | Pós-MVP. Deferido em bloco via ADR 0014 |

### E10 — Customer Confidence & Account Health

| ID | Título | MoSCoW | Status | Evidência | Débito conhecido |
|---|---|---|---|---|---|
| US48 | Customer Confidence Score 0-100 com sinais e objeções | M | **DONE** | Migration `V017__create_customer_confidence.sql` (5 tabelas + RLS) · worker emite `customerConfidence` (`models.py:252` + stub + prompt) · persistido no pipeline (`AnalysisService.java:127` → `CustomerConfidenceService.persist`) · `GET /meetings/{id}` retorna `customerConfidence` (`MeetingDetailResponse`) · UI `CustomerConfidenceCard` (`meetings/[id]/page.tsx:182`) · **PR #148 (2026-05-21)** | Entregue como **V017** (o slot V013 do ADR 0015 acabou em soft-delete, #114). Account Health **agregado** (US50-51) segue deferido (ADR 0014) |
| US49 | Trend `IMPROVING`/`STABLE`/`DECLINING` | M | **DONE** | Trend **autoritativo no servidor**: `CustomerConfidenceService.computeTrend` compara com a avaliação anterior da conta (banda morta ±5 pts), persistido em `customer_confidence_assessments.trend` · PR #148 | Palpite de trend do worker é ignorado (backend é fonte da verdade) |
| US50 | Account Health Score agregado por conta | S | MISSING | `docs/data-model.md:437-453` prevê `account_health_snapshots` mas sem migration | Deferido em bloco via ADR 0014. Reativar pós-pilot quando 3+ tenants tiverem dados suficientes para agregar |
| US51 | Alerta quando Account Health muda de banda | S | MISSING | Sem código | Deferido em bloco via ADR 0014. Reativar junto com US50 |

---

## 3. Frentes implementadas além do backlog original

Trabalho que não estava no MoSCoW original mas entrou via sub-fases ou decisão arquitetural:

| Item | PR | ADR | Status |
|---|---|---|---|
| Refresh tokens stateful + cookies httpOnly | #59 | — | DONE (migration V011, cookie `nora_refresh` 30d, access `nora_access` 15min) |
| PII Shield com PERSON_NAME (BR) — ~270 nomes + negative list | #59 | ADR 0012 | DONE |
| Speech Token Broker (token efêmero Azure Speech) | #29 | ADR 0009 | DONE |
| Live analysis endpoint (Desktop overlay highlights) | #65 | — | DONE (`POST /meetings/live-analyze`) |
| TF-IDF baseline package (`packages/nlp-baseline/`) | #54 | ADR 0010 | DONE (3 módulos, 52 testes) |
| Dataset sintético expandido (12 .txt + 3 .vtt + 2 .srt + 3 contextos JSON) | #54 | — | DONE |
| Notebook DS Sprint 1+2 (`notebooks/01-tf-idf-eda-meetings.ipynb`) | #54 | — | DONE (26 células) |
| Productivity Score full-stack | #67 | ADR 0005 | DONE (V012 + worker + backend + web 3 componentes) |
| Visual redesign editorial v2 (NoraLogo soundwave, paleta clara, Inter + Instrument Serif) | #56, #58 | — | DONE |
| Bicep IaC completo (9 módulos + main + bicepparam) | #62 | — | DONE |
| Build/push GHCR pipeline (3 imagens) | #63 | — | DONE |
| Deploy Azure via OIDC (`deploy-infra.yml`) | #64 | — | DONE |
| Customer Confidence schema LLM | #25 | ADR 0006 | DONE (schema) |
| Customer Confidence full-stack (persistência + worker emit + endpoint + UI) | #148 | ADR 0015 | DONE (V017 + `AnalysisService` wiring + trend server-side + `CustomerConfidenceCard`) |
| `meeting_attributes` JSONB + índice GIN | V007 + V008 | ADR 0007 | DONE (atributos arbitrários para IAM conditions) |
| Reprocessamento de reuniões | #46 | — | DONE (`POST /meetings/{id}/reprocess`) |
| CORS configurável por env | #42 | — | DONE (`CORS_ALLOWED_ORIGINS` em `application.yml`) |
| Skill `arquiteto-nora` para Claude Code | #53 | — | DONE (em `.claude/skills/`) |

### Onda de hardening pós-1.10 (audit follow-ups #114–#138)

Frente de segurança/infra que entrou após a Sub-fase 1.10, rotulada "audit follow-up #N". Documentada retroativamente em **ADR 0019** (RLS + FK composta), **ADR 0020** (token rotation) e **ADR 0021** (soft-delete) na auditoria 2026-05-21.

| Item | PR | Migration | Status |
|---|---|---|---|
| Soft-delete (`deleted_at` + `@SQLRestriction` + UNIQUEs parciais) | #114 | V013 | DONE |
| Refresh-token rotation + reuse-detection (token families) | #116 | V014 | DONE |
| JWT RS256 + endpoint JWKS (`/.well-known/jwks.json`) | #117 | — | DONE |
| Audit log de auth expandido (login/refresh/logout) | #118 | — | DONE |
| App Insights Java Agent + role names | #136 | — | DONE |
| Composite FK isolamento `meetings.(tenant_id,owner_user_id)→users` | #137 | V015 | DONE |
| **Row-Level Security** (`tenant_isolation` + `TenantRlsAspect`) | #138 | V016 → V019/V020 | DONE (schema V016 + RLS completa/scope auth-aware V019/V020; runbook de cutover em ADR 0026/0028). Resta o cutover/enforcement operacional em prod, não o schema |

---

## 4. Resumo do Estado (2026-05-14)

| MoSCoW | Total | DONE | PARTIAL | MISSING |
|---|---|---|---|---|
| **Must Have (M)** | 31 | **29** | **0** | **2** (US05*, US08*) |
| **Should Have (S)** | 15 | **7** | **2** (US13, US42) | **6** (US25, US31, US33, US34, US41, US43) |
| **Could Have (C)** | 5 | — | **1** (US26) | **4** (US21, US44, etc) |
| **Won't Have v1 (W)** | 7 | **1** (US09) | — | **6** |
| **Total** | **58** | **37** | **3** | **18** |

> *US05 e US08 são `M` no MoSCoW original mas foram **rebatizadas como W via decisão de escopo** (CLAUDE.md + PROJECT.md). Aqui contam como MISSING/W na prática.

**Cobertura efetiva do MVP** (M + S desejáveis para demo):
- Must Have entregue: **29 de 31** (94%) — Customer Confidence (US48-49) foi entregue full-stack em #148; restam só US05/US08 (rebatizadas W)
- Should Have entregue: **9 de 14** (64%) — gap principal é exportação, métricas tenant, simulator de policy

**Frentes que destacam o produto além do MoSCoW** (12 itens): Productivity Score full-stack, PII PERSON_NAME, Bicep IaC, deploy real Azure, dataset sintético + notebook DS, refresh tokens, Live analysis, redesign visual.

---

## 5. Decisão "Deferir Pós-MVP" — ADR 0014

> Aprovada em bloco pela Stratfy em 2026-05-14. Esta decisão fechou 14 US como **Won't Have v1** com critério de reativação documentado. **Atualização:** US15 (busca semântica) foi subsequentemente entregue em PR #206 — ver nota na tabela abaixo.

**Critério geral:** as US abaixo foram adiadas para liberar foco em Sub-fase 1.11 (Demo Polish Plano A) e 1.12 (Production Hardening). Nenhuma bloqueia o pitch FIAP × TOTVS (15/06/2026) nem o Plano A imediato.

| US | Título | Critério de reativação |
|---|---|---|
| US05 | SSO Entra ID/SAML | Quando primeiro tenant Enterprise pago exigir explicitamente (sinal comercial concreto) |
| US08 | Upload de áudio/vídeo | Quando demanda repetida em pilot indicar (>30% dos uploads são áudio) ou Azure Speech batch ficar barato (R$5/h) |
| US15 | Busca semântica por embeddings | **Não mais deferida** — entregue em PR #206 (embeddings provider-agnósticos via pgvector + HTTP embedding client; migration V021). Ver E3 / US15 acima |
| US21 | Painel de tendências (temas + carga tarefas) | Depois de US15 ligada. Sem embeddings/análise temporal o painel é raso |
| US25 | Export CSV/MD de tarefas | Quando feedback de pilot indicar uso fora do app (>2 tenants pedindo) |
| US31 | Histórico de versões do contexto da empresa | Antes de prod GA — compliance LGPD precisa de audit trail no contexto. Migration trivial (V014) |
| US33 | Métricas de uso por tenant | Quando 5+ tenants pagantes em pilot — sem dados de base não vale construir |
| US34 | Export relatório consolidado | Junto com US33 (dependência de agregações) |
| US41 | Templates de policy | Quando >3 tenants pedirem onboarding rápido com policies pré-feitas |
| US43 | Simulador de policy | **Antes** do primeiro pilot pago — sem isso, debug de policies é cego. Probabilidade alta de subir na 1.11 |
| US44 | Permission boundaries | Quando hierarquia organizacional + delegação de IAM virar necessidade real (Pilot+1) |
| US47 | MCP project state | Quando primeiro tenant pedir integração Jira/Linear para Productivity Score |
| **US50-51** | **Account Health agregado + alertas** | US48-49 (Customer Confidence por reunião) foi entregue em #148 via ADR 0015. O conjunto **agregado** (Account Health Score temporal + alertas de banda) segue deferido: pós-pilot quando 3+ tenants tiverem >10 reuniões para agregar |

> Critério de reativação por US é descritivo, não bloqueante. Sub-fase 1.13+ pode pegar qualquer um se contexto justificar.

---

## 6. Bug visual conhecido

Versão anterior deste documento (`docs/backlog-mvp.md`, antes de 2026-05-14) tinha header duplicado em linhas 158-159 da tabela "Resumo de Prioridades" — corrigido nesta versão.

---

## 7. MVP — Escopo da Versão 1.0

O MVP da NORA v1.0 contempla exclusivamente as stories classificadas como **Must Have**, distribuídas nos três fluxos centrais:

### Fluxo 1 — Usuário Core (Lucas)
1. Criar conta e fazer login
2. Fazer upload de transcrição textual
3. Receber resumo, decisões e tarefas extraídas
4. Visualizar e gerenciar tarefas extraídas
5. Buscar reuniões no histórico

### Fluxo 2 — Root do tenant Enterprise (Camila)
1. Configurar tenant com domínio corporativo
2. Convidar usuários; criar **grupos** e **políticas** estilo AWS
3. Anexar políticas a grupos/usuários; adicionar usuários a grupos
4. Configurar contexto da empresa (product context injection)
5. Visualizar todas as reuniões do tenant (Root tem bypass)
6. Auditar mudanças de IAM

### Fluxo 3 — Usuário Enterprise (Rafael)
1. Aceitar convite e fazer login com e-mail/senha corporativo
2. Visualizar apenas as reuniões permitidas pelas políticas IAM aplicáveis ao seu usuário/grupos
3. Acessar resumo e tarefas das reuniões visíveis
4. Receber mensagem clara (HTTP 403) ao tentar acessar conteúdo fora das permissões

---

## 8. Critérios de Aceitação — Stories Críticas

### US11 — Gerar resumo da reunião

**Dado que** uma reunião foi processada com sucesso,
**quando** o usuário acessa o detalhe da reunião,
**então** deve ver um resumo em português com: objetivo da reunião, principais pontos discutidos, decisões tomadas e próximos passos.

**Regras de negócio:**
- Resumo deve ter entre 150 e 500 palavras
- Deve ser gerado em até 30 segundos após o processamento
- Deve usar o contexto da empresa (Enterprise) quando disponível

---

### US14 — Contexto da empresa no processamento

**Dado que** o admin configurou o contexto da empresa,
**quando** uma reunião do tenant é processada pela NORA AI,
**então** o resumo e as tarefas geradas devem refletir a terminologia e prioridades configuradas no contexto.

**Regras de negócio:**
- Contexto é injetado como instrução base no prompt da IA
- Atualizar o contexto não reprocessa reuniões antigas
- O contexto é isolado por tenant (não vaza entre empresas)

---

### US19 — Visibilidade escopo-restrita (Enterprise)

**Dado que** um usuário Enterprise tem políticas IAM que limitam seu acesso (ex.: condition `nora:Department = "sales"`),
**quando** ele acessa o painel de reuniões,
**então** vê apenas reuniões cujos atributos satisfazem as políticas Allow aplicáveis e não caem em políticas Deny.

**Regras de negócio:**
- Filtro aplicado no backend (não apenas no frontend)
- Tentativa de acesso direto por URL a recurso fora das permissões retorna `403`
- Root do tenant tem bypass total e vê tudo

---

### US36 — Políticas IAM (Effect/Action/Resource/Condition)

**Dado que** o Root acessa "Configurações > IAM > Políticas",
**quando** ele cria uma nova política enviando um documento JSON com `version`, `statements[]` (cada um com `effect`, `action[]`, `resource[]` e `condition` opcional),
**então** a política deve ser persistida com versão 1, validada contra o schema oficial e disponível para anexação a grupos/usuários.

**Regras de negócio:**
- Políticas são sempre escopadas ao tenant; não vazam entre tenants.
- Cada alteração cria nova versão em `iam_policy_versions` (histórico imutável).
- A avaliação segue ordem: Root → Allow; senão, **Deny** explícito vence; senão, exigir pelo menos um Allow aplicável; default Deny.
- Wildcards (`*`) são suportados em `action` e `resource`.
- Conditions usam operadores estilo AWS: `StringEquals`, `StringIn`, `StringLike`, `DateGreaterThan`, `DateLessThan` (Sub-fase 1.11c). Operadores fora dessa lista são fail-closed.
