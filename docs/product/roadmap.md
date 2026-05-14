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

> Customer Confidence (US48-49) tem decisão tomada via **ADR 0015** (aceito em 2026-05-14, voto **a**: implementa mínimo viável na Sub-fase 1.11 — V013 + 5 tabelas + endpoint read-only + UI card). Account Health agregado (US50-US51) **continua deferido** via ADR 0014 — reativação pós-pilot com 3+ tenants tendo >10 reuniões por conta.

### Métricas acumuladas

- **76 PRs** mergeados em `main` (75 pré-1.10 + PR #76 da Sub-fase 1.10)
- **18 ADRs** aceitos/propostos (0001-0018; ADR 0006 substituído parcialmente por 0015; ADR 0013 e 0016 propostos). Ver `docs/adr/README.md`
- **Worker NLP**: 87% coverage (54 testes)
- **Backend Spring**: 67% coverage (174 testes); áreas críticas IAM/Auth >90%
- **Web Next.js**: 0% (sem runner; débito pra 1.12)
- **Custo Azure dev**: R$110-180/mês (dentro dos R$500 do Azure for Students)

---

## 2. Próximas Sub-fases — 1.11, 1.12, 1.13+

> Janelas alvo são **agentic** (Opus 4.6 Fast em paralelo via worktrees), não human-hours. Refletem complexidade real de cada slice, não esforço humano.

| Sub-fase | Janela alvo | Escopo | Arquiteto responsável | Pré-requisitos |
|---|---|---|---|---|
| **1.11 — Demo Polish Plano A** | 2-3 semanas agentic (alvo: fechar até 05/06/2026 pra ter 1 semana de buffer pré-pitch) | (a) **Customer Confidence mínimo** via ADR 0015: implementa schema → persistência → endpoint read-only → chart na landing real (não mais mockado); voto a do ADR 0015 = "implementa mínimo viável"<br>(b) **`AUTH_FILTER_HARD_CAP` fix**: refactor pra empurrar predicado IAM pra SQL via `meeting_attributes` JSONB + GIN (V008 já tem índice). Remove cap de 500 meetings em memória — bug oculto pra tenants com >500 reuniões<br>(c) **`PolicyEvaluator` expansion**: implementa operadores `StringIn`, `StringLike`, `DateGreaterThan`, `DateLessThan`. Cobre 90% das policies reais que TOTVS pode pedir no pitch<br>(d) **UX interna polida** (Arquiteto Design): editorial v3 nas auth pages, fix `position:fixed` gambiarra no login, ajustes finos no MeetingDetail e dashboard<br>(e) **Seed sintético TOTVS realista**: 5-7 reuniões com vocabulário TOTVS (Protheus, RM, Datasul, Fluig, RM Consult) + 3 tenants demo (1 com Customer Confidence ativado) + Camila/Rafael/Lucas users + policies de exemplo<br>(f) **Roteiro de demo**: script de 8-10 minutos cobrindo Core (Lucas faz upload, vê resumo, marca task) → Enterprise (Camila configura policy, Rafael vê só seu escopo) → Customer Confidence (Rafael vê signals do lead). Inclui plano B se algo falhar ao vivo | Joint Tech Lead + Design | Sub-fase 1.10 fechada (docs refresh consolidado); ADR 0015 criado e aprovado |
| **1.12 — Production Hardening** | 1-2 semanas agentic | (a) **`rg-nora-prod` separado** do `rg-nora-dev` (isolamento total: KV, Postgres, Storage, ACA env distintos)<br>(b) **RLS Postgres habilitado** em prod (ADR 0002 promete) — migration V013 (ou superior) com policies `tenant_isolation`<br>(c) **Monitoring alerts** Azure Monitor: P95 latency, 5xx rate, Postgres CPU/conn pool, KV access failures, Speech token exhaustion<br>(d) **LGPD operacional**: tabela `audit_events` global (não só IAM), endpoints de "direito ao esquecimento" (`POST /me/erase`), retenção declarada por tenant<br>(e) **DR runbook** (`docs/operations/dr-runbook.md`): backup Postgres + restore drill + RTO/RPO declarado + Bicep redeploy a partir de zero<br>(f) **Secrets rotation**: política de rotação JWT_SECRET, OPENAI_API_KEY, ConnectionString Postgres via KV versions + redeploy automático<br>(g) **Test coverage targets** (ADR 0018 a criar): >85% áreas críticas (IAM, Auth, PII, LLM analyzer), >60% demais, >50% web nas pages principais, >70% branch coverage backend. Adicionar Vitest no web | Tech Lead | Sub-fase 1.11 fechada; ADR 0016 (estratégia rg-prod) e ADR 0018 (coverage targets) a criar |
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
| **Customer Confidence completo** (US48-US51) | **PARTIAL** | Schema LLM já existe (ADR 0006). Persistência condicional via ADR 0015 (Sub-fase 1.11). Conjunto completo (Account Health agregado + alertas) = pós-pilot quando 3+ tenants tiverem >10 reuniões cada |
| **Upload de áudio** (US08) | MISSING / W | Reativar quando demanda repetida em pilot indicar (>30% dos uploads pedidos são áudio) ou Azure Speech batch ficar barato. Provavelmente Pilot+1 |
| **MCPs (Calendar, Tasks, CRM)** (US27-US29, US47) | MISSING / W | Reativar quando primeiro tenant Enterprise pago pedir integração concreta. Estrutura `mcp/{calendar,tasks,crm}` já existe vazia no monorepo |
| **Desktop finalização** | DONE parcial | Captura Windows + macOS BlackHole + Linux PulseAudio funcionam. ScreenCaptureKit macOS nativo = nice-to-have (escopo do amigo). Validação Windows/Teams real ainda pendente |
| **SSO Entra ID / SAML** (US05) | MISSING / W | Reativar quando primeiro tenant Enterprise pago exigir explicitamente |
| **Polimento + Demo + Pitch** | em curso na Sub-fase 1.11 | Sub-fase 1.11 (Demo Polish Plano A) cobre isso pro pitch 12/06 |
| **Painel de tendências** (US21) | MISSING / C | Reativar depois de US15 (busca semântica) ligada. Sem embeddings/análise temporal o painel é raso |
| **Templates de policy + Simulator** (US41 + US43) | MISSING / S | US43 (simulator) provavelmente sobe na 1.11 — sem isso, debug de policies é cego pra primeiro pilot pago. US41 (templates) só quando >3 tenants pedirem onboarding rápido |
| **Permission boundaries** (US44) | MISSING / C | Reativar quando hierarquia organizacional + delegação de IAM virar necessidade. Provavelmente Pilot+1 |
| **Métricas e Export tenant** (US33 + US34) | MISSING / S | Reativar quando 5+ tenants pagantes em pilot — sem dados não vale construir |

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
