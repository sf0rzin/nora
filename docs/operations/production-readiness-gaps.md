# Production Readiness — Gap Analysis

> **Historical.** Written against the Azure deployment (`rg-nora-dev`), which is gone — no
> subscription, no export, nothing to decommission (ADR 0036). NORA now runs self-hosted on a
> single bare-metal host (ADR 0034/0036); Gaps whose premise was Azure-specific (Bicep params, Key
> Vault, Container Apps scale-to-zero) no longer apply as written: ADR 0034 partially superseded
> this document's parent decision (ADR 0016), and ADR 0036 removed the Azure premise entirely.
> What survives is the *shape* of each gap — Gap 2 (migration safety), Gap 6
> (test coverage) and the underlying "dev ≠ prod" question are still real questions on the current
> substrate, just answered differently. Kept for the gap-by-gap reasoning, not as an operating
> runbook — that is `docs/operations/host-deploy.md`.
>
> **Audience (as written):** whoever operates NORA when it is promoted from the `rg-nora-dev` environment to `rg-nora-prod`.
>
> **Status:** descriptive (`docs/`). Implementation was tracked in **Sub-phase 1.12 — Production Hardening**, formalised via **ADR 0016 — Production Readiness Checklist**.
>
> **Context (as written):** the `rg-nora-dev` environment (`centralus`, 14 resources, 4 secrets in the KV, 8 Azure pitfalls catalogued) deployed NORA successfully. But **dev ≠ prod**. Seven areas had gaps that needed to be addressed before NORA took commercial traffic or exposed real customer data.

## Gap 1 — Bicep `prod.bicepparam` does not exist

**Current situation:** `infra/bicep/main.dev.bicepparam` is the only parameters file. It points to `rg-nora-dev`, region `centralus`, `enableSearch=false`, secrets coming from local env vars (generated randomly for dev).

**Gap:** without `main.prod.bicepparam`, deploying to prod today would be a manual copy-paste of values, with a risk of mixing dev/prod and leaking secrets.

**Plan (Sub-phase 1.12):**

- Create `infra/bicep/main.prod.bicepparam` parameterised with:
  - `env = 'prod'`
  - `location` (decide the region — probably stays `centralus` because of unit economics validated in the pilot, or migrates to `eastus` if Postgres becomes available there via offer expansion)
  - `enablePurgeProtection = true` on the KV (default `false` in dev for fast teardown)
  - `enableSearch = true`
  - SKUs: Postgres probably moves up to `Standard_D2ds_v5` or the GP tier (to be decided based on advanced unit economics — 1.12 includes a GA-conservative + GA-aggressive model)
  - `min replicas = 1` on **all** Container Apps (warm-up — scale-to-zero produces bad UX in prod)
- Secrets via env vars **from another Service Principal scoped to `rg-nora-prod`** (do not reuse the dev SP)
- Bicep params validated via `az deployment group what-if` before `create`

## Gap 2 — Migrations safety strategy missing

**Current situation:** Flyway runs at the startup of the API Container App. If a migration fails mid-deploy, the state is left inconsistent, with no automated rollback. In dev, just destroy the RG. In prod, **no**.

**Gap:** without a safety strategy, the next prod deploy with a new migration risks:

- A partially applied schema, the app not starting, indefinite downtime
- Manual rollback implying long downtime
- Worst case: a migration applies a destructive `ALTER TABLE` (drop column), then fails, data lost

**Plan (Sub-phase 1.12):**

Decide between 3 strategies:

1. **Pre-flight check + manual approve:** the `deploy-infra.yml` workflow runs `flyway info` (lists what is pending) → posts it as a GitHub Actions summary → requires a manual `gh workflow run` with the input "I read the migrations and approve" before bringing up the new Container App revision. Works for low-frequency deploys (1-3/week in prod). **Cost:** 1 manual step.
2. **Blue/Green deploy via revisions:** Container Apps already supports multiple revisions in parallel. The new revision runs the migration (if applicable), validates `/actuator/health`, traffic split 0 → 50 → 100. Rollback = traffic 100 → 0. **Cost:** a more complex workflow (~1 agentic day), revisions have an extra cost.
3. **Expand/contract migrations:** a migration convention with 2 phases — `V0XX_expand` (additive: add a nullable column, create a new table) → deploy → `V0YY_contract` (cleanup: drop the old column) only after X days of stability. **Cost:** continuous discipline, a PR process, but zero downtime.

