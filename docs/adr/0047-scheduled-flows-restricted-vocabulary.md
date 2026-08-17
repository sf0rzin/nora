# 0047 — Scheduled Flows: a restricted vocabulary, a claimed run, and a window that outlives a crash

- Status: accepted
- Date: 2026-08-17
- Related: ADR 0030 (the Flows engine this extends — its §5 foresaw `schedule.cron` "reusing the
  @Scheduled pattern"; ADR 0030 is accepted and unedited, this is the successor record), ADR 0036
  (the substrate is one bare-metal host with one API container), ADR 0028 (RLS enforce, which is
  why a timer thread needs `TenantRlsContext`), ADR 0029 (the multi-tenant job pattern
  `RetentionSweeper` established), ADR 0046 §1 (which reactivated US75)

## Context

`TriggerType.SCHEDULE_CRON` has existed since ADR 0030 with `hasDispatcher() == false`. Nothing in
the backend ever scheduled a workflow, so a flow saved with it sat `ACTIVE` and never ran — a
silent lie told by the product's own catalogue. PR #468 stopped the lie by making
`WorkflowDefinitionParser` **refuse** the trigger on save; the enum value survived only so rows
persisted before that rule keep deserialising. ADR 0046 §1 names this as the one place where the
Flows catalogue advertises something the engine cannot do, and reactivates US75 to close it.

Making `hasDispatcher()` return `true` is one line. Everything that makes that line honest is a
decision, and none of the five below belongs implicit in code: a schedule with an unstated timezone
fires at a time nobody predicted, and catch-up and overlap semantics that are never written down
are not absent — they are undocumented.

## Decision

**`schedule.cron` gains a real dispatcher.** A `@Scheduled` tick in the API claims each due run in
the database before executing it, and the run executes the flow **once per meeting analysed since
the previous run's window opened**.

### 1. Where the scheduler lives, and what happens if a second replica ever runs

Spring `@Scheduled` inside the API container, in `application/workflow/ScheduledFlowRunner`, beside
the two timers that already exist (`RetentionSweeper`, `StuckAnalysisSweeper`). No broker, no
separate worker: ADR 0036 says the substrate is a single bare-metal host running one API container,
and ADR 0030 already rejected a broker for this engine.

**"We only run one replica" is a deployment fact, not a property of the code**, so the code does not
rely on it. `workflow_schedules` (V032) carries the run state, and a run is taken by a
**compare-and-swap**: the claiming `UPDATE` matches on the `next_fire_at` the tick read a moment
earlier, so of two processes reading the same due row only one can win — the loser's `UPDATE` finds
no row and it moves on. That is the same shape as `MeetingRepository.claimForReanalysis` and
`failStuckProcessing`: the condition is evaluated by the database against the committed row.

`claim_owner` records which process holds a claim (a UUID minted at boot). It is diagnostic — the
correctness comes from the compare-and-swap — but on a day when two containers are accidentally up,
it is the difference between reading the answer and guessing it.

### 2. What "cron" means: a restricted vocabulary, compiled to cron

**The trigger does not accept a cron expression.** It accepts three shapes, in the trigger node's
`params`:

| `frequency` | Other params | Meaning |
|---|---|---|
| `hourly` | `minute` (0–59) | every hour at that minute |
| `daily` | `hour` (0–23), `minute` | every day at that time |
| `weekly` | `weekday` (`MON`…`SUN`), `hour`, `minute` | that weekday, at that time |

A full parser accepts `* * * * * *`, which fires every second, and the honest options at that point
are to run it (which this substrate cannot) or to accept it and quietly not run it (the defect
US75 exists to close). A closed vocabulary makes the fastest expressible schedule **hourly**, by
construction rather than by a rejection rule that has to be remembered.

Rejection is total and happens at save, as a 422 `WORKFLOW_INVALID_DEFINITION`: an unknown
`frequency`, an out-of-range `hour` or `minute`, a `weekly` with no `weekday`, and — stated
explicitly because it is the mistake a technical user will make — a `cron` or `expression` param,
which is refused with a message naming the four keys that do exist rather than being ignored.

The wire value stays `schedule.cron` because rows and the enum already carry it, and the name stays
accurate: the vocabulary **compiles** to a canonical six-field Spring cron expression
(`0 M * * * *`, `0 M H * * *`, `0 M H * * DOW`) which is stored in `workflow_schedules.cron` and
evaluated by `org.springframework.scheduling.support.CronExpression`. The arithmetic that decides
"when is the next occurrence" is the framework's, not ours. The stored expression is also what a
future decision would widen: opening the vocabulary later means accepting more expressions into a
column that already holds one, not changing the storage.

### 3. Timezone: America/Sao_Paulo, the same constant, for the same reason

Occurrences are computed in `America/Sao_Paulo`, matching `TrendsService.REPORTING_ZONE`
(architecture §19) and the two calendar actions. A schedule differs from a report only in that
getting it wrong is louder: "daily at 09:00" evaluated in UTC arrives at 06:00 local, and nobody
reads that as a bug in a timezone, they read it as a product that fires at random.

The zone is **fixed, not per tenant**, and it is written into `workflow_schedules.timezone` on every
row rather than assumed — so the day NORA has a tenant outside Brazil, the rows say what they were
computed in instead of leaving it to be inferred. The UI states the zone in the trigger's own copy;
the schedule is fully determined by the definition plus this constant, which is why no `nextFireAt`
is exposed on the API: a second source of truth for a computable value is a second thing that can be
wrong.

### 4. Catch-up: the missed **occurrences** are dropped, the missed **meetings** are not

Host down six hours across three daily-turned-hourly runs: **it fires once**, on recovery, and that
one run covers the whole six hours.

This is the decision the two obvious answers both get wrong. Firing three times sends three
notifications for one outage. Firing none silently drops six hours of analysed meetings, which for
a flow that opens Linear issues means work that never arrives. Splitting them is possible because
the run's unit of work is not the occurrence, it is the **window**:

- `next_fire_at` — when the next occurrence is due. Advanced **at claim**, to the next occurrence
  strictly after now, which is what collapses three missed occurrences into one.
- `window_from` — the start of the period the next run reads. Advanced **at release**, to the
  instant the completed run fired.

So occurrences are at-most-once and meetings are at-least-once, and a crash between claim and
release cannot loop: `next_fire_at` has already moved, so the flow does not re-fire immediately; and
`window_from` has not, so the meetings the crashed run was carrying are picked up by the next one.
The two columns diverging is itself the visible evidence that a run died mid-flight.

A very long outage is bounded by `nora.flows.schedule.max-meetings-per-run` (default 50, most recent
first, WARN when it truncates). A window is never allowed to become unbounded work.

### 5. Overlap: skip, and the lease says for how long

The claim doubles as the overlap guard: a due row whose `claimed_at` is set is not claimed again, so
the next occurrence is **skipped** rather than queued. Queueing a run whose predecessor has not
finished builds a backlog that a schedule can never drain, and the actions here write into other
people's systems.

"Skip" cannot be unconditional, because a claim left by a dead JVM would otherwise freeze the
schedule forever. `nora.flows.schedule.claim-lease-minutes` (default 30) is how long a claim is
believed. Past it the claim is presumed abandoned and can be taken. **That makes the lease a safety
property and not a tuning knob**, in exactly the way `StuckAnalysisSweeper`'s window is: set it below
the real duration of a run and a healthy long run gets a concurrent second copy. It is floored, with
a WARN, rather than obeyed.

### 6. Under whose authority a timer-fired run acts — the security question

**A scheduled run has no principal, and no IAM decision is made at fire time.** This is stated
rather than engineered around, because engineering around it would be worse.

What the run acts with is what an event-fired run already acts with: **the tenant's integration
connections.** `integration_connections` is `UNIQUE (tenant_id, provider)` (ADR 0031), the actions
resolve a connection from the tenant alone, and the async `WorkflowEventListener` has been running
that way since ADR 0030. A timer introduces no new authority and reaches no credential an
analysis-triggered flow could not already reach.

The authorization decision is made **at save time**, where a principal exists: `workflow:write` is
what lets the flow exist at all, and `workflow:test` is separately required to run one by hand
precisely because running a flow really writes into Slack, Linear and someone's calendar (§12 of
architecture.md). A schedule is a standing instruction created under that grant.

