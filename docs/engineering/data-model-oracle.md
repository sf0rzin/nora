# Modelo de Dados — NORA (Oracle 19c+)

> Espelho do schema Postgres em sintaxe **Oracle 19c+ (PL/SQL DDL)**.
> NORA roda **em Postgres em produção** (ver `data-model.md`). Este documento é entrega acadêmica para a disciplina de Database Design da FIAP, que exige modelagem Oracle.
> Cada tabela corresponde 1:1 ao schema documentado em `data-model.md`, com as adaptações de tipo e sintaxe descritas em §13.

---

## 1. `TENANTS`

```sql
CREATE TABLE tenants (
    id                    VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    name                  VARCHAR2(255) NOT NULL,
    slug                  VARCHAR2(120) NOT NULL,
    status                VARCHAR2(20)  DEFAULT 'ACTIVE'  NOT NULL,
    plan                  VARCHAR2(20)  DEFAULT 'FREE'    NOT NULL,
    allowed_email_domain  VARCHAR2(255),
    created_at            TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at            TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT tenants_slug_uk    UNIQUE (slug),
    CONSTRAINT tenants_status_chk CHECK (status IN ('ACTIVE','SUSPENDED')),
    CONSTRAINT tenants_plan_chk   CHECK (plan   IN ('FREE','PRO','ENTERPRISE'))
);

CREATE INDEX idx_tenants_status ON tenants (status);
```

---

## 2. `USERS`

```sql
CREATE TABLE users (
    id                 VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id          VARCHAR2(36) NOT NULL,
    email              VARCHAR2(255) NOT NULL,
    password_hash      VARCHAR2(255) NOT NULL,
    display_name       VARCHAR2(255) NOT NULL,
    status             VARCHAR2(20) DEFAULT 'ACTIVE' NOT NULL,
    email_verified_at  TIMESTAMP WITH TIME ZONE,
    is_root            NUMBER(1) DEFAULT 0 NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT users_tenant_fk  FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT users_status_chk CHECK (status IN ('ACTIVE','INVITED','DISABLED')),
    CONSTRAINT users_root_chk   CHECK (is_root IN (0,1)),
    -- Equivalente ao CITEXT do Postgres: unicidade case-insensitive
    CONSTRAINT users_email_uk   UNIQUE (tenant_id, email)
);

-- Função-indice para forcar email case-insensitive em buscas
CREATE UNIQUE INDEX uq_users_tenant_email_ci
    ON users (tenant_id, LOWER(email));

CREATE INDEX idx_users_tenant ON users (tenant_id);

-- Indice parcial nao existe nativamente no Oracle <23ai;
-- usar function-based index com expressao para emular "WHERE is_root = 1".
CREATE UNIQUE INDEX uq_users_root_per_tenant
    ON users (CASE WHEN is_root = 1 THEN tenant_id END);
```

---

## 3. `ROLES` e `USER_ROLES` (legado, **não usado**)

```sql
CREATE TABLE roles (
    id           VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id    VARCHAR2(36),
    code         VARCHAR2(20) NOT NULL,
    description  VARCHAR2(500),
    is_system    NUMBER(1) DEFAULT 1 NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT roles_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT roles_code_chk  CHECK (code IN ('ROOT','ADMIN','MANAGER','ANALYST','VIEWER')),
    CONSTRAINT roles_is_sys_chk CHECK (is_system IN (0,1))
);

CREATE TABLE user_roles (
    user_id     VARCHAR2(36) NOT NULL,
    role_id     VARCHAR2(36) NOT NULL,
    tenant_id   VARCHAR2(36) NOT NULL,
    granted_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT user_roles_pk      PRIMARY KEY (user_id, role_id),
    CONSTRAINT user_roles_user_fk FOREIGN KEY (user_id)   REFERENCES users(id)   ON DELETE CASCADE,
    CONSTRAINT user_roles_role_fk FOREIGN KEY (role_id)   REFERENCES roles(id),
    CONSTRAINT user_roles_ten_fk  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE
);

CREATE INDEX idx_user_roles_tenant ON user_roles (tenant_id);
```

---

## 4. `EMAIL_VERIFICATION_TOKENS`

```sql
CREATE TABLE email_verification_tokens (
    id           VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    user_id      VARCHAR2(36) NOT NULL,
    tenant_id    VARCHAR2(36) NOT NULL,
    token_hash   VARCHAR2(255) NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at  TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT evt_user_fk   FOREIGN KEY (user_id)   REFERENCES users(id)   ON DELETE CASCADE,
    CONSTRAINT evt_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT evt_hash_uk   UNIQUE (token_hash)
);

CREATE INDEX idx_email_verif_tokens_user    ON email_verification_tokens (user_id);
CREATE INDEX idx_email_verif_tokens_expires ON email_verification_tokens (expires_at);
```

