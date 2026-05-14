# Production Readiness — Gap Analysis

> **Audiência:** Tech Lead + futuros operadores quando NORA promover do ambiente `rg-nora-dev` (atual, único) pra `rg-nora-prod`.
>
> **Status:** descritivo (`docs/`). Implementação ataca-se na **Sub-fase 1.12 — Production Hardening**, formalizada via **ADR 0016 — Production Readiness Checklist** (a criar).
>
> **Contexto:** o ambiente `rg-nora-dev` atual (`centralus`, 14 recursos, 4 secrets no KV, 8 pegadinhas Azure catalogadas) deployа NORA com sucesso. Mas **dev ≠ prod**. Sete áreas têm gaps que precisam endereçar antes de NORA receber tráfego comercial ou expor dados de cliente real.

---

## Gap 1 — Bicep `prod.bicepparam` não existe

**Situação atual:** `infra/bicep/main.dev.bicepparam` é único arquivo de parâmetros. Aponta pra `rg-nora-dev`, region `centralus`, `enableSearch=false`, secrets vindos de env vars locais (gerados random pra dev).

**Gap:** sem `main.prod.bicepparam`, deploy pra prod hoje seria copy-paste manual de valores, com risco de mistura dev/prod e leak de secrets.

**Plano (Sub-fase 1.12):**

- Criar `infra/bicep/main.prod.bicepparam` parametrizado com:
  - `env = 'prod'`
  - `location` (decidir região — provavelmente continua `centralus` por unit economics validada em pilot, ou migra pra `eastus` se Postgres já tiver via offer expansion)
  - `enablePurgeProtection = true` no KV (default `false` em dev pra teardown rápido)
  - `enableSearch = true`
  - SKUs: Postgres provavelmente sobe pra `Standard_D2ds_v5` ou GP tier (decidir baseado em unit economics avançada — 1.12 inclui modelo GA-conservative + GA-aggressive)
  - `min replicas = 1` em **todas** as Container Apps (warm-up — scale-to-zero gera UX ruim em prod)
- Secrets via env vars **de outro Service Principal escopado em `rg-nora-prod`** (não reusar SP de dev)
- Bicep params validados via `az deployment group what-if` antes de `create`

---

## Gap 2 — Migrations safety strategy ausente

**Situação atual:** Flyway roda no startup do API Container App. Se migration falha mid-deploy, estado fica inconsistente, sem rollback automatizado. Em dev, basta nukar o RG. Em prod, **não**.

**Gap:** sem estratégia de safety, próximo deploy em prod com migration nova arriscando:

- Schema parcialmente aplicado, app não inicia, downtime indefinido
- Rollback manual implica downtime longo
- Cenário pior: migration aplica `ALTER TABLE` destrutivo (drop column), depois falha, dados perdidos

**Plano (Sub-fase 1.12):**

Decidir entre 3 estratégias:

1. **Pre-flight check + manual approve:** workflow `deploy-infra.yml` faz `flyway info` (lista pendente) → posta como GitHub Actions summary → exige `gh workflow run` manual com input "I read the migrations and approve" antes de subir a nova revisão de Container App. Funciona pra deploys de baixa frequência (1-3/semana em prod). **Custo:** 1 step manual.
2. **Blue/Green deploy via revisions:** Container Apps já suporta múltiplas revisions em paralelo. Nova revision roda migration (se aplicável), valida `/actuator/health`, traffic split 0 → 50 → 100. Rollback = traffic 100 → 0. **Custo:** workflow mais complexo (~1 dia agentic), revisions têm custo extra.
3. **Migrations expand/contract:** convenção de migrações com 2 fases — `V0XX_expand` (additive: adicionar coluna nullable, criar tabela nova) → deploy → `V0YY_contract` (cleanup: drop coluna velha) só após X dias de estabilidade. **Custo:** disciplina contínua, processo de PR, mas zero downtime.

Recomendação inicial: **opção (1)** pra MVP/Pilot, evoluir pra **(3)** em GA.

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
   - `az postgres flexible-server restore` apontando pra timestamp T-1h
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

1. **Alertas críticos** (Azure Monitor) wired pra email do Anthony (e webhook Slack futuro):
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
   - API uptime: 99.0% mensal (suporta ~7h downtime/mês — realista pra single-region MVP)
   - p95 latency `/meetings/{id}`: <1.5s
   - LLM analysis async: 95% concluído em <60s

---

## Gap 5 — LGPD operational checklist ausente

