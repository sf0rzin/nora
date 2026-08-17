# 0038 — Post-pitch scope realignment

- Status: accepted
- Date: 2026-08-16
- Supersedes: ADR 0014 (the deferral block and its reactivation criteria)
- Related: ADR 0006/0015 (Customer Confidence, the Enterprise surface that survives),
  ADR 0028 (RLS enforce), ADR 0029 (operational LGPD), ADR 0036 (backup posture),
  ADR 0039 (STT), ADR 0041 (MCP — the one item this realignment adds)

## Context

ADR 0014 declared the backlog's v1 closed on 2026-05-14 and deferred a block of user stories, each
with a written reactivation criterion. The criteria were commercial: "Plan A demo closed OR 100
Plan B tenants", "the first paying Enterprise tenant explicitly requires it", "cost model adds up
OR the FIAP pitch justifies it". The gate common to nearly all of them was the FIAP × TOTVS pitch.

The pitch was held on **2026-06-15**. `AGENTS.md:86` records what survived: 14 stories deferred,
US15 subsequently delivered in PR #206, leaving 13 (US48/US49 handled separately by ADR 0015).

The gate expired two months ago and nobody wrote what happens after it. In the meantime the
repository shipped, with no user story anywhere in the backlog:

- chat with persistent sessions (`V022__create_chat_sessions.sql`, `ChatSessionController.java`);
- NORA Flows — post-commit event bus, workflow engine and canvas (`V023__create_workflows.sql`,
  ADR 0030, ADR 0032);
- OAuth integrations across nine providers (`V024`–`V026`, `IntegrationsController.java`, ADR 0031);
- the control plane and the operator console (ADR 0022, 0023, 0024, 0025);
- operational LGPD (ADR 0029);
- the exit from Azure and the move to a single bare-metal host (ADR 0034, ADR 0036).

An audit on 2026-08-16 surveyed seven fronts of the repository and left 86 surviving findings. Its
thesis is the reason this ADR exists: **the repository delivers more than the documents say, and
less than the marketing sells.** Both halves are documentation failures, and the second half is the
dangerous one.

Three facts frame every decision below, and none of them was true when ADR 0014 was written:

1. The pitch happened; more FIAP deliverables are ahead, and they are evaluated by a jury.
2. The maintainer is solo. Any plan that assumes work can be divided between people is wrong.
   Scope that does not fit one person is scope to cut, not to distribute.
3. There are no users. Not few — none. Nobody is paged, nobody loses data, nobody is owed a
   subject-access request.

## Decision

### 1. The destination is declared: FIAP deliverable plus portfolio

NORA is not operating commercially and is not acquiring users. Every deferral in §6 inherits its
reason from this line, and none of them is reasonable without it.

This is a statement of destination, not of quality. The repository keeps being built to a
commercial SaaS standard, because that standard is the portfolio argument. What changes is who the
work is *for*: a jury and a technical reader, not a tenant.

### 2. The desktop is Windows-only

macOS and Linux leave the supported scope. The macOS capture path (a BlackHole virtual driver plus
an unfinished ScreenCaptureKit detection) and the Linux one (PulseAudio `parecord`) were built
against platforms nobody had ever run, and three Tauri builds per merge were being paid for to keep
them. `system_audio.rs` now carries a `compile_error!` on any other target.

ADR 0008 and ADR 0035 describe a three-platform client. They stay as they are — accepted ADRs are
immutable, and this ADR is where the reduction is recorded instead.

### 3. Enterprise is exactly what exists

The Enterprise tier becomes: AWS-style IAM (ADR 0007), Customer Confidence per meeting (ADR 0006,
minimally persisted by ADR 0015) and multi-tenancy (ADR 0002 filter, ADR 0019 composite FK,
ADR 0028 RLS). Nothing else.

The tier stops being a container for things that were promised and never built. What it promises
now, it has.

### 4. Formal death, not deferral

These stop being "deferred, reactivatable under a criterion" and become **WONT**. The distinction
matters: ADR 0014 chose "formal defer" over W precisely because defer means reactivatable. These
four are not waiting for anything.

| Item | Why it dies |
|---|---|
| **US05 — Corporate SSO (Entra ID / SAML)** | Its ADR 0014 criterion was "the first paying Enterprise tenant explicitly requires it". §1 says there will be no paying tenant. SSO is also the single largest build on the deferred list, and it buys a login path for an IAM that already has one |
| **US50 — Aggregated Account Health Score** | It aggregates over time across accounts, and there is no data to aggregate: no tenants, no history. `account_health_snapshots` was foreseen in the data model and never migrated; it stops being debt and becomes closed scope |
| **US51 — Alert when Account Health changes band** | It alerts on the band computed by US50. It cannot outlive it |
| **Enterprise DPA and SLA** | Both are contractual instruments between a controller and a customer. There is no customer, so signing either would be theatre. Their absence stops being a gap |

