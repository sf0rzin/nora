# 0046 — Finish the declared scope, and empty the limbo ADR 0038 left

- Status: accepted
- Date: 2026-08-17
- Related: ADR 0038 (the realignment this completes, and whose §5 this extends), ADR 0014
  (superseded by 0038; the source of the deferrals being closed here), ADR 0030 (Flows engine —
  US75 lands on it), ADR 0007 (AWS-style IAM — US41 and US44 land on it), ADR 0029 (per-meeting
  erasure, which US80 would extend)

## Context

The 2026-08 realignment (ADR 0038) sorted the backlog into three piles: delivered, reactivated (§5,
four stories, all four now shipped) and deliberately deferred (§6, the operations block, one written
trigger). The trail that executed it closed on 2026-08-17 at 38 of 38 tasks.

Auditing what remained turned up a fourth pile nobody had named. **Five stories are neither killed
by §4 nor reactivated by §5 nor deferred by §6.** They sit in the backlog as MISSING with a note
that reads, in each case, some variant of *"still deferred; ADR 0038 neither kills nor reactivates
it"*:

| Story | Priority |
|---|---|
| US33 — Tenant usage metrics | S |
| US34 — Export of a consolidated report for the period | S |
| US41 — Policy templates | S |
| US44 — Permission boundaries | C |
| US08 — Audio/video upload | W |

Add the two PARTIAL stories, which are a related shape — declared, half-built, with no record of
what closes them:

| Story | What is missing |
|---|---|
| US13 — Identify mentioned participants | The `Participant` model and its persistence exist; deduplication and matching across meetings do not |
| US42 — Visual policy editor | A Monaco JSON editor with schema validation exists; the form-based version does not |

**This is the exact failure mode ADR 0038 was written to end.** ADR 0014 produced fourteen deferrals
with per-item commercial gates that nobody re-read for three months; ADR 0038 replaced them with one
trigger and a written destination. But it sorted only the stories it was looking at. Seven others
kept a status of "deferred" that no accepted decision was holding up — deferred by inertia, which is
indistinguishable from forgotten and is worse, because it reads as decided.

## Decision

**The declared product scope is completed. These seven stories are reactivated and built, and the
limbo is emptied — every story in the backlog now has a status that some accepted decision is
responsible for.**

### 1. What comes back into scope

| Story | Why it comes back now |
|---|---|
| **US33 — Tenant usage metrics** | The data already exists and is already aggregated: `UsageRecorder` writes every external AI call with tenant, service, provider and model, and `GET /admin/platform/telemetry/business` already reads it. What is missing is not a pipeline, it is the tenant-facing half of one that is built — the operator can see a tenant's consumption and the tenant cannot see their own |
| **US34 — Export of a consolidated report for the period** | Its stated dependency was US33, and US33 lands in the same wave. US59/US60 already export one meeting; the shape is set |
| **US41 — Policy templates** | ADR 0038 §3 makes IAM the Enterprise tier's main artefact, and US43 (the simulator) shipped under §5 for exactly that reason. A simulator that explains a decision, paired with an editor that still requires writing JSON by hand, is half an argument |
| **US42 — Visual policy editor (form-based)** | Same reason, same pair. The backlog already records that "the form-based version pairs with US43 — usability increases together" |
| **US44 — Permission boundaries** | The weakest of the seven on its own merits, and it is included with that said. Its note has been "needs an organizational hierarchy and IAM delegation that nothing else asks for", which is true. What changed is that a boundary is the one IAM concept a reviewer looks for and does not find |
| **US13 — Identify mentioned participants** | Half-built and visibly so: the same person named two ways is two participants, in a product whose central claim is understanding a conversation |
| **US75 — Flows on a schedule (cron)** | `TriggerType.SCHEDULE_CRON` is declared, the parser refuses it, and the enum value survives only so old rows stay readable. It is the one place where the Flows engine's own catalogue advertises something the engine cannot do |

### 2. What does **not** come back, and why the line is here

**The ADR 0038 §6 operations block stays deferred, in full, on its single unchanged trigger:** NORA
acquires a user who is not the maintainer.

