# ADR 0034 — Migration from Azure Container Apps to self-hosted Proxmox (single VM + Docker Compose)

- **Status:** accepted
- **Date:** 2026-08-07
- **Supersedes:** ADR 0009 (Speech Token Broker — the Azure Speech resource ceases to exist; the
  functional replacement is **ADR 0035**)
- **Partially supersedes:** ADR 0016 (production-readiness checklist — every Azure-anchored premise
  falls: Gap 1 `prod.bicepparam`/separate SP, Gap 3 RPO/RTO relying on the Flexible Server's
  PITR, Gap 4 alerts via Azure Monitor + the App Insights workbook, Gap 7 rotation via
  Key Vault. Gap 2 and Gap 6 remain valid on a different substrate; Gap 5 was already delivered by
  ADR 0029)
- **Changes:** ADR 0023 (the operator edge is no longer a Container App ingress; the separation of
  planes and the two internal tokens remain), ADR 0022 (the 2nd Postgres is still a **separate
  server**, but the physical isolation falls — same VM), ADR 0026/0028 (the RLS design does not change; the
  cutover **endpoint** changes and the hardcoded Azure FQDN needs to be repointed), ADR 0029 (the
  resting place of PII moves from a managed datacenter to our own hardware)
- **Extends:** ADR 0025 (the Cloudflare Tunnel is no longer only for `nora-admin` and becomes the **only**
  ingress of the whole stack)
- **Related:** ADR 0017 (public repo — it is what vetoes push-based deployment), ADR 0027 (branch
  protection), ADR 0024 (control plane), `infra/proxmox/docker-compose.yml` (the stack contract),
  `docs/operations/proxmox-deploy.md` (successor runbook), `docs/operations/azure-decommission.md`

## Context

### Production is down

`nora.systems` and `api.nora.systems` return **522** (Cloudflare reaches DNS, the origin does not
respond). The Container App's raw FQDN —
`nora-api-dev.salmonbeach-349d395f.centralus.azurecontainerapps.io` — **also does not connect**, which
removes Cloudflare from the list of suspects: the origin is gone.

Established timeline:

| Date | Event |
|---|---|
| 2026-06-13 | Last successful infra deploy (`deploy-infra.yml`) |
| 2026-07-06 | Last `build-images` on `main` with `deploy-apps` OK — that is, federated OIDC still worked on that date |
| 2026-08-07 | 522 on the public domain and on the direct FQDN |

Probable cause: **the Azure for Students subscription was deactivated**. The student benefit expires
or runs out of credit with no operational warning; resources are stopped and, after a retention period,
deleted. It is neither a code nor a deploy failure — `deploy-apps` was working on 06/07.

### There is no data to preserve — and that simplifies everything

NORA is an **educational project** (FIAP Challenge 2026 × TOTVS). Even though `rg-nora-dev` serves
`nora.systems` on a real domain and the stack is built with production standards, **there is no production
data and no user base** — and, by the PO's decision, there will not be: the product is not going to operate
commercially in this incarnation.

Direct consequence, and it reorders the entire migration: **the contents of the two Azure Postgres instances are
disposable.** There is no data rescue on the critical path, there is no retention window running against
the schedule, and the decommission can be an unceremonious `az group delete`. The migration is a
platform change, not a recovery operation.

It is worth recording what *would* be true in a commercial scenario, because the asymmetry explains several
choices in this ADR that would otherwise look lax: `transcripts.raw_text` stores the raw
transcription — PII at rest, since the PII Shield redacts what goes to the LLM and not what stays in the database (ADR
0029) — and the integrations' OAuth tokens (ADR 0031) are encrypted but not recoverable without the database.
In a real operation, losing the subscription would be a data incident, and the Flexible Server's 7-day PITR
would not help: it is internal to the subscription and dies along with it. Here, it is just cleanup.

### There never was an ADR declaring Azure

Worth recording without euphemism: the hosting platform was **never decided** — it was inherited from a
student credit. What exists is a runbook (`docs/operations/azure-deploy.md`) and a catalog of
**8 Azure for Students pitfalls** discovered empirically in Sub-phase 1.9. ADR 0016
assumed Azure as a given, not as a choice. This ADR is the project's **first formal record of a hosting
platform decision**.

