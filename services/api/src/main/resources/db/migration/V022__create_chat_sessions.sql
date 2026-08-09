-- V022 — Persistent chat sessions (NORA assistant history).
--
-- Stores the chat conversation history per user, scoped by tenant. Each session
-- (chat_session) has N messages (chat_message) in chronological order. The title is derived
-- from the user's 1st message (~48 chars) when not given explicitly.
--
-- Scope: tenant-owned (ADR 0002 — tenant_id on every table) + user-scoped (each user only
-- sees their own sessions; the user_id filter is applied in the app). RLS enabled
-- (ADR 0028) in the same pattern as V019/V021: under enforce, nora_app (NOBYPASSRLS) only sees the
-- rows of the session GUC `nora.current_tenant_id`. nora_app gets the grants via the
-- ALTER DEFAULT PRIVILEGES in R001 (the table is created by Flyway-as-admin = nora_admin).

CREATE TABLE chat_session (
    id         UUID PRIMARY KEY,
    tenant_id  UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    user_id    UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title      TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE chat_message (
    id         UUID PRIMARY KEY,
    session_id UUID NOT NULL REFERENCES chat_session(id) ON DELETE CASCADE,
    tenant_id  UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    role       TEXT NOT NULL CHECK (role IN ('user', 'assistant')),
    content    TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Sidebar listing: the user's sessions, most recent first.
CREATE INDEX idx_chat_session_tenant ON chat_session (tenant_id);
CREATE INDEX idx_chat_session_user ON chat_session (user_id);
CREATE INDEX idx_chat_session_user_updated ON chat_session (user_id, updated_at DESC);

-- Loading the detail + last snippet per session.
CREATE INDEX idx_chat_message_tenant ON chat_message (tenant_id);
CREATE INDEX idx_chat_message_session_created ON chat_message (session_id, created_at);

-- RLS: tenant-owned business tables → enforced (ADR 0028). Same pattern as V019/V021.
ALTER TABLE chat_session ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON chat_session
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE chat_message ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON chat_message
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
