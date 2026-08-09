-- V019: COMPLETE Row Level Security (RLS) — closes the coverage started in V016/V017 (ADR 0002, ADR 0019, ADR 0026).
--
-- ## Why this migration exists
--
-- V016 enabled ENABLE ROW LEVEL SECURITY + POLICY tenant_isolation on 12 tenant-owned
-- tables; V017 on +3 more (customer confidence). About 19 tenant-owned tables were left
-- with NO policy. In Postgres, a tenant-owned table WITHOUT `ENABLE ROW LEVEL SECURITY`
-- is FULLY OPEN to the connected role: if RLS enforce is turned on (role
-- nora_app NOBYPASSRLS), the tables with a policy get isolated, but the ones WITHOUT
-- one stay readable cross-tenant. The worst case: `transcripts` stores
-- `raw_text` (raw transcription = PII at rest). Turning enforce on today would protect
-- `meetings` and leave `transcripts` leaking between tenants — exactly the opposite of the
-- goal. This migration closes the hole.
--
-- ## Pattern (identical to V016/V017)
--
-- For every tenant-owned table with its own `tenant_id`:
--   ALTER TABLE <t> ENABLE ROW LEVEL SECURITY;
--   CREATE POLICY tenant_isolation ON <t>
--       USING (tenant_id = nora.current_tenant_id())
--       WITH CHECK (tenant_id = nora.current_tenant_id());
--
-- The function nora.current_tenant_id() already exists (V016) and reads the session GUC
-- `nora.current_tenant_id` (NULL => fail-closed: 0 rows for a role without BYPASSRLS).
-- The TenantRlsAspect (opt-in via nora.security.rls.enforce=true) sets the GUC per
-- transaction. In dev/Testcontainers the app runs as owner and RLS stays inert.
--
-- ## Child tables WITHOUT their own tenant_id (cascade boundary — NO policy)
--
-- Three tables are direct children of an already isolated parent and do NOT carry tenant_id:
--   - iam_invitation_groups        -> child of iam_user_invitations (via invitation_id)
--   - meeting_goal_expected_outcomes -> child of meeting_goals      (via meeting_goal_id)
--   - meeting_outcome_coverage     -> child of meeting_productivity_assessments (via assessment_id)
-- They follow the SAME convention already adopted in V017 for customer_buying_signals /
-- customer_objections / meeting_outcome_coverage: isolation comes from the FK cascade to the
-- parent (ON DELETE CASCADE) + the fact that every read goes through the isolated parent. Since
-- they have no tenant_id, a direct policy would require a JOIN to the parent inside USING — cost
-- and complexity with no real security gain, since the only access path is via the
-- parent. Documented here as an EXPLICIT BOUNDARY: if one of these tables ever
-- gains direct access (not through the parent), it needs a policy via JOIN.
--
-- ## Coverage after V019
--
-- V016 (12) + V017 (3) + V019 (15) = 30 tenant-owned tables with a direct policy.
-- Legacy tables `roles` (global rows, tenant_id NULL) and `user_roles` (V002,
-- deprecated and unused in the new IAM model) get NO RLS: `roles` has global
-- rows that a per-tenant policy would hide; both go away in a future cleanup
-- migration (see the V006 note). Cascade children: 3 (above) — no policy by design.

-- ============================================================
-- transcripts (V004) — PRIORITY: stores raw_text = PII at rest.
-- ============================================================
ALTER TABLE transcripts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transcripts
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- meeting_tags (V004)
-- ============================================================
ALTER TABLE meeting_tags ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_tags
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- Analysis children (V005) — each carries an explicit tenant_id (ADR 0002).
-- ============================================================
ALTER TABLE meeting_decisions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_decisions
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE meeting_action_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_action_items
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE meeting_risks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_risks
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE meeting_opportunities ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_opportunities
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- Productivity (V012) — meeting_goals + assessments carry tenant_id.
-- ============================================================
ALTER TABLE meeting_goals ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_goals
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE meeting_productivity_assessments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_productivity_assessments
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- IAM (V006) — membership/attachments/versions/audit, all with tenant_id.
-- ============================================================
ALTER TABLE iam_user_groups ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_user_groups
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_group_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_group_policies
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_user_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_user_policies
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_policy_versions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_policy_versions
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_audit_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_audit_events
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- Auth tokens (V003) — email verification + password reset. They carry
-- tenant_id; isolating them prevents enumerating/consuming another tenant's tokens via the app role.
-- ============================================================
ALTER TABLE email_verification_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_verification_tokens
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE password_reset_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON password_reset_tokens
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- Cascade boundaries (NO policy, by design — see the header).
-- iam_invitation_groups (V010), meeting_goal_expected_outcomes (V012),
-- meeting_outcome_coverage (V012) get NEITHER ENABLE RLS nor a policy: they are direct
-- children of an isolated parent, with no tenant_id of their own. Same convention as V017
-- (customer_buying_signals / customer_objections / meeting_outcome_coverage).
-- ============================================================
