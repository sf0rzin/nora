-- V024 — Conexões OAuth com provedores externos (NORA Flows Fase 2: Gmail/Calendar/Slack).
--
-- Uma linha por (tenant, provider): o Core é individual (1 usuário root por tenant), então a
-- conexão é tenant-level; user_id registra QUEM conectou (auditoria). Tokens ficam cifrados em
-- repouso pelo adapter (AES-GCM com chave de env — ver TokenCipher) — o banco nunca vê o token
-- em claro quando a chave está configurada.
--
-- RLS enforced (ADR 0028), mesmo padrão de V022/V023: tokens de um tenant são invisíveis para
-- qualquer sessão com outro GUC de tenant.

CREATE TABLE integration_connections (
    id               UUID PRIMARY KEY,
    tenant_id        UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id          UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider         TEXT NOT NULL CHECK (provider IN ('google', 'slack')),
    scopes           TEXT NOT NULL,
    external_account TEXT,
    access_token     TEXT NOT NULL,
    refresh_token    TEXT,
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_integration_tenant_provider UNIQUE (tenant_id, provider)
);

CREATE INDEX idx_integration_connections_tenant ON integration_connections (tenant_id);

ALTER TABLE integration_connections ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON integration_connections
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