---

## 5. `PASSWORD_RESET_TOKENS`

```sql
CREATE TABLE password_reset_tokens (
    id           VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    user_id      VARCHAR2(36) NOT NULL,
    tenant_id    VARCHAR2(36) NOT NULL,
    token_hash   VARCHAR2(255) NOT NULL,
    expires_at   TIMESTAMP WITH TIME ZONE NOT NULL,
    consumed_at  TIMESTAMP WITH TIME ZONE,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT prt_user_fk   FOREIGN KEY (user_id)   REFERENCES users(id)   ON DELETE CASCADE,
    CONSTRAINT prt_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT prt_hash_uk   UNIQUE (token_hash)
);

CREATE INDEX idx_pwd_reset_tokens_user    ON password_reset_tokens (user_id);
CREATE INDEX idx_pwd_reset_tokens_expires ON password_reset_tokens (expires_at);
```

---

## 6. `MEETINGS`

```sql
CREATE TABLE meetings (
    id                 VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id          VARCHAR2(36) NOT NULL,
    owner_user_id      VARCHAR2(36) NOT NULL,
    title              VARCHAR2(500) NOT NULL,
    started_at         TIMESTAMP WITH TIME ZONE,
    ended_at           TIMESTAMP WITH TIME ZONE,
    language           VARCHAR2(10)  DEFAULT 'pt-BR'   NOT NULL,
    transcript_format  VARCHAR2(10)  NOT NULL,
    processing_status  VARCHAR2(20)  DEFAULT 'PENDING' NOT NULL,
    summary_snippet    CLOB,
    -- JSONB Postgres -> CLOB validado como JSON em Oracle 19c+
    attributes         CLOB DEFAULT '{}' NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT meetings_tenant_fk FOREIGN KEY (tenant_id)     REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT meetings_owner_fk  FOREIGN KEY (owner_user_id) REFERENCES users(id),
    CONSTRAINT meetings_format_chk CHECK (transcript_format IN ('TXT','VTT','SRT')),
    CONSTRAINT meetings_status_chk CHECK (processing_status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT meetings_attributes_json CHECK (attributes IS JSON)
);

CREATE INDEX idx_meetings_tenant_created ON meetings (tenant_id, created_at DESC);
CREATE INDEX idx_meetings_owner          ON meetings (owner_user_id);
CREATE INDEX idx_meetings_status         ON meetings (tenant_id, processing_status);

-- Equivalente ao GIN/jsonb_path_ops do Postgres: indice JSON search em Oracle.
-- Em Oracle 19c, JSON_VALUE indexes funcionam por path; para containment use Oracle Text ou JSON Search Index.
CREATE SEARCH INDEX idx_meetings_attributes_jsi
    ON meetings (attributes) FOR JSON;
```

---

## 7. `MEETING_PARTICIPANTS`

```sql
CREATE TABLE meeting_participants (
    id            VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    meeting_id    VARCHAR2(36) NOT NULL,
    tenant_id     VARCHAR2(36) NOT NULL,
    display_name  VARCHAR2(255) NOT NULL,
    email         VARCHAR2(255),
    is_internal   NUMBER(1) DEFAULT 0 NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT mp_meeting_fk     FOREIGN KEY (meeting_id) REFERENCES meetings(id) ON DELETE CASCADE,
    CONSTRAINT mp_tenant_fk      FOREIGN KEY (tenant_id)  REFERENCES tenants(id)  ON DELETE CASCADE,
    CONSTRAINT mp_is_internal_chk CHECK (is_internal IN (0,1))
);

CREATE INDEX idx_meeting_participants_meeting ON meeting_participants (meeting_id);
CREATE INDEX idx_meeting_participants_tenant  ON meeting_participants (tenant_id);
```

---

## 8. `MEETING_TAGS`

```sql
CREATE TABLE meeting_tags (
    meeting_id  VARCHAR2(36) NOT NULL,
    tenant_id   VARCHAR2(36) NOT NULL,
    tag         VARCHAR2(120) NOT NULL,

    CONSTRAINT meeting_tags_pk        PRIMARY KEY (meeting_id, tag),
    CONSTRAINT meeting_tags_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meetings(id) ON DELETE CASCADE,
    CONSTRAINT meeting_tags_tenant_fk  FOREIGN KEY (tenant_id)  REFERENCES tenants(id)  ON DELETE CASCADE
);

CREATE INDEX idx_meeting_tags_tenant_tag ON meeting_tags (tenant_id, tag);
```

