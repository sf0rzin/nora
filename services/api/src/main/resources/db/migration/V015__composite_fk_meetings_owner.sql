-- Composite FK: meetings.(tenant_id, owner_user_id) -> users.(tenant_id, id).
--
-- Before: meetings.owner_user_id REFERENCES users(id). An attacker via ORM forge
-- (e.g. setting owner_user_id = uuid-of-user-from-another-tenant in an unvalidated
-- payload) could create a meeting with an owner from a different tenant, bypassing
-- ADR 0002 isolation. The backend filters by tenant_id in every query,
-- but defense in depth in the schema closes the path.
--
-- Now: the FK requires the meeting's (tenant_id, owner_user_id) to match
-- (tenant_id, id) on the users row. Insert with a user from another tenant = rejected
-- by Postgres with ForeignKeyViolation.
--
-- Implemented in 2 steps to support a composite FK:
-- 1) Add UNIQUE (tenant_id, id) on users (the target must be UNIQUE).
-- 2) Drop the simple FK + recreate it as composite.
--
-- The primary key `id` remains the simple PK (UUID v4 is already globally unique);
-- the composite UNIQUE exists only to support the FK target.

-- 1) Composite UNIQUE on users to support the FK target.
-- Reuses the partial unique on email so id duplicates are not blocked on
-- soft-deletes (impossible in practice because UUIDv4, but defensive).
ALTER TABLE users ADD CONSTRAINT users_tenant_id_uk UNIQUE (tenant_id, id);

-- 2) Drop the simple FK + recreate it as composite.
ALTER TABLE meetings DROP CONSTRAINT meetings_owner_user_id_fkey;
ALTER TABLE meetings ADD CONSTRAINT meetings_owner_user_id_fkey
    FOREIGN KEY (tenant_id, owner_user_id)
    REFERENCES users (tenant_id, id)
    ON DELETE RESTRICT;

COMMENT ON CONSTRAINT meetings_owner_user_id_fkey ON meetings IS
    'Composite FK: tenant_id + owner_user_id juntos precisam bater na linha de users. '
    'Previne cross-tenant user assignment via ORM forge (audit follow-up #7).';
