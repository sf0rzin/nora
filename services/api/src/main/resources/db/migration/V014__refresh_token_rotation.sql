-- Refresh Token Rotation + Reuse Detection (audit follow-up #3).
--
-- Antes da rotacao: cada token era valido ate expirar (30 dias). Se um atacante
-- exfiltrasse o cookie, podia renovar access tokens livremente ate o usuario
-- vitima fazer logout.
--
-- Agora: a cada /auth/refresh emitimos um NOVO token na mesma family_id e
-- revogamos o anterior. Se o anterior (ja revogado) for apresentado, assumimos
-- comprometimento: revogamos a family inteira (atacante e vitima sao deslogados).

ALTER TABLE refresh_tokens ADD COLUMN family_id UUID;
ALTER TABLE refresh_tokens ADD COLUMN replaced_by_id UUID NULL REFERENCES refresh_tokens(id);

-- Backfill: tokens existentes formam uma family cada (mesmo UUID que o id).
UPDATE refresh_tokens SET family_id = id WHERE family_id IS NULL;

ALTER TABLE refresh_tokens ALTER COLUMN family_id SET NOT NULL;

-- Lookup pela family (revogar toda a cadeia em reuso).
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens(family_id);

COMMENT ON COLUMN refresh_tokens.family_id IS
    'Token family pra rotacao. Tokens da mesma cadeia compartilham family_id. Reuse de token revogado revoga a family inteira.';
COMMENT ON COLUMN refresh_tokens.replaced_by_id IS
    'Quando rotacionado, aponta para o token novo. NULL para o token ainda ativo da cadeia ou para tokens revogados sem sucessor (logout).';