---

## 9. `TRANSCRIPTS`

```sql
CREATE TABLE transcripts (
    id           VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    meeting_id   VARCHAR2(36) NOT NULL,
    tenant_id    VARCHAR2(36) NOT NULL,
    format       VARCHAR2(10) NOT NULL,
    raw_text     CLOB NOT NULL,
    char_count   NUMBER(10) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT transcripts_meeting_uk  UNIQUE (meeting_id),
    CONSTRAINT transcripts_meeting_fk  FOREIGN KEY (meeting_id) REFERENCES meetings(id) ON DELETE CASCADE,
    CONSTRAINT transcripts_tenant_fk   FOREIGN KEY (tenant_id)  REFERENCES tenants(id)  ON DELETE CASCADE,
    CONSTRAINT transcripts_format_chk  CHECK (format IN ('TXT','VTT','SRT')),
    CONSTRAINT transcripts_char_chk    CHECK (char_count >= 0)
);

CREATE INDEX idx_transcripts_tenant ON transcripts (tenant_id);
```

---

## 10. `TENANT_CONTEXTS`

```sql
CREATE TABLE tenant_contexts (
    id          VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id   VARCHAR2(36) NOT NULL,
    document    CLOB NOT NULL,
    updated_by  VARCHAR2(36),
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT tenant_ctx_tenant_uk UNIQUE (tenant_id),
    CONSTRAINT tenant_ctx_tenant_fk FOREIGN KEY (tenant_id)  REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT tenant_ctx_user_fk   FOREIGN KEY (updated_by) REFERENCES users(id),
    CONSTRAINT tenant_ctx_doc_json  CHECK (document IS JSON)
);

CREATE INDEX idx_tenant_contexts_tenant ON tenant_contexts (tenant_id);
```

---

## 11. `MEETING_ANALYSES` e filhos

