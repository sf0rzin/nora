-- V025 — Libera os provedores OAuth da onda 1 (GitHub, Notion, Todoist, Linear) no CHECK de
-- integration_connections.provider (V024 fixava google/slack).
--
-- Constraint nomeada pelo Postgres na criação inline (V024): integration_connections_provider_check.
-- Sem mudança de RLS/índices — só a lista de valores aceitos.

ALTER TABLE integration_connections
    DROP CONSTRAINT integration_connections_provider_check;

ALTER TABLE integration_connections
    ADD CONSTRAINT integration_connections_provider_check
    CHECK (provider IN ('google', 'slack', 'github', 'notion', 'todoist', 'linear'));
