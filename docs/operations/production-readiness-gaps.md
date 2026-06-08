---
title: "Production Readiness — Análise de Gaps"
owner: Arquiteto NORA (Tech Lead)
status: approved
version: 1.0
last_reviewed: 2026-06-06
---

# Production Readiness — Análise de Gaps

> **Audiência:** Tech Lead + futuros operadores quando NORA promover do ambiente `rg-nora-dev` (atual, único) para `rg-nora-prod`.
>
> **Status:** descritivo (`docs/`). Implementação ataca-se na **Sub-fase 1.12 — Production Hardening**, formalizada via **ADR 0016 — Production Readiness Checklist** (a criar).
>
> **Contexto:** o ambiente `rg-nora-dev` atual (`centralus`, 14 recursos, 4 secrets no KV, 8 armadilhas Azure catalogadas) faz deploy do NORA com sucesso. Mas **dev ≠ prod**. Sete áreas têm gaps que precisam endereçar antes de NORA receber tráfego comercial ou expor dados de cliente real.

---

## Gap 1 — Bicep `prod.bicepparam` não existe

**Situação atual:** `infra/bicep/main.dev.bicepparam` é único arquivo de parâmetros. Aponta para `rg-nora-dev`, region `centralus`, `enableSearch=false`, secrets vindos de env vars locais (gerados random para dev).

**Gap:** sem `main.prod.bicepparam`, deploy para prod hoje seria copy-paste manual de valores, com risco de mistura dev/prod e leak de secrets.

**Plano (Sub-fase 1.12):**

- Criar `infra/bicep/main.prod.bicepparam` parametrizado com:
  - `env = 'prod'`
  - `location` (decidir região — provavelmente continua `centralus` por unit economics validada em pilot, ou migra para `eastus` se Postgres já tiver via offer expansion)
  - `enablePurgeProtection = true` no KV (default `false` em dev para teardown rápido)
  - `enableSearch = true`
  - SKUs: Postgres provavelmente sobe para `Standard_D2ds_v5` ou GP tier (decidir baseado em unit economics avançada — 1.12 inclui modelo GA-conservative + GA-aggressive)
  - `min replicas = 1` em **todas** as Container Apps (warm-up — scale-to-zero gera UX ruim em prod)
- Secrets via env vars **de outro Service Principal escopado em `rg-nora-prod`** (não reusar SP de dev)
- Bicep params validados via `az deployment group what-if` antes de `create`

---

## Gap 2 — Migrations safety strategy ausente

**Situação atual:** Flyway roda no startup do API Container App. Se migration falha mid-deploy, estado fica inconsistente, sem rollback automatizado. Em dev, basta destruir o RG. Em prod, **não**.

**Gap:** sem estratégia de safety, próximo deploy em prod com migration nova arriscando:

- Schema parcialmente aplicado, app não inicia, downtime indefinido
- Rollback manual implica downtime longo
- Cenário pior: migration aplica `ALTER TABLE` destrutivo (drop column), depois falha, dados perdidos

**Plano (Sub-fase 1.12):**

Decidir entre 3 estratégias:

1. **Pre-flight check + manual approve:** workflow `deploy-infra.yml` faz `flyway info` (lista pendente) → posta como GitHub Actions summary → exige `gh workflow run` manual com input "I read the migrations and approve" antes de subir a nova revisão de Container App. Funciona para deploys de baixa frequência (1-3/semana em prod). **Custo:** 1 step manual.
2. **Blue/Green deploy via revisions:** Container Apps já suporta múltiplas revisions em paralelo. Nova revision roda migration (se aplicável), valida `/actuator/health`, traffic split 0 → 50 → 100. Rollback = traffic 100 → 0. **Custo:** workflow mais complexo (~1 dia agentic), revisions têm custo extra.
3. **Migrations expand/contract:** convenção de migrações com 2 fases — `V0XX_expand` (additive: adicionar coluna nullable, criar tabela nova) → deploy → `V0YY_contract` (cleanup: drop coluna velha) só após X dias de estabilidade. **Custo:** disciplina contínua, processo de PR, mas zero downtime.

Recomendação inicial: **opção (1)** para MVP/Pilot, evoluir para **(3)** em GA.

ADR 0016 documenta a escolha.

---

## Gap 3 — Backup RTO/RPO não formalizados, restore não testado

**Situação atual:** Postgres Flexible Server tem backup automático default (point-in-time recovery — PITR) com 7 dias de retenção. Storage Account tem soft-delete 7 dias (configurado no Bicep). Key Vault soft-delete 7 dias (configurado).

**Gap:** ninguém testou um restore de fato. RTO (recovery time objective) e RPO (recovery point objective) não declarados em SLA interno.

**Plano (Sub-fase 1.12):**

1. **Documentar RTO/RPO targets:**
   - RPO: **5 min** (max data perdida em incidente) — PITR do Postgres suporta
   - RTO: **2h** (max tempo offline) — restore do Postgres flexible + redeploy via Bicep `prod.bicepparam`
