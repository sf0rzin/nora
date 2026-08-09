-- Refresh Token Rotation + Reuse Detection (audit follow-up #3).
--
-- Before rotation: each token was valid until it expired (30 days). If an attacker
-- exfiltrated the cookie, they could renew access tokens freely until the victim
-- user logged out.
--
-- Now: on every /auth/refresh we issue a NEW token under the same family_id and
-- revoke the previous one. If the previous one (already revoked) is presented, we
-- assume compromise: we revoke the whole family (attacker and victim are logged out).

ALTER TABLE refresh_tokens ADD COLUMN family_id UUID;
ALTER TABLE refresh_tokens ADD COLUMN replaced_by_id UUID NULL REFERENCES refresh_tokens(id);

-- Backfill: existing tokens each form a family of their own (same UUID as the id).
UPDATE refresh_tokens SET family_id = id WHERE family_id IS NULL;

ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;

-- Lookup by family (revoke the whole chain on reuse).
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);

COMMENT ON COLUMN refresh_tokens.family_id IS
    'Token family pra rotacao. Tokens da mesma cadeia compartilham family_id. Reuse de token revogado revoga a family inteira.';
COMMENT ON COLUMN refresh_tokens.replaced_by_id IS
    'Quando rotacionado, aponta para o token novo. NULL para o token ainda ativo da cadeia ou para tokens revogados sem sucessor (logout).';
