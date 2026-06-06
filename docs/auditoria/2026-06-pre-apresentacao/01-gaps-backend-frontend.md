---
title: "Lacunas back-end × front-end (web + admin)"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
escopo: "NORA Core — superfícies web (apps/web) e admin (apps/admin). Desktop fora de escopo."
---

# Lacunas back-end × front-end (web + admin)

> Capacidades que o **back-end** (Spring `services/api`) ou o **worker** expõem, mas que
> o **front-end** (web e/ou admin) ainda não consome — ou consome apenas com *mock*.
> Cada lacuna foi confirmada por **verificação adversarial**: um agente independente
> tentou refutá-la procurando consumo real no front. Só as confirmadas estão aqui.

## Legenda

- **Severidade**: relevância de produto (alta / média / baixa).
- **Demo 15/06**: `crítico` (bloqueia o fluxo da demo) · `desejável` (agrega valor, não bloqueia) · `pós-MVP`.
- **Status front**: `ausente` · `parcial` (consumido de forma limitada) · `órfão` (wrapper existe no `api-client` mas sem nenhum *call-site*).

---

## Visão geral priorizada

| # | Capacidade | Endpoint | Superfície | Severidade | Demo 15/06 | Esforço |
|---|---|---|---|---|---|---|
| 1 | Reprocessar reunião FAILED/COMPLETED | `POST /meetings/{id}/reprocess` | web | **alta** | **crítico** | baixo |
| 2 | Direito ao esquecimento (LGPD) | `DELETE /privacy/meetings/{id}` | web | **alta** | desejável | baixo |
| 3 | Telemetria — métricas de negócio | `GET /admin/platform/telemetry/business` | admin | média | desejável | médio |
| 4 | Telemetria — saúde do sistema | `GET /admin/platform/telemetry/health` | admin | média | desejável | médio |
| 5 | Busca semântica em tela dedicada | `GET /meetings/search` | web | média | desejável | médio |
| 6 | Editar documento de policy (versionar) | `PUT /iam/policies/{id}` | web | média | desejável | baixo |
| 7 | Remover objetivo da reunião | `DELETE /meetings/{id}/goal` | web | média | desejável | baixo |
| 8 | Detalhe de policy + membros de grupo | `GET /iam/policies/{id}`, `GET /iam/groups/{id}/members` | web | baixa | pós-MVP | médio |
| 9 | Filtros de telemetria de custo (período/agrupamento) | `GET /admin/platform/telemetry/cost?groupBy` | admin | baixa | pós-MVP | baixo |
| 10 | Campos `baseUrl`/`priceCachedInputPerMTok` no form de modelos | `POST /admin/platform/models` | admin | baixa | pós-MVP | baixo |
| — | Live analysis (`POST /meetings/live-analyze`) | — | — | — | **não é gap do web** | — |
| — | Speech token broker (`POST /speech/token`) | — | — | — | **não é gap do web** | — |

> Os dois últimos itens foram **investigados e descartados** como gaps de produto: são
> consumidos corretamente pelo Desktop (Tauri) e só fariam sentido nele. Documentados
> na seção 3 para evitar que reapareçam como "pendência" em auditorias futuras.

---

## 1. Crítico para 15/06

### 1.1 Reprocessar reunião (web)

- **Back-end**: `POST /meetings/{id}/reprocess` — `MeetingsController.java:344` (responde `202`).
- **Status front**: ausente no web. **O Desktop já tem** (`apps/desktop/src/lib/meetings.ts:31` + botão real em `apps/desktop/src/pages/meeting-detail.tsx:253`).
- **Evidência**: `apps/web/src/app/(app)/meetings/[id]/page.tsx:86` mostra apenas o texto estático *"A análise desta reunião falhou. Tente reprocessar."* — sem botão, sem handler. O `api-client` do web (`apps/web/src/lib/api/client.ts`) **não tem** wrapper `reprocessMeeting()`.
- **Por que é crítico**: permite recuperar uma análise que falhar **ao vivo no palco**. É o único item que pode travar o fluxo da demo.
- **Recomendação**: criar `reprocessMeeting(id)` no `api-client` (`POST /meetings/{id}/reprocess`) e adicionar o botão "Reprocessar" no bloco de erro de `meetings/[id]/page.tsx:81-89`, re-disparando o polling de status. Espelhar o que o Desktop já faz.

---

## 2. Desejáveis (agregam valor, não bloqueiam o fluxo)

### 2.1 Direito ao esquecimento — LGPD (web)

