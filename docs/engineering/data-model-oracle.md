# Data Model — NORA (Oracle 19c+)

> A mirror of the Postgres schema in **Oracle 19c+ (PL/SQL DDL)** syntax.
> NORA runs **on Postgres in production** (see `data-model.md`). This document is an academic deliverable for FIAP's Database Design course, which requires Oracle modeling.
> Each table corresponds 1:1 to the schema documented in `data-model.md`, with the type and syntax adaptations described in §20.
> It covers migrations **V001–V032** — the whole canonical schema, including soft-delete (V013), refresh token rotation (V014), the three composite FKs (V015, V027, V029), the hashed invitation token (V018), Customer Confidence (V017), semantic search (V021), chat sessions (V022), NORA Flows (V023) and the run state of its scheduled trigger (V032), the OAuth integration connections (V024–V026), the company-context history (V028), the inbound MCP credential (V029), the trends panel's completion timestamp (V030) and Row-Level Security (V016/V017/V019/V020/V021–V024/V028/V032 — Oracle equivalent via VPD/DBMS_RLS in §23). Full inventory in §22.

> **Note (scope of this doc), checked 2026-08-17:** the mirror is **complete up to V032**. Every table documented in `data-model.md` §2 has Oracle DDL here, and every migration V001–V032 has a row in §22. Two things are deliberately **not** mirrored, and both are Postgres-side operational objects rather than schema: the role provisioning in `services/api/src/main/resources/db/operational/R001__provision_app_roles.sql` (the Oracle counterpart is a privilege grant, not a script — see §23.4) and the **separate control-plane database** of ADR 0022 (`services/api/src/main/resources/db/platform/`), which `data-model.md` does not cover either. This file carries **44 tables** (`grep -c '^CREATE TABLE '`) against the **42** `### 2.x` sections of `data-model.md`: the two counts differ by design, because §2.3 (`roles` + `user_roles`) and §2.23 (`iam_user_invitations` + `iam_invitation_groups`) each document two tables. A looser `grep -c 'CREATE TABLE'` returns 47, matching three prose mentions as well.

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
    -- is already equivalent to RESTRICT/NO ACTION (see §20).
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
    -- V028 (US31): number of the newest row in tenant_context_versions. Denormalized the same way
    -- iam_policies.current_version is (§12), so the current number is a column read, not a MAX().
    current_version NUMBER(10) DEFAULT 1 NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    -- Soft-delete (V013). NULL = active.
    deleted_at  TIMESTAMP WITH TIME ZONE NULL,

    CONSTRAINT tenant_ctx_tenant_fk FOREIGN KEY (tenant_id)  REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT tenant_ctx_user_fk   FOREIGN KEY (updated_by) REFERENCES users(id),
    CONSTRAINT tenant_ctx_ver_chk   CHECK (current_version >= 1),
    CONSTRAINT tenant_ctx_doc_json  CHECK (document IS JSON),
    -- V028: adds no new uniqueness (id is already the PK). It exists only as the target of the
    -- composite FK from tenant_context_versions below — the same two-step shape V015 used on users.
    CONSTRAINT tenant_ctx_tenant_id_uk UNIQUE (tenant_id, id)
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

### Version history (V028, US31)

Postgres source: `data-model.md` §2.40.

Immutable history of the company context: one row per edit, never updated, never deleted except by
cascade. The shape is `iam_policy_versions` (§12) — composite primary key `(entity_id, version)`,
explicit `tenant_id`, JSON document, `created_by`, `created_at`, `CHECK (version >= 1)`, index by
tenant — with **one deliberate difference**: the FK to the parent is composite. `iam_policy_versions`
predates V015, so its `tenant_id` and `policy_id` are independent foreign keys and nothing in the
database requires them to describe the same policy; this table is born with the constraint V015 and
V027 had to retrofit elsewhere.

The second difference is not visible in the DDL and matters more: this table is **read**.
`iam_policy_versions` has been written on every policy edit since V006 with no `SELECT` and no
endpoint anywhere in the codebase, which makes it a backup rather than an audit trail. V028 ships
with `GET /tenant/context/versions` and `GET /tenant/context/versions/{version}`.

```sql
CREATE TABLE tenant_context_versions (
    context_id  VARCHAR2(36) NOT NULL,
    version     NUMBER(10) NOT NULL,
    tenant_id   VARCHAR2(36) NOT NULL,
    -- The whole context document as it stood when this version was written.
    document    CLOB NOT NULL,
    -- Nullable: losing the author must not lose the record that a change happened. See the note
    -- below on the delete action.
    created_by  VARCHAR2(36),
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT tenant_cv_pk         PRIMARY KEY (context_id, version),
    -- Composite FK: tenant_id and context_id together must match one tenant_contexts row.
    CONSTRAINT tenant_cv_context_fk FOREIGN KEY (tenant_id, context_id)
        REFERENCES tenant_contexts (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT tenant_cv_tenant_fk  FOREIGN KEY (tenant_id)  REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT tenant_cv_user_fk    FOREIGN KEY (created_by) REFERENCES users(id),
    CONSTRAINT tenant_cv_ver_chk    CHECK (version >= 1),
    CONSTRAINT tenant_cv_doc_json   CHECK (document IS JSON)
);

CREATE INDEX idx_tenant_context_versions_tenant ON tenant_context_versions (tenant_id);
```

> **Soft-delete of the parent does not touch the history.** `tenant_contexts.deleted_at` marks a row
> without removing it, so the FK still holds and the trail survives — it simply stops being
> reachable through the API, whose two read endpoints join through the live context. A **hard**
> delete of the context or of the tenant takes the history with it by cascade, which is the LGPD
> erasure path (ADR 0029). Same behaviour in both engines; nothing here is Oracle-specific.

> **`created_by` uses a plain `REFERENCES users(id)` here, as `iam_policy_versions` does in §12.**
> Postgres declares it `ON DELETE SET NULL`; Oracle supports the same clause, and the mirror follows
> the convention already set for the IAM history table rather than diverging for one column.

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
    -- V030 (US21): when the item entered DONE. NULL while it is not DONE. Separate from
    -- updated_at, which also moves on a title or due-date edit and would therefore date a
    -- completion by the last time anyone touched the row.
    completed_at  TIMESTAMP WITH TIME ZONE NULL,

    CONSTRAINT mai_analysis_fk FOREIGN KEY (analysis_id) REFERENCES meeting_analyses(id) ON DELETE CASCADE,
    CONSTRAINT mai_tenant_fk   FOREIGN KEY (tenant_id)   REFERENCES tenants(id)          ON DELETE CASCADE,
    CONSTRAINT mai_priority_chk CHECK (priority IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT mai_status_chk   CHECK (status   IN ('OPEN','IN_PROGRESS','DONE')),
    CONSTRAINT mai_pos_chk      CHECK (position >= 0)
);

