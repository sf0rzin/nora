# ADR 0051 — The substrate is an Azure VM, and Azure was never gone

- Status: accepted
- Date: 2026-08-24
- Supersedes: ADR 0036 (which recorded a bare-metal host), and corrects the "Azure is gone"
  paragraph ADR 0036 inherited from ADR 0034
- Related: ADR 0034 (the migration off Azure Container Apps, whose *shape* stands), ADR 0037
  (SSH over the tunnel, unaffected), ADR 0025 (Cloudflare Tunnel as the only ingress, unaffected)

## Context

ADR 0036 states, as its title, that the substrate is *a single bare-metal Ubuntu host, not a
Proxmox VM*. It offers a test for that claim in its own evidence table:

> | A VM on a Proxmox hypervisor | Bare metal. `systemd-detect-virt` returns `none` |

On 2026-08-24 that command was run on the machine actually serving `nora.systems`:

```
systemd-detect-virt: microsoft
sys_vendor:          Microsoft Corporation
product_name:        Virtual Machine
```

The machine is `vm-nora-dev`, a `Standard_B4s_v2` running Ubuntu 24.04 in the resource group
`rg-nora-dev-cc` (`canadacentral`), created 2026-08-17T13:23Z, tagged `project=nora`. Fourteen
containers — the whole `infra/host/` compose stack — were up and healthy, the Cloudflare tunnel
was connected, `https://nora.systems` answered 200 and `https://adm.nora.systems` answered 302.

So two statements this repository makes in the present tense are false, and have been since
2026-08-17:

1. **"The substrate is a single bare-metal host, no hypervisor"** (ADR 0036). It is an Azure VM.
2. **"Azure is gone — there is no subscription, no export and nothing to decommission"**
   (`README.md` §Current state, inherited from ADR 0034 §Consequences). There is a subscription,
   there is a resource group carrying the project's own tag, and it is running the product.

**How both survived a repository-wide audit.** The 2026-08-23 audit checked every claim against
the code and no claim against the infrastructure, on a machine that had the Azure CLI installed
and authenticated the entire time. It reproduced the repository's own sentence instead of running
one command. That is worth writing down in an ADR rather than a commit message, because the
failure is not the wrong sentence — it is that *the verification method had a hole shaped exactly
like the thing being verified*. A documentation audit that only reads the tree can only ever
confirm the tree.

**What ADR 0034 got right and keeps.** The move off Azure *Container Apps* — off the managed
ingress, the Key Vault, App Insights, the Bicep IaC and the nine-module deployment — happened and
holds. Nothing here brings any of that back. What ADR 0034 overreached on was the conclusion:
leaving a set of managed services is not the same as leaving the cloud, and the sentence "there is
no subscription" was true of the *old* resource group and was written as though it were true of
Azure.

## Decision

**Record the substrate as it is: a single Azure Linux VM running Docker Compose, reached only
through the Cloudflare tunnel. Azure is the hosting provider, and this is now a deliberate choice
rather than an unrecorded fact.**

Concretely:

1. ADR 0036 is **superseded**, not edited — accepted ADRs are immutable here. Its topology,
   networking and Compose reasoning all stand; only the nature of the machine changes.
2. `README.md`, `docs/engineering/architecture.md` and the operations runbooks stop asserting
   bare metal and stop asserting the absence of a subscription.
3. The single-host properties ADR 0036 depends on remain true and keep their consequences: one
   machine, no orchestrator, no second copy, Compose as the unit of deployment, and the tunnel as
   the only ingress. A VM is still one machine.

### What being a VM changes, and what it does not

| | Bare metal (as recorded) | Azure VM (as it is) |
|---|---|---|
| Failure domain | one machine | one machine — **unchanged** |
| Ingress | tunnel only | tunnel only — **unchanged** |
| Restore story | rebuild the host by hand | the OS disk is a snapshot-able Azure resource — **better**, and unused |
| Cost of being up | electricity | **metered per hour**, which is the whole of §Consequences below |
| Lifecycle | stays on | **a schedule can turn it off**, and one does |

