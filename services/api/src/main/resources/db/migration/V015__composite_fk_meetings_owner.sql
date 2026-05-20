-- Composite FK: meetings.(tenant_id, owner_user_id) -> users.(tenant_id, id).
--
-- Antes: meetings.owner_user_id REFERENCES users(id). Atacante via ORM forge
-- (ex.: setando owner_user_id = uuid-de-user-de-outro-tenant em payload nao
-- validado) podia criar meeting com owner de tenant diferente, bypassing
-- isolamento ADR 0002. O backend filtra por tenant_id em todas as queries,
-- mas defense in depth no schema fecha o caminho.
--
-- Agora: FK exige que (tenant_id, owner_user_id) do meeting bata com
-- (tenant_id, id) na linha de users. Insert com user de outro tenant = rejected
-- pelo Postgres com ForeignKeyViolation.
--
-- Implementacao em 2 passos pra suportar FK composto:
-- 1) Adicionar UNIQUE (tenant_id, id) em users (target precisa ser UNIQUE).
-- 2) Drop FK simples + recriar como composto.
--
-- A primary key `id` continua sendo o PK simples (UUID v4 ja e globalmente unico);
-- o UNIQUE composto e apenas pra suportar a FK target.

-- 1) UNIQUE composto em users pra suportar FK target.
-- Reusa o partial unique de email pra nao bloquear duplicates de id em
-- soft-deletes (que sao impossiveis na pratica porque UUIDv4, mas defensive).
ALTER TABLE users ADD CONSTRAINT users_tenant_id_uk UNIQUE (tenant_id, id);

-- 2) Drop FK simples + recriar composto.
ALTER TABLE meetings DROP CONSTRAINT meetings_owner_user_id_fkey;
ALTER TABLE meetings ADD CONSTRAINT meetings_owner_user_id_fkey
    FOREIGN KEY (tenant_id, owner_user_id)
    REFERENCES users (tenant_id, id)
    ON DELETE RESTRICT;

COMMENT ON CONSTRAINT meetings_owner_user_id_fkey ON meetings IS
    'Composite FK: tenant_id + owner_user_id juntos precisam bater na linha de users. '
    'Previne cross-tenant user assignment via ORM forge (audit follow-up #7).';
