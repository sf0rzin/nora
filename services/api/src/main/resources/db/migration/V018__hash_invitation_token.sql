-- V018: hash the invitation token (US06, ADR 0011) — security hardening (services/api audit).
--
-- Up to V010 the `token` column stored the invitation token in PLAIN TEXT, justified by direct
-- lookup. But the invitation token IS the credential: whoever holds it creates an ACTIVE user in the
-- tenant and receives a JWT (automatic login on acceptance). A database dump exposed every PENDING
-- invitation in the clear.
--
-- The system's other one-time tokens (email_verification_tokens, password_reset_tokens,
-- refresh_tokens — see V003/V011) already persist ONLY the SHA-256. This migration aligns the
-- invitation with the same pattern: the column now stores the hash; the raw token exists only in
-- memory during creation (to build the email URL) and acceptance hashes the received token to do
-- the indexed lookup.
--
-- IMPORTANT (dev environment): existing PENDING invitations hold the RAW token in this column. After
-- the migration the backend compares against the SHA-256 of the presented token, so those legacy
-- invitations are NO longer acceptable (the stored value stops matching). As agreed for dev, we do
-- NOT migrate legacy data; we mark the old PENDING ones as EXPIRED to leave the schema consistent
-- and avoid orphan records with an undecipherable raw value. Invitations must be resent.

-- 1) Invalidates legacy PENDING invitations (the stored raw value no longer matches the new
--    hash contract). Without this they would stay PENDING forever, never acceptable.
UPDATE iam_user_invitations SET status = 'EXPIRED' WHERE status = 'PENDING';

-- 2) Renames the column and adjusts the type. The SHA-256 hex hash is 64 chars; TEXT mirrors the
--    type used in the other token tables (token_hash TEXT NOT NULL UNIQUE in V003/V011).
ALTER TABLE iam_user_invitations RENAME COLUMN token TO token_hash;
ALTER TABLE iam_user_invitations ALTER COLUMN token_hash TYPE TEXT;

-- 3) Renames the matching index to reflect the new column name. The UNIQUE constraint inherited
--    from V010 (token UNIQUE) is renamed automatically along with the column by Postgres.
ALTER INDEX idx_iam_invitations_token RENAME TO idx_iam_invitations_token_hash;

COMMENT ON COLUMN iam_user_invitations.token_hash IS
    'SHA-256 hexadecimal do token de convite. O token cru nunca e persistido (mesmo padrao de '
    'email_verification_tokens / password_reset_tokens / refresh_tokens). US06, ADR 0011.';
