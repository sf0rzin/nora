-- V020: escopo de RLS auth-aware (ADR 0028, corrige o design de enforce do ADR 0026).
--
-- O enforce de RLS (role nora_app NOBYPASSRLS) vale para as tabelas de DADOS DE NEGOCIO + PII —
-- onde esta o valor da defesa em profundidade e o gap critico (transcripts = PII em repouso):
--   meetings, transcripts, meeting_analyses + filhos (decisions/action_items/risks/opportunities/
--   participants/tags/goals/productivity/account_links), customer_accounts,
--   customer_confidence_assessments, tenant_contexts.
-- Essas tabelas SO sao tocadas por requests AUTENTICADOS (JWT -> TenantRlsAspect seta o GUC) OU
-- pelo pipeline de analise (que tem o tenantId e seta o GUC explicitamente, via TenantRlsContext).
--
-- Esta migration DESABILITA RLS em duas familias de tabela que NAO podem/devem ser enforced:
--
-- (A) IDENTIDADE — auth le/escreve cross-tenant ou SEM tenant (login por email global, signup,
--     aceite de convite por token, tokens por hash). Sob enforce, RLS aqui quebraria a auth
--     (fail-closed sem GUC). Isolamento segue pelo filtro tenant_id na aplicacao (disciplinado).
-- (B) AUTORIZACAO IAM — grupos/policies/membership/audit. Sao CONFIG de autorizacao (nao PII de
--     cliente), e fluxos de onboarding sem JWT escrevem nelas (ex.: aceite anexa o user a grupos;
--     signup grava audit). Exemptar elimina a necessidade de fiar GUC nesses fluxos. Isolamento
--     segue pelo filtro tenant_id na aplicacao (PolicyEvaluator + queries scoped, disciplinado).
--
-- As policies tenant_isolation continuam DEFINIDAS (inertes com RLS off) — reversivel, sem recriar.

-- (A) Identidade
ALTER TABLE users                     DISABLE ROW LEVEL SECURITY;
ALTER TABLE tenants                   DISABLE ROW LEVEL SECURITY;
ALTER TABLE email_verification_tokens DISABLE ROW LEVEL SECURITY;
ALTER TABLE password_reset_tokens     DISABLE ROW LEVEL SECURITY;
ALTER TABLE refresh_tokens            DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_user_invitations      DISABLE ROW LEVEL SECURITY;

-- (B) Autorizacao IAM
ALTER TABLE iam_groups                DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_policies              DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_user_groups           DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_group_policies        DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_user_policies         DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_policy_versions       DISABLE ROW LEVEL SECURITY;
ALTER TABLE iam_audit_events          DISABLE ROW LEVEL SECURITY;