The consequence is real and is named here rather than discovered later: **revoking a user's
`workflow:write` does not stop a flow they already created.** Deactivating or deleting the flow is
the revocation path; so is disconnecting the integration, which fails the action loudly with a
reconnect message in the execution log. Two guards keep the blast radius honest — the runner only
considers workflows whose `active` is true, and only tenants returned by
`TenantRepository.allActiveTenantIds()`, so a cancelled tenant's schedules stop.

Re-evaluating the creator's policy at fire time was considered and rejected: it invents an offline
principal resolution the IAM layer does not have, and it would make a flow's behaviour depend on a
person's group membership months after they wired it — a silent, invisible stop, which is the class
of failure this whole story is about.

### 7. What the run actually does, and what it is not

**One execution per meeting analysed in the window**, most recent first, capped. The fan-out is the
same shape `action_item.created` already has, and the UI copy says so in the same place and the same
words.

The reason is that every condition (`productivity_score_below`, `customer_confidence_below`,
`tag_equals`, `priority_equals`) and every one of the fourteen actions consumes a
`WorkflowEventContext` built from **one meeting**. Fanning out reuses all of it unchanged.

**It is therefore not a digest.** "Every Friday at 17:00, e-mail me a summary of the week" is not
what this trigger does, and no aggregate placeholder (`{{period.meetingCount}}` and friends) exists.
What it does is *"every Monday at 09:00, for each meeting analysed since last Monday whose
Productivity Score was below 60, open a Linear issue"* — which is what the existing condition and
action vocabulary can actually express. A digest needs an aggregate context and aggregate
placeholders; that is a separate story, and inventing half of it here would ship a trigger whose
copy promises more than its blocks can say.