CREATE INDEX idx_meeting_action_items_analysis ON meeting_action_items (analysis_id);
CREATE INDEX idx_meeting_action_items_tenant   ON meeting_action_items (tenant_id);
CREATE INDEX idx_meeting_action_items_status   ON meeting_action_items (tenant_id, status);

-- V030: the two indexes the trends panel aggregates on.
CREATE INDEX idx_meeting_action_items_tenant_created ON meeting_action_items (tenant_id, created_at);

-- Postgres uses a PARTIAL index here (WHERE completed_at IS NOT NULL): an item that is not DONE
-- can never be a completion candidate, so indexing the open backlog would cost size for nothing.
-- Oracle <23ai has no partial index, and the emulation is the same one the soft-delete uses in §1:
-- a function-based index over an expression that is NULL for the rows to be excluded. Oracle does
-- not store an entry when every indexed expression is NULL, so the effect is identical.
CREATE INDEX idx_meeting_action_items_tenant_completed
    ON meeting_action_items (
        CASE WHEN completed_at IS NOT NULL THEN tenant_id END,
        completed_at
    );


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

> **Composite FK on the two user-attachment tables (V027).** `iam_user_groups` and
> `iam_user_policies` do **not** carry a simple `user_id → users(id)` FK. V027 replaced it with
> `(tenant_id, user_id) → users(tenant_id, id)`, the same remedy V015 applied to
> `meetings.owner_user_id` (§6) and against the same hole: with two independent FKs nothing
> required the two columns to describe the *same* `users` row, so a user of tenant A could be
> attached to a group or policy registered under tenant B — and a group carries permissions.
> The target is the composite `users_tenant_id_uk UNIQUE (tenant_id, id)` already created for
> V015 (§2), so Oracle needs no new target constraint. `attached_by` stays a **simple** FK on
> purpose: it is `ON DELETE SET NULL`, and a composite FK would null `tenant_id` too, which is
> `NOT NULL`.

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
    -- Composite FK (V027): (tenant_id, user_id) must match one users row.
    -- Target: users_tenant_id_uk UNIQUE (tenant_id, id), created for V015 (§2).
    CONSTRAINT iam_ug_user_fk     FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE CASCADE,
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
    -- Composite FK (V027): identical treatment to iam_user_groups, same hole,
    -- same target (users_tenant_id_uk, §2).
    CONSTRAINT iam_up_user_fk     FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE CASCADE,
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

> **Invitation token is hashed (V018).** The column created in V010 was `token VARCHAR(128)`
> holding the raw invitation token. V018 renamed it to `token_hash`, widened it to Postgres
> `TEXT` and invalidated the legacy PENDING rows, because the invitation token *is* a
> credential: whoever holds it creates an ACTIVE user in the tenant. The mirror below carries
> the post-V018 shape — `token_hash VARCHAR2(255)`, the same type this document already uses for
> `email_verification_tokens`, `password_reset_tokens` and `refresh_tokens` (a SHA-256 hex digest
> is 64 characters). The Oracle rename would be
> `ALTER TABLE iam_user_invitations RENAME COLUMN token TO token_hash;`, which Oracle supports
> with the same syntax as Postgres; the index rename is `ALTER INDEX … RENAME TO …`, also
> identical.

```sql
CREATE TABLE iam_user_invitations (
    id                VARCHAR2(36) PRIMARY KEY,
    tenant_id         VARCHAR2(36) NOT NULL,
    email             VARCHAR2(255) NOT NULL,
    -- V018: SHA-256 hex of the invitation token. The raw token exists only in memory
    -- while the invitation email is built; acceptance hashes what it receives and looks up by hash.
    token_hash        VARCHAR2(255) NOT NULL,
    status            VARCHAR2(20) DEFAULT 'PENDING' NOT NULL,
    invited_by        VARCHAR2(36) NOT NULL,
    invited_at        TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    expires_at        TIMESTAMP WITH TIME ZONE NOT NULL,
    accepted_at       TIMESTAMP WITH TIME ZONE,
    accepted_user_id  VARCHAR2(36),

    CONSTRAINT iuv_tenant_fk    FOREIGN KEY (tenant_id)        REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT iuv_invited_fk   FOREIGN KEY (invited_by)       REFERENCES users(id),
    CONSTRAINT iuv_accepted_fk  FOREIGN KEY (accepted_user_id) REFERENCES users(id),
    CONSTRAINT iuv_token_hash_uk UNIQUE (token_hash),
    CONSTRAINT iuv_status_chk   CHECK (status IN ('PENDING','ACCEPTED','EXPIRED','REVOKED'))
);

CREATE INDEX idx_iam_invitations_tenant_status ON iam_user_invitations (tenant_id, status);
CREATE INDEX idx_iam_invitations_token_hash    ON iam_user_invitations (token_hash);
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

### 13.1 `MCP_TOKENS` (V029) — the inbound MCP credential

> Third table of this section and the same rule as the other two: **only the SHA-256 hex is
> stored**, never the token. It is the credential an external MCP client presents to `POST /mcp`
> (ADR 0041 §3), minted by a user already authenticated in the web application. Simpler than
> `refresh_tokens` on purpose — no rotation family and no reuse detection, because this credential
> is never exchanged; it sits in a client's configuration file for weeks, and revocation is the
> only kill switch its lifecycle needs.
>
> Two Oracle-specific notes. `expires_at` is **nullable** here, unlike `refresh_tokens` — a NULL
> means "until revoked", which is the normal state for this credential and not a missing value.
> And the owner index is partial on the Postgres side, so it takes the same function-based form the
> `refresh_tokens` index above uses; Oracle indexes skip rows whose key is entirely NULL, which is
> what reproduces `WHERE revoked_at IS NULL`.

```sql
CREATE TABLE mcp_tokens (
    id            VARCHAR2(36) PRIMARY KEY,
    tenant_id     VARCHAR2(36) NOT NULL,
    user_id       VARCHAR2(36) NOT NULL,
    -- Label the user recognises when deciding which credential to revoke. Not a secret.
    name          VARCHAR2(80) NOT NULL,
    -- SHA-256 hex of the WHOLE presented string, `nora_mcp_` prefix included.
    token_hash    VARCHAR2(255) NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    -- NULL = no hard expiry; the token lives until it is revoked.
    expires_at    TIMESTAMP WITH TIME ZONE,
    revoked_at    TIMESTAMP WITH TIME ZONE,
    last_used_at  TIMESTAMP WITH TIME ZONE,

    -- Composite FK (the V015/V027 remedy): the pair must describe one real user, so a
    -- credential cannot be filed under a tenant that does not own its principal.
    CONSTRAINT mcp_tokens_user_fk FOREIGN KEY (tenant_id, user_id)
        REFERENCES users (tenant_id, id) ON DELETE CASCADE,
    CONSTRAINT mcp_tokens_hash_uk UNIQUE (token_hash),
    CONSTRAINT mcp_tokens_name_chk CHECK (LENGTH(TRIM(name)) BETWEEN 1 AND 80)
);

