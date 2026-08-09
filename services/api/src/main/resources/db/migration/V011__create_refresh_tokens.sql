-- V011: stateful refresh tokens for long-session login (httpOnly + revocability).
-- Round 2 / Subphase 1.3 A: fighting XSS - short access JWT (15min) + refresh (30 days).
-- token_hash = SHA-256 of the raw token. The raw one only exists in the browser's httpOnly cookie.
-- A database leak does not allow reuse. Stateful revocation (logout, logoutAll, future password change).

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

-- Main lookup: filters active ones by user (revoke all, list sessions).
CREATE INDEX idx_refresh_tokens_user ON refresh_tokens(user_id) WHERE revoked_at IS NULL;

-- Direct lookup by hash (validation on each refresh).
CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens(token_hash);

COMMENT ON TABLE refresh_tokens IS 'Refresh tokens stateful (revogaveis). token_hash = SHA-256 do token plain. Plain so existe no client cookie.';
