-- V006: IAM estilo AWS (Root + Users + Groups + Policies + Audit).
-- Ver ADR 0007 e docs/data-model.md (secao IAM).
--
-- Notas:
-- * Conditions sao ARMAZENADAS dentro do JSONB do document mas NAO sao avaliadas no MVP
--   pelo PolicyEvaluator (ficam para post-MVP). Wildcards em action/resource sao avaliados.
-- * As tabelas antigas `roles` / `user_roles` (V002) ficam intactas por compatibilidade
--   e nao sao mais usadas no novo modelo; remocao em migration futura.

-- ============================================================
-- users.is_root
-- ============================================================
ALTER TABLE users ADD COLUMN is_root BOOLEAN NOT NULL DEFAULT FALSE;

-- Promove o primeiro usuario de cada tenant existente a Root (compat com dados ja criados).
UPDATE users u
   SET is_root = TRUE
  FROM (
        SELECT DISTINCT ON (tenant_id) id
          FROM users
         ORDER BY tenant_id, created_at ASC
       ) first_user
 WHERE u.id = first_user.id;

-- Garante que so existe um Root por tenant.
CREATE UNIQUE INDEX uq_users_root_per_tenant
    ON users (tenant_id) WHERE is_root = TRUE;

-- ============================================================
-- iam_groups
-- ============================================================
CREATE TABLE iam_groups (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name          TEXT NOT NULL,
    description   TEXT,
    created_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT iam_groups_name_uk UNIQUE (tenant_id, name)
);

CREATE INDEX idx_iam_groups_tenant ON iam_groups (tenant_id);

-- ============================================================
-- iam_user_groups (membership)
-- ============================================================
CREATE TABLE iam_user_groups (
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    group_id      UUID NOT NULL REFERENCES iam_groups(id) ON DELETE CASCADE,
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    attached_by   UUID REFERENCES users(id) ON DELETE SET NULL,
    attached_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, group_id)
);

CREATE INDEX idx_iam_user_groups_tenant ON iam_user_groups (tenant_id);
CREATE INDEX idx_iam_user_groups_group  ON iam_user_groups (group_id);

-- ============================================================
-- iam_policies
-- document JSONB:
--   { "version": "2026-05-07", "statements": [ { "effect": "Allow"|"Deny",
--      "action": ["meeting:read", ...], "resource": ["nora:tenant/.../meeting/*"],
--      "condition": {...}? } ] }
-- ============================================================
CREATE TABLE iam_policies (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id        UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name             TEXT NOT NULL,
    description      TEXT,
    document         JSONB NOT NULL,
    current_version  INTEGER NOT NULL DEFAULT 1,
    created_by       UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT iam_policies_name_uk UNIQUE (tenant_id, name),
    CONSTRAINT iam_policies_version_chk CHECK (current_version >= 1)
);

CREATE INDEX idx_iam_policies_tenant ON iam_policies (tenant_id);

-- ============================================================
-- iam_policy_versions (historico imutavel)
-- ============================================================
CREATE TABLE iam_policy_versions (
    policy_id     UUID NOT NULL REFERENCES iam_policies(id) ON DELETE CASCADE,
    version       INTEGER NOT NULL,
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    document      JSONB NOT NULL,
    created_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (policy_id, version),
    CONSTRAINT iam_policy_versions_version_chk CHECK (version >= 1)
);

CREATE INDEX idx_iam_policy_versions_tenant ON iam_policy_versions (tenant_id);

-- ============================================================
-- iam_group_policies (policy attached to group)
-- ============================================================
CREATE TABLE iam_group_policies (
    group_id      UUID NOT NULL REFERENCES iam_groups(id) ON DELETE CASCADE,
    policy_id     UUID NOT NULL REFERENCES iam_policies(id) ON DELETE CASCADE,
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    attached_by   UUID REFERENCES users(id) ON DELETE SET NULL,
    attached_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (group_id, policy_id)
);

CREATE INDEX idx_iam_group_policies_tenant ON iam_group_policies (tenant_id);
CREATE INDEX idx_iam_group_policies_policy ON iam_group_policies (policy_id);

-- ============================================================
-- iam_user_policies (policy attached directly to user)
-- ============================================================
CREATE TABLE iam_user_policies (
    user_id       UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    policy_id     UUID NOT NULL REFERENCES iam_policies(id) ON DELETE CASCADE,
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    attached_by   UUID REFERENCES users(id) ON DELETE SET NULL,
    attached_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    PRIMARY KEY (user_id, policy_id)
);

CREATE INDEX idx_iam_user_policies_tenant ON iam_user_policies (tenant_id);
CREATE INDEX idx_iam_user_policies_policy ON iam_user_policies (policy_id);

-- ============================================================
-- iam_audit_events (US40)
-- ============================================================
CREATE TABLE iam_audit_events (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    actor_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    action         TEXT NOT NULL,        -- ex.: "iam:group:create", "iam:policy:attach"
    target_type    TEXT NOT NULL,        -- "GROUP" | "POLICY" | "USER" | "MEMBERSHIP" | "ATTACHMENT"
    target_id      UUID,
    payload        JSONB,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_iam_audit_events_tenant_created
    ON iam_audit_events (tenant_id, created_at DESC);