```sql
CREATE TABLE meeting_analyses (
    id                      VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    meeting_id              VARCHAR2(36) NOT NULL,
    tenant_id               VARCHAR2(36) NOT NULL,
    summary                 CLOB NOT NULL,
    sentiment_overall       VARCHAR2(20) NOT NULL,
    -- Oracle nao tem TEXT[] nativo; armazenamos como JSON array em CLOB.
    topics                  CLOB DEFAULT '[]' NOT NULL,
    model_version           VARCHAR2(100),
    prompt_version          VARCHAR2(100),
    tokens_input            NUMBER(10) DEFAULT 0 NOT NULL,
    tokens_output           NUMBER(10) DEFAULT 0 NOT NULL,
    processing_millis       NUMBER(10) DEFAULT 0 NOT NULL,
    pii_redactions_applied  NUMBER(10) DEFAULT 0 NOT NULL,
    generated_at            TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT analyses_meeting_uk    UNIQUE (meeting_id),
    CONSTRAINT analyses_meeting_fk    FOREIGN KEY (meeting_id) REFERENCES meetings(id) ON DELETE CASCADE,
    CONSTRAINT analyses_tenant_fk     FOREIGN KEY (tenant_id)  REFERENCES tenants(id)  ON DELETE CASCADE,
    CONSTRAINT analyses_sentiment_chk CHECK (sentiment_overall IN ('POSITIVE','NEUTRAL','NEGATIVE','MIXED')),
    CONSTRAINT analyses_topics_json   CHECK (topics IS JSON),
    CONSTRAINT analyses_tokens_in_chk  CHECK (tokens_input >= 0),
    CONSTRAINT analyses_tokens_out_chk CHECK (tokens_output >= 0),
    CONSTRAINT analyses_millis_chk     CHECK (processing_millis >= 0)
);

CREATE INDEX idx_meeting_analyses_tenant  ON meeting_analyses (tenant_id);
CREATE INDEX idx_meeting_analyses_meeting ON meeting_analyses (meeting_id);


CREATE TABLE meeting_decisions (
    id           VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    analysis_id  VARCHAR2(36) NOT NULL,
    tenant_id    VARCHAR2(36) NOT NULL,
    text         CLOB NOT NULL,
    confidence   NUMBER(3,2) NOT NULL,
    position     NUMBER(5) NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT mdec_analysis_fk FOREIGN KEY (analysis_id) REFERENCES meeting_analyses(id) ON DELETE CASCADE,
    CONSTRAINT mdec_tenant_fk   FOREIGN KEY (tenant_id)   REFERENCES tenants(id)          ON DELETE CASCADE,
    CONSTRAINT mdec_conf_chk    CHECK (confidence >= 0.0 AND confidence <= 1.0),
    CONSTRAINT mdec_pos_chk     CHECK (position >= 0)
);

CREATE INDEX idx_meeting_decisions_analysis ON meeting_decisions (analysis_id);
CREATE INDEX idx_meeting_decisions_tenant   ON meeting_decisions (tenant_id);


CREATE TABLE meeting_action_items (
    id            VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    analysis_id   VARCHAR2(36) NOT NULL,
    tenant_id     VARCHAR2(36) NOT NULL,
    title         VARCHAR2(500) NOT NULL,
    assignee      VARCHAR2(255),
    due_date      DATE,
    priority      VARCHAR2(10) NOT NULL,
    source_quote  CLOB NOT NULL,
    status        VARCHAR2(20) DEFAULT 'OPEN' NOT NULL,
    position      NUMBER(5) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT mai_analysis_fk FOREIGN KEY (analysis_id) REFERENCES meeting_analyses(id) ON DELETE CASCADE,
    CONSTRAINT mai_tenant_fk   FOREIGN KEY (tenant_id)   REFERENCES tenants(id)          ON DELETE CASCADE,
    CONSTRAINT mai_priority_chk CHECK (priority IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT mai_status_chk   CHECK (status   IN ('OPEN','IN_PROGRESS','DONE')),
    CONSTRAINT mai_pos_chk      CHECK (position >= 0)
);

CREATE INDEX idx_meeting_action_items_analysis ON meeting_action_items (analysis_id);
CREATE INDEX idx_meeting_action_items_tenant   ON meeting_action_items (tenant_id);
CREATE INDEX idx_meeting_action_items_status   ON meeting_action_items (tenant_id, status);


CREATE TABLE meeting_risks (
    id            VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    analysis_id   VARCHAR2(36) NOT NULL,
    tenant_id     VARCHAR2(36) NOT NULL,
    text          CLOB NOT NULL,
    severity      VARCHAR2(10) NOT NULL,
    category      VARCHAR2(20) NOT NULL,
    source_quote  CLOB NOT NULL,
    position      NUMBER(5) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT mrisk_analysis_fk FOREIGN KEY (analysis_id) REFERENCES meeting_analyses(id) ON DELETE CASCADE,
    CONSTRAINT mrisk_tenant_fk   FOREIGN KEY (tenant_id)   REFERENCES tenants(id)          ON DELETE CASCADE,
    CONSTRAINT mrisk_sev_chk     CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT mrisk_cat_chk     CHECK (category IN ('COMPETITION','PRICE','CHURN','TIMELINE','TECHNICAL','COMPLIANCE','OTHER')),
    CONSTRAINT mrisk_pos_chk     CHECK (position >= 0)
);

CREATE INDEX idx_meeting_risks_analysis ON meeting_risks (analysis_id);
CREATE INDEX idx_meeting_risks_tenant   ON meeting_risks (tenant_id);


CREATE TABLE meeting_opportunities (
    id               VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    analysis_id      VARCHAR2(36) NOT NULL,
    tenant_id        VARCHAR2(36) NOT NULL,
    text             CLOB NOT NULL,
    estimated_value  VARCHAR2(10) NOT NULL,
    category         VARCHAR2(20) NOT NULL,
    source_quote     CLOB NOT NULL,
    position         NUMBER(5) NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT mopp_analysis_fk FOREIGN KEY (analysis_id) REFERENCES meeting_analyses(id) ON DELETE CASCADE,
    CONSTRAINT mopp_tenant_fk   FOREIGN KEY (tenant_id)   REFERENCES tenants(id)          ON DELETE CASCADE,
    CONSTRAINT mopp_val_chk     CHECK (estimated_value IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT mopp_cat_chk     CHECK (category IN ('UPSELL','CROSS_SELL','REFERRAL','EXPANSION','OTHER')),
    CONSTRAINT mopp_pos_chk     CHECK (position >= 0)
);

CREATE INDEX idx_meeting_opportunities_analysis ON meeting_opportunities (analysis_id);
CREATE INDEX idx_meeting_opportunities_tenant   ON meeting_opportunities (tenant_id);
```

---

## 12. IAM AWS-style

