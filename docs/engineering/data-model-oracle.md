# Data Model — NORA (Oracle 19c+)

> A mirror of the Postgres schema in **Oracle 19c+ (PL/SQL DDL)** syntax.
> NORA runs **on Postgres in production** (see `data-model.md`). This document is an academic deliverable for FIAP's Database Design course, which requires Oracle modeling.
> Each table corresponds 1:1 to the schema documented in `data-model.md`, with the type and syntax adaptations described in §15.
> It covers migrations **V001–V016** (including soft-delete V013, refresh token rotation V014, composite FK V015 and Row-Level Security V016 — see §18 for the Oracle equivalent of RLS via VPD/DBMS_RLS). Full inventory in §17. The canonical list of migrations (which goes beyond V016, up to V027) is in `data-model.md`.
>
> **Note (scope of this doc):** the migrations after V016 — Customer Confidence (delivered full-stack), full/auth-aware-scope RLS, invitation token hash, `meeting_embeddings` (semantic search), the chat tables, NORA Flows and the OAuth integration connections, plus the V027 composite FK — have not yet been mapped to Oracle syntax. This is a debt of the academic mirror, not of the product: the features are delivered and documented in the canonical Postgres schema in `data-model.md` (Customer Confidence in `§2.29-2.33`, V022–V027 in `§2.35-2.39`).

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
    -- Soft-delete (V013). NULL = active. Hard-delete remains available via native query (LGPD).
    deleted_at            TIMESTAMP WITH TIME ZONE NULL,

    CONSTRAINT tenants_status_chk CHECK (status IN ('ACTIVE','SUSPENDED')),
    CONSTRAINT tenants_plan_chk   CHECK (plan   IN ('FREE','PRO','ENTERPRISE'))
);

CREATE INDEX idx_tenants_status ON tenants (status);

-- Soft-delete (V013): the slug's full UNIQUE was replaced by "partial" uniqueness.
-- Postgres uses a partial index WHERE deleted_at IS NULL; Oracle <23ai has no
-- partial index, so we emulate it with a function-based index (the slug is indexed
-- only while deleted_at IS NULL; soft-deleted rows become NULL and do not
-- count toward uniqueness, freeing the slug for reuse).
CREATE UNIQUE INDEX tenants_slug_uk
    ON tenants (CASE WHEN deleted_at IS NULL THEN slug END);

-- Supports the default deleted_at IS NULL filter applied by @SQLRestriction (Spring).
CREATE INDEX idx_tenants_deleted_at ON tenants (deleted_at);
```

> **Soft-delete (V013)**: the tenant-owned entities `tenants`, `users`, `tenant_contexts` and `meetings` gain `deleted_at`. The backend annotates each `@Entity` with **`@SQLRestriction("deleted_at IS NULL")`**, so every Spring Data query filters to live records by default — the Hibernate equivalent of the `WHERE deleted_at IS NULL` filter. Hard-delete remains possible via native query (LGPD right to be forgotten / retention).

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
    -- Soft-delete (V013). NULL = active.
    deleted_at         TIMESTAMP WITH TIME ZONE NULL,

    CONSTRAINT users_tenant_fk  FOREIGN KEY (tenant_id) REFERENCES tenants(id),
    CONSTRAINT users_status_chk CHECK (status IN ('ACTIVE','INVITED','DISABLED')),
    CONSTRAINT users_root_chk   CHECK (is_root IN (0,1)),
    -- Composite UNIQUE (tenant_id, id) required as the target of the composite FK from
    -- meetings (V015). The simple PK `id` remains the identifier; this
    -- UNIQUE exists only to support the FOREIGN KEY (tenant_id, owner_user_id).
    CONSTRAINT users_tenant_id_uk UNIQUE (tenant_id, id)
);

-- (tenant_id, email): full UNIQUE replaced by "partial" uniqueness under soft-delete
-- (V013). Postgres uses a partial index WHERE deleted_at IS NULL; Oracle emulates it with a
-- function-based index. An email is only unique among live users — after a
-- soft-delete the same email can be reused in a new signup.
CREATE UNIQUE INDEX users_email_uk
    ON users (CASE WHEN deleted_at IS NULL THEN tenant_id END,
              CASE WHEN deleted_at IS NULL THEN email     END);

-- Equivalent to Postgres's CITEXT: case-insensitive uniqueness, also
-- restricted to live rows to stay consistent with the soft-delete.
CREATE UNIQUE INDEX uq_users_tenant_email_ci
    ON users (CASE WHEN deleted_at IS NULL THEN tenant_id      END,
              CASE WHEN deleted_at IS NULL THEN LOWER(email)   END);

CREATE INDEX idx_users_tenant ON users (tenant_id);

-- Supports the default deleted_at IS NULL filter of @SQLRestriction (Spring).
CREATE INDEX idx_users_deleted_at ON users (deleted_at);

-- Partial index does not exist natively in Oracle <23ai;
-- use a function-based index with an expression to emulate "WHERE is_root = 1".
CREATE UNIQUE INDEX uq_users_root_per_tenant
    ON users (CASE WHEN is_root = 1 THEN tenant_id END);
```

