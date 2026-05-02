-- V001: cria a tabela de tenants.
-- Multi-tenancy: todas as demais tabelas referenciam tenant_id.
-- Ver docs/adr/0002-multi-tenancy.md e docs/data-model.md.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE tenants (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name          TEXT NOT NULL,
    slug          TEXT NOT NULL UNIQUE,
    status        TEXT NOT NULL DEFAULT 'ACTIVE',
    plan          TEXT NOT NULL DEFAULT 'FREE',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT tenants_status_chk CHECK (status IN ('ACTIVE', 'SUSPENDED')),
    CONSTRAINT tenants_plan_chk   CHECK (plan IN ('FREE', 'PRO', 'ENTERPRISE'))
);

CREATE INDEX idx_tenants_status ON tenants (status);
