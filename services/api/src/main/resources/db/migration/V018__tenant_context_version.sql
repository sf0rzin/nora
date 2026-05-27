-- V018: versionamento do contexto do tenant (US31).
--
-- Contador incremental que sobe a cada save (1 = criacao, +1 por update).
-- Aditivo: a coluna entra com DEFAULT 1, fazendo backfill das linhas existentes
-- sem perda de dados. Sem mudanca de RLS — tenant_contexts ja e protegida via
-- row-level security (V016) e a coluna herda essa protecao.
ALTER TABLE tenant_contexts ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
