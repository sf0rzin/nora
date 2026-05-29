-- V001 (banco de PLATAFORMA — control plane, ADR 0022): catálogo de modelos LLM,
-- seleção por serviço, telemetria de custo, feature flags e auditoria do operador.
--
-- ATENÇÃO: este Flyway roda no SEGUNDO datasource (PLATFORM_DATASOURCE_*), com history
-- table própria, location classpath:db/platform. NÃO é o schema do banco transacional
-- do cliente (db/migration). Tabelas aqui são GLOBAIS (do dono da plataforma) — NÃO
-- carregam tenant_id como fronteira de segurança nem RLS. usage_events tem tenant_id
-- apenas como DIMENSÃO de telemetria (UUID solto, sem FK, sem policy).

CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- ============================================================
-- Catálogo de modelos LLM (CRUD via /admin/platform/models).
-- Fechado porém editável (ADR 0024). Pricing em USD por 1M de tokens.
-- ============================================================
CREATE TABLE llm_models (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    provider                    TEXT NOT NULL,
    model_id                    TEXT NOT NULL,
    display_name                TEXT NOT NULL,
    base_url                    TEXT NOT NULL,
    modality                    TEXT NOT NULL DEFAULT 'text',
    supports_strict_json_schema BOOLEAN NOT NULL DEFAULT FALSE,
    price_input_per_mtok        NUMERIC(12,6) NOT NULL DEFAULT 0,
    price_output_per_mtok       NUMERIC(12,6) NOT NULL DEFAULT 0,
    price_cached_input_per_mtok NUMERIC(12,6),
    enabled                     BOOLEAN NOT NULL DEFAULT TRUE,
    created_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT llm_models_provider_model_uq UNIQUE (provider, model_id),
    CONSTRAINT llm_models_modality_chk      CHECK (modality IN ('text','multimodal')),
    CONSTRAINT llm_models_price_in_chk      CHECK (price_input_per_mtok >= 0),
    CONSTRAINT llm_models_price_out_chk     CHECK (price_output_per_mtok >= 0),
    CONSTRAINT llm_models_price_cached_chk  CHECK (price_cached_input_per_mtok IS NULL OR price_cached_input_per_mtok >= 0)
);

CREATE INDEX idx_llm_models_enabled ON llm_models (enabled);

-- ============================================================
-- Seleção de modelo por serviço (binding). Editável em runtime
-- via PUT /admin/platform/config/{service}; lido no hot-path com
-- cache ~60s (ADR 0024). ON DELETE RESTRICT: não dá pra apagar um
-- modelo que está bindado (complementa o 409 do controller).
-- ============================================================
CREATE TABLE llm_config (
    service     TEXT PRIMARY KEY,
    model_id    UUID NOT NULL REFERENCES llm_models(id) ON DELETE RESTRICT,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by  TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT llm_config_service_chk CHECK (service IN ('chat','analysis','multimodal'))
);

-- ============================================================
-- Telemetria de custo de IA (ADR 0024). Desnormalizada pra agregação
-- por tenant/modelo/serviço. tenant_id é dimensão (sem FK — a tabela
-- tenants vive no banco primário). status: ok|error|stub|fallback.
-- ============================================================
CREATE TABLE usage_events (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    service            TEXT NOT NULL,
    provider           TEXT NOT NULL,
    model              TEXT NOT NULL,
    tenant_id          UUID,
    prompt_tokens      INTEGER NOT NULL DEFAULT 0,
    completion_tokens  INTEGER NOT NULL DEFAULT 0,
    cost_usd           NUMERIC(14,8) NOT NULL DEFAULT 0,
    latency_ms         INTEGER,
    status             TEXT NOT NULL DEFAULT 'ok',

    CONSTRAINT usage_events_prompt_chk     CHECK (prompt_tokens >= 0),
    CONSTRAINT usage_events_completion_chk CHECK (completion_tokens >= 0),
    CONSTRAINT usage_events_cost_chk       CHECK (cost_usd >= 0)
);