- **Back-end**: `DELETE /privacy/meetings/{id}` — `PrivacyController.java:42` (ADR 0029, *gate* `meeting:update`). Faz hard-delete do meeting + cascade do PII bruto.
- **Status front**: **ausente em todas as superfícies** (web e desktop). Nenhum *call-site* para `/privacy/meetings`.
- **Por que importa**: a **landing anuncia explicitamente** a feature — `apps/web/src/components/landing/landing-content.tsx:149-150` ("Apaga tudo permanentemente em um clique. LGPD Art. 18") e o FAQ (linha 354). Hoje é só *copy* de marketing sem implementação: descompasso entre promessa pública e produto.
- **Recomendação**: ação destrutiva "Apagar permanentemente" em `meetings/[id]` com modal de confirmação *typed-confirm* (digitar o título), tratando `404` sem vazar existência e redirecionando ao dashboard. Adicionar `deleteMeeting()` ao `api-client`. É também um ótimo diferencial de conformidade para mencionar na apresentação.

### 2.2 Telemetria — métricas de negócio (admin)

- **Back-end**: `GET /admin/platform/telemetry/business` — `PlatformAdminController.java:162`. Retorna `analyses`, `tenantsActive`, `productivityAvg`, `customerConfidenceAvg` (ligado ponta-a-ponta até `PrimaryDbBusinessMetricsSource`).
- **Status front**: *placeholder* estático. `apps/admin/src/app/telemetria/page.tsx:51-52` é um `<Placeholder>` "Próxima fatia"; `lib/data.ts` importa só `getCost`.
- **Por que importa**: são exatamente os números "wow" (produtividade média, confiança média do cliente) que **vendem o produto numa apresentação**. O back-end já calcula tudo.
- **Recomendação**: adicionar `getBusiness()` em `apps/admin/src/lib/data.ts` e renderizar os quatro indicadores na seção correspondente.

### 2.3 Telemetria — saúde do sistema (admin)

- **Back-end**: `GET /admin/platform/telemetry/health` — `PlatformAdminController.java:157` (`HealthSnapshot`: requests / failed / failureRate / p95LatencyMs por *role*, via App Insights).
- **Status front**: *placeholder* estático em `telemetria/page.tsx:48-49`. O path já consta como comentário em `apps/admin/src/lib/contracts.ts:11`.
- **Recomendação**: `getHealth()` em `lib/data.ts` + render das métricas por *role*.

### 2.4 Busca semântica em tela dedicada (web)

- **Back-end**: `GET /meetings/search?q&k` — `MeetingsController.java:115` (`MeetingSearchResponse`).
- **Status front**: parcial. O endpoint é consumido **apenas pelo BFF do chat** como contexto RAG (`apps/web/src/app/api/chat/route.ts:154`), nunca por uma tela de resultados.
- **Nuance verificada**: o campo "Buscar reuniões…" do dashboard **não** é este endpoint — `dashboard/Filters.tsx:66` faz filtro de substring via `GET /meetings?search=` (lista paginada), não busca vetorial. Os atalhos `/ buscar` e `Cmd+K` no rodapé do dashboard são `<kbd>` **decorativos** sem handler (`dashboard/page.tsx:228,231`).
- **Recomendação** (opcional para a demo): campo de busca semântica (ou paleta `Cmd+K`) que chame `GET /meetings/search` e liste resultados com `summarySnippet`.

### 2.5 Editar documento de policy IAM (web)

- **Back-end**: `PUT /iam/policies/{id}` — `IamController.java:152` (cria nova versão).
- **Status front**: **órfão**. O wrapper `updatePolicyDocument()` existe em `apps/web/src/lib/api/client.ts:475` mas **não tem nenhum chamador**. A tela de IAM (`settings/iam/page.tsx`) só importa `createPolicy`/`deletePolicy` — hoje "editar" significa excluir e recriar, **perdendo o versionamento**.
- **Recomendação**: carregar o documento da policy de volta no editor Monaco e adicionar "Salvar alterações" chamando `PUT /iam/policies/{id}`.

### 2.6 Remover objetivo da reunião (web)

- **Back-end**: `DELETE /meetings/{id}/goal` — `MeetingsController.java:330`.
- **Status front**: **órfão**. `deleteMeetingGoal()` existe em `client.ts:207` mas tem zero *call-sites*. Dá para criar/editar objetivo, mas nunca removê-lo pela interface.
- **Recomendação**: botão "Remover objetivo" no `MeetingProductivitySection`/`MeetingGoalForm`. Wrapper já pronto — esforço mínimo.

---

## 3. Pós-MVP e itens investigados-e-descartados

### 3.1 Detalhe de policy + membros de grupo (web) — pós-MVP

