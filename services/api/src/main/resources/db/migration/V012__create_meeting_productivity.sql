-- V012: Productivity Score opt-in (ADR 0005).
--
-- Persiste o objetivo declarado da reuniao (meeting_goals + meeting_goal_expected_outcomes)
-- e a avaliacao de produtividade gerada pelo worker
-- (meeting_productivity_assessments + meeting_outcome_coverage).
--
-- Regras:
--   - Opt-in por reuniao: sem MeetingGoal, nada eh emitido pelo worker e nada eh persistido.
--   - 1-1 entre meetings <-> meeting_goals e meetings <-> meeting_productivity_assessments.
--   - Toda tabela tenant-bound carrega tenant_id explicito (ver ADR 0002).
--   - Cascade ON DELETE em meeting_id garante limpeza ao remover a reuniao.

-- ============================================================
-- Objetivo declarado da reuniao (input do usuario).
-- ============================================================
CREATE TABLE meeting_goals (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id               UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    meeting_id              UUID NOT NULL UNIQUE REFERENCES meetings(id) ON DELETE CASCADE,
    purpose                 TEXT NOT NULL,
    project_state_snapshot  TEXT,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_meeting_goals_tenant ON meeting_goals (tenant_id);

-- ============================================================
-- Outcomes esperados (lista ordenada por position).
-- ============================================================
CREATE TABLE meeting_goal_expected_outcomes (
    id               UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meeting_goal_id  UUID NOT NULL REFERENCES meeting_goals(id) ON DELETE CASCADE,
    outcome_text     TEXT NOT NULL,
    position         INTEGER NOT NULL,

    CONSTRAINT meeting_goal_outcomes_position_chk CHECK (position >= 0)
);

CREATE INDEX idx_meeting_goal_outcomes_goal ON meeting_goal_expected_outcomes (meeting_goal_id);

-- ============================================================
-- Avaliacao de produtividade gerada pelo worker (1-1 com meetings).
-- ============================================================
CREATE TABLE meeting_productivity_assessments (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id         UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    meeting_id        UUID NOT NULL UNIQUE REFERENCES meetings(id) ON DELETE CASCADE,
    score             INTEGER NOT NULL,
    band              VARCHAR(10) NOT NULL,
    off_topic_ratio   NUMERIC(4,3),
    decision_density  NUMERIC(4,3),
    rationale         TEXT NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT meeting_productivity_score_chk CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT meeting_productivity_band_chk  CHECK (band IN ('LOW','MEDIUM','HIGH'))
);

CREATE INDEX idx_meeting_productivity_tenant ON meeting_productivity_assessments (tenant_id);

-- ============================================================
-- Cobertura de cada outcome esperado conforme avaliacao do LLM.
-- ============================================================
CREATE TABLE meeting_outcome_coverage (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    assessment_id     UUID NOT NULL REFERENCES meeting_productivity_assessments(id) ON DELETE CASCADE,
    expected_outcome  TEXT NOT NULL,
    status            VARCHAR(20) NOT NULL,
    evidence          TEXT,
    position          INTEGER NOT NULL,

    CONSTRAINT meeting_outcome_coverage_status_chk
        CHECK (status IN ('ADDRESSED','PARTIAL','MISSED')),
    CONSTRAINT meeting_outcome_coverage_position_chk CHECK (position >= 0)
);

CREATE INDEX idx_meeting_outcome_coverage_assessment ON meeting_outcome_coverage (assessment_id);