Initial recommendation: **option (1)** for MVP/Pilot, evolving to **(3)** at GA.

ADR 0016 documents the choice.

## Gap 3 — Backup RTO/RPO not formalised, restore not tested

**Current situation:** Postgres Flexible Server has a default automatic backup (point-in-time recovery — PITR) with 7 days of retention. The Storage Account has 7-day soft-delete (configured in Bicep). Key Vault soft-delete 7 days (configured).

**Gap:** nobody has actually tested a restore. RTO (recovery time objective) and RPO (recovery point objective) are not declared in an internal SLA.

**Plan (Sub-phase 1.12):**

1. **Document RTO/RPO targets:**
   - RPO: **5 min** (max data lost in an incident) — Postgres PITR supports it
   - RTO: **2h** (max time offline) — restore of the Postgres flexible + redeploy via Bicep `prod.bicepparam`
2. **Restore drill:** in an isolated environment, run:
   - `az postgres flexible-server restore` pointing to timestamp T-1h
   - Validate the data (count, FK integrity, latest meeting)
   - Validate that the app connects to the restore (temporarily update `DATASOURCE_URL`)
   - Document the real measured time in `docs/operations/disaster-recovery-runbook.md`
3. **Define the frequency:** drill once per quarter in a mirror environment

## Gap 4 — Monitoring + alerting not wired

**Current situation:** Application Insights is provisioned, receiving telemetry from the 3 Container Apps + Worker. Log Analytics workspace collecting logs. But:

- No **alert** configured (no email/Slack notification)
- No structured **dashboard** (you have to open the Portal and build an ad-hoc KQL query)
- No **SLO** declared

**Gap:** when NORA goes down in prod, nobody will know until a customer complains.

**Plan (Sub-phase 1.12):**

1. **Critical alerts** (Azure Monitor) wired to the maintainer's e-mail (and a future Slack webhook):
   - API Container App: `/actuator/health` non-200 for >2min
   - Postgres: connection failures >10/min or CPU >80% sustained for 5min
   - Container Apps: scale-up failed (replica retries >3)
   - Speech: `Ocp-Apim-Subscription-Key` error rate >5%
2. **Dashboard "NORA prod overview"** in an Application Insights workbook:
   - Requests/min per endpoint
   - API p50/p95/p99 latency
   - 5xx + 4xx errors
   - Postgres connections + slow queries
   - Daily costs (via the Cost Management API)
3. **Initial SLO**:
   - API uptime: 99.0% monthly (allows ~7h downtime/month — realistic for a single-region MVP)
   - p95 latency of `/meetings/{id}`: <1.5s
   - Async LLM analysis: 95% completed in <60s

## Gap 5 — Operational LGPD — DELIVERED (ADR 0029)

**Current situation:** PII Shield in the worker (redacts email, CPF, CNPJ, phone, card, BR person_name before sending to the LLM). Multi-tenancy guarantees isolation by `tenant_id`. httpOnly cookies. The operational LGPD layer has been **delivered** via **ADR 0029**:

- **Right to be forgotten:** endpoint `DELETE /privacy/meetings/{id}` (deletion by data subject/tenant).
- **Retention:** a scheduled `RetentionSweeper` purges meetings past a **global age cutoff**, and it is **off by default** — see the residue below for what that actually means.
- **Coverage:** `PrivacyFlowIntegrationTest` validates the end-to-end flow.
- **DPO declared** in `SECURITY.md` (contact: axonogenesis@proton.me).

