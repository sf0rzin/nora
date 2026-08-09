-- V025 — Allows the wave 1 OAuth providers (GitHub, Notion, Todoist, Linear) in the CHECK on
-- integration_connections.provider (V024 pinned google/slack).
--
-- Constraint named by Postgres on inline creation (V024): integration_connections_provider_check.
-- No RLS/index change — only the list of accepted values.

ALTER TABLE integration_connections
    DROP CONSTRAINT integration_connections_provider_check;

ALTER TABLE integration_connections
    ADD CONSTRAINT integration_connections_provider_check
    CHECK (provider IN ('google', 'slack', 'github', 'notion', 'todoist', 'linear'));
