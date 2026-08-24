# Production Readiness — Gap Analysis

> **Historical.** Written against the Azure deployment (`rg-nora-dev`), which is gone — no
> subscription, no export, nothing to decommission (ADR 0036). NORA now runs self-hosted on a
> single bare-metal host (ADR 0034/0036); Gaps whose premise was Azure-specific (Bicep params, Key
> Vault, Container Apps scale-to-zero) no longer apply as written: ADR 0034 partially superseded
> this document's parent decision (ADR 0016), and ADR 0036 removed the Azure premise entirely.
> What survives is the *shape* of each gap. **Reconciled 2026-08-17 against the code**, which moved
> two of them: **Gap 8 is delivered** — the BYPASSRLS telemetry path it asked for exists, with a
> guard and a test — and **Gap 2 is half delivered**, its mechanical half now a CI job. The banner
> here used to say the surviving gaps were "Gap 2 (migration safety), Gap 6 (test coverage)". Gap 6
> is the disaster-recovery scenario; test coverage is not a gap in this document at all, and it is
> measured on every CI run (see `docs/challenge/fiap-challenge-2026.md`). Kept for the gap-by-gap
> reasoning, not as an operating runbook — that is `docs/operations/host-deploy.md`.
>
> **Reconciled again 2026-08-23, and this time all eight gaps carry a note.** The 2026-08-17 pass
> reconciled Gaps 2, 5 and 8 and left Gaps 1, 3, 4, 6 and 7 describing a planet that had been
> switched off — which made the half that is still valid look as obsolete as the half that is not.
> Where each one stands now, so the reader does not have to derive it: **Gap 1** is void (there is
> no Bicep and no resource group). **Gap 2** stays half delivered. **Gap 3** and **Gap 4** were
> substantially closed on this date — an off-host backup leg, a quarterly drill timer, eight alert
> rules and a contact point — with the residue named in each. **Gap 5** stays delivered. **Gap 6**
> is a scenario that cannot happen any more, replaced by a different one nobody has written a
> runbook for. **Gap 7** is genuinely still open. **Gap 8** stays delivered.
>
> **Reconciled a third time 2026-08-24, against the infrastructure instead of the code — and the
> substrate this banner describes above is wrong.** The lines "which is gone — no subscription"
> and "a single bare-metal host" are kept unedited above because they are what the earlier passes
> believed, but ADR 0051 corrects both: the machine serving `nora.systems` is an **Azure VM**
> (`vm-nora-dev`, `rg-nora-dev-cc`), and the Azure subscription was never gone. Gap 1's premise
> ("no resource group") is therefore false again in the narrow sense — a resource group exists —
> while its conclusion stands, because nothing Bicep-shaped describes it. **Gap 9 below** records
> the incident that forced the discovery: the VM had been powering itself off nightly.
>
> **Audience (as written):** whoever operates NORA when it is promoted from the `rg-nora-dev` environment to `rg-nora-prod`.
>
> **Status:** descriptive (`docs/`). Implementation was tracked in **Sub-phase 1.12 — Production Hardening**, formalised via **ADR 0016 — Production Readiness Checklist**.
>
> **Context (as written):** the `rg-nora-dev` environment (`centralus`, 14 resources, 4 secrets in the KV, 8 Azure pitfalls catalogued) deployed NORA successfully. But **dev ≠ prod**. Seven areas had gaps that needed to be addressed before NORA took commercial traffic or exposed real customer data.

## Gap 1 — Bicep `prod.bicepparam` does not exist — **void**

> **Reconciled 2026-08-23.** `infra/bicep/` was deleted when the platform left Azure (ADR 0034,
> substrate corrected by ADR 0036), so there is no parameters file to write, no second Service
> Principal to scope and no `az deployment group what-if` to run. The substrate is one bare-metal
> host described by `infra/host/docker-compose.yml` and provisioned by `bootstrap-host.sh`; it has
> no dev/prod split, which retires the mixing risk this gap was about rather than solving it.
> Nothing survives here except the record that the gap existed.

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

## Gap 2 — Migrations safety strategy missing — **half delivered**