This gap is no longer Sub-phase 1.12 debt.

**Residue (operational, non-blocking):**

1. **Data retention policy.** What ADR 0016 listed as the intended policy — "transcripts and analyses retained while the tenant is active + 30 days after cancellation" — **was never built and does not exist in the code**. What exists is narrower, and this is the honest statement of it:
   - Meetings, and everything that cascades from them (transcript with `raw_text`, participants, tags, analyses), are purged by a **flat age cutoff**: `NORA_PRIVACY_RETENTION_DAYS` days since creation. Nothing consults tenant status, plan or cancellation date.
   - The window is **global** — one number for every tenant. There is no per-tenant column and no per-plan window (ADR 0029 records this as a deferred trade-off).
   - The sentinel sits at the **bottom** of the range: `0` or a negative value means retention is **OFF** and nothing is purged. That is the shipped default, because the purge is an irreversible hard delete with CASCADE. `N >= 1` turns it on with an N-day window. There is no value meaning "purge immediately".
   - Revoked refresh tokens: cleanup of an old token chain is still debt (ADR 0020), not an implemented retention rule.
   - Prometheus keeps 30 days (`--storage.tsdb.retention.time=30d`) and Loki is configured for 30 days; the Application Insights line above is Azure-era and no longer applies (ADR 0034/0036).
2. Endpoint `DELETE /privacy/meetings/{id}` delivered (right to be forgotten by data subject/tenant).
3. Administrative endpoint for full tenant deletion (Root only) — future operational refinement.
4. `docs/security/lgpd-operations.md` with an incident runbook: detection, escalation, ANPD communication if >50 data subjects are affected — future operational refinement.

## Gap 6 — Disaster recovery scenario "RG deleted by mistake"

**Current situation:** Bicep IaC allows recreating the infra. Postgres has PITR. Storage has soft-delete. **But** the empirical test has already shown (Sub-phase 1.9, vault `azure_access.md`) that recreating with the same name runs into:

- Soft-deleted KV reserves the global name for 7 days
- Cognitive Services Speech the same
- Postgres may hit `LocationIsOfferRestricted` if the region changes

**Gap:** the DR runbook is not documented. In a real incident, recovery would be improvised.

**Plan (Sub-phase 1.12):**

`docs/operations/disaster-recovery-runbook.md` with:

1. **Scenario A — RG destroyed, data lost:**
   - Step 1: `az keyvault purge` + `az cognitiveservices account purge`
   - Step 2: `az group create rg-nora-prod` (same name, new location if necessary)
   - Step 3: GitHub Actions `deploy-infra.yml` workflow_dispatch
   - Step 4: Validate that services are UP
   - Step 5: Restore the most recent Postgres backup
   - **Estimated RTO:** 2-3h
2. **Scenario B — only Postgres corrupted:**
   - PITR to a pre-corruption timestamp
   - RTO: 30min-1h
3. **Scenario C — an entire Azure region unavailable:**
   - Single-region MVP: accepts the downtime
   - Future (GA): geo-redundancy via Postgres geo-replica + Front Door

## Gap 7 — Secrets rotation policy missing

**Current situation:** current secrets in the KV:
- `postgres-password` — generated randomly when the SP was created
- `jwt-secret` — generated randomly
- `openai-api-key` — empty (worker in stub mode by default)
- `azure-speech-key` — coming from `speech.listKeys().key1` (it would change if someone rotated it manually)

**Gap:** none of the 4 has an automated **rotation schedule**. In prod, that is a minimum security requirement.

**Plan (Sub-phase 1.12):**

| Secret | Rotation frequency | Method |
|---|---|---|
| `postgres-password` | Every 90 days | Script: generates a new password, `ALTER USER ... PASSWORD`, updates the KV secret, forces the Container Apps to pull the new version (revision restart) |
| `jwt-secret` | Every 180 days | Updates the KV secret + a 24h grace period so valid refresh tokens persist (needs "JWT secret rotation with keyId" logic — future design) |
| `openai-api-key` | When rotated manually in the OpenAI dashboard | Updates the KV secret + restarts api/worker |
| `azure-speech-key` | Every 90 days | `az cognitiveservices account keys regenerate` + updates the KV secret + restarts api |