CREATE INDEX idx_usage_events_occurred ON usage_events (occurred_at);
CREATE INDEX idx_usage_events_tenant   ON usage_events (tenant_id, occurred_at);
CREATE INDEX idx_usage_events_model    ON usage_events (model, occurred_at);
CREATE INDEX idx_usage_events_service  ON usage_events (service, occurred_at);

-- ============================================================
-- Feature flags on/off por serviço.
-- ============================================================
CREATE TABLE feature_flags (
    key         TEXT PRIMARY KEY,
    enabled     BOOLEAN NOT NULL DEFAULT TRUE,
    description TEXT,
    updated_by  TEXT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- ============================================================
-- Auditoria do operador (quem mudou config). detail é JSONB livre.
-- ============================================================
CREATE TABLE platform_audit_log (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    occurred_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    operator_email TEXT,
    action         TEXT NOT NULL,
    target_type    TEXT,
    target_id      TEXT,
    detail         JSONB NOT NULL DEFAULT '{}'::jsonb
);

CREATE INDEX idx_platform_audit_occurred ON platform_audit_log (occurred_at);

-- ============================================================
-- SEEDS — catálogo fechado inicial (mai/2026). Pricing USD/1M tokens.
-- ============================================================
INSERT INTO llm_models
    (provider, model_id, display_name, base_url, modality, supports_strict_json_schema,
     price_input_per_mtok, price_output_per_mtok, price_cached_input_per_mtok, enabled)
VALUES
    -- DeepSeek V4 Flash: só texto, barato, cache-hit agressivo, MIT, 1M ctx.
    ('deepseek', 'deepseek-v4-flash', 'DeepSeek V4 Flash',
     'https://api.deepseek.com/v1', 'text', FALSE,
     0.140000, 0.280000, 0.014000, TRUE),
    -- gpt-4o-mini: texto (+visão), strict JSON Schema first-class (ADR 0003).
    ('openai', 'gpt-4o-mini', 'GPT-4o mini',
     'https://api.openai.com/v1', 'text', TRUE,
     0.150000, 0.600000, NULL, TRUE),
    -- Gemini 3.5 Flash: multimodal (áudio/vídeo/visão), structured output nativo.
    -- base_url = endpoint OpenAI-compatible do Gemini.
    ('google', 'gemini-3.5-flash', 'Gemini 3.5 Flash',
     'https://generativelanguage.googleapis.com/v1beta/openai', 'multimodal', TRUE,
     1.500000, 9.000000, NULL, TRUE);

-- Bindings iniciais: chat começa barato (DeepSeek); analysis exige strict (gpt-4o-mini);
-- multimodal pronto no router (Gemini), mesmo sem consumidor live ainda.
INSERT INTO llm_config (service, model_id, enabled, updated_by)
SELECT 'chat',       id, TRUE, 'seed' FROM llm_models WHERE provider='deepseek' AND model_id='deepseek-v4-flash';
INSERT INTO llm_config (service, model_id, enabled, updated_by)
SELECT 'analysis',   id, TRUE, 'seed' FROM llm_models WHERE provider='openai'   AND model_id='gpt-4o-mini';
INSERT INTO llm_config (service, model_id, enabled, updated_by)
SELECT 'multimodal', id, TRUE, 'seed' FROM llm_models WHERE provider='google'   AND model_id='gemini-3.5-flash';

INSERT INTO feature_flags (key, enabled, description, updated_by) VALUES
    ('service.chat',              TRUE,  'Chat IA do plano Core (BFF /api/chat)',          'seed'),
    ('service.analysis',          TRUE,  'Análise de reunião (worker /analyze)',           'seed'),
    ('service.multimodal',        TRUE,  'Análise multimodal (áudio/vídeo/imagem) futura', 'seed'),
    ('service.search-embeddings', FALSE, 'Busca semântica via embeddings (off no MVP)',    'seed');