> **The mechanical half is now a CI job** (`scripts/check-migrations.sh`, job `migrations` inside
> `ci-gate`), and the three options below are Azure-shaped and no longer choosable as written:
> option 1 names `deploy-infra.yml`, option 2 needs Container Apps revisions, and neither exists
> (ADR 0034/0036). Flyway now runs at Spring startup on one bare-metal host.
>
> What the CI job closes is the half that can be caught **before a database ever sees the file** —
> which is the more valuable half, because the worst case this gap names ("a destructive `ALTER
> TABLE`, then a failure, data lost") cannot be undone afterwards by any deploy strategy:
>
> - **a duplicate version number**, which is a Flyway boot failure and not a merge conflict. A live
>   risk every time branches run in parallel here;
> - **an edit to a migration that already reached `main`.** Flyway checksums the body, comments
>   included, so every database that ran the old version fails `validate` and refuses to boot until
>   someone runs `flyway repair`. `V027` carries a `!! CHECKSUM WARNING !!` block because this
>   happened. An `ALLOW_MIGRATION_EDIT` marker inside the file is the deliberate escape hatch;
> - **destructive DDL with no acknowledgement.** It does not forbid the statement — V027
>   legitimately deletes rows that cross a tenant boundary — it requires a `DESTRUCTIVE:` comment,
>   so that destruction is something somebody wrote down rather than a line nobody noticed. Scoped
>   to migrations the branch **adds**, because demanding the marker on an applied file would be
>   demanding the checksum break that the previous rule exists to prevent.
>
> **What remains open is the deploy-time half**, and it belongs to whoever operates the host: there
> is no pre-flight that lists pending migrations and pauses for approval before the container
> starts. `infra/host/scripts/restore-drill.sh` already validates the Flyway history of a restored
> dump (it fails on any row with `success = false`), so the ingredients exist; the gate does not.

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

## Gap 3 — Backup RTO/RPO not formalised, restore not tested — **shape survives, plan does not**

> **Reconciled 2026-08-23.** Everything below names Azure services that no longer exist: there is no
> Flexible Server and therefore no PITR, no Storage Account soft-delete and no Key Vault. What the
> gap *asked for* — a tested restore and a measured RTO — is still the right question, and here is
> the state of it on the bare-metal host:
>
> - **Backup:** an hourly logical dump (`infra/host/backup/run-backup.sh`) into `/srv/nora/backups`
>   with a `.sha256` and a `.toc` beside each file. **RPO is therefore one hour**, by construction
>   and not by SLA — the "5 min" below was a property of PITR and died with it.
> - **Off-host copy:** exists since 2026-08-23 (`infra/host/scripts/offsite-backup.sh`, hourly
>   timer). It **fails loudly** every run until `NORA_OFFSITE_TARGET` is filled in, with `none` as
>   the only deliberate way to switch it off — a backup leg that silently does nothing is the
>   failure this gap is about. The five observability volumes are still not copied, and that is
>   recorded as a decision in the compose rather than left as an omission.
> - **The drill:** `infra/host/scripts/restore-drill.sh` restores the most recent dump into a
>   disposable `--network none` container and validates row counts, the Flyway history (it fails on
>   any row with `success = false`), per-tenant reads and the `nora_app` grants. It runs quarterly
>   on `nora-restore-drill.timer` since 2026-08-23.
> - **What is still open, and it is the part that matters:** the drill has never actually been
>   executed. The results table in `docs/operations/host-deploy.md` still reads `(pending)`, so
>   **the RTO floor has never been measured.** ADR 0038 §6c deferred the cadence; the cadence now
>   exists, and the measurement does not. Writing "RTO: 2h" here would be worse than writing
>   nothing, which is why the number below is left as the Azure-era estimate it was.

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

## Gap 4 — Monitoring + alerting not wired — **the alerting half is delivered**

> **Reconciled 2026-08-23.** The stack below is Azure's and is gone; the replacement is
> OpenTelemetry Collector + Prometheus + Loki + Alloy + Grafana on the host (ADR 0034/0036), so
> "Azure Monitor" and "an Application Insights workbook" are not options.
>
> **What this gap asked for now exists**, and it was the last item of ADR 0038 §6a:
> `infra/host/observability/grafana/provisioning/alerting/` carries **eight rules**, one contact
> point and one notification policy. The rules are the upstream failing at the edge, the tunnel
> reporting zero connections, Postgres not being scraped, any scrape target down, a 5xx rate above
> 5%, the root filesystem below 5 GiB free, the Loki compactor idle for a day, and no verified
> database dump in three hours. Two of those conditions had **no series to fire on**, so the
> collector gained a `hostmetrics` receiver (scoped to the container's own root mount rather than
> the host filesystem) and a `postgresql` receiver — an alert rule over a metric nobody emits is a
> configuration file that looks like coverage. Separately, every systemd unit on the host now
> escalates to `nora-alert@`, which runs `scripts/notify-failure.sh`, so a failing timer is no
> longer silent.
>
> **What is still open:** the "no SLO declared" bullet. The three targets below were written for a
> service with users; ADR 0038 §1 declares there are none, so an uptime percentage here would be a
> number nobody is accountable to. The dashboard (`nora-overview.json`) remains how the stack is
> actually inspected, and it works.

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

## Gap 6 — Disaster recovery scenario "RG deleted by mistake" — **the scenario no longer exists; the question does**

> **Reconciled 2026-08-23.** There is no resource group to delete. Every command in the plan below
> — `az keyvault purge`, `az cognitiveservices account purge`, `az group create` — targets a
> subscription that was shut down on 2026-08-07, and the workflow it tells you to dispatch,
> `deploy-infra.yml`, **does not exist in this repository**: `ls .github/workflows/` lists eleven
> files and none of them is it. Following this section literally is not a slow path to recovery, it
> is a dead end, which is why the note sits above the plan rather than beside it.
>
> The equivalent question on one bare-metal host is "the machine is gone, or its disk is", and the
> pieces of an answer exist without being assembled into a runbook:
>
> - **Rebuild the host:** `infra/host/scripts/bootstrap-host.sh` provisions Docker, the compose
>   project, the tunnel, the secrets bootstrap and the four systemd timers from a clean Ubuntu.
> - **Get the data back:** `restore-into-host.sh` puts a dump into the live stack;
>   `restore-drill.sh` is the same operation into a disposable container, and prints an RTO floor.
> - **Get the secrets back:** SOPS + age, with **the private key on the host only**. This is the
>   real single point of failure of the current substrate and it is not in the list below because
>   the list predates it: losing the machine without a copy of that key elsewhere means the
>   encrypted secrets in the repository cannot be opened by anybody.
> - **Get the code and images back:** the repository is public and the images are in GHCR; a
>   release tag names both.
>
> **What is still open:** nobody has written `docs/operations/disaster-recovery-runbook.md`, the
> pieces above have never been exercised end to end, and the age key has no declared escrow.
> Recovery would still be improvised — which is exactly what this gap said in 2026-05, about a
> different planet.

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

## Gap 7 — Secrets rotation policy missing — **still open, against a different secret set**

> **Reconciled 2026-08-23**, and this is the one gap of the four where the answer is still "not
> done". The four Key Vault secrets tabled below no longer exist, `azure-speech-key` least of all —
> ADR 0035 deleted the Speech broker and ADR 0039 replaced the whole transcription path. ADR 0038
> §6d already named this section as Azure-era.
>
> What exists instead: **31 keys in `infra/host/secrets.env.example`**, encrypted with SOPS + age
> in `secrets.env.sops`, with the private key on the host only, and
> `infra/host/scripts/secrets-bootstrap.sh --regenerate` able to reissue the generated ones.
>
> What does not exist: **any rotation schedule, runbook or workflow, for any of the 31.** Not one.
> The `rotate-secrets.yml` proposed at the bottom of this section was never written. ADR 0038 §6d
> defers it with a reason that is about blast radius rather than effort — the set is a handful of
> generated passwords plus re-issuable third-party API keys, on a host with exactly one operator,
> so rotation's value is bounded by the number of people who could have leaked one. That reasoning
> holds only while §1 of ADR 0038 holds; the moment somebody other than the maintainer has access,
> this is the first item of the operations block to come back.

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

## Gap 8 — Control plane: business telemetry breaks silently under RLS enforce — **DELIVERED**

> **Closed, and it was closed before this note was written.** Everything the plan below asks for
> exists: the `nora_telemetry` BYPASSRLS role (`infra/host/postgres/init/01-roles-and-db.sql`,
> provisioned by `db/operational/R001__provision_app_roles.sql`), the dedicated read path in
> `PrimaryDbBusinessMetricsSource` selected by `nora.security.rls.telemetry.url`, and
> `RlsEnforceTelemetryGuard` — which refuses a half-applied cutover rather than degrading — with a
> test beside it. The Javadoc the plan asked for is on the class, spelling out that without the
> dedicated path the aggregation would read `analyses=0/tenants=0` **silently**.
>
> **The line below saying "does not block v1 (enforce=false today)" is the part that was wrong and
> is the reason to read this carefully.** RLS enforce has been ON on the deployed stack since
> 2026-08-10 (ADR 0038 §6g). Had the gap still been open, it would not have been a future risk — it
> would have been silently returning zeros in production for a week. It was not, because the work
> landed; but the document said otherwise, which is the failure mode this whole repository has spent
> a month removing: a status that stopped being true and kept being written in the present tense.

**Current situation:** the control plane (ADR 0022/0024) has the **business** telemetry front (cuttable) reading the **primary** database cross-tenant via `PrimaryDbBusinessMetricsSource` (`COUNT(*)` / `COUNT(DISTINCT tenant_id)` on `meeting_analyses`), **without** tenant context — an intentional operator-only aggregation. It works today because the primary datasource runs as the owner role (BYPASSRLS) with `NORA_RLS_ENFORCE=false`.

**Gap:** when the RLS enforce opt-in (ADR 0019 — tenant isolation defense-in-depth; operational cutover in ADR 0026/0028) is activated (role `nora_app` NOBYPASSRLS + `NORA_RLS_ENFORCE=true`), these queries run **without a tenant GUC** (there is no `@Transactional`, so `TenantRlsAspect` does not fire) ⇒ the `tenant_isolation` policy (fail-closed) hides **all** rows ⇒ `analyses=0`/`tenantsActive=0` **silently** (no error). The operator's panel would show a false "zero activity", with no sign that the read was suppressed.

**Plan (prerequisite for turning on RLS enforce):**

- Give the operator-only read a dedicated **BYPASSRLS** path: either a telemetry role with `BYPASSRLS`, or a `SECURITY DEFINER` view/function owned by a privileged role with `GRANT SELECT` to `nora_app`. The cross-tenant aggregation is intentional and operator-only.
- Minimal alternative: detect the state and return `enabled:false` (instead of `enabled:true` with zeros) when the cross-tenant read is not possible — that way the operator sees "unavailable", not "a real zero".
- Documented in the Javadoc of `PrimaryDbBusinessMetricsSource` and in the contract (§3). Cost: S. **Does not block v1** (enforce=false today).

## Summary

This table is now **state**, not estimate. The effort column it used to carry priced work against a
substrate that no longer exists, and a T-shirt size for a task that cannot be performed is noise.

| Gap | State on 2026-08-23 | Successor ADR? |
|---|---|---|
| 1. Bicep prod.bicepparam | **Void.** No Bicep, no resource group, no dev/prod split | ADR 0016, ADR 0034/0036 |
| 2. Migrations safety | **Half delivered.** The CI half is `scripts/check-migrations.sh`; the deploy-time pre-flight is open | ADR 0016 |
| 3. RTO/RPO + restore drill | **Substantially closed.** Off-host leg and quarterly drill timer exist; the drill has never been run, so the RTO floor is unmeasured | ADR 0036 §3, ADR 0038 §6b/§6c |
| 4. Monitoring + alerting | **Alerting delivered** — eight rules, one contact point, one notification policy, plus the two receivers the rules needed. No SLO, deliberately | ADR 0038 §6a |
| 5. Operational LGPD | **Delivered** | ADR 0029 |
| 6. DR runbook | **Open, against a different scenario.** The Azure one cannot happen; the host one has pieces and no runbook, and the age key has no escrow | ADR 0036 |
| 7. Secrets rotation | **Open.** 31 keys in SOPS + age, no schedule, no runbook, no workflow | ADR 0038 §6d |
| 8. Control plane under RLS enforce | **Delivered** — `nora_telemetry` BYPASSRLS + `RlsEnforceTelemetryGuard` | ADR 0022 |

**"Sub-phase 1.12 — Production Hardening" is not a scheduled phase.** The estimate this line
carried ("~1-2 agentic weeks") was written for a commercial launch that ADR 0038 §1 declares is not
happening. Items get built when they are worth building, which is how four of them got built on
2026-08-23 with no phase around them.

Prerequisites: the **code** items of Sub-phase 1.11 already delivered — Customer Confidence (#148), the AUTH_FILTER fix (silent 500 ceiling removed via batched scanning) and PolicyEvaluator (`StringIn`/`StringLike`/`DateGreaterThan`/`DateLessThan`). Items (e) seed and (f) demo script were delivered on 2026-08-17 (`scripts/seed-demo.sh`, [`../challenge/demo-script.md`](../challenge/demo-script.md)); they never blocked 1.12 either way.

## Gap 9 — The host powered itself off nightly, and nothing noticed for six days — **RESOLVED, with the real gap it exposed left open**

**What happened.** The substrate turned out to be an Azure VM (ADR 0051), carrying a DevTestLab
auto-shutdown schedule at 04:00 UTC. It fired on 2026-08-18 and `nora.systems` stayed down until
2026-08-24, when the VM was started by hand during the audit follow-up. Six days of outage,
detected by nobody and nothing — the alerting delivered under Gap 4 runs *on the host*, so a host
that is off cannot report that it is off.

**What was done (2026-08-24).** The schedule was deleted, and the VM resized `Standard_B4s_v2` →
`Standard_B2as_v2` (~USD 136 → ~USD 55/month) so that running 24/7 fits inside the Azure for
Students credit. All fourteen containers verified healthy after the resize; the site answers 200.

**What stays open, because the incident proved it rather than the shutdown itself:**

1. **No outside-the-host liveness check exists.** Anything that can only scream from inside the
   machine is mute in exactly this failure. The cheapest honest fix is an external uptime probe
   against `https://nora.systems/healthz` (Grafana Cloud free tier, UptimeRobot, or a GitHub
   Actions cron that curls and opens an issue) — pick one and it closes; none is configured today.
