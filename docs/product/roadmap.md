# Roadmap — NORA

> Roadmap **vivo e oficial** do NORA. Substitui o antigo `docs/plano-de-execucao.md` (arquivado — descontinuado em 2026-05-14, pois descrevia divisão semana-a-semana entre dois desenvolvedores que já não corresponde ao fluxo real: hoje a equipe Stratfy executa via Claude Code com subagentes em paralelo via worktrees).
>
> **Estrutura:**
> 1. **Histórico** — todas as Sub-fases 1.0 a 1.10 com PRs e ADRs (cross-check com audit §11)
> 2. **Próximas Sub-fases** — 1.11, 1.12, 1.13+ com janela alvo, escopo e pré-requisitos
> 3. **Fase pós-MVP (longo prazo)** — visão das fases 4-9 do plano de execução original

---

## 1. Histórico — Sub-fases 1.0 a 1.10

Numeração `1.X` corresponde a uma fatia coerente de entrega, normalmente 1+ PRs mergeados que entregam um valor verificável. Sub-fases podem ser implícitas (acumuladas pré-audit) ou explícitas (planejadas + executadas).

| Sub-fase | Data | PRs | Entrega resumida | ADRs relacionados |
|---|---|---|---|---|
| **1.0 (implícita, pré-audit)** | até 2026-05-10 | #1, #3-#8, #22-#25, #29-#50 | Scaffolding monorepo; auth e-mail/senha (US01-US04) com JWT; upload textual (US07); LLM worker (US11-US14); desktop Tauri (US09); IAM AWS-style (US35-US40); Customer Confidence schema LLM (sem persistência); Productivity opt-in; auth web flow; persistência de análise no DB. Base de tudo que veio depois. | 0001-0009 |
| **1.1 — DS Sprint 1+2** | 2026-05-11 | #54 | Notebook EDA (`notebooks/01-tf-idf-eda-meetings.ipynb` com 26 células) + dataset sintético expandido (12 .txt + 3 .vtt + 2 .srt + 3 contextos JSON) + package `packages/nlp-baseline/` (3 módulos TF-IDF, 52 testes) | ADR 0010 |
| **1.2 — Enterprise Gaps** | 2026-05-12 | #55 | US32 (domínio corporativo do tenant) + US06 (convite por e-mail) + Monaco PolicyEditor JSON com syntax highlighting + schema validation. 41 testes novos. Approach: "Passo 0 contracts antes de implementar" funcionou (schema-first reduziu retrabalho) | ADR 0011 |
| **1.3 — Hardening PII + UX** | 2026-05-12 | #59 | PII Shield expansion com PERSON_NAME (BR) — ~270 nomes + negative list ~80 termos. Polling de upload (web). Markdown rendering no resumo. Cookies httpOnly (`nora_access` 15min JWT, `nora_refresh` 30d UUID stateful em V011). Débitos catalogados: auth audit log, `logoutAllSessions` sem endpoint REST, PII ADDRESS | ADR 0012 |
| **1.4 — Bicep IaC** | 2026-05-12 | #62 | `main.bicep` + 8 módulos (containerappsenv, containerapp, postgres, storage, keyvault, appinsights, loganalytics, speech) + bicepparam dev. Custo estimado dev: R$110-180/mês. CI job `infra` no `ci.yml` | — |
| **1.5 — Briefing Desktop** | 2026-05-12 | (sem PR — vault) | Vault `40-desktop-handoff/2026-05-12-update-pos-subfase-1.4.md`. Briefing pro amigo dono do desktop com contratos NDJSON Rust↔Python + roadmap macOS BlackHole (já mergeado em PR #37) + débito ScreenCaptureKit | — |
| **1.6 — Build/Push GHCR** | 2026-05-12 | #63 | Workflow `build-images.yml` + 3 Dockerfiles (api, worker, web). Publica `ghcr.io/sys0xff/nora-{api,worker,web}:{latest, sha-XXXXXXX, ref}`. Imagens Public (passo manual no GHCR settings) | — |
| **1.7 — Deploy workflow + SP OIDC** | 2026-05-12 | #64 | `deploy-infra.yml` + Service Principal `sp-nora-github-deploy` (role `Contributor` + `Role Based Access Control Administrator` em `rg-nora-dev`) + 3 federated credentials (main / pull_request / environment:dev). Lição: fed cred separada por (branch, environment) | — |
| **1.8 — Productivity Score full-stack** | 2026-05-12/13 | #67 | Migration V012 (tabelas `meeting_goals` + `productivity_assessments`) + worker model + stub + LLM analyzer + backend Spring endpoints + web 3 componentes (`MeetingGoalForm`, `MeetingProductivitySection`, `ProductivityScoreCard`). Lição: subagentes em worktree podem ter CWD diferente — atenção a paths absolutos | ADR 0005 |
| **1.9 (implícita) — Deploy real Azure** | 2026-05-13 | #68-#75 | 8 fixes de infra resolvidos: region restriction `centralus`, imagens GHCR reais (não placeholder), Azure Speech + 2 UAIs + KV references no Container Apps, env vars completas, Postgres extensions via `azure.extensions`. Deploy success: `https://nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io`. 8 pegadinhas Azure for Students catalogadas (region restriction, offer restriction por serviço, RP não auto-registra, KV soft-delete, Cognitive Services soft-delete, networkAcls SpeechServices, Contributor não cria role assignments, Postgres CREATE EXTENSION bloqueado) | — |
| **1.10 — Docs Refresh** | 2026-05-13/14 | #76 | Audit `2026-05-13-audit-pre-subfase-1.10.md` (13 seções) + reorganização `docs/` em `product/` + `engineering/` + `operations/` + `security/` + `challenge/` + `adr/`. Skill `arquiteto-nora` polimórfica reescrita. LICENSE AGPL-3.0 + SECURITY.md criados. Memory consolidada. Coordenação async via vault em 2 ciclos (audit Tech Lead → revisão crítica Arquiteto Design → resposta Tech Lead → aprovação Stratfy (PO) em bloco) | **6 ADRs novos: 0013 (CSS strategy, proposto — Design refina) · 0014 (defer 14 US pós-MVP, aceito em bloco) · 0015 (Customer Confidence persistência mínima na 1.11, aceito voto a) · 0016 (production-readiness checklist, proposto — aceita na 1.12) · 0017 (LICENSE AGPL-3.0, aceito) · 0018 (test coverage targets, aceito)** |

| **Pós-1.10 — Hardening (audit follow-ups)** | 2026-05-15+ | #114–#138 | Onda de segurança/infra rotulada "audit follow-up #N", **fora do histórico de sub-fases nomeadas**: V013 soft-delete (`deleted_at` + `@SQLRestriction`) · V014 refresh-token rotation + reuse-detection · V015 composite FK de isolamento · **V016 Row-Level Security** (+ `TenantRlsAspect`) · JWT RS256 + JWKS · audit log de auth expandido · App Insights Java agent · upload hardening. | ADR 0019 (RLS + FK composta), ADR 0020 (token rotation), ADR 0021 (soft-delete) — criados retroativamente na auditoria 2026-05-21 |
| **Pós-1.10 — Customer Confidence (1.11a)** | 2026-05-21 | #148 | ADR 0015 implementado full-stack em 4 slices: migration **V017** (`customer_accounts` + `meeting_account_links` + `customer_confidence_assessments` + `customer_buying_signals` + `customer_objections`, todas com RLS) · worker emite `customerConfidence` (venda→presente, interna→null) · backend persiste no pipeline com **trend autoritativo** (banda ±5) + get-or-create de conta · `GET /meetings/{id}` expande retorno · UI `CustomerConfidenceCard`. CI verde c/ Testcontainers. | ADR 0015 (aplicado) |
| **Pós-1.10 — IAM/list debts (1.11b+c)** | 2026-05-23 | (1.11 b+c) | `PolicyEvaluator` ganhou `StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan` (fail-closed mantido) · `AUTH_FILTER_HARD_CAP` (teto silencioso de 500) removido via scan em lotes (`MeetingService.listAllForAuthFilter`). Testes unitários novos (PolicyEvaluator + MeetingService); `IamScopingIntegrationTest` intocado. | — |
| **Solidify — honestidade de UI + escopo reaberto** | 2026-05-27 | `claude/dev-solidify` | Varredura de solidez. **Honestidade landing/auth:** removidos botões SSO mortos, logos de clientes falsos, seletor de papel morto, links de footer mortos; páginas reais `/legal/termos` + `/legal/privacidade`; política de senha unificada (mín. 10 + letra + dígito); `NEXT_PUBLIC_USE_MOCKS=false` default; MCP marcado "Em breve". **Identidade/RBAC:** `GET /auth/me` (`isRoot`/`tenantName`/`plan`) + `isRoot` no `LoginResponse` + `GET /iam/users` + shell web restringe nav admin (IAM/Contexto) ao Root + badge "Core" enganoso removido. **US fechadas:** UC17 (gestão de usuários com dropdowns, fim do paste de UUID), US18 (busca server-side no command palette), US25 (export tarefas CSV/MD), US34 (export relatório Markdown), US43 (policy simulator `POST /iam/policies/simulate`), US33 (métricas tenant `GET /tenant/metrics` + strip no dashboard), US31 (contador de versão do contexto, **migration V018**). **UI:** editorial UI kit (`components/core/ui.tsx`) + todas as páginas internas portadas do legacy slate/shadcn; nav mobile do shell Core; auto-refresh do detalhe da reunião enquanto processa + botão "Reprocessar". `infra/AZURE-PROVISIONING.md` adicionado (setup flag-gated SSO/pgvector-search/áudio). | **ADR 0022** (substitui ADR 0014 — reverte o defer e reabre o escopo documentado completo) |

> **✅ Atualização (reconciliação 2026-05-21, pós-PR #148):** (1) **Customer Confidence (US48-49) FOI implementado** full-stack em #148: V017 + worker emit + `AnalysisService` wiring (trend server-side) + `GET /meetings/{id}` + `CustomerConfidenceCard`. Dívida narrativa da landing **resolvida**. (2) Shipou como **V017** (o slot V013 do ADR 0015 acabou em soft-delete, #114). (3) **RLS** já entregue no schema via V016 — falta só o enforcement em prod. (4) **A Sub-fase 1.11 está parcialmente entregue**: item-vitrine (a) Customer Confidence DONE; (b) AUTH_FILTER_HARD_CAP e (c) PolicyEvaluator operators seguem abertos; (e) seed TOTVS e (f) roteiro de demo não feitos. Account Health agregado (US50-US51) **continua deferido** via ADR 0014.

> **✅ Atualização (Solidify, 2026-05-27):** varredura de honestidade de UI + identidade/RBAC sobre `claude/dev-solidify`. **ADR 0022** reverteu o defer do ADR 0014 — o escopo documentado completo voltou a ser ativo. Fechadas de verdade nesta varredura: **US25, US31 (V018), US33, US34, US43** + UC17 (gestão de usuários) + US18 (busca server-side no palette). **Ainda pendentes (reabertas pelo 0022, a construir — não mais deferidas):** SSO US05, Account Health US50/US51, busca semântica US15, upload de áudio US08, MCP US27-29/US47, templates de policy US41, permission boundaries US44, painel de tendências US21, dedup de participantes US13.

### Métricas acumuladas

- **~148 PRs** mergeados em `main` (last: #148 Customer Confidence em 2026-05-21; inclui a onda de hardening "audit follow-up" #114–#138 pós-1.10)
- **22 ADRs** aceitos/propostos (0001-0022; ADR 0006 substituído parcialmente por 0015; ADR 0013 e 0016 propostos; **ADR 0022 substitui 0014** — reverte o defer). Ver `docs/adr/README.md`. O hardening pós-1.10 foi documentado retroativamente em ADR 0019 (RLS + FK composta), 0020 (token rotation) e 0021 (soft-delete)
- **Migrations**: V001–**V018** (V013 soft-delete, V014 refresh rotation, V015 composite FK, V016 RLS, V017 Customer Confidence, **V018 contador de versão do contexto do tenant — US31**)
- **Worker NLP**: 87% coverage (54 testes) — *medido 2026-05-13, re-medir pós-#114-#138*
- **Backend Spring**: 67% coverage (174 testes); áreas críticas IAM/Auth >90% — *idem, re-medir*
- **Web Next.js**: 0% (sem runner; débito pra 1.12)
- **Custo Azure dev**: R$110-180/mês (dentro dos R$500 do Azure for Students)

---

## 2. Próximas Sub-fases — 1.11, 1.12, 1.13+

> Janelas alvo são **agentic** (Opus 4.6 Fast em paralelo via worktrees), não human-hours. Refletem complexidade real de cada slice, não esforço humano.

> **Status real (2026-05-23, reconciliado):** a **Sub-fase 1.11 está majoritariamente entregue** (~3 de 6 itens de código): **(a) Customer Confidence** (#148), **(b) AUTH_FILTER_HARD_CAP** (teto silencioso de 500 removido — scan em lotes em `MeetingService.listAllForAuthFilter`) e **(c) PolicyEvaluator operators** (`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan`, fail-closed mantido) ✅. Faltam: **(e) seed TOTVS realista** (sintéticos atuais são acme/northwind genéricos — e há tensão com a "Política de Hardcode" do dataset, que proíbe tenant TOTVS), **(f) roteiro de demo**. (d) UX polish é parcial/subjetivo. Da **Sub-fase 1.12**, o item (b) **RLS já foi entregue antecipadamente** (V016).

| Sub-fase | Janela alvo | Escopo | Arquiteto responsável | Pré-requisitos |
|---|---|---|---|---|
| **1.11 — Demo Polish Plano A** | 2-3 semanas agentic (alvo: fechar até 05/06/2026 pra ter 1 semana de buffer pré-pitch) | (a) ✅ **DONE (#148)** — **Customer Confidence mínimo** via ADR 0015: schema → persistência (V017) → endpoint read-only `GET /meetings/{id}` → `CustomerConfidenceCard` no MeetingDetail<br>(b) ✅ **DONE (1.11b)** — **`AUTH_FILTER_HARD_CAP` fix**: teto silencioso de 500 removido; `MeetingService.listAllForAuthFilter` varre em lotes antes do filtro IAM. Pushdown SQL via JSONB+GIN fica como otimização de performance futura<br>(c) ✅ **DONE (1.11c)** — **`PolicyEvaluator` expansion**: `StringIn`, `StringLike`, `DateGreaterThan`, `DateLessThan` implementados (fail-closed mantido para operador desconhecido e atributo ausente)<br>(d) **UX interna polida** (Arquiteto Design): editorial v3 nas auth pages, fix `position:fixed` gambiarra no login, ajustes finos no MeetingDetail e dashboard<br>(e) **Seed sintético TOTVS realista**: 5-7 reuniões com vocabulário TOTVS (Protheus, RM, Datasul, Fluig, RM Consult) + 3 tenants demo (1 com Customer Confidence ativado) + Camila/Rafael/Lucas users + policies de exemplo<br>(f) **Roteiro de demo**: script de 8-10 minutos cobrindo Core (Lucas faz upload, vê resumo, marca task) → Enterprise (Camila configura policy, Rafael vê só seu escopo) → Customer Confidence (Rafael vê signals do lead). Inclui plano B se algo falhar ao vivo | Joint Tech Lead + Design | Sub-fase 1.10 fechada (docs refresh consolidado); ADR 0015 criado e aprovado |
| **1.12 — Production Hardening** | 1-2 semanas agentic | (a) **`rg-nora-prod` separado** do `rg-nora-dev` (isolamento total: KV, Postgres, Storage, ACA env distintos)<br>(b) **RLS Postgres** — ✅ schema entregue em **V016** (`tenant_isolation` + `TenantRlsAspect`); resta só ativar enforcement em prod (role `nora_app` `NOBYPASSRLS` + flag `nora.security.rls.enforce`)<br>(c) **Monitoring alerts** Azure Monitor: P95 latency, 5xx rate, Postgres CPU/conn pool, KV access failures, Speech token exhaustion<br>(d) **LGPD operacional**: tabela `audit_events` global (não só IAM), endpoints de "direito ao esquecimento" (`POST /me/erase`), retenção declarada por tenant<br>(e) **DR runbook** (`docs/operations/dr-runbook.md`): backup Postgres + restore drill + RTO/RPO declarado + Bicep redeploy a partir de zero<br>(f) **Secrets rotation**: política de rotação JWT_SECRET, OPENAI_API_KEY, ConnectionString Postgres via KV versions + redeploy automático<br>(g) **Test coverage targets** (ADR 0018 a criar): >85% áreas críticas (IAM, Auth, PII, LLM analyzer), >60% demais, >50% web nas pages principais, >70% branch coverage backend. Adicionar Vitest no web | Tech Lead | Sub-fase 1.11 fechada; ADR 0016 (estratégia rg-prod) e ADR 0018 (coverage targets) a criar |
| **1.13+ — Pós-pitch (12/06 em diante)** | Depende do desfecho do Plano A | **Cenário A — Plano A move (TOTVS sinaliza interesse):** dossier de pitch técnico-comercial · due-diligence material (SECURITY.md robusto, threat-model STRIDE, LGPD checklist completo, cost projection multi-tenant) · suporte a 1ª reunião comercial · roadmap de POC contratada<br>**Cenário B — Plano A neutro/negativo:** Plano C content first (post LinkedIn cobrindo NORA + 8 pegadinhas Azure + IAM AWS-style + Productivity Score; thread Twitter; case no portfolio) + Plano B pivô comercial (criar landing com pre-order, definir pricing pilot >= R$300/tenant/mês baseado em unit economics audit §13, identificar 3-5 leads B2B fora TOTVS) | Tech Lead (+ Stratfy PO) | Pitch FIAP/TOTVS realizado 12/06/2026 |

### Critérios de "Sub-fase fechada"

Pra uma sub-fase ser considerada **fechada** (`DONE`):

1. Todos os PRs do escopo mergeados em `main` com CI verde
2. Verificação manual executada (smoke test mínimo do fluxo entregue)
3. Débitos novos catalogados no audit/memory (não silenciados)
4. ADR criado se a sub-fase introduziu decisão durável
5. Roadmap atualizado movendo a sub-fase de "Próximas" pra "Histórico"

---

## 3. Fase pós-MVP (longo prazo)

> Visão das fases 4-9 do plano de execução original do NORA. Várias dessas fases já foram **absorvidas pelas sub-fases 1.X** ou **viraram Won't Have v1 com critério de reativação**. Esta seção mantém a visão de produto longo-prazo, sem ser plano de execução.

| Fase original | Status hoje | Onde foi absorvida (ou critério) |
|---|---|---|
| **Productivity Score completo** (US45-US47) | **DONE parcial** | US45 + US46 entregues em Sub-fase 1.8. US47 (MCP project state) = Won't Have v1 — reativar quando primeiro tenant pedir integração Jira/Linear |
| **Customer Confidence completo** (US48-US51) | **PARTIAL** | US48-49 (por reunião) DONE full-stack em #148 (V017). Conjunto **agregado** (Account Health US50-51) pendente — reaberto pelo ADR 0022, a construir |
| **Upload de áudio** (US08) | MISSING | Pendente (reaberto pelo ADR 0022, a construir). ADR 0022 prevê reusar o Azure Speech já provisionado (batch/fast) atrás de flag |
| **MCPs (Calendar, Tasks, CRM)** (US27-US29, US47) | MISSING | Pendente (reaberto pelo ADR 0022, a construir como código puro sem dependência Azure). Estrutura `mcp/{calendar,tasks,crm}` já existe vazia no monorepo |
| **Desktop finalização** | DONE parcial | Captura Windows + macOS BlackHole + Linux PulseAudio funcionam. ScreenCaptureKit macOS nativo = nice-to-have (escopo do amigo). Validação Windows/Teams real ainda pendente |
| **SSO Entra ID / SAML** (US05) | MISSING | Pendente (reaberto pelo ADR 0022, a construir). ADR 0022 prevê Entra ID free tier; botão só aparece quando configurado (ver `infra/AZURE-PROVISIONING.md`) |
| **Polimento + Demo + Pitch** | em curso na Sub-fase 1.11 | Sub-fase 1.11 (Demo Polish Plano A) cobre isso pro pitch 12/06 |
| **Painel de tendências** (US21) | MISSING / C | Pendente (reaberto pelo ADR 0022, a construir). Depende de US15 (busca semântica) ligada pra ter profundidade |
| **Templates de policy + Simulator** (US41 + US43) | US43 DONE / US41 MISSING | **US43 (simulator) shipou na Sub-fase Solidify** (`POST /iam/policies/simulate`). US41 (templates) segue pendente — reaberto pelo ADR 0022, a construir |
| **Permission boundaries** (US44) | MISSING / C | Pendente (reaberto pelo ADR 0022, a construir). Originalmente: hierarquia organizacional + delegação de IAM, Pilot+1 |
| **Métricas e Export tenant** (US33 + US34) | DONE | **US33 (`GET /tenant/metrics` + strip no dashboard) e US34 (export relatório Markdown) shipados na Sub-fase Solidify** (2026-05-27) |

### Visão de produto longo-prazo (não é plano de execução)

NORA evolui em três horizontes:

1. **Horizonte H1 (hoje → 12/06/2026 pitch)**: validação Plano A com TOTVS via demo polida + Customer Confidence visível + IAM enterprise-grade
2. **Horizonte H2 (Q3-Q4 2026)**: primeiros pilotos pagos (Plano A se TOTVS contratou, ou Plano B se foi pivô comercial). Foco: Customer Confidence completo, métricas tenant, simulator de policy, observabilidade. Pricing floor R$300/tenant/mês (unit economics dev/pilot estimam ~R$210/tenant em infra)
3. **Horizonte H3 (2027+)**: scale comercial. MCPs (Calendar, Jira, Salesforce/HubSpot), SSO corporativo, Audio upload via Whisper/Azure Speech batch, Account Health temporal, multi-region. Eventual saída via aquisição (TOTVS ou competitor) ou crescimento orgânico SaaS

### Notas sobre pré-requisitos cruzados

- **Customer Confidence completo** depende de Sub-fase 1.12 (LGPD operacional) antes de virar pago (Account Health tem implicações de retenção)
- **MCPs** dependem do schema de contratos compartilhados (`packages/shared-contracts/`) ser populado — hoje só tem `.gitkeep`
- **SSO Entra ID** depende de SCIM/JIT provisioning + mapeamento de claims → groups IAM. Não-trivial; reservar 2-3 sub-fases dedicadas
- **Multi-region** depende de RLS estável em prod (1.12) + replicação Postgres + replicação Storage. Reservar fase própria

---

## 4. Decisões de processo

Algumas decisões de **como** trabalhamos (não de **o que** entregar) que afetam o roadmap:

- **Numeração 1.X**: enquanto produto é MVP/pré-GA. Versão 2.X começa quando primeiro tenant pago em produção (não dev/pilot)
- **Worktrees + subagentes paralelos**: trabalho dividido por arquiteto/fatia, mergeado via PR no `main`. Drift entre worktrees é débito real (lição da Sub-fase 1.1)
- **Audit como base de docs**: antes de cada Docs Refresh (1.10, 1.13, ...) roda audit read-only ancorado em PR/migration/path. Sem audit, doc vira ficção
- **ADRs imutáveis**: uma vez aceitos, não editamos — criamos sucessor. ADR 0009 tem divergência menor entre doc (Proposto) e índice (aceito), a ser resolvida na 1.10
- **Sub-fases ≠ Sprints**: não há cadência fixa de tempo. Sub-fase fecha quando escopo entrega, não quando timer estoura

---

## Histórico do Documento

| Versão | Data | Descrição |
|---|---|---|
| 1.0 | 2026-05-14 | **Criação inicial** como roadmap vivo. Substitui `docs/plano-de-execucao.md` (descontinuado — descrevia divisão semana-a-semana entre dois desenvolvedores, fora do fluxo real atual da Stratfy). Consolida histórico das 11 Sub-fases (1.0-1.10) com cross-check do audit `2026-05-13-audit-pre-subfase-1.10.md` §11. Define próximas Sub-fases 1.11 (Demo Polish Plano A), 1.12 (Production Hardening), 1.13+ (pós-pitch) com escopo e pré-requisitos explícitos. Inclui visão longo-prazo (3 horizontes H1-H3) e notas de processo |
