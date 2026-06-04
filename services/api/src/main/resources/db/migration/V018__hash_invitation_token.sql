-- V018: hashear o token de convite (US06, ADR 0011) — hardening de seguranca (auditoria services/api).
--
-- Ate V010 a coluna `token` guardava o token de convite em TEXTO PURO, justificado por lookup
-- direto. Mas o token de convite E a credencial: quem o tem cria um user ACTIVE no tenant e recebe
-- JWT (login automatico no aceite). Um dump do banco expunha todos os convites PENDING em claro.
--
-- Os demais tokens one-time do sistema (email_verification_tokens, password_reset_tokens,
-- refresh_tokens — ver V003/V011) ja persistem APENAS o SHA-256. Esta migration alinha o convite ao
-- mesmo padrao: a coluna passa a guardar o hash; o token cru existe so em memoria durante a criacao
-- (para montar a URL do e-mail) e o aceite hasheia o token recebido para fazer lookup por indice.
--
-- IMPORTANTE (ambiente dev): convites PENDING existentes guardam o token CRU nesta coluna. Apos a
-- migration o backend passa a comparar contra o SHA-256 do token apresentado, entao esses convites
-- legados NAO sao mais aceitaveis (o valor armazenado deixa de bater). Como combinado para dev, NAO
-- migramos dados legados; marcamos os PENDING antigos como EXPIRED para deixar o schema consistente
-- e evitar registros orfaos com valor cru indecifravel. Convites devem ser reenviados.

-- 1) Invalida convites PENDING legados (o valor cru armazenado nao corresponde mais ao novo
--    contrato de hash). Sem isso ficariam PENDING para sempre, nunca aceitaveis.
UPDATE iam_user_invitations SET status = 'EXPIRED' WHERE status = 'PENDING';

-- 2) Renomeia a coluna e ajusta o tipo. O hash hexadecimal do SHA-256 tem 64 chars; TEXT espelha o
--    tipo usado nas demais tabelas de token (token_hash TEXT NOT NULL UNIQUE em V003/V011).
ALTER TABLE iam_user_invitations RENAME COLUMN token TO token_hash;
ALTER TABLE iam_user_invitations ALTER COLUMN token_hash TYPE TEXT;

-- 3) Renomeia o indice correspondente para refletir o novo nome da coluna. A constraint UNIQUE
--    herdada de V010 (token UNIQUE) e renomeada automaticamente junto da coluna pelo Postgres.
ALTER INDEX idx_iam_invitations_token RENAME TO idx_iam_invitations_token_hash;

COMMENT ON COLUMN iam_user_invitations.token_hash IS
    'SHA-256 hexadecimal do token de convite. O token cru nunca e persistido (mesmo padrao de '
    'email_verification_tokens / password_reset_tokens / refresh_tokens). US06, ADR 0011.';
