-- V031 — `iam_permission_boundaries`: the policy that CAPS a user's effective permissions
-- (US44, ADR 0049).
--
-- !! THIS FILE IS OUT OF ORDER, AND THAT IS DELIBERATE !!
-- V031 was reserved by US41 and released when policy templates shipped as a catalogue in code, so
-- V032 took the next number while this one sat empty. US44 filled the gap instead of renumbering:
-- renumbering an unreleased file is free, renumbering V032 is not — it may already have run, and
-- moving it would change its checksum.
--
-- The price is paid in configuration, not here: `spring.flyway.out-of-order` is now `true`
-- (`application.yml`, with the reasoning beside it). Without it, any database that already applied
-- V032 fails `validate` on boot with "Detected resolved migration not applied to database: 031"
-- and the API does not start. Nothing already applied is re-run and no checksum moves.
--
-- WHAT A BOUNDARY IS
-- ------------------
-- A boundary is a policy attached to a principal that LIMITS it. An action is allowed only if the
-- principal's own policies allow it AND the boundary allows it. A boundary never grants: it is an
-- intersection, not a second grant path. That is what makes handing out policy-attach rights safe
-- — the holder cannot widen themselves past their own cap.
--
-- WHY A SEPARATE TABLE AND NOT A FLAG ON `iam_user_policies`
-- ---------------------------------------------------------
-- A boolean `is_boundary` on the existing attachment table is one column instead of one table, and
-- it was rejected for a reason that is structural rather than aesthetic. `collectAttachedPolicies
-- ForUser` reads `iam_user_policies` UNION the group attachments and hands the result to the
-- evaluator as GRANTS. With a flag, the boundary document would sit inside that query's range and
-- one forgotten `AND NOT is_boundary` would turn a cap into a grant — the exact failure the whole
-- feature exists to prevent, one predicate away. A separate table means the grant query cannot see
-- the boundary at all: there is nothing to remember.
--
-- ONE PER USER, USERS ONLY
-- ------------------------
-- `PRIMARY KEY (user_id)` — `users.id` is globally unique, so this is "at most one boundary per
-- user" and nothing else. There is deliberately NO boundary on a group: two group memberships
-- would give a user two boundaries, and both ways of combining them are wrong to somebody. The
-- union widens (a boundary that grants), and the intersection makes joining a group REMOVE
-- permissions, which is the opposite of what every other row in this schema does.
--
-- THE TWO COMPOSITE FKS, AND WHY `policy_id` IS NOT `ON DELETE CASCADE`
-- --------------------------------------------------------------------
-- (tenant_id, user_id) -> users (tenant_id, id): same shape and same reason as V027 — nothing else
-- would stop tenant A from capping a user of tenant B, and a cap written against the wrong tenant
-- reads as protection while being none.
--
-- (tenant_id, policy_id) -> iam_policies (tenant_id, id): the policy acting as a cap must belong to
-- the same tenant as the user it caps. This is the FK the `iam_policies_tenant_id_id_uk` below
-- exists to point at, the same two-step V015 and V028 used.
--
-- Its delete action is `NO ACTION`, not `CASCADE`, and that is a security decision. Under CASCADE,
-- `DELETE FROM iam_policies` would silently remove somebody's cap — a privilege escalation reached
-- through the ordinary policy-delete endpoint by anyone who can delete a policy. Under NO ACTION
-- the delete fails and `IamService` turns it into `IAM_POLICY_IN_USE_AS_BOUNDARY` (409): removing a
-- cap has to be an explicit act against the boundary, which is audited as one.
--
-- NO ACTION rather than RESTRICT on purpose. RESTRICT is checked immediately; NO ACTION is deferred
-- to the end of the statement. Deleting a whole tenant cascades into both `iam_policies` and this
-- table, and only the deferred check is guaranteed to see the boundary row already gone.
--
-- ABSENCE MEANS UNRESTRICTED
-- --------------------------
-- No row = no boundary = the user's own policies decide alone. The table being EMPTY is therefore
-- the correct state of a tenant that has never used the feature, and every existing tenant is in it
-- — which is why this migration backfills nothing. The opposite reading, absence as deny-all, would
-- turn shipping this migration into a tenant-wide outage.
--
-- RLS
-- ---
-- This is an IAM AUTHORIZATION table, so it follows the family: V020 (ADR 0028) disabled RLS on
-- `iam_policies`, `iam_user_policies` and the rest because onboarding flows without a JWT write to
-- them, and left the `tenant_isolation` policies defined but inert. The same is done here — the
-- policy is created and RLS is NOT enabled, so the table matches the end state of its family
-- exactly instead of being the one member that behaves differently. Isolation comes from the
-- tenant_id predicate in every query plus the two composite FKs above.

-- ============================================================
-- The target of the composite FK below. Adds no new uniqueness — `id` is already the primary key
-- of `iam_policies` — it exists only so (tenant_id, policy_id) has something to reference. Same
-- two-step shape as V015 (`users`) and V028 (`tenant_contexts`).
-- ============================================================
ALTER TABLE iam_policies ADD CONSTRAINT iam_policies_tenant_id_id_uk UNIQUE (tenant_id, id);

-- ============================================================
-- iam_permission_boundaries
-- ============================================================
CREATE TABLE iam_permission_boundaries (
    user_id       UUID NOT NULL,
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    policy_id     UUID NOT NULL,
    attached_by   UUID REFERENCES users(id) ON DELETE SET NULL,
    attached_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id),

    CONSTRAINT iam_permission_boundaries_user_fk
        FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id)
        ON DELETE CASCADE,

    CONSTRAINT iam_permission_boundaries_policy_fk
        FOREIGN KEY (tenant_id, policy_id)
        REFERENCES iam_policies (tenant_id, id)
        ON DELETE NO ACTION
);

CREATE INDEX idx_iam_permission_boundaries_tenant ON iam_permission_boundaries (tenant_id);
CREATE INDEX idx_iam_permission_boundaries_policy ON iam_permission_boundaries (policy_id);

COMMENT ON TABLE iam_permission_boundaries IS
    'US44: the policy that caps the effective permissions of one user. Allowed = the own policies '
    'AND the boundary. A missing row means unrestricted, never deny-all.';

COMMENT ON CONSTRAINT iam_permission_boundaries_policy_fk ON iam_permission_boundaries IS
    'ON DELETE NO ACTION: deleting the policy must not silently remove somebody''s cap. '
    'The API answers IAM_POLICY_IN_USE_AS_BOUNDARY (409) instead.';

-- Defined and inert, exactly like the rest of the IAM authorization family after V020 (ADR 0028).
-- Present so that a future decision to enforce RLS over IAM config has nothing left to write.
CREATE POLICY tenant_isolation ON iam_permission_boundaries
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
