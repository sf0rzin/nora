-- V029 — Tenant-scoped MCP tokens (US27, ADR 0041 §3).
--
-- WHY
-- ---
-- ADR 0041 makes NORA an MCP server: an external client (Claude Desktop, an IDE, a coding agent)
-- reads meetings, tasks, semantic search and Customer Confidence over the inbound adapter in
-- `services/api`. That client sits in a configuration file for weeks and holds no browser session,
-- so §3 of the ADR gives it a credential of its own: a tenant-scoped bearer token minted by an
-- already-authenticated user, revocable, and stored ONLY as a SHA-256 hash.
--
-- The alternative the ADR rejected is worth naming here, because this table is what replaces it:
-- reusing the session JWT (ADR 0020's rotation family) would have meant either lengthening the
-- access token's lifetime — which weakens it on every other surface — or handing a desktop client
-- a refresh-token family it has no business holding.
--
-- SHAPE — the hashing precedent, not a new one
-- -------------------------------------------
-- `token_hash` stores the SHA-256 hex of the presented string and the raw token exists only in
-- memory, once, in the response that mints it. This is the pattern every credential in this schema
-- already follows: `email_verification_tokens` and `password_reset_tokens` (V003), `refresh_tokens`
-- (V011), and `iam_user_invitations` since V018 aligned it with the other three. A database dump
-- exposes no usable MCP credential.
--
-- The column is TEXT, like V018's `token_hash`, rather than V011's VARCHAR(255): the value is a
-- fixed 64-character hex digest and TEXT is what the newer tables in this schema use.
--
-- COMPOSITE FK, born with it
-- --------------------------
-- (tenant_id, user_id) references `users (tenant_id, id)` — the UNIQUE V015 added — so Postgres
-- itself refuses a token filed under a tenant that does not own the user. V015 and V027 had to
-- retrofit exactly that on `meetings.owner_user_id` and on the IAM attachments; V028 was born with
-- it, and so is this table. It matters more here than anywhere else: the tenant this row carries is
-- what the edge trusts when it resolves the token into a principal.
--
-- ROW LEVEL SECURITY — DELIBERATELY NOT ENFORCED, AND THIS IS THE REASON
-- ---------------------------------------------------------------------
-- V020 split the schema in two: business/PII tables run under `tenant_isolation` (V016/V019), and
-- the IDENTITY family — `users`, `refresh_tokens`, `email_verification_tokens`,
-- `password_reset_tokens`, `iam_user_invitations` — is exempt, because those rows are read BEFORE
-- any tenant is known. `mcp_tokens` is a member of that family by construction: the lookup that
-- resolves a bearer token into a principal is precisely how the request LEARNS its tenant, so at
-- that instant `nora.current_tenant_id()` is unset.
--
-- Enabling RLS here would therefore not harden anything. It would make every MCP request fail on
-- the deployed stack (`nora_app` is NOBYPASSRLS, ADR 0028) while passing locally, where RLS is off
-- by default — a defect invisible in development and total in production. Isolation for this table
-- is the application's `tenant_id` predicate, the composite FK above, and the fact that the
-- resolved principal carries the tenant the token was minted in.
--
-- SCOPE OF THE CREDENTIAL
-- -----------------------
-- The token authenticates ONLY the MCP endpoint. It is not an alternative session credential for
-- the REST API: the security chain that accepts it matches `/mcp` and nothing else. That is what
-- keeps ADR 0041 §4's read-only first cut a property of the credential and not merely of the tools
-- that happen to exist today.

CREATE TABLE mcp_tokens (
    id           UUID PRIMARY KEY,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id      UUID NOT NULL,
    name         TEXT NOT NULL,
    token_hash   TEXT NOT NULL UNIQUE,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at   TIMESTAMPTZ,
    revoked_at   TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,

    CONSTRAINT mcp_tokens_name_chk CHECK (length(btrim(name)) BETWEEN 1 AND 80),
    CONSTRAINT mcp_tokens_user_fk
        FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id)
        ON DELETE CASCADE
);

COMMENT ON TABLE mcp_tokens IS
    'Tenant-scoped bearer credentials for the inbound MCP adapter (ADR 0041). token_hash = SHA-256 '
    'hex of the presented string; the raw token is returned once and never persisted.';

COMMENT ON COLUMN mcp_tokens.name IS
    'Label chosen by the user so a token can be recognised in the list and revoked on purpose. '
    'Not a secret and not unique.';

COMMENT ON COLUMN mcp_tokens.expires_at IS
    'Optional hard expiry. NULL means the token lives until it is revoked, which is what an MCP '
    'client in a configuration file needs; revocation is the primary kill switch either way.';

COMMENT ON CONSTRAINT mcp_tokens_user_fk ON mcp_tokens IS
    'Composite FK: tenant_id and user_id together must match one users row, so a credential cannot '
    'be filed under a tenant that does not own its user. Same remedy as V015/V027/V028.';

-- The UNIQUE on token_hash already provides the index the edge lookup uses on every MCP request;
-- a second index on the same column would be dead weight (V011 carries one, and it is redundant
-- there too). What is NOT covered by it is the owner listing, which is this one:
CREATE INDEX idx_mcp_tokens_owner ON mcp_tokens (tenant_id, user_id) WHERE revoked_at IS NULL;