### The audit: leaving Azure is cheaper than the inventory suggested

The coupling survey found the opposite of what was expected:

- **Zero Azure SDKs in any app.** No `com.azure:*` in the `pom.xml`, no `azure-*` in the Python
  worker, no `@azure/*` in either Next app. The "cloud" was in the IaC and in the env vars, not in the code.
- **The deep coupling in code is TWO points:**
  1. the Application Insights `-javaagent` (`services/api/Dockerfile:16,22-28,39`);
  2. the desktop's Python sidecar (`azure-cognitiveservices-speech`) — addressed by **ADR 0035**,
     not by this one.

And a third one the inventory nearly missed:

- **`AppInsightsHealthSource.java` is a telemetry READ path.** It does a `GET` on
  `api.applicationinsights.io/v1/apps/{id}/query` with **KQL** and an `x-api-key` header. An OTel
  Collector is **write-only and does not speak KQL** — there is no replacement by configuration. That is
  a Java class rewrite, and it fell outside the initial inventory because searching for "SDK" does not catch a
  raw HTTP client.

Two execution pitfalls, established before any commit:

- **Swapping just the `OTEL_EXPORTER_OTLP_ENDPOINT` env var does NOT work.** The App Insights agent exports
  to the connection string's **Breeze** endpoint and **ignores** `OTEL_EXPORTER_OTLP_ENDPOINT`. It is
  mandatory to **swap the JAR** for `opentelemetry-javaagent.jar` and **republish the image**. Without
  that, the API comes up "with no error" and emits nothing.
- **`AZURE_SPEECH_ENDPOINT` is dead weight.** It is injected by Bicep and **has no consumer in the
  repo**; the `speechEndpoint` output is also unconsumed. The broker uses the **regional STS**
  endpoint (`application.yml:179`,
  `https://%s.api.cognitive.microsoft.com/sts/v1.0/issueToken`) with the
  `Ocp-Apim-Subscription-Key` header.

### The repository is public — and that decides the deployment model

The repo is public (ADR 0017) and `deploy-infra.yml:60-64` has a `pull_request` trigger. A **persistent
self-hosted runner on the home network** would therefore execute **arbitrary fork code** with
access to the Docker socket and to the LAN. This is not a style preference: it is what **eliminates**
push-based deployment before any convenience comparison.

### A new constraint available

There is an idle home Proxmox ("beta"), with power and link already paid for. The marginal cost of
hosting the stack there: ~zero in money.

## Decision

**Migrate NORA's production to a single Debian VM on Proxmox, orchestrated by Docker Compose,
with Cloudflare Tunnel as the only ingress, secrets in SOPS+age and PULL-based deployment.**

The complete stack contract is `infra/proxmox/docker-compose.yml` (compose project `nora`) — it
is the source of truth; what follows are the decisions that justify it.

### 1. Substrate: single Debian VM + Docker Compose

One VM, three bridge networks: `edge` (only cloudflared and caddy), `internal` (`internal: true` — no route
to the internet) and `data` (`internal: true`). **No port published on the host** other than `127.0.0.1`
for debugging via `ssh -L`. Each service is the 1:1 counterpart of a `container-app.bicep` module; the env vars
were transcribed from the range `main.bicep:694-1540`.

No scale-to-zero: on a single VM the cost of keeping one replica up is irrelevant and it eliminates the cold
start that the Consumption profile imposed.

### 2. Ingress: Cloudflare Tunnel as the only entry path

Extends ADR 0025 from `nora-admin` to the whole stack. `cloudflared` connects **outbound** to
Cloudflare; there is no inbound port, there is no origin FQDN to bypass Access. The visitor's TLS
is Cloudflare's Universal SSL (SSL mode Full, as it already was).

Host-based routing belongs to **Caddy**: apex and `www` → `web`; `api.<dom>` → `api`;
`admin.<dom>` → `admin`; `grafana.<dom>` → `grafana`.

