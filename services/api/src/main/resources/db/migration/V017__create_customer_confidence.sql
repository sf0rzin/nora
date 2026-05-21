-- V017: Customer Confidence persistence foundation (ADR 0015).
--
-- Persiste contas de cliente (customer_accounts), o vinculo reuniao<->conta
-- (meeting_account_links) e a avaliacao de confianca do cliente gerada pelo worker
-- (customer_confidence_assessments + customer_buying_signals + customer_objections).
--
-- ESCOPO: apenas a fundacao de persistencia. NENHUM wiring no pipeline de analise
-- ainda (AnalysisService nao escreve aqui). O worker ainda NAO emite o bloco
-- customerConfidence; este schema e o contrato-alvo (ver meeting-analysis-v1.schema.json).
--
-- Regras:
--   - Toda tabela tenant-bound carrega tenant_id explicito (ADR 0002).
--   - Cascade ON DELETE em meeting_id/tenant_id/assessment_id garante limpeza.
--   - 1-1 entre (meeting, account) e customer_confidence_assessments (UNIQUE composto):
--     uma reuniao pode tocar mais de uma conta, mas no maximo uma avaliacao por par.
--   - Enums espelham o bloco customerConfidence de meeting-analysis-v1.schema.json.
--   - RLS (V016) habilitado nas 3 tabelas tenant-owned; filhos (signals/objections)
--     herdam isolamento via assessment_id cascade (sem policy propria, igual coverage).

-- ============================================================
-- Conta de cliente (lead/account). Get-or-create dedup por (tenant, LOWER(name)).
-- ============================================================
CREATE TABLE customer_accounts (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id      UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name           TEXT NOT NULL,
    owner_user_id  UUID REFERENCES users(id) ON DELETE SET NULL,
    stage          TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_customer_accounts_tenant ON customer_accounts (tenant_id);
CREATE UNIQUE INDEX idx_customer_accounts_tenant_name
    ON customer_accounts (tenant_id, LOWER(name));

-- ============================================================
-- Vinculo N:N reuniao <-> conta.
-- ============================================================
CREATE TABLE meeting_account_links (
    meeting_id           UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    customer_account_id  UUID NOT NULL REFERENCES customer_accounts(id) ON DELETE CASCADE,
    tenant_id            UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,

    PRIMARY KEY (meeting_id, customer_account_id)
);

CREATE INDEX idx_meeting_account_links_tenant ON meeting_account_links (tenant_id);
CREATE INDEX idx_meeting_account_links_account
    ON meeting_account_links (customer_account_id);

-- ============================================================
-- Avaliacao de confianca do cliente gerada pelo worker.
-- 1-1 por (meeting, account): UNIQUE composto (meeting_id, customer_account_id).
-- ============================================================
CREATE TABLE customer_confidence_assessments (
    id                   UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id            UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    meeting_id           UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    customer_account_id  UUID NOT NULL REFERENCES customer_accounts(id) ON DELETE CASCADE,
    score                INTEGER NOT NULL,
    band                 VARCHAR(10) NOT NULL,
    trend                VARCHAR(10),
    rationale            TEXT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT customer_confidence_score_chk CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT customer_confidence_band_chk  CHECK (band IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT customer_confidence_trend_chk
        CHECK (trend IS NULL OR trend IN ('IMPROVING','STABLE','DECLINING')),
    CONSTRAINT customer_confidence_meeting_account_uk UNIQUE (meeting_id, customer_account_id)
);

CREATE INDEX idx_customer_confidence_tenant ON customer_confidence_assessments (tenant_id);

-- ============================================================
-- Sinais de compra detectados (lista ordenada por position). Filho da avaliacao.
-- ============================================================
CREATE TABLE customer_buying_signals (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id  UUID NOT NULL REFERENCES customer_confidence_assessments(id) ON DELETE CASCADE,
    type           VARCHAR(30) NOT NULL,
    quote          TEXT NOT NULL,
    weight         NUMERIC(4,3),
    position       INTEGER NOT NULL,

    CONSTRAINT customer_buying_signals_type_chk
        CHECK (type IN ('BUDGET_DISCUSSED','TIMELINE_DISCUSSED','STAKEHOLDER_INVOLVED',
                        'NEXT_STEP_REQUESTED','REFERENCE_REQUESTED','PROPOSAL_REQUESTED','OTHER')),
    CONSTRAINT customer_buying_signals_weight_chk
        CHECK (weight IS NULL OR (weight >= 0 AND weight <= 1)),
    CONSTRAINT customer_buying_signals_position_chk CHECK (position >= 0)
);

CREATE INDEX idx_customer_buying_signals_assessment
    ON customer_buying_signals (assessment_id);

-- ============================================================
-- Objecoes levantadas (lista ordenada por position). Filho da avaliacao.
-- ============================================================
CREATE TABLE customer_objections (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id  UUID NOT NULL REFERENCES customer_confidence_assessments(id) ON DELETE CASCADE,
    type           VARCHAR(30) NOT NULL,
    quote          TEXT NOT NULL,
    severity       VARCHAR(10) NOT NULL,
    competitor     TEXT,
    position       INTEGER NOT NULL,

    CONSTRAINT customer_objections_type_chk
        CHECK (type IN ('PRICE','TIMELINE','AUTHORITY','NEED','COMPETITOR_MENTION',
                        'TRUST','FEATURE_GAP','OTHER')),
    CONSTRAINT customer_objections_severity_chk CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT customer_objections_position_chk CHECK (position >= 0)
);

CREATE INDEX idx_customer_objections_assessment ON customer_objections (assessment_id);

-- ============================================================
-- RLS (ADR 0002, segue V016) — apenas tabelas tenant-owned.
-- customer_buying_signals e customer_objections NAO recebem policy: sao filhos
-- diretos do assessment (cascade via assessment_id), iguais a meeting_outcome_coverage.
-- ============================================================
ALTER TABLE customer_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_accounts
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE meeting_account_links ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_account_links
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE customer_confidence_assessments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer_confidence_assessments
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