2. **Restore drill:** em ambiente isolado, executar:
   - `az postgres flexible-server restore` apontando para timestamp T-1h
   - Validar dados (count, integridade FKs, latest meeting)
   - Validar app conecta no restore (atualizar `DATASOURCE_URL` temporário)
   - Documentar tempo real medido em `docs/operations/disaster-recovery-runbook.md`
3. **Definir frequência:** drill 1x/trimestre em ambiente espelho

---

## Gap 4 — Monitoring + alerting não wired

**Situação atual:** Application Insights provisionado, recebendo telemetria das 3 Container Apps + Worker. Log Analytics workspace coletando logs. Mas:

- Nenhum **alerta** configurado (sem email/Slack notification)
- Nenhum **dashboard** estruturado (precisa abrir Portal e construir consulta KQL ad-hoc)
- Nenhum **SLO** declarado

**Gap:** quando o NORA cair em prod, ninguém vai saber até cliente reclamar.

**Plano (Sub-fase 1.12):**

1. **Alertas críticos** (Azure Monitor) wired para email do contato técnico da Stratfy (e webhook Slack futuro):
   - API Container App: `/actuator/health` non-200 por >2min
   - Postgres: connection failures >10/min ou CPU >80% sustained 5min
   - Container Apps: scale-up failed (replica retries >3)
   - Speech: error rate `Ocp-Apim-Subscription-Key` >5%
2. **Dashboard "NORA prod overview"** no Application Insights workbook:
   - Requests/min por endpoint
   - Latência p50/p95/p99 do API
   - Erros 5xx + 4xx
   - Postgres connections + slow queries
   - Custos diários (via Cost Management API)
3. **SLO inicial**:
   - API uptime: 99.0% mensal (suporta ~7h downtime/mês — realista para single-region MVP)
   - p95 latency `/meetings/{id}`: <1.5s
   - LLM analysis async: 95% concluído em <60s

---

## Gap 5 — LGPD operacional — ENTREGUE (ADR 0029)

**Situação atual:** PII Shield no worker (redige email, CPF, CNPJ, telefone, cartão, person_name BR antes de enviar para o LLM). Multi-tenancy garante isolamento por `tenant_id`. Cookies httpOnly. A camada operacional de LGPD foi **entregue** via **ADR 0029**:

- **Direito ao esquecimento:** endpoint `DELETE /privacy/meetings/{id}` (exclusão por titular/tenant).
- **Retenção:** `RetentionSweeper` agendado aplica a política de retenção automaticamente.
- **Cobertura:** `PrivacyFlowIntegrationTest` valida o fluxo end-to-end.
- **DPO declarado** em `SECURITY.md` (contato: axonogenesis@proton.me).

Este gap deixou de ser débito da Sub-fase 1.12.

**Resíduo (operacional, não bloqueante):**

1. **Data retention policy** documentada em ADR 0029:
   - Transcripts: retidos enquanto tenant ativo + 30 dias pós-cancelamento
   - Análises LLM: idem
   - Logs Application Insights: 30 dias (padrão atual)
   - Refresh tokens revogados: hard delete imediato
2. Endpoint `DELETE /privacy/meetings/{id}` entregue (direito ao esquecimento por titular/tenant).
3. Endpoint administrativo de exclusão de tenant completo (Root only) — refinamento operacional futuro.
4. `docs/security/lgpd-operations.md` com runbook de incidente: detecção, escalação, comunicação ANPD se >50 titulares afetados — refinamento operacional futuro.

---

## Gap 6 — Disaster recovery cenário "RG deletado por engano"

**Situação atual:** Bicep IaC permite recriar infra. Postgres tem PITR. Storage tem soft-delete. **Mas** o teste empírico já mostrou (Sub-fase 1.9, vault `azure_access.md`) que recriar com mesmo nome esbarra em:

- KV soft-deleted reserva nome global 7 dias
- Cognitive Services Speech idem
- Postgres pode ter `LocationIsOfferRestricted` se região mudar

**Gap:** runbook DR não documentado. Em incidente real, recuperação seria improvisada.

**Plano (Sub-fase 1.12):**

`docs/operations/disaster-recovery-runbook.md` com:

1. **Cenário A — RG destruído, dados perdidos:**
   - Step 1: `az keyvault purge` + `az cognitiveservices account purge`
   - Step 2: `az group create rg-nora-prod` (mesmo nome, nova location se necessário)
   - Step 3: GitHub Actions `deploy-infra.yml` workflow_dispatch
   - Step 4: Validar serviços UP
   - Step 5: Restore Postgres backup mais recente
   - **RTO estimado:** 2-3h
2. **Cenário B — apenas Postgres corrompido:**
   - PITR para timestamp pré-corrupção
   - RTO: 30min-1h
3. **Cenário C — região Azure inteira indisponível:**
   - MVP single-region: aceita downtime
   - Futuro (GA): geo-redundância via Postgres georeplica + Front Door

---

## Gap 7 — Secrets rotation policy ausente

**Situação atual:** Secrets atuais no KV:
- `postgres-password` — gerado random na criação do SP
- `jwt-secret` — gerado random
- `openai-api-key` — vazio (worker em modo stub default)
- `azure-speech-key` — vindo de `speech.listKeys().key1` (mudaria se membro da Stratfy rotacionar manualmente)