```sql
CREATE TABLE iam_groups (
    id           VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id    VARCHAR2(36) NOT NULL,
    name         VARCHAR2(120) NOT NULL,
    description  VARCHAR2(500),
    created_by   VARCHAR2(36),
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT iam_groups_tenant_fk FOREIGN KEY (tenant_id)  REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT iam_groups_user_fk   FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT iam_groups_name_uk   UNIQUE (tenant_id, name)
);

CREATE INDEX idx_iam_groups_tenant ON iam_groups (tenant_id);


CREATE TABLE iam_user_groups (
    user_id      VARCHAR2(36) NOT NULL,
    group_id     VARCHAR2(36) NOT NULL,
    tenant_id    VARCHAR2(36) NOT NULL,
    attached_by  VARCHAR2(36),
    attached_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT iam_ug_pk          PRIMARY KEY (user_id, group_id),
    CONSTRAINT iam_ug_user_fk     FOREIGN KEY (user_id)     REFERENCES users(id)      ON DELETE CASCADE,
    CONSTRAINT iam_ug_group_fk    FOREIGN KEY (group_id)    REFERENCES iam_groups(id) ON DELETE CASCADE,
    CONSTRAINT iam_ug_tenant_fk   FOREIGN KEY (tenant_id)   REFERENCES tenants(id)    ON DELETE CASCADE,
    CONSTRAINT iam_ug_attached_fk FOREIGN KEY (attached_by) REFERENCES users(id)
);

CREATE INDEX idx_iam_user_groups_tenant ON iam_user_groups (tenant_id);
CREATE INDEX idx_iam_user_groups_group  ON iam_user_groups (group_id);


CREATE TABLE iam_policies (
    id               VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id        VARCHAR2(36) NOT NULL,
    name             VARCHAR2(120) NOT NULL,
    description      VARCHAR2(500),
    document         CLOB NOT NULL,
    current_version  NUMBER(10) DEFAULT 1 NOT NULL,
    created_by       VARCHAR2(36),
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT iam_policies_tenant_fk FOREIGN KEY (tenant_id)  REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT iam_policies_user_fk   FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT iam_policies_name_uk   UNIQUE (tenant_id, name),
    CONSTRAINT iam_policies_ver_chk   CHECK (current_version >= 1),
    CONSTRAINT iam_policies_doc_json  CHECK (document IS JSON)
);

CREATE INDEX idx_iam_policies_tenant ON iam_policies (tenant_id);


CREATE TABLE iam_policy_versions (
    policy_id    VARCHAR2(36) NOT NULL,
    version      NUMBER(10) NOT NULL,
    tenant_id    VARCHAR2(36) NOT NULL,
    document     CLOB NOT NULL,
    created_by   VARCHAR2(36),
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT iam_pv_pk        PRIMARY KEY (policy_id, version),
    CONSTRAINT iam_pv_policy_fk FOREIGN KEY (policy_id)  REFERENCES iam_policies(id) ON DELETE CASCADE,
    CONSTRAINT iam_pv_tenant_fk FOREIGN KEY (tenant_id)  REFERENCES tenants(id)      ON DELETE CASCADE,
    CONSTRAINT iam_pv_user_fk   FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT iam_pv_ver_chk   CHECK (version >= 1),
    CONSTRAINT iam_pv_doc_json  CHECK (document IS JSON)
);

CREATE INDEX idx_iam_policy_versions_tenant ON iam_policy_versions (tenant_id);


CREATE TABLE iam_group_policies (
    group_id     VARCHAR2(36) NOT NULL,
    policy_id    VARCHAR2(36) NOT NULL,
    tenant_id    VARCHAR2(36) NOT NULL,
    attached_by  VARCHAR2(36),
    attached_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT iam_gp_pk          PRIMARY KEY (group_id, policy_id),
    CONSTRAINT iam_gp_group_fk    FOREIGN KEY (group_id)    REFERENCES iam_groups(id)   ON DELETE CASCADE,
    CONSTRAINT iam_gp_policy_fk   FOREIGN KEY (policy_id)   REFERENCES iam_policies(id) ON DELETE CASCADE,
    CONSTRAINT iam_gp_tenant_fk   FOREIGN KEY (tenant_id)   REFERENCES tenants(id)      ON DELETE CASCADE,
    CONSTRAINT iam_gp_attached_fk FOREIGN KEY (attached_by) REFERENCES users(id)
);

CREATE INDEX idx_iam_group_policies_tenant ON iam_group_policies (tenant_id);
CREATE INDEX idx_iam_group_policies_policy ON iam_group_policies (policy_id);


CREATE TABLE iam_user_policies (
    user_id      VARCHAR2(36) NOT NULL,
    policy_id    VARCHAR2(36) NOT NULL,
    tenant_id    VARCHAR2(36) NOT NULL,
    attached_by  VARCHAR2(36),
    attached_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT iam_up_pk          PRIMARY KEY (user_id, policy_id),
    CONSTRAINT iam_up_user_fk     FOREIGN KEY (user_id)     REFERENCES users(id)        ON DELETE CASCADE,
    CONSTRAINT iam_up_policy_fk   FOREIGN KEY (policy_id)   REFERENCES iam_policies(id) ON DELETE CASCADE,
    CONSTRAINT iam_up_tenant_fk   FOREIGN KEY (tenant_id)   REFERENCES tenants(id)      ON DELETE CASCADE,
    CONSTRAINT iam_up_attached_fk FOREIGN KEY (attached_by) REFERENCES users(id)
);

CREATE INDEX idx_iam_user_policies_tenant ON iam_user_policies (tenant_id);
CREATE INDEX idx_iam_user_policies_policy ON iam_user_policies (policy_id);


CREATE TABLE iam_audit_events (
    id             VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id      VARCHAR2(36) NOT NULL,
    actor_user_id  VARCHAR2(36),
    action         VARCHAR2(120) NOT NULL,
    target_type    VARCHAR2(40) NOT NULL,
    target_id      VARCHAR2(36),
    payload        CLOB,
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT iam_audit_tenant_fk FOREIGN KEY (tenant_id)     REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT iam_audit_user_fk   FOREIGN KEY (actor_user_id) REFERENCES users(id),
    CONSTRAINT iam_audit_payload_json CHECK (payload IS JSON)
);

CREATE INDEX idx_iam_audit_events_tenant_created
    ON iam_audit_events (tenant_id, created_at DESC);
```

