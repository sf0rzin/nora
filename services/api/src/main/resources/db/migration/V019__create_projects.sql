-- V019: Projects — agrupa reunioes de um tenant em projetos (project tracking).
--
-- Um usuario Core organiza suas meetings em projects. Relacao 1:N (um project tem
-- muitas meetings; uma meeting pertence a no maximo um project, opcional/nullable).
--
-- Regras (ADR 0002 multi-tenancy + defense in depth, segue V015/V016/V017):
--   - Toda tabela tenant-bound carrega tenant_id explicito.
--   - Soft-delete (deleted_at) igual V013: hard-delete continua possivel via native
--     query; default das queries Spring Data filtra deleted_at IS NULL via @SQLRestriction.
--   - UNIQUE (tenant_id, id) habilita a FK composta de meetings (target precisa ser UNIQUE).
--   - meetings.(tenant_id, project_id) -> projects.(tenant_id, id): FK composta previne
--     cross-tenant linking via ORM forge, exatamente como meetings.owner em V015.
--   - RLS (V016) habilitado em projects; policy tenant_isolation identica as demais.

-- ============================================================
-- Projeto (agrupador de reunioes). Tenant-owned, soft-delete.
-- ============================================================
CREATE TABLE projects (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id    UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name         VARCHAR(200) NOT NULL,
    description  TEXT NULL,
    status       VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted_at   TIMESTAMPTZ NULL,

    CONSTRAINT projects_status_chk CHECK (status IN ('ACTIVE', 'ARCHIVED')),
    -- Target da FK composta de meetings (V015 pattern). PK simples `id` continua sendo o
    -- identificador; o UNIQUE composto existe apenas pra suportar a FK target.
    CONSTRAINT projects_tenant_id_uk UNIQUE (tenant_id, id)
);

CREATE INDEX idx_projects_tenant ON projects (tenant_id);
CREATE INDEX idx_projects_deleted_at ON projects (deleted_at);

-- ============================================================
-- Vinculo meeting -> project (1:N, opcional). FK composta (tenant_id, project_id)
-- previne linkar meeting de um tenant a project de outro (defense in depth, V015).
-- ============================================================
ALTER TABLE meetings ADD COLUMN project_id UUID NULL;
ALTER TABLE meetings ADD CONSTRAINT meetings_project_id_fkey
    FOREIGN KEY (tenant_id, project_id)
    REFERENCES projects (tenant_id, id)
    ON DELETE SET NULL;

CREATE INDEX idx_meetings_project ON meetings (project_id);

COMMENT ON CONSTRAINT meetings_project_id_fkey ON meetings IS
    'Composite FK: tenant_id + project_id juntos precisam bater na linha de projects. '
    'Previne cross-tenant project assignment via ORM forge (segue V015).';

-- ============================================================
-- RLS (ADR 0002, segue V016/V017) — projects e tenant-owned.
-- ============================================================
ALTER TABLE projects ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON projects
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());
