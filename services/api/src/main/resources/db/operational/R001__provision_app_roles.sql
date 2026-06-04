-- R001 — Provisionamento dos roles de aplicacao para RLS enforce (ADR 0002, ADR 0019, ADR 0026).
--
-- ATENCAO: este script NAO e uma migration Flyway da aplicacao.
-- Ele cria/ajusta ROLES e DEFAULT PRIVILEGES — operacoes que exigem privilegio de
-- ADMIN do Postgres (o owner do banco / postgresAdminLogin), NAO o role nora_app.
-- A aplicacao roda Flyway como nora_app e NAO pode (nem deve) rodar este script.
--
-- ## Quando rodar
--
-- Manualmente (ou via o workflow `rls-cutover.yml` com a credencial de admin), UMA vez
-- por ambiente, ANTES de ligar nora.security.rls.enforce=true (ver sequencia de cutover
-- no ADR 0028, que corrige o 0026). E idempotente: pode rodar de novo sem efeito colateral.
--
-- ## Como rodar (exemplo)
--
--   psql "<connection string como ADMIN>" \
--     -v app_password="<senha-forte-do-nora_app>" \
--     -v telemetry_password="<senha-forte-do-nora_telemetry>" \
--     -f R001__provision_app_roles.sql
--
--   IMPORTANTE: passar as senhas CRUAS (sem aspas em volta). Os DO blocks usam
--   :'app_password' (psql quoted-variable), que ja transforma o valor em literal SQL
--   com escaping correto. Passar com aspas (-v app_password="'...'") faz a senha do
--   role virar literalmente 'senha' (COM as aspas) e a API nao conecta.
--
-- Depois, apontar DATASOURCE_USERNAME/PASSWORD da API pra nora_app e setar
-- NORA_RLS_ENFORCE=true via o flip do bicepparam (ver Bicep + ADR 0028).
--
-- ## O que este script faz
--
--   1. Cria role nora_app (LOGIN, NOBYPASSRLS) — o role de runtime da API.
--   2. Cria role nora_telemetry (LOGIN, BYPASSRLS) — leitura operador-only
--      cross-tenant da telemetria de negocio (ver PrimaryDbBusinessMetricsSource).
--   3. GRANT SELECT/INSERT/UPDATE/DELETE em todas as tabelas tenant-owned (e demais
--      tabelas do schema public, incluindo a flyway_schema_history) ao nora_app.
--   4. GRANT SELECT (somente leitura) ao nora_telemetry nas tabelas de agregacao.
--   5. ALTER DEFAULT PRIVILEGES pra que tabelas FUTURAS (proximas migrations)
--      herdem os grants automaticamente — senao cada nova tabela exigiria um grant
--      manual antes da API enxerga-la.
--
-- Tudo dentro de uma transacao implicita do psql -f (cada statement). Os DO blocks
-- abaixo tornam CREATE ROLE idempotente (CREATE ROLE nao tem IF NOT EXISTS).

-- ============================================================
-- 1. Role de runtime da API: nora_app (NOBYPASSRLS => RLS vale de verdade).
-- ============================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_app') THEN
        EXECUTE format('CREATE ROLE nora_app WITH LOGIN PASSWORD %L', :'app_password');
    ELSE
        EXECUTE format('ALTER ROLE nora_app WITH LOGIN PASSWORD %L', :'app_password');
    END IF;
END
$$;

-- Garante NOBYPASSRLS (o ponto inteiro do enforce). Idempotente.
ALTER ROLE nora_app NOBYPASSRLS;

-- ============================================================
-- 2. Role de telemetria operador-only: nora_telemetry (BYPASSRLS => le cross-tenant).
--    Usado APENAS pela agregacao de metricas de negocio (COUNT/COUNT DISTINCT em
--    meeting_analyses). NUNCA usado pelo caminho de request normal. Ver ADR 0026.
-- ============================================================
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_telemetry') THEN
        EXECUTE format('CREATE ROLE nora_telemetry WITH LOGIN PASSWORD %L', :'telemetry_password');
    ELSE
        EXECUTE format('ALTER ROLE nora_telemetry WITH LOGIN PASSWORD %L', :'telemetry_password');
    END IF;
END
$$;

-- BYPASSRLS: este role IGNORA as policies (leitura agregada cross-tenant intencional).
ALTER ROLE nora_telemetry BYPASSRLS;

-- ============================================================
-- 3. GRANTs ao nora_app — todas as tabelas tenant-owned + infra do schema public.
--    Sob o ADR 0028 o Flyway roda como ADMIN (nora_admin), entao as tabelas (inclusive
--    flyway_schema_history) sao OWNED pelo admin — o nora_app (nao-owner, NOBYPASSRLS)
--    precisa destes GRANTs explicitos pra enxerga-las, e fica sujeito a RLS.
-- ============================================================
GRANT USAGE ON SCHEMA public TO nora_app;
GRANT USAGE ON SCHEMA nora   TO nora_app;  -- precisa enxergar nora.current_tenant_id()
GRANT EXECUTE ON FUNCTION nora.current_tenant_id() TO nora_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO nora_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nora_app;

-- ============================================================
-- 4. GRANTs ao nora_telemetry — somente leitura, restrita ao necessario.
--    Hoje a telemetria so toca meeting_analyses; manter o minimo (least privilege).
--    Se a telemetria passar a ler outras tabelas, adicionar grants explicitos aqui.
-- ============================================================
GRANT USAGE ON SCHEMA public TO nora_telemetry;
GRANT SELECT ON meeting_analyses TO nora_telemetry;

-- ============================================================
-- 5. DEFAULT PRIVILEGES — tabelas/sequences FUTURAS criadas pelo owner (admin que
--    roda as migrations) ja nascem com grant pro nora_app. Sem isso, cada migration
--    nova exigiria um GRANT manual antes da API enxergar a tabela.
--    Observacao: ALTER DEFAULT PRIVILEGES e por-role-CRIADOR do objeto. Sob o ADR 0028
--    o Flyway roda como ADMIN (nora_admin) — o MESMO role que roda este R001 — entao o
--    ALTER DEFAULT PRIVILEGES sem FOR ROLE abaixo (executado pelo admin) ja cobre as
--    tabelas das proximas migrations. Por isso e CRITICO rodar o R001 como nora_admin
--    (o mesmo da connection string do Flyway), nao como outro superuser.
-- ============================================================
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nora_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO nora_app;

-- Defesa adicional: se algum dia o nora_app criar objetos (ele NAO cria sob 0028,
-- onde o Flyway e admin), eles ja herdam grants. Inofensivo hoje; explicito pra
-- evitar surpresa em evolucao do schema.
ALTER DEFAULT PRIVILEGES FOR ROLE nora_app IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nora_app;