- `GET /iam/groups/{id}/members` tem wrapper `listGroupMembers()` (`client.ts:442`) **sem call-site**; `GET /iam/policies/{id}` não tem nem wrapper. A UX atual de IAM pede colar UUIDs à mão (`settings/iam/page.tsx:185`). Construir um painel/*drawer* que liste membros e detalhe policies substitui a UX de protótipo.

### 3.2 Filtros de telemetria de custo (admin) — pós-MVP

- `getCost()` (`lib/data.ts:78`) fixa `groupBy="service"` e nunca passa `from`/`to`. O back-end suporta `groupBy={tenant|model|service}` e janela de período (`PlatformAdminController.java:148`). Adicionar seletor de período e *toggle* de agrupamento.

### 3.3 Campos do form de modelos (admin) — pós-MVP

- O `AddModelForm` (`apps/admin/src/app/modelos/modelos-client.tsx`) não expõe `baseUrl` nem `priceCachedInputPerMTok`, embora `NewModelInput` (`lib/data.ts:113,118`) e o back-end (`PlatformAdminController.java:83`) os aceitem. Necessário para cadastrar provider self-hosted/proxy e precificar *cache hit*.

### 3.4 Itens descartados como gaps de produto (são features do Desktop)

| Capacidade | Endpoint | Por que NÃO é gap do web |
|---|---|---|
| Live analysis (overlay ao vivo) | `POST /meetings/live-analyze` (`MeetingsController.java:373`) | Consumido pelo Desktop (`apps/desktop/src-tauri/src/live_analysis.rs:103`). Só existiria no web se houvesse captura ao vivo no navegador — fora do produto. |
| Speech token broker | `POST /speech/token` (`SpeechController.java:24`) | Consumido pelo Desktop (`speech_token.rs:30`, `stt_sidecar.rs:251`). O *broker* só faz sentido no cliente de captura de áudio. |

> Registrados aqui para que não voltem a aparecer como "pendência do web" em auditorias futuras.

---

## 4. Riscos de configuração da demo + *reverse gaps*

### 4.1 Checklist de configuração antes de apresentar

> Não são código a construir — são variáveis de ambiente que, se erradas, fazem o
> produto exibir dados fictícios no palco.

- [ ] **Web**: `NEXT_PUBLIC_USE_MOCKS` **não** pode ser `true` (default já é `false`). Ligado, `listMeetings()`/`getMeeting()` servem *fixtures* JSON (`apps/web/src/fixtures/*.json`) em vez da API.
- [ ] **Admin**: `NORA_ADMIN_USE_MOCKS=false` **explícito** (o default é mock!) **+** `PLATFORM_API_BASE_URL` **+** `PLATFORM_INTERNAL_TOKEN`. Sem isso, o console mostra catálogo fictício (`deepseek-v4-flash`, `gemini-3.5-flash`), custo fixo (`1.8423 USD / 412 calls`) e qualquer mutação (criar/remover modelo, *bind*) é um *no-op* silencioso.
- [ ] Conferir `NEXT_PUBLIC_API_BASE_URL` apontando para a API correta.

### 4.2 *Reverse gaps* — o front mostra algo que o back-end não tem

A maioria é **honesta** (a UI declara "Em breve", não finge ter dados) — apenas registrada para transparência.

| Item | Arquivo | Natureza |
|---|---|---|
| Página `/integrações` — catálogo de 8 conectores MCP | `app/(app)/integracoes/page.tsx` | Honesto: tudo "Em breve". Não há back-end de MCP. Manter como roadmap. |
| Página `/projetos` — agrupamento automático | `app/(app)/projetos/page.tsx` | Honesto: *empty-state*, zero chamadas de API. Feature não implementada em nenhuma camada. |
| Botões "Continuar com Microsoft" (SSO) | `components/auth/auth-screen.tsx` | Botão morto: mostra "ainda não disponível". Não há OAuth/SAML no back-end (US05 deferida). |
| Hero composer da landing | `components/landing/landing-hero.tsx` | *Reverse gap* de marketing: o prompt vira `?q=` e leva para o signup. Esperado numa landing. |
| Campo `role` no signup | `components/auth/auth-screen.tsx` | Coletado e **descartado**: `SignupRequest` só aceita `{email, password, displayName}`. Opcional adicionar ao DTO se quiser métricas de segmentação. |
| Direito ao esquecimento na landing | `components/landing/landing-content.tsx` | **Ver item 2.1** — o back-end existe, o front não chama. Este é acionável. |

---

## Resumo de esforço

- **1 item crítico** de fluxo (reprocessar), baixo esforço.
- **2 itens críticos de configuração** (mocks web + admin), sem código.
- **6 itens desejáveis**, a maioria de baixo esforço (vários têm o wrapper pronto no `api-client`, só faltando o *call-site* na UI).
- O fluxo Core (upload → análise → resumo/decisões/tarefas → chat RAG) está **completo e real**.