-- Postgres: partial index WHERE revoked_at IS NULL. Function-based equivalent, as above.
CREATE INDEX idx_mcp_tokens_owner
    ON mcp_tokens (CASE WHEN revoked_at IS NULL THEN tenant_id END,
                   CASE WHEN revoked_at IS NULL THEN user_id END);
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

## 15. Customer Confidence (V017)

Postgres source: `data-model.md` §2.29–§2.33. Five tables — the account, the N:N link to the
meeting, the assessment, and two ordered child lists.

Two translation decisions worth naming before the DDL:

- **`UNIQUE (tenant_id, LOWER(name))` becomes a function-based unique index.** Postgres accepts an
  expression directly inside a `UNIQUE` index; Oracle does too, but only as a `CREATE UNIQUE INDEX`,
  never as a table-level `CONSTRAINT`. That is why the constraint moves out of the `CREATE TABLE`
  body here and does not in, say, `customer_confidence_assessments`, whose UNIQUE is over plain
  columns. Same effect: get-or-create deduplicates the account case-insensitively per tenant.
- **`customer_buying_signals` and `customer_objections` have no `tenant_id`, on purpose.** They are
  direct children of the assessment and are isolated by the `assessment_id` cascade — exactly like
  `meeting_outcome_coverage` (§14). They therefore get **no VPD policy** in §23 either.

```sql
CREATE TABLE customer_accounts (
    id             VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id      VARCHAR2(36) NOT NULL,
    name           VARCHAR2(255) NOT NULL,
    owner_user_id  VARCHAR2(36),
    stage          VARCHAR2(60),
    created_at     TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at     TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT ca_tenant_fk FOREIGN KEY (tenant_id)     REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT ca_owner_fk  FOREIGN KEY (owner_user_id) REFERENCES users(id)   ON DELETE SET NULL
);

CREATE INDEX idx_customer_accounts_tenant ON customer_accounts (tenant_id);

-- Postgres: CREATE UNIQUE INDEX ... (tenant_id, LOWER(name)).
-- Oracle indexes expressions the same way, but the uniqueness cannot be written as a
-- table CONSTRAINT — only as a function-based UNIQUE INDEX. `name` is VARCHAR2 (not CLOB),
-- which is what makes LOWER() indexable here.
CREATE UNIQUE INDEX idx_customer_accounts_tenant_name
    ON customer_accounts (tenant_id, LOWER(name));


CREATE TABLE meeting_account_links (
    meeting_id           VARCHAR2(36) NOT NULL,
    customer_account_id  VARCHAR2(36) NOT NULL,
    tenant_id            VARCHAR2(36) NOT NULL,

    CONSTRAINT mal_pk         PRIMARY KEY (meeting_id, customer_account_id),
    CONSTRAINT mal_meeting_fk FOREIGN KEY (meeting_id)          REFERENCES meetings(id)          ON DELETE CASCADE,
    CONSTRAINT mal_account_fk FOREIGN KEY (customer_account_id) REFERENCES customer_accounts(id) ON DELETE CASCADE,
    CONSTRAINT mal_tenant_fk  FOREIGN KEY (tenant_id)           REFERENCES tenants(id)           ON DELETE CASCADE
);

CREATE INDEX idx_meeting_account_links_tenant  ON meeting_account_links (tenant_id);
CREATE INDEX idx_meeting_account_links_account ON meeting_account_links (customer_account_id);


CREATE TABLE customer_confidence_assessments (
    id                   VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    tenant_id            VARCHAR2(36) NOT NULL,
    meeting_id           VARCHAR2(36) NOT NULL,
    customer_account_id  VARCHAR2(36) NOT NULL,
    score                NUMBER(3) NOT NULL,
    band                 VARCHAR2(10) NOT NULL,
    -- NULL => first meeting of this account, so there is nothing to compare against.
    trend                VARCHAR2(10),
    rationale            CLOB NOT NULL,
    created_at           TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT cca_tenant_fk  FOREIGN KEY (tenant_id)           REFERENCES tenants(id)           ON DELETE CASCADE,
    CONSTRAINT cca_meeting_fk FOREIGN KEY (meeting_id)          REFERENCES meetings(id)          ON DELETE CASCADE,
    CONSTRAINT cca_account_fk FOREIGN KEY (customer_account_id) REFERENCES customer_accounts(id) ON DELETE CASCADE,
    CONSTRAINT cca_score_chk  CHECK (score BETWEEN 0 AND 100),
    CONSTRAINT cca_band_chk   CHECK (band IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT cca_trend_chk  CHECK (trend IS NULL OR trend IN ('IMPROVING','STABLE','DECLINING')),
    -- A meeting may touch several accounts, but at most one assessment per pair.
    CONSTRAINT cca_meeting_account_uk UNIQUE (meeting_id, customer_account_id)
);

CREATE INDEX idx_customer_confidence_tenant ON customer_confidence_assessments (tenant_id);


CREATE TABLE customer_buying_signals (
    id             VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    assessment_id  VARCHAR2(36) NOT NULL,
    type           VARCHAR2(30) NOT NULL,
    quote          CLOB NOT NULL,
    weight         NUMBER(4,3),
    position       NUMBER(5) NOT NULL,

    CONSTRAINT cbs_assessment_fk FOREIGN KEY (assessment_id)
        REFERENCES customer_confidence_assessments(id) ON DELETE CASCADE,
    CONSTRAINT cbs_type_chk   CHECK (type IN ('BUDGET_DISCUSSED','TIMELINE_DISCUSSED','STAKEHOLDER_INVOLVED',
                                              'NEXT_STEP_REQUESTED','REFERENCE_REQUESTED','PROPOSAL_REQUESTED','OTHER')),
    CONSTRAINT cbs_weight_chk CHECK (weight IS NULL OR (weight >= 0 AND weight <= 1)),
    CONSTRAINT cbs_pos_chk    CHECK (position >= 0)
);

CREATE INDEX idx_customer_buying_signals_assessment ON customer_buying_signals (assessment_id);


CREATE TABLE customer_objections (
    id             VARCHAR2(36) DEFAULT SYS_GUID() PRIMARY KEY,
    assessment_id  VARCHAR2(36) NOT NULL,
    type           VARCHAR2(30) NOT NULL,
    quote          CLOB NOT NULL,
    severity       VARCHAR2(10) NOT NULL,
    competitor     VARCHAR2(255),
    position       NUMBER(5) NOT NULL,

    CONSTRAINT cobj_assessment_fk FOREIGN KEY (assessment_id)
        REFERENCES customer_confidence_assessments(id) ON DELETE CASCADE,
    CONSTRAINT cobj_type_chk     CHECK (type IN ('PRICE','TIMELINE','AUTHORITY','NEED','COMPETITOR_MENTION',
                                                 'TRUST','FEATURE_GAP','OTHER')),
    CONSTRAINT cobj_severity_chk CHECK (severity IN ('LOW','MEDIUM','HIGH')),
    CONSTRAINT cobj_pos_chk      CHECK (position >= 0)
);

CREATE INDEX idx_customer_objections_assessment ON customer_objections (assessment_id);
```

