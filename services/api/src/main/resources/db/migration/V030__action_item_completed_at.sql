-- V030 — `meeting_action_items.completed_at`, plus the two indexes the trends panel aggregates on
-- (US21).
--
-- WHY THE COLUMN EXISTS
-- --------------------
-- The trends panel charts task load over time: how many action items were OPENED in a period and
-- how many were COMPLETED in it. "Opened" is exact — `created_at` is written once, by the analysis
-- that extracted the item, and nothing ever rewrites it. "Completed" had no column at all.
--
-- The only candidate was `updated_at`, and it is not a completion timestamp: `PATCH /tasks/{id}`
-- also writes title and due date (see `TaskRepositoryAdapter.updateTitle`/`updateDueDate`), each
-- of which touches `updated_at`. A task finished in March and renamed in June would have been
-- charted as a June completion. A chart is read as a fact, so deriving one from a column that
-- means "last touched" would have been a wrong number presented confidently — worse than no
-- series at all.
--
-- WHAT THE BACKFILL DOES, AND WHAT IT CANNOT DO
-- ---------------------------------------------
-- Rows already in DONE get `completed_at = updated_at`. That value is an UPPER BOUND on the real
-- completion instant, not a measurement: it is the last time anything about the row changed. It is
-- seeded anyway because the alternative — leaving historical DONE rows NULL — would draw a series
-- that shows zero completions before this migration, which is a stronger false statement than an
-- approximate date. Rows that are not DONE stay NULL, which is the truth: they were never
-- completed.
--
-- From here on the value is exact. `TaskRepositoryAdapter.updateStatus` is the single writer of
-- `status` in the codebase; it sets `completed_at = NOW()` on a transition into DONE and clears it
-- on a transition out of DONE, so an item reopened after being finished stops counting as a
-- completion instead of counting twice.
--
-- NO CHECK CONSTRAINT PAIRING status AND completed_at
-- ---------------------------------------------------
-- `CHECK ((status = 'DONE') = (completed_at IS NOT NULL))` was considered and rejected: it would
-- be violated by exactly the historical rows this migration cannot date, and a constraint that has
-- to be created NOT VALID to be created at all documents less than this comment does.

ALTER TABLE meeting_action_items ADD COLUMN completed_at TIMESTAMPTZ;

COMMENT ON COLUMN meeting_action_items.completed_at IS
    'When the item entered DONE; NULL while it is not DONE. Rows already DONE when V030 ran carry the pre-existing updated_at, which is an upper bound and not a measurement.';

UPDATE meeting_action_items
   SET completed_at = updated_at
 WHERE status = 'DONE'
   AND completed_at IS NULL;

-- ============================================================
-- Indexes for the aggregation.
--
-- The panel buckets by week or month over one tenant, so both queries are
-- `WHERE tenant_id = ? AND <ts> >= ? AND <ts> < ?`. The existing indexes are (analysis_id),
-- (tenant_id) and (tenant_id, status) — none of them carries a timestamp, so every bucket query
-- would fall back to the tenant index and re-read the whole tenant.
--
-- The completion index is PARTIAL: `completed_at IS NULL` for every item that is not DONE, and
-- those rows are never a candidate for a completion bucket. Indexing them would grow the index by
-- the size of the open backlog for nothing.
-- ============================================================
CREATE INDEX idx_meeting_action_items_tenant_created
    ON meeting_action_items (tenant_id, created_at);

CREATE INDEX idx_meeting_action_items_tenant_completed
    ON meeting_action_items (tenant_id, completed_at)
 WHERE completed_at IS NOT NULL;

-- RLS: nothing to do. `meeting_action_items` carries `tenant_isolation` + ENABLE ROW LEVEL
-- SECURITY since V019 (ADR 0026), and a policy is defined over the ROW, not over the column set —
-- a new column inherits it. The `nora_app` role needs no new grant either: V019 granted the table.