## 3. `ROLES` and `USER_ROLES` (legacy, **not used**)

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
    -- Postgres JSONB -> CLOB validated as JSON in Oracle 19c+
    attributes         CLOB DEFAULT '{}' NOT NULL,
    created_at         TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at         TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    -- Soft-delete (V013). NULL = active.
    deleted_at         TIMESTAMP WITH TIME ZONE NULL,

    CONSTRAINT meetings_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    -- Composite FK (V015): (tenant_id, owner_user_id) must match the row in
    -- users (tenant_id, id). Blocks a cross-tenant user assignment forged via the ORM
    -- (defense in depth for the ADR 0002 isolation). Oracle supports a composite FK
    -- natively; the target is the UNIQUE users_tenant_id_uk (§2).
    -- Postgres uses ON DELETE RESTRICT; in Oracle the absence of an ON DELETE clause
    -- is already equivalent to RESTRICT/NO ACTION (see §15).
    CONSTRAINT meetings_owner_fk  FOREIGN KEY (tenant_id, owner_user_id)
        REFERENCES users (tenant_id, id),
    CONSTRAINT meetings_format_chk CHECK (transcript_format IN ('TXT','VTT','SRT')),
    CONSTRAINT meetings_status_chk CHECK (processing_status IN ('PENDING','PROCESSING','COMPLETED','FAILED')),
    CONSTRAINT meetings_attributes_json CHECK (attributes IS JSON)
);

CREATE INDEX idx_meetings_tenant_created ON meetings (tenant_id, created_at DESC);
CREATE INDEX idx_meetings_owner          ON meetings (owner_user_id);
CREATE INDEX idx_meetings_status         ON meetings (tenant_id, processing_status);

-- Supports the default deleted_at IS NULL filter of @SQLRestriction (Spring) — V013.
CREATE INDEX idx_meetings_deleted_at     ON meetings (deleted_at);

-- Equivalent to Postgres's GIN/jsonb_path_ops: a JSON search index in Oracle.
-- In Oracle 19c, JSON_VALUE indexes work by path; for containment use Oracle Text or a JSON Search Index.
CREATE SEARCH INDEX idx_meetings_attributes_jsi
    ON meetings (attributes) FOR JSON;
```

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

## 10. `TENANT_CONTEXTS`

```sql
CREATE TABLE tenant_contexts (
    id          VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id   VARCHAR2(36) NOT NULL,
    document    CLOB NOT NULL,
    updated_by  VARCHAR2(36),
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    -- Soft-delete (V013). NULL = active.
    deleted_at  TIMESTAMP WITH TIME ZONE NULL,

    CONSTRAINT tenant_ctx_tenant_fk FOREIGN KEY (tenant_id)  REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT tenant_ctx_user_fk   FOREIGN KEY (updated_by) REFERENCES users(id),
    CONSTRAINT tenant_ctx_doc_json  CHECK (document IS JSON)
);