## 16. `MEETING_EMBEDDINGS` (V021) — semantic search

Postgres source: `data-model.md` §2.34.

**There is no vector type in play, in either engine.** This is the one table where a reader is most
likely to assume otherwise, so it is worth stating plainly: the Postgres side does **not** use
`pgvector`. The extension is present in the `pgvector/pgvector:pg16` image but is **never created**:
the only `CREATE EXTENSION` statements in the whole migration set are `pgcrypto` (V001) and `citext`
(V002). V021 stores the embedding as a **JSON array of floats in a plain `TEXT` column**, with
cosine similarity computed in Java by `EmbeddingService` over the tenant's rows. That is a
deliberate scale decision recorded in the V021 header, not an oversight. The Oracle mirror therefore has nothing to translate: Oracle 19c has no vector type
either (`VECTOR` arrives in 23ai), and none is needed.

One divergence is introduced on purpose and is flagged rather than hidden: the Oracle column carries
`CHECK (embedding IS JSON)` while the Postgres column is plain `TEXT` with **no** validation. The
`IS JSON` check is *stricter* than production. It is here because this document's convention (§20)
is that any JSON-bearing column declares `IS JSON`, and because an academic DDL that silently
accepts `'not json'` in a column documented as a JSON array would be worse modelling. A reader
porting the other way should know Postgres does not enforce it.

```sql
CREATE TABLE meeting_embeddings (
    -- PK is the meeting itself: one embedding per meeting (vector of the summary/title).
    meeting_id    VARCHAR2(36) PRIMARY KEY,
    tenant_id     VARCHAR2(36) NOT NULL,
    -- Which model/provider produced the vector. Search only compares vectors from the SAME
    -- space (same provider + model); switching provider requires a re-backfill, which is
    -- POST /admin/platform/embeddings/backfill since ADR 0044.
    model         VARCHAR2(120) NOT NULL,
    dim           NUMBER(10) NOT NULL,
    -- JSON array of floats. Postgres: TEXT, unvalidated. See the note above.
    embedding     CLOB NOT NULL,
    source_chars  NUMBER(10) DEFAULT 0 NOT NULL,
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT me_meeting_fk FOREIGN KEY (meeting_id) REFERENCES meetings(id) ON DELETE CASCADE,
    CONSTRAINT me_tenant_fk  FOREIGN KEY (tenant_id)  REFERENCES tenants(id)  ON DELETE CASCADE,
    CONSTRAINT me_json_chk   CHECK (embedding IS JSON)
);

CREATE INDEX idx_meeting_embeddings_tenant ON meeting_embeddings (tenant_id);
```

> **`dim` deliberately has no CHECK.** A `dim > 0` constraint would be reasonable modelling, and it
> is left out precisely because V021 does not have one: this document is a mirror, and inventing
> constraints makes it a worse mirror. `IS JSON` above is the single exception, and it is there only
> because §20's own convention demands it of every JSON-bearing column.

## 17. `CHAT_SESSION` and `CHAT_MESSAGE` (V022)

Postgres source: `data-model.md` §2.35–§2.36.

Both table names are **singular**, unlike every other table in the schema. That is what the database
actually has; the mirror keeps it rather than "fixing" the name.

Three points about the translation:

- **No `DEFAULT SYS_GUID()` on these PKs.** V022 declares `id UUID PRIMARY KEY` with no default —
  the application assigns the id. The same is true of `workflows`, `workflow_executions`,
  `integration_connections` (§18, §19) and, since V010/V011, of `iam_user_invitations` and
  `refresh_tokens` (§13). Where Postgres has `DEFAULT gen_random_uuid()`, this document writes
  `DEFAULT SYS_GUID()`; where it does not, neither does the mirror.
- **`role` needs no quoting.** `ROLE` is an Oracle *keyword* (`CREATE ROLE`) but not a **reserved**
  word — it does not appear in the reserved-word list of the Oracle SQL Language Reference, so it is
  legal as an unquoted column name. Quoting it as `"ROLE"` would make the identifier case-sensitive
  everywhere it is referenced, which is a worse trade than leaving it bare. Recorded in §20.
- **Per-user scoping is not in the database, in either engine.** RLS/VPD isolates by **tenant**. The
  rule "each user only sees their own sessions" is an application filter (`AND user_id = :userId` in
  the persistence adapter). A query that forgot it would still pass the policy. The mirror does not
  invent a policy Postgres does not have — see §23.4.