## Consequences

**Positive**

- The two false present-tense statements stop being made.
- The restore story improves in principle: an Azure disk snapshot is a real off-host copy, which
  ADR 0036 §44 records the bare-metal reading as not having. It is not configured yet, and this
  ADR does not pretend otherwise.

**Negative / debts, and the first is serious**

- **The host used to shut itself down every night with nothing to start it again — decided and
  fixed the same day.** A DevTestLab auto-shutdown schedule was enabled at 04:00 UTC. It fired on
  2026-08-18 and the site stayed down for six days, until the VM was started by hand on
  2026-08-24 to write this ADR. A public deployment that turns itself off nightly and waits for a
  human is not hosted, it is demonstrated. Because fixing it costs money, the choice was put to
  the maintainer with the prices attached — `Standard_B4s_v2` at USD 0.186/hour is USD 136/month
  continuously, against an Azure for Students credit of USD 100 — and the maintainer picked the
  option that fits inside the credit: **the schedule was deleted and the VM resized to
  `Standard_B2as_v2`** (2 vCPU / 8 GiB, USD 0.07524/hour, ~USD 55/month). The stack idles at
  ~2.1 GiB, so the smaller memory holds it with room. Verified after the resize: all fourteen
  containers healthy, the tunnel connected, `nora.systems` answering 200. The resize itself cost
  about three minutes of downtime, because changing size requires deallocating; the stack came
  back on Docker's `restart: unless-stopped` alone — which is the next bullet's point.
- **The running stack does not match the deployment doctrine the repository documents.** The four
- **The running stack did not match the deployment doctrine, and now does.** For most of
  2026-08-24 the four containers ran `ghcr.io/sf0rzin/nora-*:latest` instead of the promoted
  `sha-<short>`, none of the `nora-*` timers was installed, and there was no age key or
  `secrets.env.sops` — the stack ran from a plaintext `.env` with 40 keys in it. Closed the same
  day by running `bootstrap-host.sh` on the VM, encrypting the secrets against two age
  recipients, and starting the three timers. What the first run of the pull agent exposed in
  exchange is recorded as Gap 9.3: a commit touching only `infra/**` never moves the release
  pointer, and `--follow-release` will therefore roll such a commit back.

## Alternatives Considered

1. **Amend ADR 0036 in place.** Rejected — the ADR index declares accepted ADRs immutable, and
   the point of that rule is precisely the case where a decision turned out to rest on a fact that
   was not checked. Editing it would erase the evidence that it was believed.
2. **Move the deployment to bare metal so the documentation becomes true.** Rejected, and the
   maintainer decided the direction: Azure hosts the project. It is also the wrong way round —
   documentation is cheaper to correct than substrate.
3. **Record it as "a Linux host" and stop naming the provider.** Rejected. It is vague in exactly
   the place the last two ADRs were wrong, and it would hide the one property that actually
   changed: the machine is metered and a schedule can switch it off.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-24 | sys0xFF | Created and accepted. Supersedes ADR 0036 after `systemd-detect-virt` returned `microsoft` on the machine serving `nora.systems`, with the whole compose stack healthy and the site answering 200. Records that Azure was never gone, that the nightly auto-shutdown is an open and costed decision, and that the live deployment does not follow the documented one |
| 2026-08-24 | sys0xFF | The auto-shutdown decision was made the same day: schedule deleted, VM resized `B4s_v2` → `B2as_v2` to run 24/7 inside the student credit (~USD 55/month). Stack verified healthy after the resize. The deploy-doctrine divergence (`:latest` tags, no timers, no git checkout) remains the open item |
| 2026-08-24 | sys0xFF | The host was bootstrapped: sops/age installed, the host age key generated, the plaintext `.env` encrypted to `secrets.env.sops` against two recipients (host + operator offline, the second one the project never had), and the three timers started. `deploy.sh --if-changed --follow-release` verified by hand — pointer `sha-e8f0460`, 42 variables decrypted, every service healthy. The live deployment and the documented one are the same thing from this date |
