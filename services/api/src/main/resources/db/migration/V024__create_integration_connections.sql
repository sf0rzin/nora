-- V024 — OAuth connections to external providers (NORA Flows Phase 2: Gmail/Calendar/Slack).
--
-- One row per (tenant, provider): Core is individual (1 root user per tenant), so the
-- connection is tenant-level; user_id records WHO connected (audit). Tokens are encrypted at
-- rest by the adapter (AES-GCM with a key from env — see TokenCipher) — the database never sees the
-- token in the clear when the key is configured.
--
-- RLS enforced (ADR 0028), same pattern as V022/V023: one tenant's tokens are invisible to
-- any session with another tenant GUC.

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
