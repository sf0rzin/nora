-- V023 — NORA Flows: workflows (automações) + histórico de execuções.
--
-- Um workflow liga um GATILHO (evento de domínio, ex.: meeting.analysis_completed) a AÇÕES
-- (ex.: send_email), opcionalmente filtradas por CONDIÇÕES. O grafo completo (nós + arestas,
-- como desenhado no canvas) vive em definition_json; trigger_type é denormalizado para o
-- match O(1) do engine (índice parcial por tenant + trigger em workflows ativos).
--
-- workflow_executions guarda cada disparo (real ou teste manual) com o log passo a passo
-- (log_json: array de {at, nodeId, level, message}) — é o histórico que a UI mostra.
--
-- Escopo: ambas tenant-owned (ADR 0002) com RLS enforced (ADR 0028), mesmo padrão de V022.

CREATE TABLE workflows (
    id              UUID PRIMARY KEY,
    tenant_id       UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name            TEXT NOT NULL,
    trigger_type    TEXT NOT NULL,
    definition_json JSONB NOT NULL,
    active          BOOLEAN NOT NULL DEFAULT TRUE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE workflow_executions (
    id          UUID PRIMARY KEY,
    workflow_id UUID NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    tenant_id   UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    event_type  TEXT NOT NULL,
    status      TEXT NOT NULL CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED')),
    log_json    JSONB NOT NULL DEFAULT '[]'::jsonb,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ
);

-- Listagem da página /fluxos: workflows do tenant, mais recentes primeiro.
CREATE INDEX idx_workflows_tenant ON workflows (tenant_id);
-- Match do engine: workflows ATIVOS do tenant para um trigger (caminho quente do listener).
CREATE INDEX idx_workflows_tenant_trigger ON workflows (tenant_id, trigger_type) WHERE active;

CREATE INDEX idx_workflow_executions_tenant ON workflow_executions (tenant_id);
-- Histórico de execuções de um workflow, mais recentes primeiro.
CREATE INDEX idx_workflow_executions_wf ON workflow_executions (workflow_id, created_at DESC);

-- RLS: tabelas de negócio tenant-owned → enforced (ADR 0028). Mesmo padrão de V019/V021/V022.
ALTER TABLE workflows ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflows
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE workflow_executions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_executions
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