2. **The live deployment does not follow the documented one** — `latest` tags, no systemd timers,
   no git checkout on the host (ADR 0051 §Consequences; measured 2026-08-24). Until the bootstrap
   in `host-deploy.md` is run on the real machine, the deploy, off-host backup and restore-drill
   machinery this repository carries is installed nowhere.

## History

| Date | Change |
|---|---|
| 2026-08-24 | **Gap 9 added and immediately part-resolved.** The substrate is an Azure VM (ADR 0051); its nightly auto-shutdown had kept the site down for six days with nothing noticing. Schedule deleted, VM resized to fit the student credit 24/7. The exposed residue — no external liveness probe, and a live deployment that does not follow this repository's deploy machinery — is recorded in the gap rather than closed by it |
| 2026-05-14 | Doc created during Sub-phase 1.10 (Docs Refresh) |
| 2026-05-28 | Gap 8 added: the control plane's business telemetry (ADR 0022) goes to zero under RLS enforce — a BYPASSRLS role is a prerequisite before turning on RLS enforce |
| 2026-06-06 | Doc x code reconciliation + standardisation: Gap 5 (operational LGPD) marked as delivered via ADR 0029; reference correction ADR 0019 → ADR 0029 for LGPD |
| 2026-08-17 | **The document was declared historical and partly reconciled**, and this row is written on 2026-08-23 because the pass that made the change did not record itself here — the most consequential revision the file had ever had was absent from its own history table. What that pass did: added the `**Historical.**` banner (Azure is gone; ADR 0034 partially supersedes ADR 0016 and ADR 0036 removed the premise), marked **Gap 8 delivered** with the `nora_telemetry` BYPASSRLS path and its startup guard, marked **Gap 2 half delivered** with `scripts/check-migrations.sh` as its CI half, and corrected a banner that had been naming Gap 6 as "test coverage" when Gap 6 is the disaster-recovery scenario |
| 2026-08-23 | **Reconciliation completed across all eight gaps, and four of them moved.** Gaps 1, 3, 4, 6 and 7 had been left describing Azure while Gaps 2, 5 and 8 carried notes, which made the still-valid half look as obsolete as the dead half — the state this document was in when it was cited as "not actionable". Gap 1 is void. Gap 3 gained an off-host backup leg and a quarterly drill timer, with the unmeasured RTO named as the residue. Gap 4's alerting half is delivered: eight rules, a contact point, a notification policy and the two collector receivers two of the rules needed. Gap 6 records that its scenario cannot occur and that the host equivalent has pieces but no runbook, plus the age-key escrow nobody had written down. Gap 7 is restated against the 31 SOPS keys and stays open. The Summary table stopped estimating effort against a dead substrate and now states state, and **Gap 8 was moved above the Summary**, where it had sat below the table that summarised it |