```sql
CREATE TABLE chat_session (
    -- No DEFAULT: the application assigns the id (V022).
    id          VARCHAR2(36) PRIMARY KEY,
    tenant_id   VARCHAR2(36) NOT NULL,
    user_id     VARCHAR2(36) NOT NULL,
    -- Nullable: stays undefined until the first user message. ChatSession.deriveTitle
    -- collapses whitespace and cuts at 48 chars — application logic, not a column constraint.
    title       VARCHAR2(500),
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT cs_tenant_fk FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT cs_user_fk   FOREIGN KEY (user_id)   REFERENCES users(id)   ON DELETE CASCADE
);

CREATE INDEX idx_chat_session_tenant ON chat_session (tenant_id);
CREATE INDEX idx_chat_session_user   ON chat_session (user_id);
-- Sidebar listing: the user's sessions, most recent first. Oracle supports DESC in an index
-- key the same way Postgres does.
CREATE INDEX idx_chat_session_user_updated ON chat_session (user_id, updated_at DESC);


CREATE TABLE chat_message (
    id          VARCHAR2(36) PRIMARY KEY,
    session_id  VARCHAR2(36) NOT NULL,
    -- Denormalized so the table can carry its own policy. There is deliberately no user_id:
    -- ownership is inherited from the parent session.
    tenant_id   VARCHAR2(36) NOT NULL,
    -- `role` is a keyword but not a reserved word in Oracle: no quoting needed.
    role        VARCHAR2(20) NOT NULL,
    content     CLOB NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT cm_session_fk FOREIGN KEY (session_id) REFERENCES chat_session(id) ON DELETE CASCADE,
    CONSTRAINT cm_tenant_fk  FOREIGN KEY (tenant_id)  REFERENCES tenants(id)      ON DELETE CASCADE,
    -- Lowercase on the wire, as in Postgres. ChatRole maps to and from the enum.
    CONSTRAINT cm_role_chk   CHECK (role IN ('user','assistant'))
);

CREATE INDEX idx_chat_message_tenant         ON chat_message (tenant_id);
CREATE INDEX idx_chat_message_session_created ON chat_message (session_id, created_at);
```

## 18. `WORKFLOWS`, `WORKFLOW_EXECUTIONS` (V023) and `WORKFLOW_SCHEDULES` (V032) — NORA Flows

Postgres source: `data-model.md` §2.37–§2.38 and §2.42. ADR 0030 (engine), ADR 0032 (canvas),
ADR 0047 (the scheduled trigger).

- **`trigger_type` has no CHECK, and the mirror does not add one.** It is plain `TEXT` in Postgres.
  The valid set lives in the Java `TriggerType` enum and in `WorkflowDefinitionParser`. All four
  wire values are dispatched since V032; before it, `schedule.cron` was refused on save because
  nothing in the backend scheduled a workflow. Writing an Oracle `CHECK` here would document a
  constraint the production database does not have; the honest mirror leaves the column open and
  says why. `workflow_executions.status`, by contrast, **is** constrained in the database, so it is
  constrained here.
- **The partial index becomes a function-based index.** Postgres has
  `CREATE INDEX … (tenant_id, trigger_type) WHERE active`; Oracle before 23ai has no partial index,
  so the same trick this document already uses for `is_root` (§2) and the soft-delete uniques
  applies. Because `active` is `NUMBER(1)` rather than a real boolean, the predicate is
  `active = 1`. Rows with `active = 0` index as `(NULL, NULL)` and, in a **non-unique** Oracle
  index, an all-NULL key is not stored at all — which is precisely the small-index property the
  Postgres partial index was for.

```sql
CREATE TABLE workflows (
    id               VARCHAR2(36) PRIMARY KEY,
    tenant_id        VARCHAR2(36) NOT NULL,
    name             VARCHAR2(255) NOT NULL,
    -- Denormalized out of definition_json for the engine's match index below.
    -- NO CHECK, matching Postgres: the valid set is enforced in the application.
    trigger_type     VARCHAR2(60) NOT NULL,
    -- The full canvas graph (nodes + edges). Postgres JSONB -> CLOB validated as JSON.
    definition_json  CLOB NOT NULL,
    active           NUMBER(1) DEFAULT 1 NOT NULL,
    created_at       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at       TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT wf_tenant_fk  FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT wf_active_chk CHECK (active IN (0,1)),
    CONSTRAINT wf_def_json   CHECK (definition_json IS JSON)
);

CREATE INDEX idx_workflows_tenant ON workflows (tenant_id);

-- Engine hot path: the tenant's ACTIVE workflows for a fired trigger.
-- Postgres: CREATE INDEX ... (tenant_id, trigger_type) WHERE active.
-- Oracle <23ai has no partial index; a function-based index reproduces it. Inactive rows
-- produce an all-NULL key, which a non-unique Oracle index does not store.
CREATE INDEX idx_workflows_tenant_trigger ON workflows (
    CASE WHEN active = 1 THEN tenant_id    END,
    CASE WHEN active = 1 THEN trigger_type END
);


CREATE TABLE workflow_executions (
    id           VARCHAR2(36) PRIMARY KEY,
    workflow_id  VARCHAR2(36) NOT NULL,
    -- Own tenant_id so the table can carry its own policy.
    tenant_id    VARCHAR2(36) NOT NULL,
    event_type   VARCHAR2(60) NOT NULL,
    status       VARCHAR2(20) NOT NULL,
    -- Step-by-step log: array of {at, nodeId, level, message}.
    log_json     CLOB DEFAULT '[]' NOT NULL,
    created_at   TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    -- NULL while RUNNING.
    finished_at  TIMESTAMP WITH TIME ZONE,

    CONSTRAINT wfe_workflow_fk FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE,
    CONSTRAINT wfe_tenant_fk   FOREIGN KEY (tenant_id)   REFERENCES tenants(id)   ON DELETE CASCADE,
    CONSTRAINT wfe_status_chk  CHECK (status IN ('RUNNING','SUCCESS','FAILED')),
    CONSTRAINT wfe_log_json    CHECK (log_json IS JSON)
);

CREATE INDEX idx_workflow_executions_tenant ON workflow_executions (tenant_id);
CREATE INDEX idx_workflow_executions_wf     ON workflow_executions (workflow_id, created_at DESC);
```

### 18.1 `WORKFLOW_SCHEDULES` (V032) — the run state of a scheduled flow

Postgres source: `data-model.md` §2.42. ADR 0047.

- **`cron` has no CHECK either, for the same reason as `trigger_type`.** The column holds the
  canonical six-field expression that `ScheduleSpec`'s closed vocabulary compiles to, and the
  vocabulary is enforced in Java at save. A regular-expression `CHECK` here would re-state a shape
  Java already produced, and would drift from it the first time the vocabulary widens.
- **The PK is the FK.** `workflow_id` is both, exactly as in Postgres — one schedule per workflow,
  and the row dies with the workflow through the cascade. No surrogate key is added, because the
  mirror is a translation, not a redesign.
- **Nothing here is Oracle-specific.** There is no partial index and no boolean, so the DDL below
  differs from Postgres only in the type spellings this document uses everywhere: `VARCHAR2(36)`
  for a UUID and `TIMESTAMP WITH TIME ZONE` for `TIMESTAMPTZ`.