---

## 13. Convites e refresh tokens

```sql
CREATE TABLE iam_user_invitations (
    id                VARCHAR2(36) PRIMARY KEY,
    tenant_id         VARCHAR2(36) NOT NULL,
    email             VARCHAR2(255) NOT NULL,
    token             VARCHAR2(128) NOT NULL,
    status            VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
    invited_by        VARCHAR2(36) NOT NULL,
    invited_at        TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at       TIMESTAMP WITH TIME ZONE,
    accepted_user_id  VARCHAR2(36),

    CONSTRAINT iuv_tenant_fk    FOREIGN KEY (tenant_id)        REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT iuv_invited_fk   FOREIGN KEY (invited_by)       REFERENCES users(id),
    CONSTRAINT iuv_accepted_fk  FOREIGN KEY (accepted_user_id) REFERENCES users(id),
    CONSTRAINT iuv_token_uk     UNIQUE (token),
    CONSTRAINT iuv_status_chk   CHECK (status IN ('PENDING','ACCEPTED','EXPIRED','REVOKED'))
);

CREATE INDEX idx_iam_invitations_tenant_status ON iam_user_invitations (tenant_id, status);
CREATE INDEX idx_iam_invitations_token         ON iam_user_invitations (token);
CREATE INDEX idx_iam_invitations_email         ON iam_user_invitations (tenant_id, email);


CREATE TABLE iam_invitation_groups (
    invitation_id  VARCHAR2(36) NOT NULL,
    group_id       VARCHAR2(36) NOT NULL,

    CONSTRAINT iig_pk         PRIMARY KEY (invitation_id, group_id),
    CONSTRAINT iig_inv_fk     FOREIGN KEY (invitation_id) REFERENCES iam_user_invitations(id) ON DELETE CASCADE,
    CONSTRAINT iig_group_fk   FOREIGN KEY (group_id)      REFERENCES iam_groups(id)            ON DELETE CASCADE
);


CREATE TABLE refresh_tokens (
    id            VARCHAR2(36) PRIMARY KEY,
    user_id       VARCHAR2(36) NOT NULL,
    tenant_id     VARCHAR2(36) NOT NULL,
    token_hash    VARCHAR2(255) NOT NULL,
    expires_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at    TIMESTAMP WITH TIME ZONE,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    last_used_at  TIMESTAMP WITH TIME ZONE,

    CONSTRAINT rt_user_fk   FOREIGN KEY (user_id)   REFERENCES users(id)   ON DELETE CASCADE,
    CONSTRAINT rt_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT rt_hash_uk   UNIQUE (token_hash)
);

-- Postgres usa indice parcial WHERE revoked_at IS NULL.
-- Em Oracle, function-based index emula o mesmo comportamento.
CREATE INDEX idx_refresh_tokens_user
    ON refresh_tokens (CASE WHEN revoked_at IS NULL THEN user_id END);

CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens (token_hash);
```