**When nothing was analysed in the window, no execution row is written.** One empty row per
occurrence would be a truer record, and it was rejected on arithmetic: an hourly schedule on a quiet
week writes 168 empty rows, `WorkflowService.EXECUTIONS_LIMIT` is 50, and the history the user opens
would contain nothing but no-ops with the real runs pushed off the end. The empty sweep is logged at
DEBUG, the way `RetentionSweeper` logs its own, and the trigger's help text in the canvas says
plainly that a period with no analysed meetings produces no execution.

### 8. A property worth naming: this is the outbox debt's recovery path

ADR 0030 accepted, explicitly, that an event is lost if the process dies between the `COMPLETED`
commit and the listener dispatch, with `POST /workflows/{id}/test` as the only manual recovery.

A scheduled run does not read an in-memory event. It reads `meeting_analyses.generated_at` from
committed state. A flow whose trigger is a schedule therefore **cannot lose a meeting to that
window** — the analysis either committed inside the period or it did not. That does not pay off the
outbox debt for the three event triggers, and this ADR does not claim it does. It does mean the
product now contains one trigger that is durable against it, which is worth knowing before choosing
between two triggers that otherwise look interchangeable.

## Consequences

**Positive**

- The Flows catalogue stops advertising a trigger the engine refuses. `hasDispatcher()` is true for
  all four values and the parser accepts all four, so the enum's own invariant becomes a fact rather
  than a comment.
- The catch-up split (occurrences at-most-once, meetings at-least-once) means an outage costs
  notification punctuality, not data.
- The claim is a database fact, so the design survives a second replica without a code change and
  without a lock service.
- One trigger in the product is durable against the outbox gap of ADR 0030 §Consequences.

**Negative / debts**

- **The vocabulary will feel narrow to a technical user.** "Every 15 minutes" and "weekdays only"
  are both reasonable and both unexpressible. Widening it is a decision about what this substrate
  can honour, not a parser change, which is why the shape is stored as a cron expression.
- **The lease is a safety property with a default that has never met a real long-running flow.**
  Thirty minutes is an assumption about how long fourteen HTTP actions take; the floor and the WARN
  are what keep a bad value visible.
- **A flow outlives its author's permissions** (§6). Named, guarded by `active` and by tenant
  status, not solved.
- **No digest, and the gap is visible in the product** — a user who wants a weekly summary will
  build a per-meeting fan-out and be mildly disappointed. Better than copy that promises a summary
  the blocks cannot produce.
- The tick runs every minute per active tenant. At this scale that is nothing; at a scale where it
  is not, the per-tenant loop that RLS forces (ADR 0028) is the thing that has to change, in all
  three timers at once.

## Alternatives Considered

1. **A full cron parser.** Familiar, and it is what the wire value's name suggests. Rejected: it
   accepts sub-minute schedules a single host cannot honour, and every guard against that is a
   rejection rule bolted onto an accepting parser — the exact shape that let `schedule.cron` be
   accepted-and-never-run in the first place. A closed vocabulary makes the guarantee structural.
2. **A per-tenant timezone column.** More correct in the abstract. Rejected for now because NORA has
   one reporting zone everywhere else (architecture §19) and a schedule that disagrees with the
   trends panel about what "Monday" means is worse than one that is uniformly Brazilian. The column
   on `workflow_schedules` records the zone per row, so introducing the setting later is a backfill
   rather than an archaeology exercise.
3. **Fire the flow once per occurrence against the most recent meeting.** Simplest possible
   dispatcher. Rejected: on a quiet week it re-sends the same three-week-old meeting every morning,
   which is a product that looks broken and is technically working.
4. **Build the digest context now** (period aggregate, new placeholders, new conditions). It is what
   a scheduled trigger most obviously wants to be. Rejected as a different story: it is a new context
   shape, a new placeholder vocabulary and new conditions, none of which US75 or ADR 0046 §1 asked
   for, and half of it delivered is a trigger whose copy over-promises.
5. **An advisory lock (`pg_try_advisory_lock`) instead of a claim row.** Cheaper to write and it
   solves overlap. Rejected because a lock is held in a session and dies with it, so it records
   nothing: `next_fire_at`, `window_from` and `last_fire_at` are exactly the state that has to
   survive a restart, and a table that holds them makes the lock redundant.
6. **Leave `schedule.cron` refused and move US75 to WONT.** Consistent with ADR 0038's subtraction,
   and the cheapest. Rejected by ADR 0046 §1, which reactivated it for a reason this ADR agrees
   with: the engine's substrate already exists, and the gap is in the product's own catalogue.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-08-17 | sys0xFF | Created and accepted. Gives `TriggerType.SCHEDULE_CRON` a real dispatcher and fixes the five semantics that were undocumented rather than decided: a `@Scheduled` tick guarded by a database compare-and-swap rather than by the single-replica deployment fact; a closed frequency vocabulary compiled to a canonical cron expression, with total rejection at save; `America/Sao_Paulo` as the fixed occurrence zone, recorded per row; catch-up that drops missed occurrences but not missed meetings, via separate `next_fire_at` and `window_from` columns; and skip-on-overlap bounded by a claim lease. Records that a timer-fired run carries no principal and acts under the tenant's integration connections, with the revocation gap named. Successor record to ADR 0030 §5, which is accepted and unedited |
