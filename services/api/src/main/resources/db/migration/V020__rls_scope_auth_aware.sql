-- V020: auth-aware RLS scope (ADR 0028, fixes the enforce design of ADR 0026).
--
-- RLS enforce (role nora_app NOBYPASSRLS) applies to the BUSINESS DATA + PII tables —
-- where the value of defense in depth and the critical gap are (transcripts = PII at rest):
--   meetings, transcripts, meeting_analyses + children (decisions/action_items/risks/opportunities/
--   participants/tags/goals/productivity/account_links), customer_accounts,
--   customer_confidence_assessments, tenant_contexts.
-- These tables are ONLY touched by AUTHENTICATED requests (JWT -> TenantRlsAspect sets the GUC) OR
-- by the analysis pipeline (which has the tenantId and sets the GUC explicitly, via TenantRlsContext).
--
-- This migration DISABLES RLS on two table families that cannot/should not be enforced:
--
-- (A) IDENTITY — auth reads/writes cross-tenant or WITHOUT a tenant (login by global email, signup,
--     invitation acceptance by token, tokens by hash). Under enforce, RLS here would break auth
--     (fail-closed without the GUC). Isolation continues via the tenant_id filter in the app (disciplined).
-- (B) IAM AUTHORIZATION — groups/policies/membership/audit. These are authorization CONFIG (not customer
--     PII), and onboarding flows without a JWT write to them (e.g. acceptance attaches the user to groups;
--     signup writes audit). Exempting them removes the need to thread the GUC through those flows. Isolation
--     continues via the tenant_id filter in the app (PolicyEvaluator + scoped queries, disciplined).
--
-- The tenant_isolation policies stay DEFINED (inert with RLS off) — reversible, no need to recreate.

-- (A) Identity
ALTER TABLE users                     DISABLE ROW LEVEL SECURITY;
ALTER TABLE tenants                   DISABLE ROW LEVEL SECURITY;
ALTER TABLE email_verification_tokens DISABLE ROW LEVEL SECURITY;
ALTER TABLE password_reset_tokens     DISABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens            DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_user_invitations      DISABLE ROW LEVEL SECURITY;

-- (B) IAM authorization
ALTER TABLE iam_groups                DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_policies              DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_user_groups           DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_group_policies        DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_user_policies         DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_policy_versions       DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_audit_events          DISABLE ROW LEVEL SECURITY;
