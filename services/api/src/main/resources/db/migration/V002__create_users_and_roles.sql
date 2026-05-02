-- V002: cria usuarios, papeis (roles) e ligacao usuario-papel.
-- Roles padrao MVP: ROOT, ADMIN, MANAGER, ANALYST, VIEWER (ver backlog US35 e docs/PROJECT.md secao RBAC).
-- is_system = true marca as roles padrao e impede edicao/remocao.
-- Roles customizadas (is_system = false) sao pos-MVP.

CREATE EXTENSION IF NOT EXISTS "citext";

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT,
    email           CITEXT NOT NULL,
    password_hash   TEXT NOT NULL,
    display_name    TEXT NOT NULL,
    status          TEXT NOT NULL DEFAULT 'ACTIVE',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT users_status_chk CHECK (status IN ('ACTIVE', 'INVITED', 'DISABLED')),
    CONSTRAINT users_email_uk   UNIQUE (tenant_id, email)
);

CREATE INDEX idx_users_tenant ON users (tenant_id);

CREATE TABLE roles (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID REFERENCES tenants(id) ON DELETE CASCADE,
    code          TEXT NOT NULL,
    description   TEXT,
    is_system     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT roles_code_chk CHECK (code IN ('ROOT', 'ADMIN', 'MANAGER', 'ANALYST', 'VIEWER'))
);

-- Roles globais do sistema (tenant_id = NULL). Hierarquia logica: ROOT > ADMIN > MANAGER > ANALYST > VIEWER.
INSERT INTO roles (code, description, is_system) VALUES
    ('ROOT',    'Superadmin global. Reservado para suporte interno NORA.', TRUE),
    ('ADMIN',   'Administrador do tenant. Acesso total dentro do tenant.', TRUE),
    ('MANAGER', 'Gestor com visibilidade ampliada conforme escopo (departamentos/tags/contas).', TRUE),
    ('ANALYST', 'Analista com leitura ampla e edicao limitada de tarefas e anotacoes.', TRUE),
    ('VIEWER',  'Usuario somente leitura no escopo atribuido.', TRUE);

CREATE TABLE user_roles (
    user_id     UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role_id     UUID NOT NULL REFERENCES roles(id) ON DELETE RESTRICT,
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    granted_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, role_id)
);

CREATE INDEX idx_user_roles_tenant ON user_roles (tenant_id);
