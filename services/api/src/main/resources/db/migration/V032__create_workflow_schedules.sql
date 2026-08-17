-- V032 — `workflow_schedules`: the run state of a `schedule.cron` flow (US75, ADR 0047).
--
-- WHY A TABLE AND NOT A LOCK
-- --------------------------
-- ADR 0036 says the substrate is one bare-metal host running one API container, so Spring's
-- @Scheduled is enough to make the timer fire. "We only run one replica" is a DEPLOYMENT FACT, not
-- a property of the code, and the three values below are exactly what has to survive a restart:
-- when the next occurrence is due, where the period the next run reads begins, and whether a run
-- is currently in flight. A `pg_try_advisory_lock` would solve the second replica and record none
-- of it, so the table makes the lock redundant rather than the other way round.
--
-- One row per scheduled workflow: `workflow_id` is the PK, written by `WorkflowService` on every
-- save of a flow whose trigger is `schedule.cron` and deleted when the trigger changes to anything
-- else. The FK CASCADE removes it with the workflow.
--
-- THE TWO TIMESTAMPS THAT LOOK REDUNDANT AND ARE NOT (ADR 0047 §4)
-- ----------------------------------------------------------------
-- `next_fire_at` is advanced AT CLAIM, to the next occurrence strictly after now. That is what
-- collapses a six-hour outage's three missed occurrences into ONE run on recovery instead of
-- three notifications for one outage.
--
-- `window_from` is advanced AT RELEASE, to the instant the completed run fired. It is the lower
-- bound of `meeting_analyses.generated_at` the next run reads, so the meetings of a run that died
-- mid-flight are picked up by the following one instead of being dropped with it.
--
-- Together: occurrences are AT-MOST-ONCE, meetings are AT-LEAST-ONCE. A crash between claim and
-- release cannot loop (next_fire_at already moved) and cannot silently lose analysed meetings
-- (window_from did not). The two values diverging is the visible evidence that a run died.
--
-- `last_fire_at` is diagnostic only — the instant of the most recent claim, so a reader can tell
-- "never ran" from "ran and the window has not advanced since".
--
-- THE CLAIM IS A COMPARE-AND-SWAP
-- -------------------------------
-- The claiming UPDATE matches on the `next_fire_at` the tick read a moment earlier, so of two
-- processes reading the same due row only one can win: the loser's UPDATE matches no row. Same
-- shape as `meetings.claimForReanalysis` (V004 + adapter) — the condition is evaluated by the
-- database against the committed row, never in Java.
--
-- `claimed_at` doubles as the overlap guard: a due row with a live claim is SKIPPED, not queued.
-- It is believed for `nora.flows.schedule.claim-lease-minutes` (default 30) and presumed abandoned
-- after that, because a claim left by a dead JVM would otherwise freeze the schedule forever.
--
-- TIMEZONE IS STORED, NOT ASSUMED
-- -------------------------------
-- Occurrences are computed in America/Sao_Paulo, the same constant as the trends panel
-- (`TrendsService.REPORTING_ZONE`) and the calendar actions. The column records it PER ROW so the
-- day NORA has a tenant outside Brazil, the rows say what they were computed in — introducing a
-- per-tenant zone becomes a backfill instead of an archaeology exercise.
--
-- `cron` holds the canonical six-field Spring expression the restricted vocabulary compiles to
-- (`0 M * * * *`, `0 M H * * *`, `0 M H * * DOW`). The vocabulary — not this column — is what
-- bounds the fastest expressible schedule to hourly; storing the compiled form is what makes
-- widening it later a parser decision rather than a schema change.

CREATE TABLE workflow_schedules (
    workflow_id  UUID PRIMARY KEY REFERENCES workflows(id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    cron         TEXT NOT NULL,
    timezone     TEXT NOT NULL,
    next_fire_at TIMESTAMPTZ NOT NULL,
    window_from  TIMESTAMPTZ NOT NULL,
    last_fire_at TIMESTAMPTZ,
    claimed_at   TIMESTAMPTZ,
    claim_owner  TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

COMMENT ON COLUMN workflow_schedules.next_fire_at IS
    'When the next occurrence is due. Advanced AT CLAIM, so missed occurrences collapse into one.';
COMMENT ON COLUMN workflow_schedules.window_from IS
    'Lower bound of meeting_analyses.generated_at the next run reads. Advanced AT RELEASE, so a run that dies mid-flight does not drop its meetings.';
COMMENT ON COLUMN workflow_schedules.claimed_at IS
    'Set while a run is in flight; the overlap guard. Believed for nora.flows.schedule.claim-lease-minutes, then presumed abandoned.';

-- The tick's only query: the tenant's due schedules. Ordered by next_fire_at so the oldest
-- overdue occurrence is claimed first when several are due in the same pass.
CREATE INDEX idx_workflow_schedules_due ON workflow_schedules (tenant_id, next_fire_at);

-- RLS: tenant-owned business table -> enforced (ADR 0028), same pattern as V023, which created the
-- two tables this one hangs off. The scheduler thread carries no JWT, so `ScheduledFlowRunner`
-- propagates the tenant through `TenantRlsContext` per tenant exactly as `RetentionSweeper` and
-- `StuckAnalysisSweeper` do; without it every statement here would match zero rows and the job
-- would report "nothing due" forever, which is the silent failure ADR 0029 documented.
ALTER TABLE workflow_schedules ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_schedules
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