-- tenant_id: full UNIQUE (one context per tenant) replaced by "partial"
-- uniqueness under soft-delete (V013). Postgres uses a partial index WHERE
-- deleted_at IS NULL; Oracle emulates it with a function-based index.
CREATE UNIQUE INDEX tenant_ctx_tenant_uk
    ON tenant_contexts (CASE WHEN deleted_at IS NULL THEN tenant_id END);

CREATE INDEX idx_tenant_contexts_tenant ON tenant_contexts (tenant_id);

-- Supports the default deleted_at IS NULL filter of @SQLRestriction (Spring) — V013.
CREATE INDEX idx_tenant_contexts_deleted_at ON tenant_contexts (deleted_at);
```

## 11. `MEETING_ANALYSES` and children

```sql
CREATE TABLE meeting_analyses (
    id                      VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    meeting_id              VARCHAR2(36) NOT NULL,
    tenant_id               VARCHAR2(36) NOT NULL,
    summary                 CLOB NOT NULL,
    sentiment_overall       VARCHAR2(20) NOT NULL,
    -- Oracle has no native TEXT[]; we store it as a JSON array in a CLOB.
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

## 12. AWS-style IAM

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

## 13. Invitations and refresh tokens

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
    id              VARCHAR2(36) PRIMARY KEY,
    user_id         VARCHAR2(36) NOT NULL,
    tenant_id       VARCHAR2(36) NOT NULL,
    token_hash      VARCHAR2(255) NOT NULL,
    expires_at      TIMESTAMP WITH TIME ZONE NOT NULL,
    revoked_at      TIMESTAMP WITH TIME ZONE,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    last_used_at    TIMESTAMP WITH TIME ZONE,
    -- Token rotation + reuse detection (V014). Tokens in the same chain
    -- share family_id; reusing a revoked token revokes the entire family.
    family_id       VARCHAR2(36) NOT NULL,
    -- When rotated, points to the new token (self-FK). NULL on the chain's
    -- active token or on revoked tokens with no successor (logout).
    replaced_by_id  VARCHAR2(36) NULL,

    CONSTRAINT rt_user_fk        FOREIGN KEY (user_id)   REFERENCES users(id)   ON DELETE CASCADE,
    CONSTRAINT rt_tenant_fk      FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT rt_replaced_by_fk FOREIGN KEY (replaced_by_id) REFERENCES refresh_tokens(id),
    CONSTRAINT rt_hash_uk        UNIQUE (token_hash)
);

-- Postgres uses a partial index WHERE revoked_at IS NULL.
-- In Oracle, a function-based index emulates the same behavior.
CREATE INDEX idx_refresh_tokens_user
    ON refresh_tokens (CASE WHEN revoked_at IS NULL THEN user_id END);

CREATE INDEX idx_refresh_tokens_hash ON refresh_tokens (token_hash);