```sql
CREATE TABLE workflow_schedules (
    -- One row per scheduled flow: the PK is also the FK to workflows.
    workflow_id   VARCHAR2(36) PRIMARY KEY,
    -- Own tenant_id so the table can carry its own policy.
    tenant_id     VARCHAR2(36) NOT NULL,
    -- Canonical six-field expression the schedule vocabulary compiles to.
    -- NO CHECK, matching Postgres: the vocabulary is enforced in the application.
    cron          VARCHAR2(120) NOT NULL,
    -- IANA zone the occurrences were computed in. Stored per row, not assumed.
    timezone      VARCHAR2(60) NOT NULL,
    -- Advanced AT CLAIM, so missed occurrences collapse into one run on recovery.
    next_fire_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Advanced AT RELEASE, so a run that dies mid-flight does not drop its meetings.
    window_from   TIMESTAMP WITH TIME ZONE NOT NULL,
    -- Diagnostic: instant of the most recent claim. NULL = never ran.
    last_fire_at  TIMESTAMP WITH TIME ZONE,
    -- Set while a run is in flight; the overlap guard. NULL = idle.
    claimed_at    TIMESTAMP WITH TIME ZONE,
    -- Id of the process holding the claim, minted per boot. Diagnostic.
    claim_owner   VARCHAR2(64),
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT wfs_workflow_fk FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE,
    CONSTRAINT wfs_tenant_fk   FOREIGN KEY (tenant_id)   REFERENCES tenants(id)   ON DELETE CASCADE
);

-- The scheduler tick's only query: the tenant's due schedules, oldest overdue first.
CREATE INDEX idx_workflow_schedules_due ON workflow_schedules (tenant_id, next_fire_at);
```

## 19. `INTEGRATION_CONNECTIONS` (V024, CHECK expanded in V025 and V026)

Postgres source: `data-model.md` §2.39. ADR 0031.

The provider list arrived in three steps, and the Oracle DDL below states the **current** list
(V026). The history is worth keeping because each step dropped and recreated the same constraint —
in Postgres it was created inline in V024 and therefore named by the server
(`integration_connections_provider_check`); the mirror names it explicitly, which is what makes the
later `ALTER` predictable instead of a guess at a generated name:

| Migration | Accepted values | Added |
|---|---|---|
| **V024** | `google`, `slack` | initial pair |
| **V025** | + `github`, `notion`, `todoist`, `linear` | wave 1 |
| **V026** | + `microsoft`, `telegram`, `trello` | wave 2 — the current nine |

The Oracle equivalent of each expansion is the same two statements Postgres used, with identical
syntax:

```sql
ALTER TABLE integration_connections DROP CONSTRAINT ic_provider_chk;
ALTER TABLE integration_connections ADD  CONSTRAINT ic_provider_chk
    CHECK (provider IN (...));
```

**One table, three acquisition modes.** Most providers use the OAuth2 authorization-code flow
(Microsoft with refresh); **Telegram** pairs by code and stores the bot's `chat_id` in
`access_token` rather than a token; **Trello** uses a token the user pastes. Same table, same
cipher, different paths in.

```sql
CREATE TABLE integration_connections (
    id                VARCHAR2(36) PRIMARY KEY,
    tenant_id         VARCHAR2(36) NOT NULL,
    -- Records WHO connected (audit). The connection itself is tenant-level.
    user_id           VARCHAR2(36) NOT NULL,
    provider          VARCHAR2(30) NOT NULL,
    -- Postgres TEXT is unbounded; VARCHAR2(4000) is the ceiling under the default
    -- MAX_STRING_SIZE = STANDARD. Comfortably above any real scope string or token
    -- envelope; a deployment expecting longer values would use CLOB instead (§20).
    scopes            VARCHAR2(4000) NOT NULL,
    external_account  VARCHAR2(255),
    -- NOT a raw token: an envelope written by TokenCipher, `enc:v1:base64(iv):base64(ct)`
    -- (AES-256-GCM, random IV per value). See the encryption note below.
    access_token      VARCHAR2(4000) NOT NULL,
    refresh_token     VARCHAR2(4000),
    -- NULL when the token does not expire.
    expires_at        TIMESTAMP WITH TIME ZONE,
    created_at        TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,
    updated_at        TIMESTAMP WITH TIME ZONE DEFAULT SYSTIMESTAMP NOT NULL,

    CONSTRAINT ic_tenant_fk   FOREIGN KEY (tenant_id) REFERENCES tenants(id) ON DELETE CASCADE,
    CONSTRAINT ic_user_fk     FOREIGN KEY (user_id)   REFERENCES users(id)   ON DELETE CASCADE,
    -- One connection per provider per tenant: reconnecting updates the row, it does not add one.
    CONSTRAINT uq_integration_tenant_provider UNIQUE (tenant_id, provider),
    -- Current list (V026). Named explicitly: Postgres generated this name inline in V024.
    CONSTRAINT ic_provider_chk CHECK (provider IN ('google','slack','github','notion','todoist',
                                                   'linear','microsoft','telegram','trello'))
);

CREATE INDEX idx_integration_connections_tenant ON integration_connections (tenant_id);
```

> **Encryption at rest is the application's job in both engines.** `TokenCipher` writes AES-256-GCM
> with a random IV per value, keyed from `NORA_INTEGRATIONS_ENC_KEY`; the column type is the same
> whether the value is ciphertext or a `plain:`-prefixed local-dev fallback, so the prefix is the
> only way to tell them apart. Nothing here uses Oracle TDE or `DBMS_CRYPTO` — that would be a
> different design, not a translation of this one, and claiming it would misdescribe the product.

