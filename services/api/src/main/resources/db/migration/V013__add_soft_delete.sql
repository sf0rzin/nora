-- Soft-delete em entities tenant-owned (Meeting, Tenant, TenantContext, User).
--
-- Hard-delete continua possivel via native query quando necessario (LGPD direito
-- ao esquecimento, compliance retention). Default das queries do Spring Data agora
-- filtra deleted_at IS NULL via @SQLRestriction no JpaEntity.
--
-- Trocamos os UNIQUE totais por partial unique indexes WHERE deleted_at IS NULL para
-- que novos registros possam reusar slug/email/tenant_id apos um soft-delete (caso
-- contrario um user deletado bloquearia novo signup com mesmo email para sempre).

ALTER TABLE meetings ADD COLUMN deleted_at TIMESTAMPTZ NULL;
ALTER TABLE tenants ADD COLUMN deleted_at TIMESTAMPTZ NULL;
ALTER TABLE tenant_contexts ADD COLUMN deleted_at TIMESTAMPTZ NULL;
ALTER TABLE users ADD COLUMN deleted_at TIMESTAMPTZ NULL;

-- tenants.slug: substitui UNIQUE total por partial.
ALTER TABLE tenants DROP CONSTRAINT tenants_slug_key;
CREATE UNIQUE INDEX tenants_slug_uk ON tenants (slug) WHERE deleted_at IS NULL;

-- users (tenant_id, email): substitui UNIQUE total por partial.
ALTER TABLE users DROP CONSTRAINT users_email_uk;
CREATE UNIQUE INDEX users_email_uk ON users (tenant_id, email) WHERE deleted_at IS NULL;

-- tenant_contexts.tenant_id: substitui UNIQUE total por partial.
ALTER TABLE tenant_contexts DROP CONSTRAINT tenant_contexts_tenant_id_key;
CREATE UNIQUE INDEX tenant_contexts_tenant_id_uk ON tenant_contexts (tenant_id) WHERE deleted_at IS NULL;

-- indexes para queries que filtram nao-deletados (apoia @SQLRestriction).
CREATE INDEX meetings_deleted_at_idx ON meetings (deleted_at);
CREATE INDEX tenants_deleted_at_idx ON tenants (deleted_at);
CREATE INDEX tenant_contexts_deleted_at_idx ON tenant_contexts (deleted_at);
CREATE INDEX users_deleted_at_idx ON users (deleted_at);