A dedicated workflow `.github/workflows/rotate-secrets.yml` with a monthly cron can automate part of it.

## Summary

| Gap | Effort | Successor ADR? |
|---|---|---|
| 1. Bicep prod.bicepparam | M | ADR 0016 |
| 2. Migrations safety | M (decision between 3 strategies) | ADR 0016 |
| 3. RTO/RPO + restore drill | M (drill + doc) | — |
| 4. Monitoring + alerting | M (alerts + workbook + SLO) | — |
| 5. Operational LGPD — **delivered** | — (delivered via ADR 0029) | ADR 0029 |
| 6. DR runbook | S (doc + dry run) | — |
| 7. Secrets rotation | M (workflows + rotation scripts) | — |
| 8. Control plane under RLS enforce | S (BYPASSRLS role for business telemetry) | ADR 0022 |

**Total estimate for Sub-phase 1.12 — Production Hardening: ~1-2 agentic weeks.**

Prerequisites: the **code** items of Sub-phase 1.11 already delivered — Customer Confidence (#148), the AUTH_FILTER fix (silent 500 ceiling removed via batched scanning) and PolicyEvaluator (`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan`). Items (e) seed and (f) demo script were delivered on 2026-08-17 (`scripts/seed-demo.sh`, [`../challenge/demo-script.md`](../challenge/demo-script.md)); they never blocked 1.12 either way.

## Gap 8 — Control plane: business telemetry breaks silently under RLS enforce

**Current situation:** the control plane (ADR 0022/0024) has the **business** telemetry front (cuttable) reading the **primary** database cross-tenant via `PrimaryDbBusinessMetricsSource` (`COUNT(*)` / `COUNT(DISTINCT tenant_id)` on `meeting_analyses`), **without** tenant context — an intentional operator-only aggregation. It works today because the primary datasource runs as the owner role (BYPASSRLS) with `NORA_RLS_ENFORCE=false`.

**Gap:** when the RLS enforce opt-in (ADR 0019 — tenant isolation defense-in-depth; operational cutover in ADR 0026/0028) is activated (role `nora_app` NOBYPASSRLS + `NORA_RLS_ENFORCE=true`), these queries run **without a tenant GUC** (there is no `@Transactional`, so `TenantRlsAspect` does not fire) ⇒ the `tenant_isolation` policy (fail-closed) hides **all** rows ⇒ `analyses=0`/`tenantsActive=0` **silently** (no error). The operator's panel would show a false "zero activity", with no sign that the read was suppressed.

**Plan (prerequisite for turning on RLS enforce):**

- Give the operator-only read a dedicated **BYPASSRLS** path: either a telemetry role with `BYPASSRLS`, or a `SECURITY DEFINER` view/function owned by a privileged role with `GRANT SELECT` to `nora_app`. The cross-tenant aggregation is intentional and operator-only.
- Minimal alternative: detect the state and return `enabled:false` (instead of `enabled:true` with zeros) when the cross-tenant read is not possible — that way the operator sees "unavailable", not "a real zero".
- Documented in the Javadoc of `PrimaryDbBusinessMetricsSource` and in the contract (§3). Cost: S. **Does not block v1** (enforce=false today).

## History

| Date | Change |
|---|---|
| 2026-05-14 | Doc created during Sub-phase 1.10 (Docs Refresh) |
| 2026-05-28 | Gap 8 added: the control plane's business telemetry (ADR 0022) goes to zero under RLS enforce — a BYPASSRLS role is a prerequisite before turning on RLS enforce |
| 2026-06-06 | Doc x code reconciliation + standardisation: Gap 5 (operational LGPD) marked as delivered via ADR 0029; reference correction ADR 0019 → ADR 0029 for LGPD |
