-- V021 — Meeting embeddings for RAG (semantic search in the chat).
--
-- Stores ONE embedding per meeting (vector of the summary/title), as a JSON of floats in TEXT.
-- Scale decision: similarity (cosine) is computed in Java over the tenant's embeddings —
-- adequate for tens/hundreds of meetings per tenant without the pgvector
-- dependency (which would require extension allow-listing on Azure). pgvector is the future
-- optimization when volume justifies it (ANN index). See chat RAG / ADR 0004 (provider-agnostic).
--
-- `model` records which model/provider generated the vector: search only compares vectors from the
-- SAME space (same provider+model). Switching provider requires a re-backfill.

CREATE TABLE meeting_embeddings (
    meeting_id   UUID PRIMARY KEY REFERENCES meetings(id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    model        TEXT NOT NULL,
    dim          INT  NOT NULL,
    embedding    TEXT NOT NULL, -- JSON array of floats
    source_chars INT  NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX idx_meeting_embeddings_tenant ON meeting_embeddings (tenant_id);

-- RLS: tenant-owned business table → enforced (ADR 0028). Under enforce, nora_app
-- (NOBYPASSRLS) only sees the rows of the session GUC. Same pattern as V019.
-- nora_app gets the grants automatically via the ALTER DEFAULT PRIVILEGES in R001
-- (the table is created by Flyway-as-admin = nora_admin).
ALTER TABLE meeting_embeddings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_embeddings
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