-- Lookup by family to revoke the entire chain on reuse (V014).
CREATE INDEX idx_refresh_tokens_family ON refresh_tokens (family_id);
```

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

## 15. Notable Postgres ↔ Oracle differences

| Topic | Postgres | Oracle |
|---|---|---|
| **UUID PK** | `UUID PRIMARY KEY DEFAULT gen_random_uuid()` (`pgcrypto` extension) | `VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY` (also accepts `RAW(16)`) |
| **Long text** | `TEXT` (unlimited) | `CLOB` (up to 4GB) or `VARCHAR2(4000)` for short text. Oracle has no plain `TEXT`. |
| **Timestamp with TZ** | `TIMESTAMPTZ` | `TIMESTAMP WITH TIME ZONE` |
| **Binary JSON** | `JSONB` with native operators (`@>`, `?`, `->`) | `CLOB CHECK(column IS JSON)` in 19c. In 21c+ there is a native `JSON` type. Operators via `JSON_VALUE`, `JSON_QUERY`, `JSON_EXISTS`. |
| **Boolean** | `BOOLEAN` (TRUE/FALSE) | `NUMBER(1) CHECK (val IN (0,1))`. Oracle 23ai has a native `BOOLEAN`, but we avoid it for 19c+ portability. |
| **Case-insensitive email** | `citext` extension (`CITEXT`) | function-based index `LOWER(email)` + UNIQUE; queries use `LOWER()` |
| **`gen_random_uuid()`** | `pgcrypto` | `SYS_GUID()` (returns `RAW(16)`; converted to `VARCHAR2(36)` via implicit cast) |
| **`NOW()`** | function | `SYSTIMESTAMP` (with timezone) or `CURRENT_TIMESTAMP` |
| **Native array (`TEXT[]`)** | native | nonexistent. Use `JSON_ARRAY` in a CLOB with an `IS JSON` check, or an N:N child table. |
| **Partial index (`WHERE …`)** | native (`CREATE INDEX … WHERE`) | nonexistent; emulate with a function-based index (`CASE WHEN … THEN … END`). Used both for `is_root`/refresh-tokens and for the **partial unique of the soft-delete** (V013): `UNIQUE INDEX (CASE WHEN deleted_at IS NULL THEN col END)`, which frees slug/email/tenant_id for reuse after a soft-delete. |
| **GIN index for JSONB** | `USING GIN (col jsonb_path_ops)` | `CREATE SEARCH INDEX … FOR JSON` (Oracle Text/JSON Search Index) in 19c+. |
| **Cascade FK** | `ON DELETE CASCADE` / `ON DELETE RESTRICT` / `ON DELETE SET NULL` | identical (`ON DELETE CASCADE`, `ON DELETE SET NULL`; **`RESTRICT` does not exist** — the default behavior with no clause is equivalent to `NO ACTION`/`RESTRICT`). |
| **Composite FK** | `FOREIGN KEY (a, b) REFERENCES t(a, b)` (the target needs a composite UNIQUE/PK) | identical — Oracle supports composite FKs natively; the target is the `UNIQUE (tenant_id, id)` (V015, §2). |
| **Row-Level Security** | `ALTER TABLE … ENABLE ROW LEVEL SECURITY` + `CREATE POLICY … USING (…) WITH CHECK (…)`; context via a session GUC (`SET LOCAL`) + `NOBYPASSRLS` role (V016) | **VPD/FGAC**: `DBMS_RLS.ADD_POLICY` + a PL/SQL policy function that returns the predicate; context via an application context (`SYS_CONTEXT`); bypass via the `EXEMPT ACCESS POLICY` privilege. See §18. |

## 16. Portability observations

- **Identity columns**: Oracle 12c+ supports `GENERATED BY DEFAULT AS IDENTITY` for auto-incrementing sequences. We do not use it here because all PKs are UUIDs.
- **`updated_at` triggers**: the equivalent of `DEFAULT NOW()` on update (which Postgres handles via a separate trigger, common in Spring apps) would require a PL/SQL `BEFORE UPDATE` trigger on each table. The NORA backend's JPA already sets `updated_at` on commit, so the trigger is not strictly necessary, but in a purely academic deliverable it is good practice:

```sql
CREATE OR REPLACE TRIGGER trg_tenants_updated_at
BEFORE UPDATE ON tenants
FOR EACH ROW
BEGIN
    :NEW.updated_at := SYSTIMESTAMP;
