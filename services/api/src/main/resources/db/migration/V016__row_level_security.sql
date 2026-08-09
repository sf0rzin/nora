-- Row Level Security (RLS) — defense in depth for the tenant_id filter (ADR 0002).
--
-- Today the backend filters tenant_id in every query (TenantContext + repository
-- by design). If a new service/repo forgets the filter, data would leak. RLS in
-- Postgres adds an extra layer: the DB rejects rows with tenant_id != current
-- session GUC `nora.current_tenant_id`.
--
-- ## How it works
--
-- 1) This migration creates POLICIES and ENABLE RLS on every tenant-owned table.
-- 2) Spring `TenantRlsAspect` (conditional bean) intercepts @Transactional and
--    runs `SET LOCAL nora.current_tenant_id = '<uuid>'` at the start of the
--    transaction. The GUC is local — it auto-resets on commit/rollback.
-- 3) The Postgres owner (admin) bypasses RLS by default. In prod, the app should
--    run with a dedicated role (`nora_app`) without BYPASSRLS, and RLS becomes real
--    enforcement. In tests/dev with Testcontainers/local Postgres, the app runs as
--    owner and RLS stays inert — tests keep working with no change.
--
-- ## Operating in prod (opt-in)
--
-- To turn on real enforcement in prod:
--   1. CREATE ROLE nora_app WITH LOGIN PASSWORD '...';
--   2. GRANT SELECT/INSERT/UPDATE/DELETE on every tenant-owned table to nora_app;
--   3. ALTER ROLE nora_app NOBYPASSRLS;
--   4. Change the API connection string to use nora_app.
--   5. Set nora.security.rls.enforce=true (activates the aspect).
--
-- Without these 5 steps, RLS is in the schema but inert (admin bypasses it).

-- ============================================================
-- Schema 'nora' + helper that extracts tenant_id from the session.
-- ============================================================
-- The schema must exist BEFORE the function. Use current_setting with
-- missing_ok=true (second parameter) so it does not blow up when the GUC is not
-- set (e.g. tests running directly without the aspect). When NULL, it returns
-- FALSE for any row — admin/owner bypasses by default.

CREATE SCHEMA IF NOT EXISTS nora;

CREATE OR REPLACE FUNCTION nora.current_tenant_id() RETURNS uuid AS $$
DECLARE
    raw text;
BEGIN
    raw := current_setting('nora.current_tenant_id', true);
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;
    END IF;
    RETURN raw::uuid;
EXCEPTION WHEN invalid_text_representation THEN
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

-- ============================================================
-- POLICIES — core tenant-owned tables
-- ============================================================
-- Convention: policy `tenant_isolation` per table. WHEN the GUC is not set
-- (NULL), the policy does not match — admin bypasses, app on a non-admin role
-- without SET LOCAL returns 0 rows (fail-closed).

-- meetings
ALTER TABLE meetings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meetings
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- tenants (self-reference: can access its own tenant)
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants
    USING (id = nora.current_tenant_id())
    WITH CHECK (id = nora.current_tenant_id());

-- tenant_contexts
ALTER TABLE tenant_contexts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_contexts
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- users
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON users
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- refresh_tokens
ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON refresh_tokens
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- POLICIES — IAM tenant-owned tables
-- ============================================================

ALTER TABLE iam_groups ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_groups
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_policies
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_user_invitations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_user_invitations
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- POLICIES — analysis (children of meetings, already filtered via meeting_id
-- + the explicit tenant_id every table carries)
-- ============================================================

ALTER TABLE meeting_analyses ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_analyses
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE meeting_participants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_participants
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

COMMENT ON FUNCTION nora.current_tenant_id() IS
    'Le GUC nora.current_tenant_id da session corrente. Retorna NULL se nao setado. '
    'Setado pelo TenantRlsAspect via SET LOCAL no inicio de cada @Transactional.';
