-- V009: tenant corporate domain (US32).
-- Restricts which e-mails can be invited to the tenant. NULL = no restriction.
-- The cross validation email -> domain happens in the invite flow (US06, future slice).
-- See ADR 0011-invite-flow-corporate-domain.md.

ALTER TABLE tenants ADD COLUMN allowed_email_domain VARCHAR(255);

COMMENT ON COLUMN tenants.allowed_email_domain IS
    'Dominio corporativo do tenant (ex: acme.com). NULL = sem restricao. Validado em invites (ADR 0011, US32).';