**`CF_ACCESS_AUD` becomes mandatory** (`:?` in the compose file). Today, in production, it arrives **empty**
— it is registered as a Secret and read as `vars.` in the workflows — and `apps/admin/src/lib/access.ts`
does **fail-OPEN**: Tier 2 of ADR 0025 is silently disabled in production. The migration
closes that by construction: without the variable, the `admin` container does not come up.

### 3. Caddy: a new component, with no Azure counterpart, and the reason for it

Container Apps had `activeRevisionsMode: Single` with a **readiness gate**: it only switched traffic
after the new revision's probe passed. `docker compose up -d` does the opposite — **it takes the old
container down before bringing the new one up**. The Spring boot with Flyway takes ~30s (the healthcheck uses
`start_period: 45s` and `retries: 12`, calibrated in the Bicep for that).

Caddy exists to turn that hole into latency: `lb_try_duration` holds the request and
resends it when the origin comes back. It is **mitigation, not elimination** — see §Consequences.

### 4. Secrets: SOPS + age

`infra/proxmox/secrets.env.sops` is **versioned encrypted**; the age private key lives **only on the host**,
at `/etc/nora/age.key` (`chmod 400`, owner `root`). The deploy decrypts it into a `.env` in **tmpfs**, which
never touches the disk.

It replaces Key Vault + 3 User-Assigned Managed Identities + the role assignments. The consequences
of this are not good — they are recorded below, without makeup.

### 5. PULL-based deployment, never push

An agent on the host itself pulls the desired tag from GHCR and applies it. **No GitHub runner touches the
home network.** CI remains the owner of building and publishing the images; the host is the owner
of the rollout.

Images keep coming from the already existing GHCR
(`ghcr.io/sys0xff/nora-{api,worker,web,admin}`). The rollout mechanism is the **immutable
`sha-<short>` tags** — `latest` serves for bootstrap, not for rollout, because it does not give a rollback target.

### 6. Observability: two legs, not one

Coverage equivalent to what existed requires **two** distinct legs:

| What it did on Azure | Replacement |
|---|---|
| App Insights `-javaagent` (traces/metrics) | `opentelemetry-javaagent.jar` → `otel-collector` (OTLP gRPC 4317) → Prometheus (30d retention) |
| `appLogsConfiguration` → Log Analytics (the apps' stdout) | Alloy reading the Docker socket → Loki |
| Workbook / Metrics Explorer | Grafana at `grafana.<dom>`, behind the same Tunnel |

An OTel Collector alone does **not** cover log shipping — hence Alloy. It was chosen over the `loki` log
driver because the plugin breaks `docker logs` and, in `mode=non-blocking`, silently drops lines
under backpressure; Alloy has a WAL, so backpressure becomes delay, not loss.

**Swapping the JAR is mandatory and is an image change**, not an env change (see §Context).

### 7. `AppInsightsHealthSource` → `PrometheusHealthSource`

The control plane's health telemetry read (ADR 0024) is rewritten: the App Insights REST Query API
(KQL, `x-api-key`) is replaced by the **Prometheus Query API**
(`NORA_PLATFORM_HEALTH_PROMETHEUS_URL: http://prometheus:9090`, window in
`NORA_PLATFORM_HEALTH_WINDOW`).

Positive side effect: `NORA_PLATFORM_HEALTH_APP_ID` / `NORA_PLATFORM_HEALTH_API_KEY` were never
provisioned anywhere, so that dashboard has been "unavailable" from the start. It starts
working for the first time.

### 8. Data: two Postgres, three roles, no `sslmode`

- Image `pgvector/pgvector:pg16`, **two separate servers** (`nora` and `nora_platform`),
  preserving the `PLATFORM_DATASOURCE_URL` contract and ADR 0022's blast radius — with the caveat
  that "separate" is now a process, not a machine.
- **The JDBC URL does not carry `?sslmode=require`.** The official image comes up with SSL OFF and Hikari fails at
  boot. The traffic does not leave the `data` bridge, which is `internal: true`.
- The **three** RLS roles (ADR 0026/0028) are provisioned: `nora_app` (NOBYPASSRLS, runtime),
  `nora_telemetry` (BYPASSRLS, operator dashboard) and the admin/owner (Flyway/DDL). **Omitting
  `nora_telemetry` silently zeroes the dashboard** — fail-closed with no error, the most expensive failure mode to
  diagnose. It is in the runbook as a blocking item.
- The Azure FQDN `nora-pg-dev-wgl3a3.postgres.database.azure.com` is hardcoded in **four**
  places and needs to be repointed or retired: `infra/bicep/main.dev.bicepparam:140`,
  `.github/workflows/rls-cutover.yml:40`, `docs/operations/rls-cutover-runbook.md:69`,
  `docs/operations/azure-deploy.md:398`.

### 9. Backup

Hourly logical `pg_dump` of both databases, 14-day retention, plus a VM snapshot on the Proxmox
Backup Server (outside the compose file). It replaces the Flexible Server's 7-day PITR — **with a loss
of guarantee**, quantified below.

<a id="escopo-excluido"></a>

## Excluded scope — what is DELETED, not ported

Migrating this would be porting debt. Nothing here has a consumer:

| Resource / variable | Why it goes |
|---|---|
| **Storage Account** (LRS, blob soft-delete 7d) | **Zero consumers** in the code. Provisioned and never used. |
| **Azure AI Search** (Basic, optional) | Off by default. Semantic search is V021 (`meeting_embeddings`) + the HTTP embedding client — it never depended on it. |
| **Easy Auth / Entra App Registration** | Abandoned by **ADR 0025**; the Bicep still provisioned the secret. `EASYAUTH_CLIENT_ID` / `EASYAUTH_CLIENT_SECRET` are orphans referenced in the workflow and nonexistent as a Secret. |
| **`daprAIConnectionString`** | **No app enables Dapr.** |
| **`AZURE_SPEECH_ENDPOINT`** + output `speechEndpoint` | Injected and unconsumed. The broker uses the regional STS endpoint (`application.yml:179`). |
| **`AZURE_SEARCH_ENDPOINT` / `AZURE_SEARCH_INDEX`** | Dependent on the AI Search that is off. |
| **`APPLICATIONINSIGHTS_CONNECTION_STRING`** | Dies with the javaagent swap. |
| **`NORA_PLATFORM_HEALTH_APP_ID` / `_API_KEY`** | Never provisioned; replaced by Prometheus. |
| **Key Vault + 3 UAIs + role assignments** | Replaced by SOPS+age (with the consequences below). |
| **Azure Speech (Cognitive Services S0)** | See **ADR 0035**. |

**Explicitly NOT in scope:** actually turning on `pgvector`. V021 avoided the extension **on
purpose**, because of Azure's allow-list — it stores embeddings as JSON in a `TEXT` column and
computes cosine in Java. Leaving Azure unlocks the extension, but changing that is a **RAG refactor**,
not an infra migration. The `pgvector/pgvector:pg16` image makes the extension *available* and **does not
create it**.

## Consequences

<a id="disponibilidade"></a>

### Availability — it gets worse, and by how much

**The readiness gate and the rolling update are lost.** `activeRevisionsMode: Single` only cut
traffic over after the probe passed. Compose has no such concept. Result: a **window of ~45s per
API deploy** in which the origin does not respond (Spring boot + Flyway ~30s; healthcheck with
`start_period: 45s`). Mitigated — not eliminated — by `lb_try_duration` in Caddy: requests that
exceed the try duration still fail, and in-flight connections drop. Web and admin suffer less (shorter
boot), but they suffer.

**The managed PITR is lost — and here that is acceptable, not a debt.** RPO goes from ~5 min (ADR
0016 Gap 3, relying on the Flexible Server's PITR) to **up to 1 hour**, the `pg_dump` interval. RTO
goes from minutes to **hours**: provision/restore the VM, restore the logical dump, bring the stack up,
verify.

In a commercial product this would be a serious regression and would require pgBackRest with WAL archiving. **That is not
the case for this project.** NORA is educational, with no production data and no user base; what the
database holds is demonstration content, reproducible. An hourly `pg_dump` with 14-day retention,
plus a VM snapshot on the Proxmox Backup Server, is **proportional to what is at stake**. ADR 0016's
RPO/RTO targets were written assuming real operation; this ADR **replaces them with proportional
targets**, instead of keeping on paper a number that nobody is going to sustain.

`restore-drill.sh` remains valid — not because of loss risk, but because a restore that has never been tested
is a procedure that does not exist, and `production-readiness-gaps.md:67` already admitted that none had ever
been done. It is cheap to close that gap now.

**Single host = single point of failure.** No multi-AZ, no failover, no redundant hypervisor. A power
or home-link outage takes down the entire stack, including the observability that would tell us
it went down. Scenario C of ADR 0016 Gap 6 ("Azure region unavailable — single-region MVP accepts
downtime") becomes "single-**host** accepts downtime": same verdict, larger blast radius, higher
probability. For an academic demo with a scheduled pitch, the relevant risk is not average downtime — it is
**being down on presentation day**. The practical mitigation is rehearsing the stack boot beforehand,
not building HA.

**The 99.0% monthly SLO (ADR 0016 Gap 4) stops making sense and is withdrawn.** It was a target inherited
from a commercial premise. Nothing in this stack guarantees it, nobody is on call to defend it, and
keeping it on paper would only produce the illusion of a commitment. Recorded in its place: **the stack
is best-effort, with a window of ~45s per API deploy and no guarantee during host outages.**

### Secrets: the number GOES UP (the counterintuitive result)

On Azure, the API **did not have the Postgres password in an env var**: it came from Key Vault via `secretRef` +
a User-Assigned Managed Identity. The **identity was the credential**, and it was the platform that
rotated it.

Without a managed identity, each of those references becomes a **static value** in
`secrets.env.sops`: `POSTGRES_ADMIN_PASSWORD`, `POSTGRES_PLATFORM_ADMIN_PASSWORD`, `JWT_SECRET`,
`NORA_PLATFORM_INTERNAL_TOKEN`, `NORA_PLATFORM_ADMIN_TOKEN`, `NORA_INTEGRATIONS_ENC_KEY`,
`NORA_INTEGRATIONS_STATE_SECRET`, the LLM/embeddings providers' keys, `RESEND_API_KEY`, the
integrations' OAuth pairs, `GRAFANA_ADMIN_PASSWORD`, `CLOUDFLARE_TUNNEL_TOKEN`, the RLS roles'
passwords.

Out go 1 Key Vault and 3 UAIs. In come **~20 static values encrypted by ONE age key that lives in a
file on the host**. Whoever reads `/etc/nora/age.key` decrypts everything — the blast radius of compromising the host
grows materially. Honest and **partial** mitigation: 400/root permission, VM disk encrypted at
rest, and the rotation policy of ADR 0016 Gap 7 remains valid with `sops updatekeys` in place
of the KV. **It is not equivalent to a managed identity. It is worse.** Accepted for the price and the urgency.

### Operation and cost

- **The cost in money drops to ~zero** (power and link already exist, the Proxmox is idle). **The cost in
  human attention goes up**: patching the host and Docker, disk, snapshots, key rotation, and the restore
  drill — which is now the only thing between us and data loss.
- **`az` is lost.** All of `docs/operations/azure-deploy.md` and the 8 Azure for Students pitfalls
  become history. In exchange we gain a **new class of pitfalls** (`sslmode` in the JDBC
  URL, Loki retention without a compactor, `NEXT_PUBLIC` baked in at build time, `CF_ACCESS_AUD`
  fail-open, `initdb` that only runs on an empty volume, the 502 window) — cataloged in
  `docs/operations/proxmox-deploy.md`.
- **Secretless federated OIDC is lost.** The PULL deployment does not use any cloud credential, but the
  host will need a GHCR pull credential if the images stop being public.
- ADR 0022 remains valid in the contract (2nd datasource, independent dump), but the **physical
  isolation** it bought disappears: the two Postgres instances now share host, kernel and disk.

### What actually improves

- **The stack becomes reproducible locally.** `docker compose up` brings up what runs in production; the
  Bicep never allowed that.
- **`pgvector` becomes available.** Azure's allow-list is gone, unlocking the V021 refactor for
  whoever wants to do it (not now — see §Excluded scope).
- **Zero public surface, for the whole stack.** No inbound port, no raw origin
  FQDN. ADR 0025 wanted that only for the admin.
- **The control plane's health dashboard starts working** (it never worked, for lack of
  provisioning).
- **The end of the dependency on a student subscription that can be deactivated without warning** — which is,
  literally, the incident that generated this ADR.

## Alternatives Considered

1. **Reactivating / migrating to Pay-As-You-Go on Azure.** A real cost of ~R$110-180/month on a stack with no
   revenue, for an educational project that already has idle hardware available. Rejected: paying
   a monthly fee for infrastructure that `beta` hosts for free is not justifiable, and it keeps
   the dependency on a provider whose shutdown has already proven to be silent. With no data to preserve, there
   is not even the argument of reactivating temporarily to extract a dump.
2. **Another PaaS (Fly.io / Render / Railway).** It would preserve the readiness gate and managed backup, and it is
   honestly the best alternative if Proxmox turns out to be expensive in human attention. Rejected
   now for the cost in money and for not solving the lesson (remaining hostage to a platform).
   **Declared reevaluation trigger:** two unavailability incidents caused by the host in
   one quarter, or the first paying tenant.
3. **k3s on Proxmox.** It would solve the rolling update with a real readiness probe — the most
   expensive item we are losing. Rejected: for ~12 containers on **one** host, operating k3s (etcd,
   ingress controller, CNI, storage class, upgrade cycle) costs more than the single gate that
   Caddy already mitigates in good part. **Upgrade trigger:** more than one host, or a real
   zero-downtime requirement.
4. **Docker Swarm.** It has `update_config` with native rolling update, which would solve the 502 window.
   Rejected: practically no upstream maintenance — it would mean putting production on an orchestrator
   at end of life to gain one feature.
5. **PUSH deployment with a self-hosted runner.** Rejected **for security, not for taste**: the repo is
   public (ADR 0017) and `deploy-infra.yml:60-64` fires on `pull_request`. A persistent runner on the
   home network would execute arbitrary fork code with access to the Docker socket and to the LAN. There is
   no runner configuration that makes that acceptable in a public repo.
6. **Migrating only the compute and keeping App Insights pointing at Azure.** Rejected: the connection
   string dies with the subscription, and App Insights is precisely one of the components whose
   shutdown we are fleeing.
7. **Keeping the App Insights `-javaagent` and just swapping the OTLP env var.** It is not an alternative — it is a bug.
   The agent ignores `OTEL_EXPORTER_OTLP_ENDPOINT`. Recorded here because it was the first hypothesis and
   would have failed silently.

## Application Plan

Detailed in [`proxmox-deploy.md`](../operations/proxmox-deploy.md) and
[`azure-decommission.md`](../operations/azure-decommission.md). Since there is no data to preserve, the
order is simple and has no irreversible step until step 5:

1. Republish the `api` images (javaagent swap + `PrometheusHealthSource`) and the rest.
2. Provision the VM, run `bootstrap-host.sh`, encrypt the secrets.
3. Bring the stack up with an **empty** database — Flyway creates the schema from scratch, and the RLS roles come from
   `postgres/init/01-roles-and-db.sql`. Repopulating with demonstration data is a content step, not a
   migration one.
4. Verify with real traffic on a test hostname, and run `restore-drill.sh` once.
5. Point the DNS and, afterwards, `az group delete`.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-07 | sys0xFF + NORA Architect | Creation and acceptance. Migration motivated by the unavailability of the Azure environment (522 since ~July/2026, Azure for Students subscription probably deactivated) and by the choice not to pay for hosting for an educational project that has its own hardware. Supersedes 0009, partially supersedes 0016, changes 0022/0023/0026/0028/0029, extends 0025. |
| 2026-08-07 | sys0xFF | Premise correction, same date. The original version treated data rescue as the critical path and kept ADR 0016's RPO/RTO targets and SLO. The PO clarified that NORA is educational, with no production data and no user base, and that it will not operate commercially. Rescue removed from the plan; RPO/RTO replaced by proportional targets; the 99.0% SLO withdrawn instead of kept without backing. |