---

## 14. Productivity Score

```sql
CREATE TABLE meeting_goals (
    id                      VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id               VARCHAR2(36) NOT NULL,
    meeting_id              VARCHAR2(36) NOT NULL,
    purpose                 CLOB NOT NULL,
    project_state_snapshot  CLOB,
    created_at              TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at              TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT mg_tenant_fk  FOREIGN KEY (tenant_id)  REFERENCES tenants(id)  ON DELETE CASCADE,
    CONSTRAINT mg_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meetings(id) ON DELETE CASCADE,
    CONSTRAINT mg_meeting_uk UNIQUE (meeting_id)
);

CREATE INDEX idx_meeting_goals_tenant ON meeting_goals (tenant_id);


CREATE TABLE meeting_goal_expected_outcomes (
    id               VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    meeting_goal_id  VARCHAR2(36) NOT NULL,
    outcome_text     CLOB NOT NULL,
    position         NUMBER(5) NOT NULL,

    CONSTRAINT mgeo_goal_fk FOREIGN KEY (meeting_goal_id) REFERENCES meeting_goals(id) ON DELETE CASCADE,
    CONSTRAINT mgeo_pos_chk CHECK (position >= 0)
);

CREATE INDEX idx_meeting_goal_outcomes_goal ON meeting_goal_expected_outcomes (meeting_goal_id);


CREATE TABLE meeting_productivity_assessments (
    id                VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id         VARCHAR2(36) NOT NULL,
    meeting_id        VARCHAR2(36) NOT NULL,
    score             NUMBER(3) NOT NULL,
    band              VARCHAR2(10) NOT NULL,
    off_topic_ratio   NUMBER(4,3),
    decision_density  NUMBER(4,3),
    rationale         CLOB NOT NULL,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT mpa_tenant_fk  FOREIGN KEY (tenant_id)  REFERENCES tenants(id)  ON DELETE CASCADE,
    CONSTRAINT mpa_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meetings(id) ON DELETE CASCADE,
    CONSTRAINT mpa_meeting_uk UNIQUE (meeting_id),
    CONSTRAINT mpa_score_chk  CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT mpa_band_chk   CHECK (band IN ('LOW','MEDIUM','HIGH'))
);

CREATE INDEX idx_meeting_productivity_tenant ON meeting_productivity_assessments (tenant_id);


CREATE TABLE meeting_outcome_coverage (
    id                VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    assessment_id     VARCHAR2(36) NOT NULL,
    expected_outcome  CLOB NOT NULL,
    status            VARCHAR2(20) NOT NULL,
    evidence          CLOB,
    position          NUMBER(5) NOT NULL,

    CONSTRAINT moc_assessment_fk FOREIGN KEY (assessment_id) REFERENCES meeting_productivity_assessments(id) ON DELETE CASCADE,
    CONSTRAINT moc_status_chk    CHECK (status IN ('ADDRESSED','PARTIAL','MISSED')),
    CONSTRAINT moc_pos_chk       CHECK (position >= 0)
);

CREATE INDEX idx_meeting_outcome_coverage_assessment ON meeting_outcome_coverage (assessment_id);
```

---

## 15. Diferenças notáveis Postgres ↔ Oracle