Customer Confidence **per meeting** (US48/US49, ADR 0015) is unaffected. It is the signal that is
built, and it stays in Enterprise per §3. What dies is the aggregate on top of it.

### 5. Reactivation

Four stories that ADR 0014 deferred come back into scope:

| Story | Why it comes back |
|---|---|
| **US21 — Trends panel** | Its ADR 0014 criterion was "after US15 is turned on". US15 (semantic search with embeddings) *was* delivered — PR #206, migration `V021`. The stated criterion is met; nobody noticed |
| **US25 — Export tasks as CSV/MD** | Small, self-contained, and it is the kind of thing a jury asks for by reflex. Its criterion ("pilot feedback from >2 tenants") is unreachable by §1 and is replaced by "it is cheap and it demonstrates" |
| **US31 — Version history of the company context** | The company context is the product's central claim — analysis calibrated to the customer's own business. A field that can be silently overwritten with no history undermines the claim it supports |
| **US43 — Policy simulator** | IAM is the Enterprise tier's main artefact (§3), and debugging a policy today means reading `PolicyEvaluator` by hand. A simulator is how the IAM model becomes demonstrable instead of merely present |

**Their status stays MISSING.** Nothing has been built. What changes is the note beside them: they
are open scope, not deferred scope.

### 6. The operations block becomes a declared deferral

This is the direct consequence of §1, and it is the part most likely to be read as negligence, so
it is written item by item with what exists, what is deferred and why. A declared deferral is
recorded with a reason and is never hidden; that is the whole difference between this and debt.

**a) Monitoring alerts.** What exists: Prometheus v3.1.0, Loki 3.3.2, Grafana 11.5.1 and a
provisioned dashboard (`infra/host/observability/grafana/provisioning/dashboards/nora-overview.json`).
What does not exist: a single alert rule or contact point — `provisioning/` contains only
`dashboards/` and `datasources/`, and `infra/host/observability/prometheus.yml:34-42` records that
there is no Alertmanager and no `rule_files`. **Deferred because there is nobody on call.** An
alert with no recipient is a configuration file. What is deferred is paging, not observability:
the dashboards are how the stack is actually inspected, and they work.

**b) Off-host backup.** What exists: an hourly logical dump (`infra/host/backup/run-backup.sh`)
writing to `/srv/nora/backups` with a `.sha256` and a `.toc` beside each file. What does not
exist: any copy off the host. This ADR does **not** re-decide it — ADR 0036 §3 already withdrew
the second leg and stated the position plainly ("the database has an hourly on-host dump and
nothing off-host"). What this ADR records is that the reason still holds under §1: the content is
reproducible demonstration material, and everything that is not the database lives in the
repository.

**c) Restore drill cadence.** What exists: a real drill — `infra/host/scripts/restore-drill.sh`
restores the most recent dump into a disposable `--network none` container, validates counts,
Flyway version, per-tenant reads and `nora_app` grants, and prints an RTO floor.
`docs/operations/host-deploy.md` prescribes it quarterly. What does not exist: any drill having
been run. The results table in that runbook still reads "(pending — first drill within 30 days
after go-live)". **So the deferral is the cadence, not the capability, and the honest consequence
is that the RTO floor has never been measured.** Writing "there is no restore drill" would be
false; writing "the RTO is 2h" would be worse.

**d) Secret rotation.** What exists: secrets in SOPS + age, with the private key on the host only,
and `secrets.env.example` listing every key with its shape. What does not exist: a rotation
schedule, a rotation runbook or a rotation workflow. The plan in
`docs/operations/production-readiness-gaps.md` §Gap 7 is Azure-era and names Key Vault secrets that
no longer exist. **Deferred because the credential set is a handful of generated passwords and
re-issuable third-party API keys, on a host with exactly one operator** — rotation's value is
bounded by the number of people who could have leaked one.

**e) The roll-forward consumer.** What exists: `deploy-host.yml` publishes an immutable release
pointer (`release/prod/<sha>`), and `deploy.sh --tag sha-<short>` rolls the host forward when the
operator runs it. What does not exist: anything on the host that reads the pointer.
`bootstrap-host.sh` installs a timer whose `ExecStart=` runs `deploy.sh --if-changed` with no
`--tag`, so it re-probes the tag already running — whose digest never changes — and never discovers
a newer release. **Deferred because one operator with SSH is an acceptable substitute for an
automatic consumer.** It is recorded here rather than left implicit because a deployment path that
silently never rolls forward is the kind of thing a reader assumes works.

**f) Code signing for the desktop installer.** What exists: update artifacts are signed — the
Tauri updater key (`TAURI_SIGNING_PRIVATE_KEY`, public half in `tauri.conf.json`
`plugins.updater.pubkey`) authenticates updates delivered to an already-installed app. What does
not exist: an Authenticode signature on the installer itself — `bundle` in
`apps/desktop/src-tauri/tauri.conf.json` carries no `windows.certificateThumbprint` and no
`signCommand`, so a first install shows the SmartScreen warning. **Deferred because an OV/EV
certificate is a recurring cost with an identity-verification process, for an installer whose
audience is the maintainer and a jury.** The distinction between the two signatures is written out
because "the desktop is signed" is true of one and false of the other.

