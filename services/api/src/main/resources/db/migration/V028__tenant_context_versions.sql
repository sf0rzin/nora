-- V028 — Immutable version history for the tenant's company context (US31).
--
-- WHY
-- ---
-- `tenant_contexts` (V005) is 1-1 with the tenant and carries only `created_at`/`updated_at`.
-- Every `PUT /tenant/context` overwrites `document` in place, so the previous products, ICP,
-- competitors and objection-handling are gone the moment they are replaced. That document is the
-- input that makes an analysis specific to the customer's business, and there was no way to answer
-- either "who changed what, and when?" or "which context was this meeting analysed against?".
--
-- SHAPE — copied from `iam_policy_versions` (V006:87-101), which is this repository's chosen form
-- for immutable versioning: a separate table, composite primary key (entity_id, version), explicit
-- `tenant_id`, `document JSONB`, `created_by`, `created_at`, `CHECK (version >= 1)` and an index by
-- tenant. The parent gains `current_version` the way `iam_policies` has it (V006:75,81), so the
-- current number is a column read rather than a MAX() over the history.
--
-- ...WITH ONE DELIBERATE DIFFERENCE. `iam_policy_versions` predates V015, so its `tenant_id` and
-- its `policy_id` are two independent foreign keys and nothing in the database requires them to
-- describe the same policy. V015 and V027 closed exactly that hole for `meetings.owner_user_id`
-- and for the IAM user attachments; this table is born with the composite FK instead of waiting
-- for a V0xx to retrofit it. A row whose (tenant_id, context_id) pair does not match a
-- `tenant_contexts` row is rejected by Postgres, not merely by the application.
--
-- READ PATH IS PART OF THIS CHANGE, NOT A FOLLOW-UP. `iam_policy_versions` is written on every
-- policy edit and there is no SELECT against it anywhere in the codebase and no endpoint exposing
-- it — a tenant admin cannot list or inspect it. A history nobody can read is a backup, not an
-- audit trail. `GET /tenant/context/versions` and `GET /tenant/context/versions/{version}` ship in
-- the same pull request as this migration.

-- ============================================================
-- 1) Composite-FK target on the parent.
--
-- `id` is already the primary key, so this UNIQUE adds no new uniqueness — it exists only because
-- a composite FK needs a UNIQUE (tenant_id, id) to point at. Same two-step shape as V015.
-- ============================================================
ALTER TABLE tenant_contexts ADD CONSTRAINT tenant_contexts_tenant_id_id_uk UNIQUE (tenant_id, id);

-- ============================================================
-- 2) Current version on the parent (mirrors iam_policies.current_version).
--
-- DEFAULT 1 is what makes this safe on a populated table: every existing context row becomes
-- version 1 without a separate UPDATE, and step 4 writes the matching history row for it.
-- ============================================================
ALTER TABLE tenant_contexts ADD COLUMN current_version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE tenant_contexts ADD CONSTRAINT tenant_contexts_version_chk CHECK (current_version >= 1);

-- ============================================================
-- 3) The history table.
--
-- SOFT-DELETE, decided rather than left implicit. `tenant_contexts` carries `deleted_at` (V013)
-- and a partial UNIQUE on `tenant_id`, so a tenant can in principle end up with one dead context
-- row and one live one. The rule here:
--
--   * a SOFT delete does not touch the history. The parent row still exists, so the FK is still
--     satisfied and the trail survives the deletion — which is the point of an audit trail. It
--     becomes unreachable through the API, because both read endpoints resolve the history through
--     the LIVE context row (`deleted_at IS NULL`), exactly as `@SQLRestriction` does for the
--     document itself.
--   * a HARD delete of the context, or of the tenant, takes the history with it via ON DELETE
--     CASCADE. That is the LGPD erasure path (ADR 0029), and an erasure that left the previous
--     versions of the company document behind would not be an erasure.
--   * a context created after a soft delete gets a new `id`, therefore its own history starting at
--     version 1. Two lifetimes, two trails, never interleaved.
--
-- `created_by` is ON DELETE SET NULL like everywhere else: removing a user must not delete the
-- record that a change happened, only the pointer to who made it.
-- ============================================================
CREATE TABLE tenant_context_versions (
    context_id  UUID NOT NULL,
    version     INTEGER NOT NULL,
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    document    JSONB NOT NULL,
    created_by  UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (context_id, version),
    CONSTRAINT tenant_context_versions_version_chk CHECK (version >= 1),
    CONSTRAINT tenant_context_versions_context_fk
        FOREIGN KEY (tenant_id, context_id)
        REFERENCES tenant_contexts (tenant_id, id)
        ON DELETE CASCADE
);

COMMENT ON CONSTRAINT tenant_context_versions_context_fk ON tenant_context_versions IS
    'Composite FK: tenant_id and context_id together must match one tenant_contexts row, so a '
    'version cannot be filed under a tenant that does not own the context. Same remedy as V015/V027.';

CREATE INDEX idx_tenant_context_versions_tenant ON tenant_context_versions (tenant_id);

-- ============================================================
-- 4) Baseline for the contexts that already exist.
--
-- Without this, every tenant that had ever saved a context would see a history whose newest entry
-- is the FIRST edit made after this migration — a hole directly in front of the document currently
-- in force, which is the worst possible shape for an audit trail.
--
-- `created_at` is taken from the parent's `updated_at`, and `created_by` from `updated_by`. Both
-- are APPROXIMATE and knowingly so: they describe the LAST edit the row received, not the moment
-- version 1 was written, because that moment was never recorded. Every version from 2 onwards
-- carries a real timestamp and a real author.
--
-- Soft-deleted contexts are included. They are rows of `tenant_contexts` like any other, the FK
-- requires a matching parent either way, and excluding them would leave `current_version = 1` on a
-- row with no version 1 behind it.
-- ============================================================
INSERT INTO tenant_context_versions (context_id, version, tenant_id, document, created_by, created_at)
SELECT c.id, 1, c.tenant_id, c.document, c.updated_by, c.updated_at
  FROM tenant_contexts c;

-- ============================================================
-- 5) RLS — tenant-owned business table, therefore enforced.
--
-- `tenant_contexts` is one of the tables V020 kept under enforce (it is business data, written only
-- by authenticated requests that set the GUC through TenantRlsAspect), so its history has to be
-- enforced too: leaving the parent protected and the history open would put the same document one
-- table away from any session. Same pattern as V019/V021.
--
-- nora_app receives its grants automatically from the ALTER DEFAULT PRIVILEGES in R001, because
-- Flyway creates the table as nora_admin.
-- ============================================================
ALTER TABLE tenant_context_versions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_context_versions
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
