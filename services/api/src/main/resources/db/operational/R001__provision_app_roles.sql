-- R001 — Provisioning of the application roles for RLS enforce (ADR 0002, ADR 0019, ADR 0026).
--
-- WARNING: this script is NOT an application Flyway migration.
-- It creates/adjusts ROLES and DEFAULT PRIVILEGES — operations that require Postgres
-- ADMIN privilege (the database owner / postgresAdminLogin), NOT the nora_app role.
-- The application runs Flyway as nora_app and cannot (and must not) run this script.
--
-- ## When to run it
--
-- Manually (or via the `rls-cutover.yml` workflow with the admin credential), ONCE
-- per environment, BEFORE turning on nora.security.rls.enforce=true (see the cutover sequence
-- in ADR 0028, which fixes 0026). It is idempotent: it can be run again with no side effect.
--
-- ## How to run it (example)
--
--   psql "<connection string as ADMIN>" \
--     -v app_password="<strong-password-for-nora_app>" \
--     -v telemetry_password="<strong-password-for-nora_telemetry>" \
--     -f R001__provision_app_roles.sql
--
--   IMPORTANT: pass the passwords RAW (no surrounding quotes). The DO blocks use
--   :'app_password' (psql quoted-variable), which already turns the value into an SQL literal
--   with correct escaping. Passing it quoted (-v app_password="'...'") makes the role's
--   password literally 'password' (WITH the quotes) and the API does not connect.
--
-- Afterwards, point the API's DATASOURCE_USERNAME/PASSWORD at nora_app and set
-- NORA_RLS_ENFORCE=true via the bicepparam flip (see Bicep + ADR 0028).
--
-- ## What this script does
--
--   1. Creates role nora_app (LOGIN, NOBYPASSRLS) — the API's runtime role.
--   2. Creates role nora_telemetry (LOGIN, BYPASSRLS) — operator-only cross-tenant
--      read of the business telemetry (see PrimaryDbBusinessMetricsSource).
--   3. GRANT SELECT/INSERT/UPDATE/DELETE on every tenant-owned table (and the other
--      tables of the public schema, including flyway_schema_history) to nora_app.
--   4. GRANT SELECT (read only) to nora_telemetry on the aggregation tables.
--   5. ALTER DEFAULT PRIVILEGES so that FUTURE tables (upcoming migrations)
--      inherit the grants automatically — otherwise every new table would require a
--      manual grant before the API could see it.
--
-- Every psql -f statement autocommits. The idempotent creation uses \gexec (creates the
-- role only when missing) + a top-level ALTER ROLE for the password/flag.
--
-- IMPORTANT: the password CANNOT be interpolated inside a DO $$..$$ block — psql does
-- NOT substitute :'app_password' inside dollar-quotes (it becomes the literal ":'app_password'"
-- -> syntax error). That is why ALTER ROLE ... PASSWORD :'app_password' stays top-level,
-- where psql turns the variable into a correctly quoted/escaped SQL literal.

-- ============================================================
-- 1. The API's runtime role: nora_app (NOBYPASSRLS => RLS actually applies).
-- ============================================================
-- Creates it only when missing (SELECT of the command -> \gexec runs what came back; 0 rows = no-op).
SELECT 'CREATE ROLE nora_app LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_app')
\gexec
-- Guarantees LOGIN + password + NOBYPASSRLS (the whole point of enforce). Idempotent.
ALTER ROLE nora_app WITH LOGIN PASSWORD :'app_password' NOBYPASSRLS;

-- ============================================================
-- 2. Operator-only telemetry role: nora_telemetry (BYPASSRLS => reads cross-tenant).
--    Used ONLY by the business metrics aggregation (COUNT/COUNT DISTINCT on
--    meeting_analyses). NEVER used by the normal request path. See ADR 0026.
-- ============================================================
SELECT 'CREATE ROLE nora_telemetry LOGIN'
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'nora_telemetry')
\gexec
-- BYPASSRLS: this role IGNORES the policies (intentional cross-tenant aggregate read).
ALTER ROLE nora_telemetry WITH LOGIN PASSWORD :'telemetry_password' BYPASSRLS;

-- ============================================================
-- 3. GRANTs to nora_app — every tenant-owned table + public schema infra.
--    Under ADR 0028 Flyway runs as ADMIN (nora_admin), so the tables (including
--    flyway_schema_history) are OWNED by the admin — nora_app (non-owner, NOBYPASSRLS)
--    needs these explicit GRANTs to see them, and is subject to RLS.
-- ============================================================
GRANT USAGE ON SCHEMA public TO nora_app;
GRANT USAGE ON SCHEMA nora   TO nora_app;  -- needs to see nora.current_tenant_id()
GRANT EXECUTE ON FUNCTION nora.current_tenant_id() TO nora_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO nora_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO nora_app;

-- ============================================================
-- 4. GRANTs to nora_telemetry — read only, restricted to what is needed.
--    Today the telemetry only touches meeting_analyses; keep it minimal (least privilege).
--    If the telemetry starts reading other tables, add explicit grants here.
-- ============================================================
GRANT USAGE ON SCHEMA public TO nora_telemetry;
GRANT SELECT ON meeting_analyses TO nora_telemetry;

-- ============================================================
-- 5. DEFAULT PRIVILEGES — FUTURE tables/sequences created by the owner (the admin that
--    runs the migrations) are born with a grant for nora_app. Without this, every new
--    migration would require a manual GRANT before the API could see the table.
--    Note: ALTER DEFAULT PRIVILEGES is per-CREATOR-role of the object. Under ADR 0028
--    Flyway runs as ADMIN (nora_admin) — the SAME role that runs this R001 — so the
--    ALTER DEFAULT PRIVILEGES without FOR ROLE below (executed by the admin) already covers
--    the tables of the upcoming migrations. That is why it is CRITICAL to run R001 as nora_admin
--    (the same one from Flyway's connection string), not as another superuser.
-- ============================================================
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nora_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO nora_app;

-- Extra defense: if nora_app ever creates objects (it does NOT under 0028,
-- where Flyway is admin), they already inherit grants. Harmless today; explicit to
-- avoid a surprise as the schema evolves.
ALTER DEFAULT PRIVILEGES FOR ROLE nora_app IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO nora_app;
