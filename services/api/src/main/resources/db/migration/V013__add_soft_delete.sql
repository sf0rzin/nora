-- Soft-delete on tenant-owned entities (Meeting, Tenant, TenantContext, User).
--
-- Hard-delete is still possible via native query when needed (LGPD right to be
-- forgotten, compliance retention). Spring Data queries now filter
-- deleted_at IS NULL by default via @SQLRestriction on JpaEntity.
--
-- We swapped the full UNIQUEs for partial unique indexes WHERE deleted_at IS NULL so
-- that new records can reuse slug/email/tenant_id after a soft-delete (otherwise a
-- deleted user would block a new signup with the same email forever).

ALTER TABLE meetings ADD COLUMN deleted_at TIMESTAMPTZ NULL;
ALTER TABLE tenants ADD COLUMN deleted_at TIMESTAMPTZ NULL;
ALTER TABLE tenant_contexts ADD COLUMN deleted_at TIMESTAMPTZ NULL;
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ NULL;

-- tenants.slug: replaces the full UNIQUE with a partial one.
ALTER TABLE tenants DROP CONSTRAINT tenants_slug_key;
CREATE UNIQUE INDEX tenants_slug_uk ON tenants (slug) WHERE deleted_at IS NULL;

-- users (tenant_id, email): replaces the full UNIQUE with a partial one.
ALTER TABLE users DROP CONSTRAINT users_email_uk;
CREATE UNIQUE INDEX users_email_uk ON users (tenant_id, email) WHERE deleted_at IS NULL;

-- tenant_contexts.tenant_id: replaces the full UNIQUE with a partial one.
ALTER TABLE tenant_contexts DROP CONSTRAINT tenant_contexts_tenant_id_key;
CREATE UNIQUE INDEX tenant_contexts_tenant_id_uk ON tenant_contexts (tenant_id) WHERE deleted_at IS NULL;

-- indexes for queries that filter non-deleted rows (backs @SQLRestriction).
CREATE INDEX meetings_deleted_at_idx ON meetings (deleted_at);
CREATE INDEX tenants_deleted_at_idx ON tenants (deleted_at);
CREATE INDEX tenant_contexts_deleted_at_idx ON tenant_contexts (deleted_at);
CREATE INDEX users_deleted_at_idx ON users (deleted_at);