That includes **US80 — tenant-wide erasure and LGPD portability export**, which §6h defers by name.
The reason §6h gives is not effort, it is standing: both are duties owed to the data subjects of a
live service, and there is no subject to owe them to. Building them now would produce an untested
deletion path across every table in the schema, whose first real exercise would be on someone's real
data. **That is a worse outcome than the honest gap**, and the gap is already written down — in the
backlog, in ADR 0038 §6h, and in issue #456 where the landing page's export promise is recorded as
unbacked.

**US08 — audio/video upload** also stays out, and this decision names the reason rather than
inheriting ADR 0014's unverifiable one (">30% of uploads are audio in a pilot", a criterion with no
pilot to measure). The real reason is that transcription is now a *streaming realtime* session
(ADR 0039/0045): a file upload needs the provider's **batch** transcription API, which is a second
provider surface, a second credential path, a second cost model and a second failure mode. It is not
a small addition to a shipped capability; it is a new one wearing its clothes. **It moves to WONT**
rather than staying MISSING, so the backlog stops implying it is coming.

### 3. Every remaining story has an owner

After this ADR the backlog contains no story whose status rests on inertia. Each is one of:
delivered; reactivated by ADR 0038 §5 or by this ADR and built; deferred by ADR 0038 §6 under its
single trigger; or WONT by ADR 0038 §4, ADR 0041 or this ADR.

**That property is the point of this decision, more than any individual story in §1.** A backlog
where one row's status is unexplained is a backlog whose other rows cannot be trusted either.

## Consequences

**Positive**

- The backlog becomes fully accountable: every status traces to an accepted decision.
- IAM gains the two halves that make it demonstrable rather than merely present — a simulator that
  explains, and an editor that does not require hand-written JSON.
- The Flows catalogue stops advertising a trigger the engine refuses.
- US08 stops being a promise. A `W`/MISSING row is read as "coming later"; `WONT` with a reason is
  read correctly.

**Negative / debts**

- This adds build scope to a solo maintainer, immediately after a realignment whose whole argument
  was subtraction. The difference from ADR 0014's optimism is that these are **small, already-scoped
  stories against substrate that exists**, not commercial bets — but it is the same shape of
  decision and deserves to be named as such.
- US44 is included on a thinner argument than the other six, stated above rather than dressed up.
- Closing the limbo does not close the **landing page**, which is a separate and larger honesty gap
  (issue #456, frozen by an explicit maintainer decision). Nothing here makes those claims true, and
  two of them — LGPD export, and MCP as the outbound mechanism — remain false *by decision* after
  this ADR rather than by omission.

## Alternatives Considered

1. **Move all seven to WONT.** The move most consistent with ADR 0038's spirit of subtraction, and
   the cheapest. Rejected because five of the seven are small stories against substrate that already
   exists — `UsageRecorder` for US33, the Flows engine for US75, `PolicyEvaluator` for US41/US42 —
   and deleting a promise costs credibility that delivering it does not.
2. **Leave them as they are and simply document the limbo.** Honest, and it was the state until this
   ADR. Rejected: "deferred because nobody decided" is not a status, and writing that down without
   resolving it converts an oversight into a policy.
3. **Give each a reactivation criterion, as ADR 0014 did.** Rejected explicitly, and it is the one
   alternative this project has already run: fourteen per-item criteria produced a list nobody
   re-read. ADR 0038 replaced them with a single trigger for a reason, and reintroducing per-item
   gates here would undo that within a day of it being proven.
4. **Include US80 and US08 to reach a clean 86-for-86.** Rejected. A backlog with no MISSING rows is
   a target, not a virtue, and both would be built for the number rather than for a user — US80
   against no data subject, US08 against a provider surface that does not exist in this codebase.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-17 | sys0xFF | Created and accepted. Names the limbo ADR 0038 left — seven stories neither killed, reactivated nor deferred by any accepted decision — and empties it: US33, US34, US41, US42, US44, US13 and US75 are reactivated and built; US08 moves to WONT with a reason that is verifiable, unlike the commercial criterion it inherited; the ADR 0038 §6 operations block including US80 stays deferred on its unchanged single trigger |