## 20. Notable Postgres ↔ Oracle differences

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
| **Composite FK** | `FOREIGN KEY (a, b) REFERENCES t(a, b)` (the target needs a composite UNIQUE/PK) | identical — Oracle supports composite FKs natively; the target is the `UNIQUE (tenant_id, id)` (V015, §2). Used three times: `meetings` (V015, §6), the two IAM user-attachment tables (V027, §12) and `tenant_context_versions` (V028, §10), which is born with it rather than having it retrofitted. |
| **UNIQUE over an expression** | may be written as `CREATE UNIQUE INDEX … (tenant_id, LOWER(name))` (V017) | the index form is identical, but Oracle cannot express it as a table-level `CONSTRAINT … UNIQUE (…)` — an expression only goes in an index. `customer_accounts` (§15) therefore declares it outside the `CREATE TABLE`. |
| **Reserved words** | `role`, `type`, `text`, `position` are all usable as column names | Same here, and none of them needed quoting or renaming. `ROLE` (`CREATE ROLE`) and `TYPE` (`CREATE TYPE`) are Oracle **keywords** but **not reserved words** — they are absent from the reserved-word list in the Oracle SQL Language Reference — so `chat_message.role` (§17) and `customer_buying_signals.type` / `customer_objections.type` (§15) stay unquoted. Quoting one as `"ROLE"` would make the identifier case-sensitive at every reference, which is the worse trade. Note that `USER` and `SESSION` **are** reserved, but only as bare words: the columns `user_id` and `session_id` are unaffected. |
| **Vector / embedding type** | none in use — `pgvector` is **not** created; V021 stores a JSON array of floats in `TEXT` and similarity runs in Java | none needed. Oracle 19c has no vector type either (`VECTOR` arrives in 23ai); the column mirrors as `CLOB` (§16). |
| **Row-Level Security** | `ALTER TABLE … ENABLE ROW LEVEL SECURITY` + `CREATE POLICY … USING (…) WITH CHECK (…)`; context via a session GUC (`SET LOCAL`) + `NOBYPASSRLS` role (V016) | **VPD/FGAC**: `DBMS_RLS.ADD_POLICY` + a PL/SQL policy function that returns the predicate; context via an application context (`SYS_CONTEXT`); bypass via the `EXEMPT ACCESS POLICY` privilege. See §23. |
| **Disabling a policy without dropping it** | `ALTER TABLE … DISABLE ROW LEVEL SECURITY` — a **table** property; every policy on the table goes inert at once, and the policies stay defined (V020) | `DBMS_RLS.ENABLE_POLICY(…, enable => FALSE)` — a **per-policy** operation. Near-equivalent, not exact: see §23.4. |

## 21. Portability observations

- **Identifier length**: several names in this document exceed 30 bytes — the table
  `meeting_productivity_assessments` (32) and indexes such as
  `idx_meeting_outcome_coverage_assessment` (39) and `idx_workflow_executions_tenant`. Oracle
  raised the identifier limit from 30 to 128 bytes in **12.2**, so this is valid on the 19c+
  baseline this document targets and would **not** load on 11g. Names were kept identical to the
  Postgres ones rather than abbreviated, so the two schemas stay diffable.
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

## 22. Oracle ≡ Postgres inventory

Every migration V001–V032 appears in exactly one row below, so the two schemas can be checked
against each other line by line.

| # | Table | Postgres (migration) | Oracle (§ in this doc) |
|---|---|---|---|
| 1 | tenants | V001, V009 (`allowed_email_domain`) | §1 |
| 2 | users | V002, V003 (`email_verified_at`), V006 (`is_root`) | §2 |
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
| 18 | customer_accounts, meeting_account_links, customer_confidence_assessments, customer_buying_signals, customer_objections | V017 | §15 |
| 19 | iam_user_invitations.token → token_hash (SHA-256) | V018 | §13 |
| 20 | meeting_embeddings (semantic search) | V021 | §16 |
| 21 | chat_session, chat_message | V022 | §17 |
| 22 | workflows, workflow_executions | V023 | §18 |
| 23 | integration_connections | V024, V025 + V026 (provider CHECK) | §19 |
| 24 | composite FK iam_user_groups / iam_user_policies .(tenant_id, user_id) → users.(tenant_id, id) | V027 | §12 |
| 25 | tenant_context_versions + `tenant_contexts.current_version` (company-context history, US31) | V028 | §10 |
| 26 | mcp_tokens (inbound MCP credential, US27) | V029 | §13.1 |
| 27 | `meeting_action_items.completed_at` + its two aggregation indexes (trends panel, US21) | V030 | §11 |
| 28 | workflow_schedules (run state of a scheduled flow, US75) | V032 | §18.1 |
| 29 | Row-Level Security (RLS → VPD/DBMS_RLS), all waves | V016, V017, V019, V020, V021, V022, V023, V024, V028, V032 | §23 |

> **V027 carries a checksum warning on the Postgres side that has no Oracle counterpart.** The
> migration was edited after it had already been applied, so a database that ran the earlier
> version fails Flyway `validate` until someone runs `flyway repair` once. That is a migration-tool
> concern, not a schema one: the DDL in §12 is the end state either way. `data-model.md` §2.17
> records the detail, including that V027 **deletes** the pre-existing cross-tenant rows the new
> constraint cannot accept, reporting the counts via `RAISE NOTICE`.

## 23. Row-Level Security (V016 → V032) — Oracle equivalent: VPD / DBMS_RLS

In Postgres, migration V016 enables **Row-Level Security (RLS)**: each tenant-owned table gains `ALTER TABLE … ENABLE ROW LEVEL SECURITY` + a `tenant_isolation` policy whose predicate is `tenant_id = nora.current_tenant_id()`. The function reads a **session GUC** (`nora.current_tenant_id`) set by the Spring **`TenantRlsAspect`** via `SET LOCAL` at the start of each `@Transactional`. Enforcement is **opt-in in prod**: it only becomes real when the API connects with a dedicated role **without `BYPASSRLS`** (`nora_app NOBYPASSRLS`) and `nora.security.rls.enforce=true`; the owner/admin (used in dev/Testcontainers) bypasses by default, leaving the RLS schema inert without breaking tests.

V016 was the first of nine waves, not the whole story — **§23.4 has the full coverage**, including
the 13 tables V020 later took back out of enforcement.

