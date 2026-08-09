-- V003: e-mail verification and password reset token tables + email_verified_at flag on users.
-- Stories: US01 (signup), US02 (e-mail verification), US03 (login blocked if not verified), US04 (reset).
-- Tokens stored as a SHA-256 hash: the raw token only exists in the e-mail sent to the user.
-- A database leak does not allow direct reuse of the tokens.

ALTER TABLE users ADD COLUMN email_verified_at TIMESTAMPTZ;

CREATE TABLE email_verification_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    token_hash   TEXT NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_email_verif_tokens_user ON email_verification_tokens (user_id);
CREATE INDEX idx_email_verif_tokens_expires ON email_verification_tokens (expires_at);

CREATE TABLE password_reset_tokens (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id      UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    token_hash   TEXT NOT NULL UNIQUE,
    expires_at   TIMESTAMPTZ NOT NULL,
    consumed_at  TIMESTAMPTZ,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_pwd_reset_tokens_user ON password_reset_tokens (user_id);
CREATE INDEX idx_pwd_reset_tokens_expires ON password_reset_tokens (expires_at);
