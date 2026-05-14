# 0016 — Production-readiness checklist e separação `rg-nora-prod`

- Status: proposto (aceita formalmente na Sub-fase 1.12 quando implementação começa)
- Data: 2026-05-14
- Decisores: Tech Lead (Stratfy aprova plano antes de execução em 1.12)

## Contexto

`rg-nora-dev` foi deployado com sucesso em 2026-05-13 (run 25815047515) após 8 pegadinhas Azure for Students catalogadas. Stack funcional, NORA real respondendo em `https://nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io`.

**Mas dev ≠ prod.** O Arquiteto Design no audit pré-Sub-fase 1.10 (§4.3) identificou 7 gaps de production-readiness que precisam endereçar antes de NORA receber tráfego comercial ou expor dados de cliente real (Plano A com TOTVS, Plano B com primeiros tenants).

Detalhes completos em `docs/operations/production-readiness-gaps.md`.

## Decisão

**Sub-fase 1.12 — Production Hardening** (após 1.11) implementa os 7 gaps de prod-readiness, formalizados neste ADR:

### Gap 1 — Bicep `prod.bicepparam` separado
- `infra/bicep/main.prod.bicepparam` com:
  - `env = 'prod'`
  - `location` (decidir: continuar `centralus` ou migrar `eastus` baseado em unit economics avançada)
  - `enablePurgeProtection = true` no Key Vault
  - `enableSearch = true`
  - SKUs prod-grade (Postgres GP tier ou D2ds_v5; AI Search Standard se justificar)
  - `min replicas = 1` em **todas** as Container Apps (warm-up — scale-to-zero gera UX ruim em prod)
- **Service Principal separado** `sp-nora-github-deploy-prod` (não reusar dev)

### Gap 2 — Migrations safety strategy
Escolha entre 3 estratégias (decidida na 1.12):
- (a) Pre-flight check + manual approve (workflow `deploy-infra.yml` postа `flyway info` como summary; exige `gh workflow run` manual)
- (b) Blue/Green via Container Apps revisions (traffic split 0→50→100)
- (c) Migrations expand/contract (convenção V0XX_expand + V0YY_contract)

**Inclinação inicial:** (a) pra MVP/Pilot, evoluir pra (c) em GA.

### Gap 3 — Backup RTO/RPO formalizado + restore drill
- **RPO**: 5min (PITR Postgres suporta)
- **RTO**: 2h (restore + redeploy Bicep)
- Drill 1x/trimestre em ambiente espelho, documentado em `docs/operations/disaster-recovery-runbook.md`

### Gap 4 — Monitoring + alerting wired
- **Alertas Azure Monitor → email do contato técnico da Stratfy + Slack futuro:**
  - API `/actuator/health` non-200 por >2min
  - Postgres connection failures >10/min ou CPU >80% sustained 5min
  - Container Apps scale-up failed
  - Speech error rate >5%
- **Dashboard "NORA prod overview"** no App Insights workbook
- **SLO inicial:** API uptime 99.0% mensal, p95 latency `/meetings/{id}` <1.5s, LLM analysis 95% concluído em <60s

### Gap 5 — LGPD operational
- Doc dedicado `docs/security/lgpd-operations.md`:
  - Data retention policy (transcripts + análises retidos enquanto tenant ativo + 30 dias pós-cancelamento)
  - Endpoint `DELETE /tenants/{tenantId}/me` (direito ao esquecimento user)
  - Endpoint `DELETE /admin/tenants/{tenantId}` (Root only — exclui tenant completo cascata)
  - DPO declarado em `SECURITY.md`
  - Runbook de incidente: detecção → escalação → comunicação ANPD se >50 titulares afetados

### Gap 6 — Disaster recovery runbook
`docs/operations/disaster-recovery-runbook.md` com 3 cenários:
- (A) RG nukado — RTO 2-3h (purge KV/Speech + recreate + restore)
- (B) Postgres corrompido — PITR restore, RTO 30min-1h
- (C) Região Azure indisponível — MVP single-region aceita downtime; pós-GA: geo-redundância

### Gap 7 — Secrets rotation policy
| Secret | Frequência | Método |
|---|---|---|
| `postgres-password` | 90 dias | Script: novo password + `ALTER USER` + atualiza KV + revision restart |
| `jwt-secret` | 180 dias | Atualiza KV + grace period 24h (logic de keyId no JWT — design futuro) |
| `openai-api-key` | Quando rotacionado no dashboard OpenAI | Atualiza KV + restart api/worker |
| `azure-speech-key` | 90 dias | `az cognitiveservices account keys regenerate` + KV + restart |

Workflow `.github/workflows/rotate-secrets.yml` com cron mensal.

## Consequências

**Positivas:**
- NORA pode operar comercialmente atendendo clientes brasileiros (LGPD compliance operacional)
- Plano A TOTVS code-walkthrough mostra produto enterprise-grade
- Plano B onboarding de primeiros tenants tem rede de segurança operacional
- Backup + DR testados eliminam classe inteira de pesadelos noturnos

**Negativas:**
- Sub-fase 1.12 estimada ~1-2 semanas agentic — significativo investimento
- Postgres prod tier custa mais (~R$200-300/mês vs R$85 dev) — Unit Economics avançada na 1.12 modela
- Secrets rotation exige manutenção contínua (workflows ativos, alerts quando falhar)

## Alternativas Consideradas

1. **Compartilhar `rg-nora-dev` pra prod** — rejeitado por blast radius (debug acidental pode derrubar prod)
2. **Multi-region desde MVP** — rejeitado por complexity vs benefit pré-tração
3. **Postgres read replica** — adiado, single instance OK pra escala inicial

## Plano de Aplicação

Sub-fase 1.12, branch `feat/sub-1.12-production-hardening`. Esforço total **~1-2 semanas agentic**. PR por gap (7 PRs sequenciais ou paralelos quando independentes).

Pré-requisito: Sub-fase 1.11 mergeada (Customer Confidence + AUTH_FILTER fix + PolicyEvaluator stringIn/Like).

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-14 | Tech Lead | ADR proposto durante Sub-fase 1.10 (Docs Refresh). Detalhamento em `docs/operations/production-readiness-gaps.md`. Aceitação formal quando Sub-fase 1.12 começar |