**Gap:** nenhum dos 4 tem **schedule de rotação** automatizado. Em prod, isso é exigência mínima de segurança.

**Plano (Sub-fase 1.12):**

| Secret | Frequência rotação | Método |
|---|---|---|
| `postgres-password` | A cada 90 dias | Script: gera new password, `ALTER USER ... PASSWORD`, atualiza KV secret, força Container Apps a puxar nova versão (revision restart) |
| `jwt-secret` | A cada 180 dias | Atualiza KV secret + grace period 24h para refresh tokens válidos persistirem (precisa logic de "JWT secret rotation com keyId" — design futuro) |
| `openai-api-key` | Quando rotacionada manualmente no OpenAI dashboard pela Stratfy | Atualiza KV secret + restart api/worker |
| `azure-speech-key` | A cada 90 dias | `az cognitiveservices account keys regenerate` + atualiza KV secret + restart api |

Workflow dedicado `.github/workflows/rotate-secrets.yml` com cron mensal pode automatizar parte.

---

## Resumo

| Gap | Esforço | ADR sucessor? |
|---|---|---|
| 1. Bicep prod.bicepparam | M | ADR 0016 |
| 2. Migrations safety | M (decisão entre 3 estratégias) | ADR 0016 |
| 3. RTO/RPO + restore drill | M (drill + doc) | — |
| 4. Monitoring + alerting | M (alerts + workbook + SLO) | — |
| 5. LGPD operacional — **entregue** | — (entregue via ADR 0029) | ADR 0029 |
| 6. DR runbook | S (doc + dry run) | — |
| 7. Secrets rotation | M (workflows + rotation scripts) | — |
| 8. Control plane sob RLS enforce | S (role BYPASSRLS p/ telemetria de negócio) | ADR 0022 |

**Estimativa total Sub-fase 1.12 — Production Hardening: ~1-2 semanas agentic.**

Pré-requisitos: itens de **código** da Sub-fase 1.11 já entregues — Customer Confidence (#148), AUTH_FILTER fix (teto silencioso de 500 removido via scan em lotes) e PolicyEvaluator (`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan`). Restam (e) seed e (f) roteiro de demo, que não bloqueiam 1.12.

---

## Gap 8 — Control plane: telemetria de negócio quebra silenciosamente sob RLS enforce

**Situação atual:** o control plane (ADR 0022/0024) tem a frente de telemetria de **negócio** (cortável) lendo o banco **primário** cross-tenant via `PrimaryDbBusinessMetricsSource` (`COUNT(*)` / `COUNT(DISTINCT tenant_id)` em `meeting_analyses`), **sem** contexto de tenant — agregação operador-only intencional. Funciona hoje porque o datasource primário roda como role owner (BYPASSRLS) com `NORA_RLS_ENFORCE=false`.

**Gap:** quando o opt-in de RLS enforce (ADR 0019 — tenant isolation defense-in-depth; cutover operacional em ADR 0026/0028) for ativado (role `nora_app` NOBYPASSRLS + `NORA_RLS_ENFORCE=true`), essas queries rodam **sem GUC de tenant** (não há `@Transactional`, o `TenantRlsAspect` não dispara) ⇒ a policy `tenant_isolation` (fail-closed) esconde **todas** as linhas ⇒ `analyses=0`/`tenantsActive=0` **silencioso** (sem erro). O painel do operador mostraria "zero atividade" falso, sem sinal de que a leitura foi suprimida.

**Plano (pré-requisito de ligar RLS enforce):**

- Dar à leitura operador-only um caminho **BYPASSRLS** dedicado: ou uma role de telemetria com `BYPASSRLS`, ou uma view/função `SECURITY DEFINER` owned por role privilegiada com `GRANT SELECT` ao `nora_app`. A agregação cross-tenant é intencional e operador-only.
- Alternativa mínima: detectar o estado e devolver `enabled:false` (em vez de `enabled:true` com zeros) quando a leitura cross-tenant não for possível — assim o operador vê "indisponível", não "zero real".
- Documentado no Javadoc de `PrimaryDbBusinessMetricsSource` e no contrato (§3). Custo: S. **Não bloqueia o v1** (enforce=false hoje).

---

## Histórico

| Data | Autor | Mudança |
|---|---|---|
| 2026-05-14 | Tech Lead | Doc criado durante Sub-fase 1.10 (Docs Refresh). Análise informada por revisão do Arquiteto Design no audit (§4.3) |
| 2026-05-28 | Co-arquiteto (Opus) | Gap 8 adicionado: telemetria de negócio do control plane (ADR 0022) zera sob RLS enforce — pré-requisito de role BYPASSRLS antes de ligar o RLS enforce |
| 2026-06-06 | Arquiteto NORA (Tech Lead) | Reconciliação doc x código + padronização (auditoria pré-apresentação): Gap 5 (LGPD operacional) marcado como entregue via ADR 0029; correção de referência ADR 0019 → ADR 0029 para LGPD |
