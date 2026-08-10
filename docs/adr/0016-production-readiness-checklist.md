# 0016 — Production-readiness checklist and `rg-nora-prod` separation

- Status: partially superseded by 0034 (the Azure-anchored premises fall: Gap 1 `prod.bicepparam`/separate SP, Gap 3 RPO/RTO resting on Flexible Server PITR, Gap 4 alerts via Azure Monitor/App Insights, Gap 7 rotation via Key Vault. Gap 2 and Gap 6 remain valid on a different substrate; Gap 5 was delivered by ADR 0029)
- Date: 2026-05-14
- Related: ADR 0034 (migration to Proxmox — redefines the substrate, RPO/RTO and secret rotation)

## Context

`rg-nora-dev` was deployed successfully on 2026-05-13 (run 25815047515) after 8 catalogued Azure for Students gotchas. Functional stack, the real NORA responding at `https://nora-web-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io`.

**But dev ≠ prod.** The Design Architect in the pre-Sub-phase 1.10 audit (§4.3) identified 7 production-readiness gaps that need addressing before NORA receives commercial traffic or exposes real customer data (Plan A with TOTVS, Plan B with the first tenants).

Full details in `docs/operations/production-readiness-gaps.md`.

## Decision

**Sub-phase 1.12 — Production Hardening** (after 1.11) implements the 7 prod-readiness gaps, formalized in this ADR:

### Gap 1 — Separate `prod.bicepparam` Bicep
- `infra/bicep/main.prod.bicepparam` with:
  - `env = 'prod'`
  - `location` (to decide: stay on `centralus` or migrate to `eastus` based on advanced unit economics)
  - `enablePurgeProtection = true` on the Key Vault
  - `enableSearch = true`
  - Prod-grade SKUs (Postgres GP tier or D2ds_v5; AI Search Standard if justified)
  - `min replicas = 1` on **all** Container Apps (warm-up — scale-to-zero produces bad UX in prod)
- **Separate Service Principal** `sp-nora-github-deploy-prod` (do not reuse dev's)

### Gap 2 — Migrations safety strategy
Choice among 3 strategies (decided in 1.12):
- (a) Pre-flight check + manual approve (the `deploy-infra.yml` workflow posts `flyway info` as a summary; requires a manual `gh workflow run`)
- (b) Blue/Green via Container Apps revisions (traffic split 0→50→100)
- (c) Expand/contract migrations (V0XX_expand + V0YY_contract convention)

**Initial leaning:** (a) for MVP/Pilot, evolving to (c) at GA.

### Gap 3 — Formalized backup RTO/RPO + restore drill
- **RPO**: 5min (Postgres PITR supports it)
- **RTO**: 2h (restore + Bicep redeploy)
- Drill once per quarter in a mirror environment, documented in `docs/operations/disaster-recovery-runbook.md`

### Gap 4 — Monitoring + alerting wired
- **Azure Monitor alerts → email of Stratfy's technical contact + Slack in the future:**
  - API `/actuator/health` non-200 for >2min
  - Postgres connection failures >10/min or CPU >80% sustained for 5min
  - Container Apps scale-up failed
  - Speech error rate >5%
- **"NORA prod overview" dashboard** in an App Insights workbook
- **Initial SLO:** API uptime 99.0% monthly, p95 latency `/meetings/{id}` <1.5s, LLM analysis 95% completed in <60s

### Gap 5 — LGPD operational
- Dedicated doc `docs/security/lgpd-operations.md`:
  - Data retention policy (transcripts + analyses retained while the tenant is active + 30 days post-cancellation)
  - Endpoint `DELETE /tenants/{tenantId}/me` (user's right to be forgotten)
  - Endpoint `DELETE /admin/tenants/{tenantId}` (Root only — deletes the complete tenant in cascade)
  - DPO declared in `SECURITY.md`
  - Incident runbook: detection → escalation → ANPD communication if >50 data subjects are affected

### Gap 6 — Disaster recovery runbook
`docs/operations/disaster-recovery-runbook.md` with 3 scenarios:
- (A) RG nuked — RTO 2-3h (purge KV/Speech + recreate + restore)
- (B) Postgres corrupted — PITR restore, RTO 30min-1h
- (C) Azure region unavailable — the single-region MVP accepts downtime; post-GA: geo-redundancy

### Gap 7 — Secrets rotation policy
| Secret | Frequency | Method |
|---|---|---|
| `postgres-password` | 90 days | Script: new password + `ALTER USER` + update KV + revision restart |
| `jwt-secret` | 180 days | Update KV + 24h grace period (keyId logic in the JWT — future design) |
| `openai-api-key` | When rotated in the OpenAI dashboard | Update KV + restart api/worker |
| `azure-speech-key` | 90 days | `az cognitiveservices account keys regenerate` + KV + restart |

Workflow `.github/workflows/rotate-secrets.yml` with a monthly cron.

## Consequences

**Positive:**
- NORA can operate commercially serving Brazilian customers (operational LGPD compliance)
- The Plan A TOTVS code-walkthrough shows an enterprise-grade product
- Plan B onboarding of the first tenants has an operational safety net
- Tested backup + DR eliminate an entire class of nighttime nightmares

**Negative:**
- Sub-phase 1.12 estimated at ~1-2 agentic weeks — a significant investment
- Prod-tier Postgres costs more (~R$200-300/month vs R$85 dev) — the advanced Unit Economics in 1.12 models this
- Secrets rotation requires continuous maintenance (active workflows, alerts when it fails)

## Alternatives Considered

1. **Share `rg-nora-dev` for prod** — rejected because of blast radius (accidental debugging could take prod down)
2. **Multi-region from the MVP** — rejected because of complexity vs benefit before traction
3. **Postgres read replica** — deferred, a single instance is OK for the initial scale

## Application Plan

Sub-phase 1.12, branch `feat/sub-1.12-production-hardening`. Total effort **~1-2 agentic weeks**. One PR per gap (7 PRs, sequential or parallel when independent).

Prerequisite: Sub-phase 1.11 merged (Customer Confidence + AUTH_FILTER fix + PolicyEvaluator stringIn/Like).

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-14 | Tech Lead | ADR proposed during Sub-phase 1.10 (Docs Refresh). Detailed in `docs/operations/production-readiness-gaps.md`. Formal acceptance when Sub-phase 1.12 begins |
