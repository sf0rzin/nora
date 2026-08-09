-- V010: user invitations by corporate e-mail (US06, ADR 0011).
-- UUID token in text, secret never returned in listings. Status managed by the InvitationService.
-- Idempotency (same email with a valid PENDING) and on-read expire are implemented in the
-- application layer; the schema only guarantees constraints and indexes for fast queries.

CREATE TABLE iam_user_invitations (
    id                UUID PRIMARY KEY,
    tenant_id         UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    email             VARCHAR(255) NOT NULL,
    token             VARCHAR(128) NOT NULL UNIQUE,
    status            VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    invited_by        UUID NOT NULL REFERENCES users(id),
    invited_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    expires_at        TIMESTAMPTZ NOT NULL,
    accepted_at       TIMESTAMPTZ,
    accepted_user_id  UUID REFERENCES users(id),

    CONSTRAINT iam_invitation_status_valid CHECK (status IN ('PENDING','ACCEPTED','EXPIRED','REVOKED'))
);

CREATE INDEX idx_iam_invitations_tenant_status ON iam_user_invitations(tenant_id, status);
CREATE INDEX idx_iam_invitations_token         ON iam_user_invitations(token);
CREATE INDEX idx_iam_invitations_email         ON iam_user_invitations(tenant_id, email);

CREATE TABLE iam_invitation_groups (
    invitation_id  UUID NOT NULL REFERENCES iam_user_invitations(id) ON DELETE CASCADE,
    group_id       UUID NOT NULL REFERENCES iam_groups(id) ON DELETE CASCADE,
    PRIMARY KEY (invitation_id, group_id)
);

COMMENT ON TABLE iam_user_invitations IS
    'Convites de usuario (US06, ADR 0011). Status: PENDING/ACCEPTED/EXPIRED/REVOKED.';