| Tópico | Postgres | Oracle |
|---|---|---|
| **UUID PK** | `UUID PRIMARY KEY DEFAULT gen_random_uuid()` (extensão `pgcrypto`) | `VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY` (também aceita `RAW(16)`) |
| **Texto longo** | `TEXT` (sem limite) | `CLOB` (até 4GB) ou `VARCHAR2(4000)` para curto. Oracle não tem `TEXT` puro. |
| **Timestamp com TZ** | `TIMESTAMPTZ` | `TIMESTAMP WITH TIME ZONE` |
| **JSON binário** | `JSONB` com operadores nativos (`@>`, `?`, `->`) | `CLOB CHECK(column IS JSON)` em 19c. Em 21c+ existe tipo `JSON` nativo. Operadores via `JSON_VALUE`, `JSON_QUERY`, `JSON_EXISTS`. |
| **Boolean** | `BOOLEAN` (TRUE/FALSE) | `NUMBER(1) CHECK (val IN (0,1))`. Oracle 23ai tem `BOOLEAN` nativo, mas evitamos pra portabilidade 19c+. |
| **Email case-insensitive** | extensão `citext` (`CITEXT`) | function-based index `LOWER(email)` + UNIQUE; queries usam `LOWER()` |
| **`gen_random_uuid()`** | `pgcrypto` | `SYS_GUID()` (retorna `RAW(16)`; convertido para `VARCHAR2(36)` via cast implícito) |
| **`NOW()`** | função | `SYSTIMESTAMP` (com timezone) ou `CURRENT_TIMESTAMP` |
| **Array nativo (`TEXT[]`)** | nativo | inexistente. Usar `JSON_ARRAY` em CLOB com `IS JSON` check, ou tabela child N:N. |
| **Índice parcial (`WHERE …`)** | nativo (`CREATE INDEX … WHERE`) | inexistente; emular com function-based index (`CASE WHEN … THEN … END`). |
| **Índice GIN para JSONB** | `USING GIN (col jsonb_path_ops)` | `CREATE SEARCH INDEX … FOR JSON` (Oracle Text/JSON Search Index) em 19c+. |
| **Cascade FK** | `ON DELETE CASCADE` / `ON DELETE RESTRICT` / `ON DELETE SET NULL` | idêntico (`ON DELETE CASCADE`, `ON DELETE SET NULL`; **`RESTRICT` não existe** — comportamento padrão sem cláusula é equivalente a `NO ACTION`/`RESTRICT`). |

---

## 16. Observações de portabilidade

- **Identity columns**: Oracle 12c+ suporta `GENERATED BY DEFAULT AS IDENTITY` para sequências autoincrementais. Não usamos aqui porque todas as PKs são UUID.
- **Triggers de `updated_at`**: o equivalente do `DEFAULT NOW()` na atualização (que Postgres trata via trigger separado, comum em apps Spring) precisaria de trigger PL/SQL `BEFORE UPDATE` em cada tabela. O JPA do backend NORA já seta `updated_at` no commit, então o trigger não é estritamente necessário, mas em uma entrega acadêmica pura é boa prática:

```sql
CREATE OR REPLACE TRIGGER trg_tenants_updated_at
BEFORE UPDATE ON tenants
FOR EACH ROW
BEGIN
    :NEW.updated_at := SYSTIMESTAMP;
END;
/
```

- **Funções equivalentes notáveis**:
  - `COALESCE` é igual.
  - `EXTRACT(EPOCH FROM ...)` → `(EXTRACT(DAY FROM diff) * 86400 + EXTRACT(HOUR FROM diff) * 3600 + ...)` ou `(CAST(timestamp1 AS DATE) - CAST(timestamp2 AS DATE)) * 86400`.
  - `ARRAY_AGG` Postgres → `LISTAGG` Oracle (com sintaxe diferente).

- **JSON queries em queries reais**:
  - Postgres: `WHERE attributes @> '{"department":"Vendas"}'::jsonb`
  - Oracle: `WHERE JSON_VALUE(attributes, '$.department') = 'Vendas'`

- **Extensões**: o equivalente de `CREATE EXTENSION IF NOT EXISTS "pgcrypto"` em Oracle é zero — `SYS_GUID()` está disponível por padrão.

---

## 17. Inventário Oracle ≡ Postgres

| # | Tabela | Postgres (migration) | Oracle (§ neste doc) |
|---|---|---|---|
| 1 | tenants | V001 | §1 |
| 2 | users | V002, V003, V006 | §2 |
| 3 | roles / user_roles (legado) | V002 | §3 |
| 4 | email_verification_tokens | V003 | §4 |
| 5 | password_reset_tokens | V003 | §5 |
| 6 | meetings | V004, V007 (attributes), V008 (índice GIN) | §6 |
| 7 | meeting_participants | V004 | §7 |
| 8 | meeting_tags | V004 | §8 |
| 9 | transcripts | V004 | §9 |
| 10 | tenant_contexts | V005 | §10 |
| 11 | meeting_analyses + decisions/action_items/risks/opportunities | V005 | §11 |
| 12 | iam_groups, iam_user_groups, iam_policies, iam_policy_versions, iam_group_policies, iam_user_policies, iam_audit_events | V006 | §12 |
| 13 | iam_user_invitations, iam_invitation_groups, refresh_tokens | V010, V011 | §13 |
| 14 | meeting_goals + expected_outcomes + productivity_assessments + outcome_coverage | V012 | §14 |

> `tenants.allowed_email_domain` (V009) está incluído em §1.
