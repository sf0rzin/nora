-- V003: tabelas de tokens de verificacao de e-mail e reset de senha + flag email_verified_at em users.
-- Stories: US01 (signup), US02 (verificacao por e-mail), US03 (login bloqueado se nao verificado), US04 (reset).
-- Tokens armazenados como hash SHA-256: o token cru so existe no e-mail enviado ao usuario.
-- Vazamento do banco nao permite reuso direto dos tokens.

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
