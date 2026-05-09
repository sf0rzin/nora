-- V005: persistencia da analise gerada pelo NLP Worker (US11/US12) e
-- contexto do tenant usado como input do prompt (US14/US30).
--
-- Espelha docs/api/llm-schemas/meeting-analysis-v1.schema.json + ADR 0003.
-- Toda tabela tenant-bound carrega tenant_id explicito (ver ADR 0002).

-- ============================================================
-- Contexto do tenant (1-1 com tenants).
-- Guardamos o documento normalizado em JSONB para nao acoplar
-- o schema relacional ao formato exato do prompt; a validacao
-- estrutural fica no domain/Pydantic.
-- ============================================================
CREATE TABLE tenant_contexts (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE,
    document      JSONB NOT NULL,
    updated_by    UUID REFERENCES users(id) ON DELETE SET NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_tenant_contexts_tenant ON tenant_contexts (tenant_id);

-- ============================================================
-- Analise da reuniao (1-1 com meetings).
-- ============================================================
CREATE TABLE meeting_analyses (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meeting_id              UUID NOT NULL UNIQUE REFERENCES meetings(id) ON DELETE CASCADE,
    tenant_id               UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    summary                 TEXT NOT NULL,
    sentiment_overall       TEXT NOT NULL,
    topics                  TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[],
    model_version           TEXT,
    prompt_version          TEXT,
    tokens_input            INTEGER NOT NULL DEFAULT 0,
    tokens_output           INTEGER NOT NULL DEFAULT 0,
    processing_millis       INTEGER NOT NULL DEFAULT 0,
    pii_redactions_applied  INTEGER NOT NULL DEFAULT 0,
    generated_at            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT meeting_analyses_sentiment_chk
        CHECK (sentiment_overall IN ('POSITIVE','NEUTRAL','NEGATIVE','MIXED')),
    CONSTRAINT meeting_analyses_tokens_in_chk  CHECK (tokens_input >= 0),
    CONSTRAINT meeting_analyses_tokens_out_chk CHECK (tokens_output >= 0),
    CONSTRAINT meeting_analyses_millis_chk     CHECK (processing_millis >= 0)
);

CREATE INDEX idx_meeting_analyses_tenant   ON meeting_analyses (tenant_id);
CREATE INDEX idx_meeting_analyses_meeting  ON meeting_analyses (meeting_id);

-- ============================================================
-- Decisoes da analise.
-- ============================================================
CREATE TABLE meeting_decisions (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id  UUID NOT NULL REFERENCES meeting_analyses(id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    text         TEXT NOT NULL,
    confidence   NUMERIC(3,2) NOT NULL,
    position     INTEGER NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT meeting_decisions_confidence_chk
        CHECK (confidence >= 0.0 AND confidence <= 1.0),
    CONSTRAINT meeting_decisions_position_chk CHECK (position >= 0)
);

CREATE INDEX idx_meeting_decisions_analysis ON meeting_decisions (analysis_id);
CREATE INDEX idx_meeting_decisions_tenant   ON meeting_decisions (tenant_id);

-- ============================================================
-- Action items extraidos pela analise.
-- ============================================================
CREATE TABLE meeting_action_items (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id   UUID NOT NULL REFERENCES meeting_analyses(id) ON DELETE CASCADE,
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    title         TEXT NOT NULL,
    assignee      TEXT,
    due_date      DATE,
    priority      TEXT NOT NULL,
    source_quote  TEXT NOT NULL,
    status        TEXT NOT NULL DEFAULT 'OPEN',
    position      INTEGER NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT meeting_action_items_priority_chk
        CHECK (priority IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT meeting_action_items_status_chk
        CHECK (status IN ('OPEN','IN_PROGRESS','DONE')),
    CONSTRAINT meeting_action_items_position_chk CHECK (position >= 0)
);

CREATE INDEX idx_meeting_action_items_analysis ON meeting_action_items (analysis_id);
CREATE INDEX idx_meeting_action_items_tenant   ON meeting_action_items (tenant_id);
CREATE INDEX idx_meeting_action_items_status   ON meeting_action_items (tenant_id, status);

-- ============================================================
-- Riscos identificados pela analise.
-- ============================================================
CREATE TABLE meeting_risks (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id   UUID NOT NULL REFERENCES meeting_analyses(id) ON DELETE CASCADE,
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    text          TEXT NOT NULL,
    severity      TEXT NOT NULL,
    category      TEXT NOT NULL,
    source_quote  TEXT NOT NULL,
    position      INTEGER NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT meeting_risks_severity_chk
        CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT meeting_risks_category_chk
        CHECK (category IN ('COMPETITION','PRICE','CHURN','TIMELINE','TECHNICAL','COMPLIANCE','OTHER')),
    CONSTRAINT meeting_risks_position_chk CHECK (position >= 0)
);

CREATE INDEX idx_meeting_risks_analysis ON meeting_risks (analysis_id);
CREATE INDEX idx_meeting_risks_tenant   ON meeting_risks (tenant_id);

-- ============================================================
-- Oportunidades comerciais identificadas pela analise.
-- ============================================================
CREATE TABLE meeting_opportunities (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    analysis_id      UUID NOT NULL REFERENCES meeting_analyses(id) ON DELETE CASCADE,
    tenant_id        UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    text             TEXT NOT NULL,
    estimated_value  TEXT NOT NULL,
    category         TEXT NOT NULL,
    source_quote     TEXT NOT NULL,
    position         INTEGER NOT NULL,
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT meeting_opportunities_estimated_chk
        CHECK (estimated_value IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT meeting_opportunities_category_chk
        CHECK (category IN ('UPSELL','CROSS_SELL','REFERRAL','EXPANSION','OTHER')),
    CONSTRAINT meeting_opportunities_position_chk CHECK (position >= 0)
);

CREATE INDEX idx_meeting_opportunities_analysis ON meeting_opportunities (analysis_id);
CREATE INDEX idx_meeting_opportunities_tenant   ON meeting_opportunities (tenant_id);
