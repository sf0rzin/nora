-- =============================================================================
-- NORA -- initdb do Postgres primario: extensoes + os TRES roles do RLS.
-- =============================================================================
--
-- Estilo sem acentuacao, igual ao R001 em que este script se baseia
-- (services/api/src/main/resources/db/operational/R001__provision_app_roles.sql).
--
-- ## Quando roda
--
-- SO no PRIMEIRO boot do container `postgres`, com o volume `pgdata` VAZIO.
-- O entrypoint da imagem executa /docker-entrypoint-initdb.d/*.sql em ordem
-- alfabetica, como o superuser $POSTGRES_USER (nora_admin), conectado em
-- $POSTGRES_DB (nora), com ON_ERROR_STOP ligado -- qualquer erro aqui aborta
-- o initdb e o container NAO fica healthy. Ler ./README.md antes de mexer.
--
-- O banco `nora` em si NAO e criado aqui: vem de POSTGRES_DB no compose. O
-- `nora_platform` roda em OUTRO container, que nao monta este diretorio.
--
-- ## O que este script faz (e o que deixa para depois)
--
--   1. Cria as extensoes pgcrypto e citext. Sao as duas -- e SO as duas --
--      que as 26 migrations usam (V001 gen_random_uuid, V002 CITEXT).
--      NAO cria `vector`: a V021 evitou pgvector de proposito por causa da
--      allow-list do Azure, guarda embedding como JSON em coluna TEXT e
--      computa cosseno em Java. A imagem pgvector/pgvector:pg16 apenas DEIXA
--      a extensao disponivel; criar aqui nao ligaria nada e daria a impressao
--      falsa de que o RAG usa indice vetorial. Isso e refactor de RAG, nao
--      migracao de infra.
--   2. Cria o schema `nora` (a V016 tambem cria, com IF NOT EXISTS) para que
--      o GRANT USAGE possa ser feito AGORA, antes de existir migration.
--   3. Provisiona os DOIS roles nomeados do ADR 0026/0028, de forma
--      idempotente. O terceiro role e o proprio admin/owner ($POSTGRES_USER),
--      que o initdb ja criou -- e quem roda o Flyway e e dono das tabelas.
--   4. Concede USAGE + DEFAULT PRIVILEGES, que sao o que realmente importa
--      neste ponto do tempo: quando o Flyway (rodando como admin) criar as
--      tabelas, elas ja nascem com GRANT para nora_app.
--
-- ## Diferencas em relacao ao R001 (leia antes de comparar os dois)
--
--   a) O R001 roda DEPOIS das migrations, num banco populado, e por isso faz
--      GRANT ... ON ALL TABLES. Aqui nao existe UMA tabela ainda: os GRANTs
--      em ALL TABLES sao no-op no primeiro boot e so tem efeito se alguem
--      re-rodar este arquivo a mao (ele continua idempotente para isso).
--      Quem carrega o peso e o ALTER DEFAULT PRIVILEGES do item 5.
--   b) O GRANT EXECUTE em nora.current_tenant_id() vira ALTER DEFAULT
--      PRIVILEGES ... ON FUNCTIONS, porque a funcao so nasce na V016.
--   c) O GRANT SELECT em meeting_analyses (nora_telemetry) e CONDICIONAL --
--      a tabela vem na V005. Ver o aviso grande na secao 4: e esse GRANT que
--      fica pendente, e a falha dele e SILENCIOSA.
--   d) As senhas vem de variavel de AMBIENTE (\getenv), nao de `psql -v`,
--      porque o entrypoint do container nao aceita passar -v.
--
-- ## Senhas
--
-- Le NORA_APP_PASSWORD e NORA_TELEMETRY_PASSWORD do ambiente do processo psql
-- (mesmos nomes do scripts/rls-cutover.sh; RLS_TELEMETRY_PASSWORD e aceito
-- como nome legado, era o GitHub Secret do cutover no Azure). O compose NAO
-- injeta essas variaveis no container `postgres` -- por padrao os roles sao
-- criados SEM senha, o que e fail-closed: com scram-sha-256 no pg_hba, role
-- sem senha simplesmente nao autentica. Ninguem fica exposto; o que fica
-- pendente e o cutover.
--
-- O caminho SUPORTADO de fechar isso e o scripts/rls-cutover.sh, que roda o
-- R001 completo (com psql -v) num banco ja migrado. Este arquivo so adianta o
-- que da para adiantar no initdb.
-- =============================================================================

\set ON_ERROR_STOP on

\echo '=== NORA initdb: extensoes + roles de RLS (ADR 0026/0028) ==='

-- Default vazio ANTES do \getenv: se a variavel de ambiente nao existir, a
-- variavel do psql continua sendo string vazia em vez de ficar indefinida.
-- RLS_TELEMETRY_PASSWORD e lido primeiro e NORA_TELEMETRY_PASSWORD depois, de
-- modo que o nome canonico (o do scripts/rls-cutover.sh) vence quando os dois
-- estiverem no ambiente.
\set app_password ''
\set telemetry_password ''
\getenv app_password NORA_APP_PASSWORD
\getenv telemetry_password RLS_TELEMETRY_PASSWORD
\getenv telemetry_password NORA_TELEMETRY_PASSWORD

SELECT :'app_password'       <> '' AS have_app_password,
       :'telemetry_password' <> '' AS have_telemetry_password
\gset


-- ============================================================
-- 1. Extensoes -- as duas que as 26 migrations usam, nada alem disso.
-- ============================================================
-- Idempotentes e redundantes de proposito: a V001/V002 tambem as criam com
-- IF NOT EXISTS. Cria-las aqui garante que existam mesmo se alguem rodar um
-- restore de dump logico antes do Flyway, e deixa explicito o inventario.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- V001: gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "citext";     -- V002: users.email CITEXT

-- NAO criar `vector`. Ver item 1 do cabecalho.


-- ============================================================
-- 2. Schema `nora` -- home de nora.current_tenant_id() (V016).
-- ============================================================
-- Criado aqui pelo admin, que e o mesmo role que o Flyway usa sob o ADR 0028,
-- entao o owner fica correto e a V016 (CREATE SCHEMA IF NOT EXISTS) vira no-op.
CREATE SCHEMA IF NOT EXISTS nora;


-- ============================================================
-- 3. Os roles nomeados
-- ============================================================
-- Terceiro role do trio: o admin/owner, ja criado pelo initdb a partir de
-- POSTGRES_USER/POSTGRES_PASSWORD. E ele que roda Flyway/DDL e e dono das
-- tabelas. Nao mexer nele aqui.

-- ---- nora_app: runtime da API. NOBYPASSRLS e o ponto inteiro do enforce. ----
-- Cria so quando falta (0 linhas no SELECT => \gexec e no-op).
SELECT 'CREATE ROLE nora_app LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_app')
\gexec
-- Reafirma as flags a cada execucao. Os demais atributos ficam no default do
-- Postgres (NOSUPERUSER, NOCREATEDB, NOCREATEROLE) -- e assim tem que ser.
ALTER ROLE nora_app WITH LOGIN NOBYPASSRLS;

\if :have_app_password
ALTER ROLE nora_app WITH PASSWORD :'app_password';
\echo '  nora_app: senha definida a partir de NORA_APP_PASSWORD'
\else
\echo '  nora_app: SEM SENHA (NORA_APP_PASSWORD ausente no ambiente).'
\echo '            Role criado e inerte -- nao autentica ate um ALTER ROLE.'
\endif

-- ---- nora_telemetry: leitura agregada cross-tenant do painel do operador ----
-- BYPASSRLS: IGNORA as policies de proposito (COUNT/COUNT DISTINCT em
-- meeting_analyses, ver PrimaryDbBusinessMetricsSource). NUNCA usado no
-- caminho de request normal.
SELECT 'CREATE ROLE nora_telemetry LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_telemetry')
\gexec
ALTER ROLE nora_telemetry WITH LOGIN BYPASSRLS;

\if :have_telemetry_password
ALTER ROLE nora_telemetry WITH PASSWORD :'telemetry_password';
\echo '  nora_telemetry: senha definida (RLS_TELEMETRY_PASSWORD/NORA_TELEMETRY_PASSWORD)'
\else
\echo '  nora_telemetry: SEM SENHA (nem RLS_TELEMETRY_PASSWORD nem'
\echo '                  NORA_TELEMETRY_PASSWORD no ambiente do container).'
\echo '                  Role criado e inerte -- nao autentica ate um ALTER ROLE.'
\endif


-- ============================================================
-- 4. GRANTs
-- ============================================================
-- CONNECT ja e concedido a PUBLIC por default; explicito aqui para o script
-- continuar correto se alguem endurecer o banco com um REVOKE ... FROM PUBLIC.
GRANT CONNECT ON DATABASE nora TO nora_app;
GRANT CONNECT ON DATABASE nora TO nora_telemetry;

-- ---- nora_app ----
-- Sob o ADR 0028 o Flyway roda como ADMIN, entao as tabelas (inclusive a
-- flyway_schema_history) sao OWNED pelo admin: o nora_app e nao-owner,
-- NOBYPASSRLS, e depende destes grants para enxergar qualquer coisa.
GRANT USAGE ON SCHEMA public TO nora_app;
GRANT USAGE ON SCHEMA nora   TO nora_app;  -- precisa enxergar nora.current_tenant_id()

-- No-op no primeiro boot (nao ha tabela); util se este arquivo for re-rodado
-- a mao num banco ja migrado.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES    IN SCHEMA public TO nora_app;
GRANT USAGE, SELECT                  ON ALL SEQUENCES IN SCHEMA public TO nora_app;
GRANT EXECUTE                        ON ALL FUNCTIONS IN SCHEMA nora   TO nora_app;

-- ---- nora_telemetry ----
GRANT USAGE ON SCHEMA public TO nora_telemetry;

-- #########################################################################
-- # ATENCAO -- unico grant que NAO da para fechar no initdb.              #
-- #                                                                       #
-- # meeting_analyses nasce na V005, ou seja, depois que este script roda. #
-- # O bloco abaixo concede se a tabela existir (re-execucao manual) e     #
-- # AVISA se nao existir. E deliberadamente um SELECT em UMA tabela, e    #
-- # nao um ALTER DEFAULT PRIVILEGES: o nora_telemetry tem BYPASSRLS, um   #
-- # grant amplo daria a ele leitura cross-tenant de TUDO.                 #
-- #                                                                       #
-- # Se este grant nunca for feito, o painel de negocio do operador nao    #
-- # da erro: ele retorna ZERO. Falha silenciosa. Rodar depois do primeiro #
-- # `flyway migrate` (o R001 completo tambem fecha isso):                 #
-- #                                                                       #
-- #   docker compose exec -T postgres psql -U nora_admin -d nora \        #
-- #     -c "GRANT SELECT ON meeting_analyses TO nora_telemetry"           #
-- #########################################################################
DO $$
BEGIN
    IF to_regclass('public.meeting_analyses') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON public.meeting_analyses TO nora_telemetry';
        RAISE NOTICE 'nora_telemetry: GRANT SELECT em meeting_analyses aplicado.';
    ELSE
        RAISE WARNING 'nora_telemetry: meeting_analyses ainda nao existe (vem na V005). '
                      'GRANT SELECT PENDENTE -- sem ele o painel de negocio retorna zero '
                      'EM SILENCIO. Rodar apos as migrations.';
    END IF;
END
$$;


-- ============================================================
-- 5. DEFAULT PRIVILEGES -- e isto que faz o script funcionar no initdb.
-- ============================================================
-- ALTER DEFAULT PRIVILEGES e por-role-CRIADOR. Este script roda como o admin,
-- que e o MESMO role que o Flyway usa sob o ADR 0028 -- entao toda tabela,
-- sequence e funcao criada pelas 26 migrations ja nasce com grant para o
-- nora_app, sem precisar de um GRANT manual por migration nova.
--
-- Corolario: se um dia o Flyway passar a rodar com OUTRO role, estas linhas
-- param de cobrir as tabelas novas e o sintoma sera "permission denied for
-- table X" so na primeira query da feature nova.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nora_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO nora_app;
-- Funcoes ja sao EXECUTE para PUBLIC por default; explicito para o caso de o
-- schema ser endurecido depois.
ALTER DEFAULT PRIVILEGES IN SCHEMA nora
    GRANT EXECUTE ON FUNCTIONS TO nora_app;

-- Defesa adicional: se algum dia o nora_app criar objetos (ele NAO cria sob o
-- 0028), eles ja herdam grants. Inofensivo hoje; explicito para evitar
-- surpresa em evolucao do schema.
ALTER DEFAULT PRIVILEGES FOR ROLE nora_app IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nora_app;


-- ============================================================
-- 6. Verificacao -- sai no log do container (`docker compose logs postgres`).
-- ============================================================
\echo '--- roles provisionados ---'
SELECT rolname,
       rolcanlogin  AS login,
       rolbypassrls AS bypassrls,
       rolsuper     AS superuser,
       (rolpassword IS NOT NULL) AS tem_senha
FROM pg_authid
WHERE rolname IN ('nora_app', 'nora_telemetry', current_user)
ORDER BY rolname;

\echo '--- extensoes ---'
SELECT extname FROM pg_extension ORDER BY extname;

\echo '=== NORA initdb: concluido. Proximo passo: Flyway (a API roda no boot). ==='