The native equivalent in Oracle is **VPD (Virtual Private Database)**, also called *Fine-Grained Access Control (FGAC)*, configured via **`DBMS_RLS.ADD_POLICY`** + a **policy function** that returns a dynamic predicate (`WHERE` clause). The session context (Postgres's GUC) becomes an Oracle **application context** (`CREATE CONTEXT … USING …`), read with `SYS_CONTEXT`.

### 23.1 Application context (equivalent to the session GUC)

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

### 23.2 Policy function (dynamic predicate)

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

### 23.3 Applying the policy (representative: `meetings` and `users`)

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

The remaining tenant-owned tables covered by V016 follow **exactly the same pattern** (`DBMS_RLS.ADD_POLICY` with `NORA_TENANT_PREDICATE`): `tenant_contexts`, `refresh_tokens`, `iam_groups`, `iam_policies`, `iam_user_invitations`, `meeting_analyses` and `meeting_participants`. The `tenants` table is the only special case (it filters by `id`, not `tenant_id`) — and the same is true of every table added in the later waves listed in §23.4, none of which needs a different predicate.

### 23.4 Full coverage: nine waves, and the 13 tables V020 took back out

**36 tables carry a `tenant_isolation` policy**, added over nine migrations. Those 36, plus the
5 cascade-boundary children that deliberately get none, the 2 legacy tables outside the model and
`mcp_tokens` (V029, below), account for all 44 tables in this document.

| Wave | Count | Tables |
|---|---|---|
| **V016** | 10 | `meetings`, `tenants`, `tenant_contexts`, `users`, `refresh_tokens`, `iam_groups`, `iam_policies`, `iam_user_invitations`, `meeting_analyses`, `meeting_participants` |
| **V017** | 3 | `customer_accounts`, `meeting_account_links`, `customer_confidence_assessments` |
| **V019** | 15 | `transcripts` (the priority — `raw_text` is PII at rest), `meeting_tags`, `meeting_decisions`, `meeting_action_items`, `meeting_risks`, `meeting_opportunities`, `meeting_goals`, `meeting_productivity_assessments`, `iam_user_groups`, `iam_group_policies`, `iam_user_policies`, `iam_policy_versions`, `iam_audit_events`, `email_verification_tokens`, `password_reset_tokens` |
| **V021** | 1 | `meeting_embeddings` |
| **V022** | 2 | `chat_session`, `chat_message` |
| **V023** | 2 | `workflows`, `workflow_executions` |
| **V024** | 1 | `integration_connections` |
| **V028** | 1 | `tenant_context_versions` |
| **V032** | 1 | `workflow_schedules` |

**No policy, by design (5):** `iam_invitation_groups`, `meeting_goal_expected_outcomes`,
`meeting_outcome_coverage`, `customer_buying_signals`, `customer_objections` — children with no
`tenant_id` of their own, isolated through the FK cascade to their parent. In Oracle they get no
`DBMS_RLS.ADD_POLICY` call for the same reason: `NORA_TENANT_PREDICATE` references a `tenant_id`
column that does not exist on them.

**Outside the model (2):** `roles` and `user_roles`, the unused V002 RBAC tables (§3).

**No policy, by design (1 more) — `mcp_tokens` (V029, §13.1).** It joins the Identity family V020
took out of enforcement, and for the sharpest version of that family's reason: the lookup on
`token_hash` is *how a request learns which tenant it is in*, so the session context the predicate
would read is necessarily unset when it runs. A policy there would return zero rows for every MCP
request under enforcement — in Oracle exactly as in Postgres, since `SYS_CONTEXT` would be as empty
as the GUC. Isolation for that table is the application's `tenant_id` predicate plus the composite
FK to `users(tenant_id, id)`.

#### V020 disables 13 of the 36 — and it is a scope decision, not a retreat

ADR 0028 narrowed enforcement to **business data + PII**, the tables touched only by authenticated
requests or by the analysis pipeline, both of which set the tenant context. V020 turns RLS **off**
on two families that cannot be enforced without breaking flows that have no JWT — login, signup,
invitation acceptance, lookup by token hash, and the onboarding writes to authorization config:

- **(A) Identity, 6:** `users`, `tenants`, `email_verification_tokens`, `password_reset_tokens`, `refresh_tokens`, `iam_user_invitations`.
- **(B) IAM authorization, 7:** `iam_groups`, `iam_policies`, `iam_user_groups`, `iam_group_policies`, `iam_user_policies`, `iam_policy_versions`, `iam_audit_events`.

The policies stay **defined but inert**, so re-enabling is one statement rather than a recreation.
Isolation on those 13 continues through the application's `tenant_id` filter. **23 tables** are left
under enforcement.

Note what that means for §23.3: `USERS` is one of the 13. It is kept in the example above because it
is the clearest illustration of the pattern, but on the real schema its policy is added and then
disabled.

#### The Oracle equivalent of "policy defined, RLS disabled"

```sql
-- V020's ALTER TABLE users DISABLE ROW LEVEL SECURITY, in Oracle.
-- The policy stays in DBA_POLICIES with ENABLE = 'NO' — nothing is dropped, nothing is
-- recreated when it is turned back on.
BEGIN
    DBMS_RLS.ENABLE_POLICY(
        object_schema => 'NORA',
        object_name   => 'USERS',
        policy_name   => 'TENANT_ISOLATION',
        enable        => FALSE
    );
END;
/
```

**Is it an exact equivalent? For this schema, yes; in general, no** — and the difference is worth
stating rather than glossing:

- Postgres's `DISABLE ROW LEVEL SECURITY` is a property of the **table**: every policy on it goes
  inert at once, and any policy *added later* while RLS is off is also inert. Oracle's
  `ENABLE_POLICY` acts on **one named policy**: a table with several VPD policies needs one call
  each, and a policy added afterwards is enabled by default and starts filtering immediately.
- NORA has exactly **one** policy per table (`tenant_isolation`), so on this schema the two produce
  the same visible rows and the same reversibility. The gap is latent, not active.
- The bypass mechanisms are not the same object either: Postgres exempts the table owner and any
  role with `BYPASSRLS`; Oracle exempts users holding the `EXEMPT ACCESS POLICY` **system
  privilege**. This is why the Postgres-side role provisioning in `db/operational/R001` has no file
  to mirror here — the Oracle counterpart is a `GRANT`, not a schema object.
- `DBMS_RLS.DROP_POLICY` is the destructive alternative and is **not** what V020 corresponds to.
  Reaching for it would lose the "reversible without recreating" property the migration was
  written to keep.

### 23.5 Operational differences

| Aspect | Postgres (V016–V024) | Oracle (VPD) |
|---|---|---|
| Enabling | `ALTER TABLE … ENABLE ROW LEVEL SECURITY` + `CREATE POLICY` | `DBMS_RLS.ADD_POLICY(...)` per table |
| Disabling without dropping (V020) | `ALTER TABLE … DISABLE ROW LEVEL SECURITY` — table-level, policies survive | `DBMS_RLS.ENABLE_POLICY(…, enable => FALSE)` — per policy. See §23.4 |
| Predicate | SQL expression in the policy (`USING (...) WITH CHECK (...)`) | string returned by the PL/SQL **policy function** |
| Session context | GUC `nora.current_tenant_id` via `SET LOCAL` | application context `NORA_CTX` via `DBMS_SESSION.SET_CONTEXT` |
| Reading the context | `current_setting('nora.current_tenant_id', true)` | `SYS_CONTEXT('NORA_CTX','tenant_id')` |
| Write enforcement | `WITH CHECK` in the policy | `update_check => TRUE` parameter |
| Bypass (dev/admin) | owner or a role with `BYPASSRLS` | users with the `EXEMPT ACCESS POLICY` system privilege |
| Fail-closed (no context) | policy does not match → 0 rows (in a non-admin role) | policy function returns `1 = 0` → 0 rows |
| Driver in the app | `TenantRlsAspect` (Spring) sets the GUC per `@Transactional` | the same aspect would call `nora_session.set_tenant(...)` |