END;
/
```

- **Notable equivalent functions**:
  - `COALESCE` is the same.
  - `EXTRACT(EPOCH FROM ...)` → `(EXTRACT(DAY FROM diff) * 86400 + EXTRACT(HOUR FROM diff) * 3600 + ...)` or `(CAST(timestamp1 AS DATE) - CAST(timestamp2 AS DATE)) * 86400`.
  - Postgres `ARRAY_AGG` → Oracle `LISTAGG` (with different syntax).

- **JSON queries in real queries**:
  - Postgres: `WHERE attributes @> '{"department":"Vendas"}'::jsonb`
  - Oracle: `WHERE JSON_VALUE(attributes, '$.department') = 'Vendas'`

- **Extensions**: the Oracle equivalent of `CREATE EXTENSION IF NOT EXISTS "pgcrypto"` is nothing — `SYS_GUID()` is available by default.

## 17. Oracle ≡ Postgres inventory

| # | Table | Postgres (migration) | Oracle (§ in this doc) |
|---|---|---|---|
| 1 | tenants | V001 | §1 |
| 2 | users | V002, V003, V006 | §2 |
| 3 | roles / user_roles (legacy) | V002 | §3 |
| 4 | email_verification_tokens | V003 | §4 |
| 5 | password_reset_tokens | V003 | §5 |
| 6 | meetings | V004, V007 (attributes), V008 (GIN index) | §6 |
| 7 | meeting_participants | V004 | §7 |
| 8 | meeting_tags | V004 | §8 |
| 9 | transcripts | V004 | §9 |
| 10 | tenant_contexts | V005 | §10 |
| 11 | meeting_analyses + decisions/action_items/risks/opportunities | V005 | §11 |
| 12 | iam_groups, iam_user_groups, iam_policies, iam_policy_versions, iam_group_policies, iam_user_policies, iam_audit_events | V006 | §12 |
| 13 | iam_user_invitations, iam_invitation_groups, refresh_tokens | V010, V011 | §13 |
| 14 | meeting_goals + expected_outcomes + productivity_assessments + outcome_coverage | V012 | §14 |
| 15 | soft-delete (`deleted_at` + partial uniques) in tenants/users/tenant_contexts/meetings | V013 | §1, §2, §6, §10 |
| 16 | refresh_tokens rotation (`family_id`, `replaced_by_id`) | V014 | §13 |
| 17 | composite FK meetings.(tenant_id, owner_user_id) → users.(tenant_id, id) | V015 | §2, §6 |
| 18 | Row-Level Security (RLS → VPD/DBMS_RLS) | V016 | §18 |

> `tenants.allowed_email_domain` (V009) is included in §1.

## 18. Row-Level Security (V016) — Oracle equivalent: VPD / DBMS_RLS

In Postgres, migration V016 enables **Row-Level Security (RLS)**: each tenant-owned table gains `ALTER TABLE … ENABLE ROW LEVEL SECURITY` + a `tenant_isolation` policy whose predicate is `tenant_id = nora.current_tenant_id()`. The function reads a **session GUC** (`nora.current_tenant_id`) set by the Spring **`TenantRlsAspect`** via `SET LOCAL` at the start of each `@Transactional`. Enforcement is **opt-in in prod**: it only becomes real when the API connects with a dedicated role **without `BYPASSRLS`** (`nora_app NOBYPASSRLS`) and `nora.security.rls.enforce=true`; the owner/admin (used in dev/Testcontainers) bypasses by default, leaving the RLS schema inert without breaking tests.

The native equivalent in Oracle is **VPD (Virtual Private Database)**, also called *Fine-Grained Access Control (FGAC)*, configured via **`DBMS_RLS.ADD_POLICY`** + a **policy function** that returns a dynamic predicate (`WHERE` clause). The session context (Postgres's GUC) becomes an Oracle **application context** (`CREATE CONTEXT … USING …`), read with `SYS_CONTEXT`.

### 18.1 Application context (equivalent to the session GUC)

```sql
-- Package that sets the current tenant in the context (called by the Spring aspect,
-- equivalent to Postgres's SET LOCAL nora.current_tenant_id).
CREATE OR REPLACE PACKAGE nora_session AS
    PROCEDURE set_tenant(p_tenant_id IN VARCHAR2);
END nora_session;
/

CREATE OR REPLACE PACKAGE BODY nora_session AS
    PROCEDURE set_tenant(p_tenant_id IN VARCHAR2) IS
    BEGIN
        DBMS_SESSION.SET_CONTEXT('NORA_CTX', 'tenant_id', p_tenant_id);
    END set_tenant;
END nora_session;
/

-- "Secure" context: only the nora_session package can write attributes into it.
CREATE CONTEXT NORA_CTX USING nora_session;
```

### 18.2 Policy function (dynamic predicate)

```sql
-- Returns the predicate applied to each row. When the context is not set
-- (SYS_CONTEXT returns NULL — e.g., an admin session without the aspect), the predicate
-- '1 = 0' matches no rows => fail-closed, mirroring the Postgres behavior
-- when the GUC is empty and the role does not bypass RLS.
CREATE OR REPLACE FUNCTION nora_tenant_predicate (
    p_schema IN VARCHAR2,
    p_object IN VARCHAR2
) RETURN VARCHAR2 AS
    v_tenant VARCHAR2(36) := SYS_CONTEXT('NORA_CTX', 'tenant_id');
BEGIN
    IF v_tenant IS NULL THEN
        RETURN '1 = 0';
    END IF;
    RETURN 'tenant_id = SYS_CONTEXT(''NORA_CTX'', ''tenant_id'')';
END nora_tenant_predicate;
/
```

### 18.3 Applying the policy (representative: `meetings` and `users`)

```sql
-- meetings: SELECT/INSERT/UPDATE/DELETE filtered by tenant.
-- update_check => TRUE mirrors Postgres's WITH CHECK (prevents writing a row
-- from another tenant, not just reading it).
BEGIN
    DBMS_RLS.ADD_POLICY(
        object_schema   => 'NORA',
        object_name     => 'MEETINGS',
        policy_name      => 'TENANT_ISOLATION',
        function_schema => 'NORA',
        policy_function => 'NORA_TENANT_PREDICATE',
        statement_types => 'SELECT,INSERT,UPDATE,DELETE',
        update_check    => TRUE
    );