**Situação atual:** PII Shield no worker (redige email, CPF, CNPJ, telefone, cartão, person_name BR antes de mandar pro LLM). Multi-tenancy garante isolamento por `tenant_id`. Cookies httpOnly. Mas:

- **Não há política formalizada** de data retention (quanto tempo guarda meeting transcripts? Análises?)
- **Não há endpoint** "Direito ao esquecimento" (DELETE cascade por user_id ou tenant_id)
- **Não há DPO designado** (mesmo que seja Anthony, precisa estar declarado)
- **Não há `docs/security/lgpd-operations.md`** com runbook de incidente

**Gap:** sem isso, NORA não pode operar comercialmente atendendo clientes Enterprise no Brasil (TOTVS, etc.) — LGPD requer.

**Plano (Sub-fase 1.12):**

1. ADR 0019 ou doc dedicado — **Data retention policy**:
   - Transcripts: retidos enquanto tenant ativo + 30 dias pós-cancelamento
   - Análises LLM: idem
   - Logs Application Insights: 30 dias (padrão atual)
   - Refresh tokens revogados: hard delete imediato
2. Endpoint `DELETE /tenants/{tenantId}/me` — usuário pede exclusão dos próprios dados; CASCADE: meetings, transcripts, analysis, action items, productivity assessments, IAM membership
3. Endpoint `DELETE /admin/tenants/{tenantId}` (Root only) — exclui tenant completo
4. DPO declarado em `SECURITY.md`
5. `docs/security/lgpd-operations.md` com runbook de incidente: detecção, escalação, comunicação ANPD se >50 titulares afetados

---

## Gap 6 — Disaster recovery cenário "RG deletado por engano"

**Situação atual:** Bicep IaC permite recriar infra. Postgres tem PITR. Storage tem soft-delete. **Mas** o teste empírico já mostrou (Sub-fase 1.9, vault `azure_access.md`) que recriar com mesmo nome esbarra em:

- KV soft-deleted reserva nome global 7 dias
- Cognitive Services Speech idem
- Postgres pode ter `LocationIsOfferRestricted` se região mudar

**Gap:** runbook DR não documentado. Em incidente real, recuperação seria improvisada.

**Plano (Sub-fase 1.12):**

`docs/operations/disaster-recovery-runbook.md` com:

1. **Cenário A — RG nukado, dados perdidos:** 
   - Step 1: `az keyvault purge` + `az cognitiveservices account purge`
   - Step 2: `az group create rg-nora-prod` (mesmo nome, nova location se necessário)
   - Step 3: GitHub Actions `deploy-infra.yml` workflow_dispatch
   - Step 4: Validar serviços UP
   - Step 5: Restore Postgres backup mais recente
   - **RTO estimado:** 2-3h
2. **Cenário B — apenas Postgres corrompido:**
   - PITR pra timestamp pré-corrupção
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
- `azure-speech-key` — vindo de `speech.listKeys().key1` (mudaria se Anthony rotacionar manualmente)

**Gap:** nenhum dos 4 tem **schedule de rotação** automatizado. Em prod, isso é exigência mínima de segurança.

**Plano (Sub-fase 1.12):**

| Secret | Frequência rotação | Método |
|---|---|---|
| `postgres-password` | A cada 90 dias | Script: gera new password, `ALTER USER ... PASSWORD`, atualiza KV secret, força Container Apps a puxar nova versão (revision restart) |
| `jwt-secret` | A cada 180 dias | Atualiza KV secret + grace period 24h pra refresh tokens válidos persistirem (precisa logic de "JWT secret rotation com keyId" — design futuro) |
| `openai-api-key` | Quando Anthony rotacionar manualmente no OpenAI dashboard | Atualiza KV secret + restart api/worker |
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
| 5. LGPD operational | L (data retention + endpoints + DPO + runbook) | ADR 0019 ou doc |
| 6. DR runbook | S (doc + dry run) | — |
| 7. Secrets rotation | M (workflows + rotation scripts) | — |

**Estimativa total Sub-fase 1.12 — Production Hardening: ~1-2 semanas agentic.**

Pré-requisitos: Sub-fase 1.11 (Demo Polish Plano A) fechada com Customer Confidence implementado, AUTH_FILTER fix, PolicyEvaluator stringIn+stringLike — pois fix de 1.11 entra em prod junto com 1.12.

---

## Histórico

| Data | Autor | Mudança |
|---|---|---|
| 2026-05-14 | Tech Lead | Doc criado durante Sub-fase 1.10 (Docs Refresh). Análise informada por revisão do Arquiteto Design no audit (§4.3) |
