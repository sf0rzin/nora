-- V023 — NORA Flows: workflows (automations) + execution history.
--
-- A workflow links a TRIGGER (domain event, e.g. meeting.analysis_completed) to ACTIONS
-- (e.g. send_email), optionally filtered by CONDITIONS. The full graph (nodes + edges,
-- as drawn on the canvas) lives in definition_json; trigger_type is denormalized for the
-- engine's O(1) match (partial index by tenant + trigger on active workflows).
--
-- workflow_executions stores each firing (real or manual test) with the step-by-step log
-- (log_json: array of {at, nodeId, level, message}) — it is the history the UI shows.
--
-- Scope: both tenant-owned (ADR 0002) with RLS enforced (ADR 0028), same pattern as V022.

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

-- Listing for the /fluxos page: the tenant's workflows, most recent first.
CREATE INDEX idx_workflows_tenant ON workflows (tenant_id);
-- Engine match: the tenant's ACTIVE workflows for a trigger (the listener's hot path).
CREATE INDEX idx_workflows_tenant_trigger ON workflows (tenant_id, trigger_type) WHERE active;

CREATE INDEX idx_workflow_executions_tenant ON workflow_executions (tenant_id);
-- Execution history of a workflow, most recent first.
CREATE INDEX idx_workflow_executions_wf ON workflow_executions (workflow_id, created_at DESC);

-- RLS: tenant-owned business tables → enforced (ADR 0028). Same pattern as V019/V021/V022.
ALTER TABLE workflows ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflows
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE workflow_executions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON workflow_executions
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
