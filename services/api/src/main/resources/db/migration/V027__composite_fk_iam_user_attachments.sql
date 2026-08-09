-- Composite FK: iam_user_groups / iam_user_policies .(tenant_id, user_id) -> users.(tenant_id, id).
--
-- Before: the two link tables had independent FKs -- user_id -> users(id) and
-- tenant_id -> tenants(id) -- with nothing requiring the two columns to describe the SAME
-- users row. Nothing in the database prevented attaching a user from tenant A to a group/policy
-- registered under tenant B: the row satisfies both FKs in isolation.
--
-- In the app, IamService.addUserToGroup and attachPolicyToUser validate that the GROUP and the POLICY
-- belong to the caller's tenant, but never validate the userId. The group carries permissions,
-- so a cross-tenant link grants an outside user the target tenant's rights.
--
-- V015 closed exactly this class of hole for meetings.owner_user_id and already created the
-- UNIQUE (tenant_id, id) on users that serves as the target -- these two tables never got
-- the equivalent. It is the same remedy, in the same shape.
--
-- Now: Postgres rejects with ForeignKeyViolation any insert whose (tenant_id, user_id)
-- does not match a users row. Defense in depth: the check in IamService remains the
-- first barrier and returns a domain error; this is the floor that does not depend on anyone
-- remembering to write the check.
--
-- Out of scope, on purpose: the `attached_by` column. It also references users(id),
-- but it is ON DELETE SET NULL, and a composite FK would null BOTH columns when the actor is
-- removed -- including tenant_id, which is NOT NULL. That would be an FK that cannot execute its
-- own delete action.

ALTER TABLE iam_user_groups DROP CONSTRAINT iam_user_groups_user_id_fkey;
ALTER TABLE iam_user_groups ADD CONSTRAINT iam_user_groups_user_id_fkey
    FOREIGN KEY (tenant_id, user_id)
    REFERENCES users (tenant_id, id)
    ON DELETE CASCADE;

COMMENT ON CONSTRAINT iam_user_groups_user_id_fkey ON iam_user_groups IS
    'Composite FK: tenant_id + user_id juntos precisam bater na linha de users. '
    'Previne anexar usuario de outro tenant a um grupo (audit 2026-08).';

ALTER TABLE iam_user_policies DROP CONSTRAINT iam_user_policies_user_id_fkey;
ALTER TABLE iam_user_policies ADD CONSTRAINT iam_user_policies_user_id_fkey
    FOREIGN KEY (tenant_id, user_id)
    REFERENCES users (tenant_id, id)
    ON DELETE CASCADE;

COMMENT ON CONSTRAINT iam_user_policies_user_id_fkey ON iam_user_policies IS
    'Composite FK: tenant_id + user_id juntos precisam bater na linha de users. '
    'Previne anexar usuario de outro tenant a uma policy (audit 2026-08).';
