-- V019: Row Level Security (RLS) COMPLETA — fecha a cobertura iniciada em V016/V017 (ADR 0002, ADR 0019, ADR 0026).
--
-- ## Por que esta migration existe
--
-- V016 habilitou ENABLE ROW LEVEL SECURITY + POLICY tenant_isolation em 12 tabelas
-- tenant-owned; V017 em +3 (customer confidence). Ficaram SEM policy ~19 tabelas
-- tenant-owned. No Postgres, uma tabela tenant-owned SEM `ENABLE ROW LEVEL SECURITY`
-- e TOTALMENTE ABERTA ao role conectado: se o enforce de RLS for ligado (role
-- nora_app NOBYPASSRLS), as tabelas com policy ficam isoladas, mas as SEM policy
-- continuam legiveis cross-tenant. O caso mais grave: `transcripts` guarda
-- `raw_text` (transcricao bruta = PII em repouso). Ligar o enforce hoje protegeria
-- `meetings` e deixaria `transcripts` vazado entre tenants — exatamente o oposto do
-- objetivo. Esta migration fecha o buraco.
--
-- ## Padrao (identico a V016/V017)
--
-- Para toda tabela tenant-owned com `tenant_id` proprio:
--   ALTER TABLE <t> ENABLE ROW LEVEL SECURITY;
--   CREATE POLICY tenant_isolation ON <t>
--       USING (tenant_id = nora.current_tenant_id())
--       WITH CHECK (tenant_id = nora.current_tenant_id());
--
-- A funcao nora.current_tenant_id() ja existe (V016) e le o GUC de sessao
-- `nora.current_tenant_id` (NULL => fail-closed: 0 rows pra role sem BYPASSRLS).
-- O TenantRlsAspect (opt-in via nora.security.rls.enforce=true) seta o GUC por
-- transacao. Em dev/Testcontainers o app roda como owner e RLS fica inerte.
--
-- ## Tabelas filhas SEM tenant_id proprio (fronteira de cascade — SEM policy)
--
-- Tres tabelas sao filhas diretas de um pai ja isolado e NAO carregam tenant_id:
--   - iam_invitation_groups        -> filha de iam_user_invitations (via invitation_id)
--   - meeting_goal_expected_outcomes -> filha de meeting_goals       (via meeting_goal_id)
--   - meeting_outcome_coverage     -> filha de meeting_productivity_assessments (via assessment_id)
-- Elas seguem a MESMA convencao ja adotada em V017 para customer_buying_signals /
-- customer_objections / meeting_outcome_coverage: o isolamento vem do cascade FK ao
-- pai (ON DELETE CASCADE) + do fato de que toda leitura passa pelo pai isolado. Como
-- nao tem tenant_id, uma policy direta exigiria JOIN ao pai dentro do USING — custo e
-- complexidade sem ganho real de seguranca, ja que o unico caminho de acesso e via o
-- pai. Documentado aqui como FRONTEIRA EXPLICITA: se algum dia uma dessas tabelas
-- ganhar acesso direto (sem passar pelo pai), ela precisa de policy via JOIN.
--
-- ## Cobertura apos V019
--
-- V016 (12) + V017 (3) + V019 (15) = 30 tabelas tenant-owned com policy direta.
-- Tabelas legadas `roles` (linhas globais tenant_id NULL) e `user_roles` (V002,
-- deprecadas e fora de uso no modelo IAM novo) NAO recebem RLS: `roles` tem linhas
-- globais que uma policy por tenant esconderia; ambas saem em migration de limpeza
-- futura (ver nota V006). Filhas de cascade: 3 (acima) — sem policy por design.

-- ============================================================
-- transcripts (V004) — PRIORIDADE: guarda raw_text = PII em repouso.
-- ============================================================
ALTER TABLE transcripts ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON transcripts
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- meeting_tags (V004)
-- ============================================================
ALTER TABLE meeting_tags ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_tags
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- Filhos da analise (V005) — cada um carrega tenant_id explicito (ADR 0002).
-- ============================================================
ALTER TABLE meeting_decisions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_decisions
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE meeting_action_items ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_action_items
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE meeting_risks ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_risks
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE meeting_opportunities ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_opportunities
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- Productivity (V012) — meeting_goals + assessments carregam tenant_id.
-- ============================================================
ALTER TABLE meeting_goals ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_goals
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE meeting_productivity_assessments ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON meeting_productivity_assessments
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- IAM (V006) — membership/attachments/versions/audit, todos com tenant_id.
-- ============================================================
ALTER TABLE iam_user_groups ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_user_groups
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_group_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_group_policies
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_user_policies ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_user_policies
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_policy_versions ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_policy_versions
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE iam_audit_events ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON iam_audit_events
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- Tokens de auth (V003) — verificacao de e-mail + reset de senha. Carregam
-- tenant_id; isolar evita enumerar/consumir tokens de outro tenant via role app.
-- ============================================================
ALTER TABLE email_verification_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_verification_tokens
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

ALTER TABLE password_reset_tokens ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON password_reset_tokens
    USING (tenant_id = nora.current_tenant_id())
    WITH CHECK (tenant_id = nora.current_tenant_id());

-- ============================================================
-- Fronteiras de cascade (SEM policy, por design — ver cabecalho).
-- iam_invitation_groups (V010), meeting_goal_expected_outcomes (V012),
-- meeting_outcome_coverage (V012) NAO recebem ENABLE RLS nem policy: sao filhas
-- diretas de um pai isolado, sem tenant_id proprio. Mesma convencao de V017
-- (customer_buying_signals / customer_objections / meeting_outcome_coverage).
-- ============================================================
