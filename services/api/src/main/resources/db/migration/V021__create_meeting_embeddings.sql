-- V021 — Embeddings de reunião para RAG (busca semântica no chat).
--
-- Guarda UM embedding por meeting (vetor do resumo/título), como JSON de floats em TEXT.
-- Decisão de escala: a similaridade (cosseno) é computada em Java sobre os embeddings do
-- tenant — adequado pra dezenas/centenas de reuniões por tenant sem a dependência do
-- pgvector (que exigiria allow-list de extensão no Azure). pgvector é a otimização futura
-- quando o volume justificar (índice ANN). Ver chat RAG / ADR 0004 (provider-agnóstico).
--
-- `model` registra qual modelo/provider gerou o vetor: a busca só compara vetores do MESMO
-- espaço (mesmo provider+modelo). Trocar de provider exige re-backfill.

CREATE TABLE meeting_embeddings (
    meeting_id   UUID PRIMARY KEY REFERENCES meetings(id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    model        TEXT NOT NULL,
    dim          INT  NOT NULL,
    embedding    TEXT NOT NULL, -- JSON array de floats
    source_chars INT  NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_meeting_embeddings_tenant ON meeting_embeddings (tenant_id);

-- RLS: tabela de negócio tenant-owned → enforced (ADR 0028). Sob enforce, o nora_app
-- (NOBYPASSRLS) só enxerga as linhas do GUC de sessão. Mesmo padrão da V019.
-- nora_app recebe os grants automaticamente via ALTER DEFAULT PRIVILEGES do R001
-- (a tabela é criada pelo Flyway-as-admin = nora_admin).
ALTER TABLE meeting_embeddings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_embeddings
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
