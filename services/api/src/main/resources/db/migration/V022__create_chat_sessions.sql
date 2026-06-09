-- V022 — Sessões de chat persistentes (histórico do assistente NORA).
--
-- Guarda o histórico de conversas do chat por usuário, escopado por tenant. Cada sessão
-- (chat_session) tem N mensagens (chat_message) na ordem cronológica. O título é derivado
-- da 1ª mensagem do usuário (~48 chars) quando não informado explicitamente.
--
-- Escopo: tenant-owned (ADR 0002 — tenant_id em toda tabela) + user-scoped (cada usuário só
-- enxerga as próprias sessões; o filtro por user_id é aplicado na aplicação). RLS habilitado
-- (ADR 0028) no mesmo padrão de V019/V021: sob enforce, o nora_app (NOBYPASSRLS) só vê as
-- linhas do GUC de sessão `nora.current_tenant_id`. nora_app recebe os grants via
-- ALTER DEFAULT PRIVILEGES do R001 (a tabela é criada pelo Flyway-as-admin = nora_admin).

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

-- Listagem da sidebar: sessões do usuário, mais recentes primeiro.
CREATE INDEX idx_chat_session_tenant ON chat_session (tenant_id);
CREATE INDEX idx_chat_session_user ON chat_session (user_id);
CREATE INDEX idx_chat_session_user_updated ON chat_session (user_id, updated_at DESC);

-- Carregamento do detalhe + último snippet por sessão.
CREATE INDEX idx_chat_message_tenant ON chat_message (tenant_id);
CREATE INDEX idx_chat_message_session_created ON chat_message (session_id, created_at);

-- RLS: tabelas de negócio tenant-owned → enforced (ADR 0028). Mesmo padrão de V019/V021.
ALTER TABLE chat_session ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON chat_session
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE chat_message ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON chat_message
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
