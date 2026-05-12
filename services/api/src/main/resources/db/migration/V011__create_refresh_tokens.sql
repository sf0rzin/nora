-- V011: refresh tokens stateful para login sessao longa (httpOnly + revogabilidade).
-- Round 2 / Subfase 1.3 A: combate ao XSS - access JWT curto (15min) + refresh (30 dias).
-- token_hash = SHA-256 do token cru. O cru so existe no cookie httpOnly do navegador.
-- Vazamento do banco nao permite reuso. Revogacao stateful (logout, logoutAll, troca de senha futura).

CREATE TABLE refresh_tokens (
    id           UUID PRIMARY KEY,
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    token_hash   VARCHAR(255) NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ NOT NULL,
    revoked_at   TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_used_at TIMESTAMPTZ
);

-- Lookup principal: filtra ativos por usuario (revogar todos, listar sessoes).
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id) WHERE revoked_at IS NULL;

-- Lookup direto pelo hash (validacao em cada refresh).
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash);

COMMENT ON TABLE refresh_tokens IS 'Refresh tokens stateful (revogaveis). token_hash = SHA-256 do token plain. Plain so existe no client cookie.';