**g) RLS enforce as the repository default.** RLS is **enforced on the deployed stack** since
2026-08-10: the API connects as `nora_app` (NOBYPASSRLS), the operator aggregate reads through
`nora_telemetry` (BYPASSRLS), and `RlsEnforceTelemetryGuard` refuses to boot on a half-applied
cutover (ADR 0028; `AGENTS.md:42`). It is **off by default in the repository**, where the ADR 0002
application filter is the only control. **What is deferred is flipping the repository default, and
nothing else.** Writing "RLS is not enforced" would be false. The default is deferred because
flipping it makes every local checkout require the three-role provisioning before the application
boots, and the person paying that cost is the same person the deferral protects.

**h) Tenant-wide erasure and data export.** What exists: per-meeting erasure
(`DELETE /privacy/meetings/{id}`, ADR 0029) with a physical hard delete and FK cascade to
`transcripts`, plus an opt-in global retention sweeper. What does not exist: deletion of an entire
tenant, and any LGPD portability export — `PrivacyController` carries exactly one mapping.
ADR 0029 §Negative already records data-subject erasure as the next increment and names the open
semantic question. **Deferred because both are duties owed to the data subjects of a live service,
and there is no subject to owe them to.** Reactivation trigger for this whole block, and it is a
single one: **NORA acquires a user who is not the maintainer.**

Unlike ADR 0014, the deferrals above carry no per-item commercial gate. Per-item gates are what
produced a list of criteria nobody re-read for three months.

## Consequences

**Positive**

- The 19 decisions of the realignment acquire a durable record inside the repository. Until now
  they existed only in the maintainer's working notes, which means an agent reading `docs/adr/`
  would have implemented ADR 0014's plan and been right to.
- The distinction between "decided against" (§4) and "not now, and here is why" (§6) becomes
  visible. ADR 0014 collapsed both into "deferred".
- The operations block stops being a checklist that has been blocking for three months and cannot
  be worked. A gap with a written reason is a position; a gap without one is a smell.
- Four stories whose stated criteria were already met (§5) stop being invisible.

**Negative / debts**

- The documents that cite ADR 0014's deferral table now cite a superseded ADR. `backlog.md`,
  `roadmap.md`, `vision.md`, `AGENTS.md`, `docs/engineering/architecture.md` and
  `docs/challenge/` all need to be reconciled against this one; until they are, they describe a
  product from two quarters ago.
- `docs/operations/production-readiness-gaps.md` still describes Gaps 3, 4, 6 and 7 against Azure
  primitives (Key Vault, Application Insights, Flexible Server PITR) that no longer exist. This ADR
  states the current position; that document has not caught up.
- The landing page carries claims that this realignment does not fix. DEC-04 froze the landing
  pending a separate decision, and the specific claims are catalogued in issue #456. Not touching
  it is a decision, not an oversight.
- §1 is a ceiling as well as a licence. Anything argued from "when we have customers" is, by this
  ADR, arguing from a future that is not planned.

## Alternatives Considered

1. **Extend ADR 0014's gate to the next FIAP deliverable.** The smallest edit: keep the deferral
   block, move the date. Rejected because it is the same mistake with a later expiry — the last
   gate passed and produced nothing written, and there is no reason to expect the next one to
   behave differently. A gate on a date is a promise to think later.
2. **Delete the dead stories from the backlog.** Rejected for exactly the reason ADR 0014 rejected
   it (its Alternative 2): it loses traceability, and the history of a decision is worth keeping.
   US05 marked WONT with a reason is information; US05 absent is a gap the next reader re-derives.
3. **Keep the operations block as blocking debt and simply not work it.** Rejected: a list that
   has blocked for three months, that one person cannot work, and that nothing depends on, is not a
   plan. It also quietly corrupts every honest statement around it — a reader who sees seven open
   P0 gaps concludes the project is mid-hardening, which is not what is happening.
4. **Mark the whole operations block W and be done.** Rejected: it erases the difference between
   an item that is closed (§4) and one that is a flag flip away (§6g). Both are "not now"; only one
   of them is "not ever".
5. **Build the operations block anyway, as a portfolio argument.** Considered seriously — alerting
   and rotation are demonstrable engineering. Rejected because §2 (solo) makes it a direct trade
   against the FIAP artefacts a jury actually reads, and the artefacts win. The reasoning above is
   itself the portfolio argument: knowing what to defer, and writing down why, is the skill.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-16 | sys0xFF | Created and accepted. Supersedes ADR 0014, whose gate — the 2026-06-15 FIAP pitch — expired unrecorded. Declares the project's destination, closes US05/US50/US51 and the Enterprise DPA/SLA, reactivates US21/US25/US31/US43, and converts the operations block into a declared deferral with a per-item reason and a single reactivation trigger |
