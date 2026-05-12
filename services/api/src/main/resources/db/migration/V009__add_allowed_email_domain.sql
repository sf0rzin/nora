-- V009: dominio corporativo do tenant (US32).
-- Restringe quais e-mails podem ser convidados ao tenant. NULL = sem restricao.
-- A validacao cruzada email -> domain ocorre no fluxo de convites (US06, fatia futura).
-- Ver ADR 0011-invite-flow-corporate-domain.md.

ALTER TABLE tenants ADD COLUMN allowed_email_domain VARCHAR(255);

COMMENT ON COLUMN tenants.allowed_email_domain IS
    'Dominio corporativo do tenant (ex: acme.com). NULL = sem restricao. Validado em invites (ADR 0011, US32).';
