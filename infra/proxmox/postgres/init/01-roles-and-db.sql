-- =============================================================================
-- NORA -- primary Postgres initdb: extensions + the THREE RLS roles.
-- =============================================================================
--
-- Unaccented style, same as the R001 this script is based on
-- (services/api/src/main/resources/db/operational/R001__provision_app_roles.sql).
--
-- ## When it runs
--
-- ONLY on the FIRST boot of the `postgres` container, with the `pgdata` volume
-- EMPTY. The image entrypoint runs /docker-entrypoint-initdb.d/*.sql in
-- alphabetical order, as the superuser $POSTGRES_USER (nora_admin), connected to
-- $POSTGRES_DB (nora), with ON_ERROR_STOP on -- any error here aborts
-- the initdb and the container does NOT become healthy. Read ./README.md first.
--
-- The `nora` database itself is NOT created here: it comes from POSTGRES_DB in
-- the compose. `nora_platform` runs in ANOTHER container, which does not mount
-- this directory.
--
-- ## What this script does (and what it leaves for later)
--
--   1. Creates the pgcrypto and citext extensions. They are the two -- and ONLY
--      the two -- that the 26 migrations use (V001 gen_random_uuid, V002 CITEXT).
--      Does NOT create `vector`: V021 avoided pgvector on purpose because of the
--      Azure allow-list, stores the embedding as JSON in a TEXT column and
--      computes cosine in Java. The pgvector/pgvector:pg16 image only MAKES the
--      extension available; creating it here would wire up nothing and would give
--      the false impression that RAG uses a vector index. That is a RAG refactor,
--      not an infra migration.
--   2. Creates the `nora` schema (V016 also creates it, with IF NOT EXISTS) so
--      that the GRANT USAGE can be done NOW, before any migration exists.
--   3. Provisions the TWO named roles of ADR 0026/0028, idempotently. The third
--      role is the admin/owner itself ($POSTGRES_USER), which initdb already
--      created -- it is the one that runs Flyway and owns the tables.
--   4. Grants USAGE + DEFAULT PRIVILEGES, which are what actually matters at
--      this point in time: when Flyway (running as admin) creates the tables,
--      they are already born with a GRANT to nora_app.
--
-- ## Differences from R001 (read before comparing the two)
--
--   a) R001 runs AFTER the migrations, on a populated database, and therefore
--      does GRANT ... ON ALL TABLES. Here not ONE table exists yet: the GRANTs
--      on ALL TABLES are a no-op on the first boot and only take effect if
--      someone re-runs this file by hand (it stays idempotent for that).
--      What carries the weight is the ALTER DEFAULT PRIVILEGES of item 5.
--   b) The GRANT EXECUTE on nora.current_tenant_id() becomes ALTER DEFAULT
--      PRIVILEGES ... ON FUNCTIONS, because the function is only born in V016.
--   c) The GRANT SELECT on meeting_analyses (nora_telemetry) is CONDITIONAL --
--      the table comes in V005. See the big warning in section 4: it is that
--      GRANT that stays pending, and its failure is SILENT.
--   d) The passwords come from an ENVIRONMENT variable (\getenv), not from
--      `psql -v`, because the container entrypoint does not accept passing -v.
--
-- ## Passwords
--
-- Reads NORA_APP_PASSWORD and NORA_TELEMETRY_PASSWORD from the psql process
-- environment (same names as scripts/rls-cutover.sh; RLS_TELEMETRY_PASSWORD is
-- accepted as a legacy name, it was the GitHub Secret of the cutover on Azure).
-- The compose does NOT inject these variables into the `postgres` container --
-- by default the roles are created WITHOUT a password, which is fail-closed:
-- with scram-sha-256 in pg_hba, a role without a password simply does not
-- authenticate. Nobody is exposed; what stays pending is the cutover.
--
-- The SUPPORTED path to close this is scripts/rls-cutover.sh, which runs the
-- full R001 (with psql -v) on an already migrated database. This file only gets
-- ahead of what can be gotten ahead of in the initdb.
-- =============================================================================

\set ON_ERROR_STOP on

\echo '=== NORA initdb: extensions + RLS roles (ADR 0026/0028) ==='

-- Empty default BEFORE the \getenv: if the environment variable does not exist,
-- the psql variable stays an empty string instead of becoming undefined.
-- RLS_TELEMETRY_PASSWORD is read first and NORA_TELEMETRY_PASSWORD after, so
-- that the canonical name (the one from scripts/rls-cutover.sh) wins when both
-- are in the environment.
\set app_password ''
\set telemetry_password ''
\getenv app_password NORA_APP_PASSWORD
\getenv telemetry_password RLS_TELEMETRY_PASSWORD
\getenv telemetry_password NORA_TELEMETRY_PASSWORD

SELECT :'app_password'       <> '' AS have_app_password,
       :'telemetry_password' <> '' AS have_telemetry_password
\gset


-- ============================================================
-- 1. Extensions -- the two that the 26 migrations use, nothing beyond that.
-- ============================================================
-- Idempotent and redundant on purpose: V001/V002 also create them with
-- IF NOT EXISTS. Creating them here guarantees they exist even if someone runs a
-- logical dump restore before Flyway, and makes the inventory explicit.
CREATE EXTENSION IF NOT EXISTS "pgcrypto";   -- V001: gen_random_uuid()
CREATE EXTENSION IF NOT EXISTS "citext";     -- V002: users.email CITEXT

-- Do NOT create `vector`. See item 1 of the header.


-- ============================================================
-- 2. Schema `nora` -- home of nora.current_tenant_id() (V016).
-- ============================================================
-- Created here by the admin, which is the same role Flyway uses under ADR 0028,
-- so the owner is correct and V016 (CREATE SCHEMA IF NOT EXISTS) becomes a no-op.
CREATE SCHEMA IF NOT EXISTS nora;


-- ============================================================
-- 3. The named roles
-- ============================================================
-- Third role of the trio: the admin/owner, already created by initdb from
-- POSTGRES_USER/POSTGRES_PASSWORD. It is the one that runs Flyway/DDL and owns
-- the tables. Do not touch it here.

-- ---- nora_app: API runtime. NOBYPASSRLS is the whole point of the enforce. ----
-- Create only when missing (0 rows in the SELECT => \gexec is a no-op).
SELECT 'CREATE ROLE nora_app LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_app')
\gexec
-- Reasserts the flags on every run. The remaining attributes stay at the
-- Postgres default (NOSUPERUSER, NOCREATEDB, NOCREATEROLE) -- and must stay so.
ALTER ROLE nora_app WITH LOGIN NOBYPASSRLS;

\if :have_app_password
ALTER ROLE nora_app WITH PASSWORD :'app_password';
\echo '  nora_app: password set from NORA_APP_PASSWORD'
\else
\echo '  nora_app: NO PASSWORD (NORA_APP_PASSWORD absent from the environment).'
\echo '            Role created and inert -- does not authenticate until an ALTER ROLE.'
\endif

-- ---- nora_telemetry: cross-tenant aggregate reads for the operator panel ----
-- BYPASSRLS: IGNORES the policies on purpose (COUNT/COUNT DISTINCT on
-- meeting_analyses, see PrimaryDbBusinessMetricsSource). NEVER used on the
-- normal request path.
SELECT 'CREATE ROLE nora_telemetry LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_telemetry')
\gexec
ALTER ROLE nora_telemetry WITH LOGIN BYPASSRLS;

\if :have_telemetry_password
ALTER ROLE nora_telemetry WITH PASSWORD :'telemetry_password';
\echo '  nora_telemetry: password set (RLS_TELEMETRY_PASSWORD/NORA_TELEMETRY_PASSWORD)'
\else
\echo '  nora_telemetry: NO PASSWORD (neither RLS_TELEMETRY_PASSWORD nor'
\echo '                  NORA_TELEMETRY_PASSWORD in the container environment).'
\echo '                  Role created and inert -- does not authenticate until an ALTER ROLE.'
\endif


-- ============================================================
-- 4. GRANTs
-- ============================================================
-- CONNECT is already granted to PUBLIC by default; explicit here so the script
-- stays correct if someone hardens the database with a REVOKE ... FROM PUBLIC.
GRANT CONNECT ON DATABASE nora TO nora_app;
GRANT CONNECT ON DATABASE nora TO nora_telemetry;

-- ---- nora_app ----
-- Under ADR 0028 Flyway runs as ADMIN, so the tables (including
-- flyway_schema_history) are OWNED by the admin: nora_app is a non-owner,
-- NOBYPASSRLS, and depends on these grants to see anything at all.
GRANT USAGE ON SCHEMA public TO nora_app;
GRANT USAGE ON SCHEMA nora   TO nora_app;  -- needs to see nora.current_tenant_id()

-- No-op on the first boot (there is no table); useful if this file is re-run
-- by hand on an already migrated database.
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES    IN SCHEMA public TO nora_app;
GRANT USAGE, SELECT                  ON ALL SEQUENCES IN SCHEMA public TO nora_app;
GRANT EXECUTE                        ON ALL FUNCTIONS IN SCHEMA nora   TO nora_app;

-- ---- nora_telemetry ----
GRANT USAGE ON SCHEMA public TO nora_telemetry;

-- #########################################################################
-- # WARNING -- the only grant that cannot be closed in the initdb.        #
-- #                                                                       #
-- # meeting_analyses is born in V005, that is, after this script runs.    #
-- # The block below grants if the table exists (manual re-run) and        #
-- # WARNS if it does not. It is deliberately a SELECT on ONE table, and   #
-- # not an ALTER DEFAULT PRIVILEGES: nora_telemetry has BYPASSRLS, a      #
-- # broad grant would give it cross-tenant reads of EVERYTHING.           #
-- #                                                                       #
-- # If this grant is never done, the operator business panel does not     #
-- # error: it returns ZERO. Silent failure. Run after the first           #
-- # `flyway migrate` (the full R001 also closes this):                    #
-- #                                                                       #
-- #   docker compose exec -T postgres psql -U nora_admin -d nora \        #
-- #     -c "GRANT SELECT ON meeting_analyses TO nora_telemetry"           #
-- #########################################################################
DO $$
BEGIN
    IF to_regclass('public.meeting_analyses') IS NOT NULL THEN
        EXECUTE 'GRANT SELECT ON public.meeting_analyses TO nora_telemetry';
        RAISE NOTICE 'nora_telemetry: GRANT SELECT on meeting_analyses applied.';
    ELSE
        RAISE WARNING 'nora_telemetry: meeting_analyses does not exist yet (it comes in V005). '
                      'GRANT SELECT PENDING -- without it the business panel returns zero '
                      'SILENTLY. Run after the migrations.';
    END IF;
END
$$;


-- ============================================================
-- 5. DEFAULT PRIVILEGES -- this is what makes the script work in the initdb.
-- ============================================================
-- ALTER DEFAULT PRIVILEGES is per-CREATOR-role. This script runs as the admin,
-- which is the SAME role Flyway uses under ADR 0028 -- so every table, sequence
-- and function created by the 26 migrations is already born with a grant to
-- nora_app, without needing a manual GRANT per new migration.
--
-- Corollary: if one day Flyway starts running with ANOTHER role, these lines
-- stop covering the new tables and the symptom will be "permission denied for
-- table X" only on the first query of the new feature.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nora_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO nora_app;
-- Functions are already EXECUTE to PUBLIC by default; explicit in case the
-- schema is hardened later.
ALTER DEFAULT PRIVILEGES IN SCHEMA nora
    GRANT EXECUTE ON FUNCTIONS TO nora_app;

-- Extra defense: if nora_app ever creates objects (it does NOT create under
-- 0028), they already inherit grants. Harmless today; explicit to avoid a
-- surprise as the schema evolves.
ALTER DEFAULT PRIVILEGES FOR ROLE nora_app IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nora_app;


-- ============================================================
-- 6. Verification -- goes to the container log (`docker compose logs postgres`).
-- ============================================================
\echo '--- provisioned roles ---'
SELECT rolname,
       rolcanlogin  AS login,
       rolbypassrls AS bypassrls,
       rolsuper     AS superuser,
       (rolpassword IS NOT NULL) AS tem_senha
FROM pg_authid
WHERE rolname IN ('nora_app', 'nora_telemetry', current_user)
ORDER BY rolname;

\echo '--- extensions ---'
SELECT extname FROM pg_extension ORDER BY extname;

\echo '=== NORA initdb: complete. Next step: Flyway (the API runs at boot). ==='
