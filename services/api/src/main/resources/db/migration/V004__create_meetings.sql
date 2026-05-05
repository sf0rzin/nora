-- V004: cria reunioes (meetings) e suas dependencias diretas para o upload textual (US07).
-- analyses e action_items virao em V005 (US11/US12).
-- Toda tabela tenant-bound carrega tenant_id explicito (ver docs/adr/0002-multi-tenancy.md).

CREATE TABLE meetings (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    owner_user_id      UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT,
    title              TEXT NOT NULL,
    started_at         TIMESTAMPTZ,
    ended_at           TIMESTAMPTZ,
    language           TEXT NOT NULL DEFAULT 'pt-BR',
    transcript_format  TEXT NOT NULL,
    processing_status  TEXT NOT NULL DEFAULT 'PENDING',
    summary_snippet    TEXT,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT meetings_format_chk
        CHECK (transcript_format IN ('TXT', 'VTT', 'SRT')),
    CONSTRAINT meetings_status_chk
        CHECK (processing_status IN ('PENDING', 'PROCESSING', 'COMPLETED', 'FAILED'))
);

CREATE INDEX idx_meetings_tenant_created ON meetings (tenant_id, created_at DESC);
CREATE INDEX idx_meetings_owner ON meetings (owner_user_id);
CREATE INDEX idx_meetings_status ON meetings (tenant_id, processing_status);

CREATE TABLE meeting_participants (
    id            UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meeting_id    UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    tenant_id     UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    display_name  TEXT NOT NULL,
    email         TEXT,
    is_internal   BOOLEAN NOT NULL DEFAULT FALSE,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_meeting_participants_meeting ON meeting_participants (meeting_id);
CREATE INDEX idx_meeting_participants_tenant ON meeting_participants (tenant_id);

CREATE TABLE meeting_tags (
    meeting_id   UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    tag          TEXT NOT NULL,

    PRIMARY KEY (meeting_id, tag)
);

CREATE INDEX idx_meeting_tags_tenant_tag ON meeting_tags (tenant_id, tag);

CREATE TABLE transcripts (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    meeting_id   UUID NOT NULL UNIQUE REFERENCES meetings(id) ON DELETE CASCADE,
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    format       TEXT NOT NULL,
    raw_text     TEXT NOT NULL,
    char_count   INTEGER NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),

    CONSTRAINT transcripts_format_chk CHECK (format IN ('TXT', 'VTT', 'SRT')),
    CONSTRAINT transcripts_char_count_chk CHECK (char_count >= 0)
);

CREATE INDEX idx_transcripts_tenant ON transcripts (tenant_id);