END;
/

-- users: identical. The `tenants` table uses the `id` column (self-reference) instead
-- of `tenant_id`, so it would need a dedicated policy function that returns
-- 'id = SYS_CONTEXT(''NORA_CTX'',''tenant_id'')'.
BEGIN
    DBMS_RLS.ADD_POLICY(
        object_schema   => 'NORA',
        object_name     => 'USERS',
        policy_name     => 'TENANT_ISOLATION',
        function_schema => 'NORA',
        policy_function => 'NORA_TENANT_PREDICATE',
        statement_types => 'SELECT,INSERT,UPDATE,DELETE',
        update_check    => TRUE
    );
END;
/
```

The remaining tenant-owned tables covered by V016 follow **exactly the same pattern** (`DBMS_RLS.ADD_POLICY` with `NORA_TENANT_PREDICATE`): `tenant_contexts`, `refresh_tokens`, `iam_groups`, `iam_policies`, `iam_user_invitations`, `meeting_analyses` and `meeting_participants`. The `tenants` table is the only special case (it filters by `id`, not `tenant_id`).

### 18.4 Operational differences

| Aspect | Postgres (V016) | Oracle (VPD) |
|---|---|---|
| Enabling | `ALTER TABLE … ENABLE ROW LEVEL SECURITY` + `CREATE POLICY` | `DBMS_RLS.ADD_POLICY(...)` per table |
| Predicate | SQL expression in the policy (`USING (...) WITH CHECK (...)`) | string returned by the PL/SQL **policy function** |
| Session context | GUC `nora.current_tenant_id` via `SET LOCAL` | application context `NORA_CTX` via `DBMS_SESSION.SET_CONTEXT` |
| Reading the context | `current_setting('nora.current_tenant_id', true)` | `SYS_CONTEXT('NORA_CTX','tenant_id')` |
| Write enforcement | `WITH CHECK` in the policy | `update_check => TRUE` parameter |
| Bypass (dev/admin) | owner or a role with `BYPASSRLS` | users with the `EXEMPT ACCESS POLICY` system privilege |
| Fail-closed (no context) | policy does not match → 0 rows (in a non-admin role) | policy function returns `1 = 0` → 0 rows |
| Driver in the app | `TenantRlsAspect` (Spring) sets the GUC per `@Transactional` | the same aspect would call `nora_session.set_tenant(...)` |
