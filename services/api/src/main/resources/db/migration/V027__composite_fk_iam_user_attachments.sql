-- Composite FK: iam_user_groups / iam_user_policies .(tenant_id, user_id) -> users.(tenant_id, id).
--
-- Antes: as duas tabelas de vinculo tinham FKs independentes -- user_id -> users(id) e
-- tenant_id -> tenants(id) -- sem nada exigindo que as duas colunas descrevessem a MESMA
-- linha de users. Nada no banco impedia anexar um usuario do tenant A a um grupo/policy
-- registrado sob o tenant B: a linha satisfaz as duas FKs isoladamente.
--
-- No app, IamService.addUserToGroup e attachPolicyToUser validam que o GRUPO e a POLICY
-- pertencem ao tenant do chamador, mas nunca validam o userId. O grupo carrega permissoes,
-- entao um vinculo cross-tenant concede a um usuario de fora os direitos do tenant alvo.
--
-- V015 fechou exatamente esta classe de furo para meetings.owner_user_id e ja criou o
-- UNIQUE (tenant_id, id) em users que serve de alvo -- estas duas tabelas nunca receberam
-- o equivalente. E o mesmo remedio, no mesmo formato.
--
-- Agora: o Postgres rejeita com ForeignKeyViolation qualquer insert cujo (tenant_id, user_id)
-- nao bata numa linha de users. Defense in depth: o check no IamService continua sendo a
-- primeira barreira e devolve erro de dominio; este e o piso que nao depende de ninguem
-- lembrar de escrever o check.
--
-- Fora de escopo, de proposito: a coluna `attached_by`. Ela tambem referencia users(id),
-- mas e ON DELETE SET NULL, e um FK composto anularia as DUAS colunas ao remover o ator --
-- incluindo tenant_id, que e NOT NULL. Ficaria um FK que nao consegue executar a propria
-- acao de delete.

ALTER TABLE iam_user_groups DROP CONSTRAINT iam_user_groups_user_id_fkey;
ALTER TABLE iam_user_groups ADD CONSTRAINT iam_user_groups_user_id_fkey
    FOREIGN KEY (tenant_id, user_id)
    REFERENCES users (tenant_id, id)
    ON DELETE CASCADE;

COMMENT ON CONSTRAINT iam_user_groups_user_id_fkey ON iam_user_groups IS
    'Composite FK: tenant_id + user_id juntos precisam bater na linha de users. '
    'Previne anexar usuario de outro tenant a um grupo (audit 2026-08).';

ALTER TABLE iam_user_policies DROP CONSTRAINT iam_user_policies_user_id_fkey;
ALTER TABLE iam_user_policies ADD CONSTRAINT iam_user_policies_user_id_fkey
    FOREIGN KEY (tenant_id, user_id)
    REFERENCES users (tenant_id, id)
    ON DELETE CASCADE;

COMMENT ON CONSTRAINT iam_user_policies_user_id_fkey ON iam_user_policies IS
    'Composite FK: tenant_id + user_id juntos precisam bater na linha de users. '
    'Previne anexar usuario de outro tenant a uma policy (audit 2026-08).';
