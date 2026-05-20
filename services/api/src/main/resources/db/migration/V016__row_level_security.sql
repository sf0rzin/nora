-- Row Level Security (RLS) — defesa em profundidade do tenant_id filter (ADR 0002).
--
-- Hoje o backend filtra tenant_id em todas as queries (TenantContext + repository
-- por design). Se um servico/repo novo esquecer o filtro, vazaria dados. RLS no
-- Postgres adiciona uma camada extra: o DB rejeita rows com tenant_id != current
-- session GUC `nora.current_tenant_id`.
--
-- ## Como funciona
--
-- 1) Esta migration cria POLICIES e ENABLE RLS em todas as tabelas tenant-owned.
-- 2) Spring `TenantRlsAspect` (bean condicional) intercepta @Transactional e
--    executa `SET LOCAL nora.current_tenant_id = '<uuid>'` no inicio da
--    transacao. O GUC e local — auto-reseta no commit/rollback.
-- 3) Postgres owner (admin) bypassa RLS por default. Em prod, o app deveria
--    rodar com role dedicado (`nora_app`) sem BYPASSRLS, e RLS vira enforcement
--    real. Em tests/dev com Testcontainers/local Postgres, o app roda como owner
--    e RLS fica inerte — testes continuam funcionando sem mudanca.
--
-- ## Operacao em prod (opt-in)
--
-- Para ativar enforcement real em prod:
--   1. CREATE ROLE nora_app WITH LOGIN PASSWORD '...';
--   2. GRANT SELECT/INSERT/UPDATE/DELETE em todas as tenant-owned tables a nora_app;
--   3. ALTER ROLE nora_app NOBYPASSRLS;
--   4. Mudar a connection string da API pra usar nora_app.
--   5. Setar nora.security.rls.enforce=true (ativa o aspect).
--
-- Sem esses 5 passos, RLS esta no schema mas inerte (admin bypassa).

-- ============================================================
-- Schema 'nora' + helper que extrai tenant_id da session.
-- ============================================================
-- O schema precisa existir ANTES da function. Usar current_setting com
-- missing_ok=true (segundo parametro) pra nao explodir quando GUC nao esta
-- setada (ex.: tests rodando direto sem aspect). Quando NULL, retorna
-- FALSE para qualquer row — admin/owner bypassa por default.

CREATE SCHEMA IF NOT EXISTS nora;

CREATE OR REPLACE FUNCTION nora.current_tenant_id() RETURNS uuid AS $$
DECLARE
    raw text;
BEGIN
    raw := current_setting('nora.current_tenant_id', true);
    IF raw IS NULL OR raw = '' THEN
        RETURN NULL;
    END IF;
    RETURN raw::uuid;
EXCEPTION WHEN invalid_text_representation THEN
    RETURN NULL;
END;
$$ LANGUAGE plpgsql STABLE;

-- ============================================================
-- POLICIES — tabelas tenant-owned core
-- ============================================================
-- Convencao: policy `tenant_isolation` por tabela. WHEN GUC nao setado
-- (NULL), policy nao matcha — admin bypassa, app com role nao-admin sem
-- SET LOCAL retorna 0 rows (fail-closed).

-- meetings
ALTER TABLE meetings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meetings
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- tenants (auto-referencia: pode acessar o proprio tenant)
ALTER TABLE tenants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenants
    USING (id = nora.current_tenant_id())
    WITH CHECK (id = nora.current_tenant_id());

-- tenant_contexts
ALTER TABLE tenant_contexts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant_contexts
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- users
ALTER TABLE users ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON users
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- refresh_tokens
ALTER TABLE refresh_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON refresh_tokens
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- POLICIES — tabelas tenant-owned IAM
-- ============================================================

ALTER TABLE iam_groups ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_groups
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_policies
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_user_invitations ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_user_invitations
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- POLICIES — analise (filhos de meetings, ja sao filtrados via meeting_id
-- + tenant_id explicito que toda tabela carrega)
-- ============================================================

ALTER TABLE meeting_analyses ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_analyses
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE meeting_participants ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_participants
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

COMMENT ON FUNCTION nora.current_tenant_id() IS
    'Le GUC nora.current_tenant_id da session corrente. Retorna NULL se nao setado. '
    'Setado pelo TenantRlsAspect via SET LOCAL no inicio de cada @Transactional.';
