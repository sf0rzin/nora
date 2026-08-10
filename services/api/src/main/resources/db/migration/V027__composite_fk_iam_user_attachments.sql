-- Composite FK: iam_user_groups / iam_user_policies .(tenant_id, user_id) -> users.(tenant_id, id).
--
-- !! CHECKSUM WARNING !!
-- This file was EDITED after it had already been applied to at least one database. Flyway
-- checksums the migration body, so any database that ran the earlier version of V027 will now
-- fail `validate` on startup with a checksum mismatch and the API will not boot until someone
-- runs `flyway repair` there once (or recreates the environment from scratch). That is the price
-- of fixing V027 in place, and there is no alternative: the failure this fixes happens WHILE V027
-- runs, so a follow-up V028 would never get the chance to execute.
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

-- Step 1 -- remove the rows the new constraint cannot accept.
--
-- ADD CONSTRAINT ... FOREIGN KEY validates every existing row immediately, and the rows it would
-- reject are precisely the ones this migration exists because of: addUserToGroup and
-- attachPolicyToUser never validated the userId against the tenant, so a (tenant_id, user_id) pair
-- matching no users row was writable for the whole life of the schema. On a database carrying even
-- one of them the ALTER aborts, Flyway fails, and the API does not start. Restoring a production
-- dump onto a new host is exactly the situation where that data is present.
--
-- Deleting them is the remediation, not a workaround. Each such row is a group membership or a
-- policy attachment whose user belongs to a different tenant than the link claims -- it grants
-- access across a tenant boundary, which is the whole reason for the constraint. The counts go out
-- with RAISE NOTICE so the operator reads them in the migration log instead of losing data
-- silently.
DO $$
DECLARE
    removed_memberships INTEGER;
    removed_attachments INTEGER;
BEGIN
    DELETE FROM iam_user_groups g
     WHERE NOT EXISTS (
               SELECT 1
                 FROM users u
                WHERE u.id = g.user_id
                  AND u.tenant_id = g.tenant_id
           );
    GET DIAGNOSTICS removed_memberships = ROW_COUNT;

    DELETE FROM iam_user_policies p
     WHERE NOT EXISTS (
               SELECT 1
                 FROM users u
                WHERE u.id = p.user_id
                  AND u.tenant_id = p.tenant_id
           );
    GET DIAGNOSTICS removed_attachments = ROW_COUNT;

    RAISE NOTICE 'V027: removed % cross-tenant group membership(s)', removed_memberships;
    RAISE NOTICE 'V027: removed % cross-tenant policy attachment(s)', removed_attachments;
END
$$;

-- Step 2 -- swap the simple FK for the composite one.
--
-- DROP ... IF EXISTS: the name is the one Postgres generates for an inline column REFERENCES
-- (<table>_<column>_fkey, V006), and depending on a generated name to be exactly right is not
-- worth a failed boot. IF EXISTS also makes re-running this file over a database that already has
-- the composite constraint a no-op instead of an error.
ALTER TABLE iam_user_groups DROP CONSTRAINT IF EXISTS iam_user_groups_user_id_fkey;
ALTER TABLE iam_user_groups ADD CONSTRAINT iam_user_groups_user_id_fkey
    FOREIGN KEY (tenant_id, user_id)
    REFERENCES users (tenant_id, id)
    ON DELETE CASCADE;

COMMENT ON CONSTRAINT iam_user_groups_user_id_fkey ON iam_user_groups IS
    'Composite FK: tenant_id + user_id juntos precisam bater na linha de users. '
    'Previne anexar usuario de outro tenant a um grupo (audit 2026-08).';

ALTER TABLE iam_user_policies DROP CONSTRAINT IF EXISTS iam_user_policies_user_id_fkey;
ALTER TABLE iam_user_policies ADD CONSTRAINT iam_user_policies_user_id_fkey
    FOREIGN KEY (tenant_id, user_id)
    REFERENCES users (tenant_id, id)
    ON DELETE CASCADE;

COMMENT ON CONSTRAINT iam_user_policies_user_id_fkey ON iam_user_policies IS
    'Composite FK: tenant_id + user_id juntos precisam bater na linha de users. '
    'Previne anexar usuario de outro tenant a uma policy (audit 2026-08).';
