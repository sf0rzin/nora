# Modelo de Dados — NORA (Postgres 16)

> Estado real do schema, alinhado com **migrations V001–V012** em `services/api/src/main/resources/db/migration/`.
> Cada tabela é mapeada para a migration de origem. Quando há **drift** entre o que estava documentado e o que está no banco, está marcado explicitamente.
> Multi-tenancy: coluna `tenant_id` em toda tabela tenant-bound (ADR 0002). RLS habilitado em produção é débito pendente.

---

## 1. Visão geral (ER)

```mermaid
erDiagram
  TENANTS ||--o{ USERS : has
  TENANTS ||--o{ IAM_GROUPS : defines
  TENANTS ||--o{ IAM_POLICIES : defines
  TENANTS ||--|| TENANT_CONTEXTS : "1:1"
  TENANTS ||--o{ MEETINGS : owns
  TENANTS ||--o{ IAM_USER_INVITATIONS : invites

  USERS ||--o{ IAM_USER_GROUPS : member
  IAM_GROUPS ||--o{ IAM_USER_GROUPS : has
  IAM_GROUPS ||--o{ IAM_GROUP_POLICIES : attached
  IAM_POLICIES ||--o{ IAM_GROUP_POLICIES : grants
  USERS ||--o{ IAM_USER_POLICIES : attached
  IAM_POLICIES ||--o{ IAM_USER_POLICIES : grants
  IAM_POLICIES ||--o{ IAM_POLICY_VERSIONS : history

  USERS ||--o{ MEETINGS : owns
  USERS ||--o{ REFRESH_TOKENS : has
  USERS ||--o{ EMAIL_VERIFICATION_TOKENS : has
  USERS ||--o{ PASSWORD_RESET_TOKENS : has

  MEETINGS ||--|| TRANSCRIPTS : has
  MEETINGS ||--o{ MEETING_PARTICIPANTS : has
  MEETINGS ||--o{ MEETING_TAGS : tagged
  MEETINGS ||--|| MEETING_ANALYSES : produces
  MEETING_ANALYSES ||--o{ MEETING_DECISIONS : extracts
  MEETING_ANALYSES ||--o{ MEETING_ACTION_ITEMS : extracts
  MEETING_ANALYSES ||--o{ MEETING_RISKS : extracts
  MEETING_ANALYSES ||--o{ MEETING_OPPORTUNITIES : extracts

  MEETINGS ||--|| MEETING_GOALS : "opt-in (Productivity)"
  MEETING_GOALS ||--o{ MEETING_GOAL_EXPECTED_OUTCOMES : has
  MEETINGS ||--|| MEETING_PRODUCTIVITY_ASSESSMENTS : produces
  MEETING_PRODUCTIVITY_ASSESSMENTS ||--o{ MEETING_OUTCOME_COVERAGE : covers
```

---

## 2. Tabelas

### 2.1 `tenants` — V001

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | extensão `pgcrypto` |
| `name` | `TEXT NOT NULL` | |
| `slug` | `TEXT NOT NULL UNIQUE` | usado em URLs |
| `status` | `TEXT NOT NULL DEFAULT 'ACTIVE'` | CHECK: `ACTIVE`, `SUSPENDED` |
| `plan` | `TEXT NOT NULL DEFAULT 'FREE'` | CHECK: `FREE`, `PRO`, `ENTERPRISE` |
| `allowed_email_domain` | `VARCHAR(255)` | **V009** — domínio corporativo. NULL = sem restrição |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_tenants_status(status)`.

**Propósito**: raiz de tudo. Toda tabela tenant-bound referencia `tenants(id)`. `allowed_email_domain` adicionada em V009 (US32, ADR 0011) para restringir convites a um domínio corporativo.

---

### 2.2 `users` — V002 (+ alterações V003, V006)

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE RESTRICT` | |
| `email` | `CITEXT NOT NULL` | extensão `citext` (case-insensitive); UNIQUE por `(tenant_id, email)` |
| `password_hash` | `TEXT NOT NULL` | bcrypt/argon2 |
| `display_name` | `TEXT NOT NULL` | |
| `status` | `TEXT NOT NULL DEFAULT 'ACTIVE'` | CHECK: `ACTIVE`, `INVITED`, `DISABLED` |
| `email_verified_at` | `TIMESTAMPTZ` | **V003**. NULL = não verificado |
| `is_root` | `BOOLEAN NOT NULL DEFAULT FALSE` | **V006**. Exatamente um por tenant (índice parcial UNIQUE) |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_users_tenant(tenant_id)`, `uq_users_root_per_tenant ON users(tenant_id) WHERE is_root = TRUE` (V006:26-27 — garante 1 Root por tenant).

**Propósito**: identidade. Root tem bypass total em `AuthorizationService:41`.

---

### 2.3 `roles` e `user_roles` — V002 (legado, **não usado**)

Tabelas criadas em V002 para um modelo RBAC inicial (ROLES: `ROOT`, `ADMIN`, `MANAGER`, `ANALYST`, `VIEWER`) que foi **substituído** pelo IAM AWS-style em V006 (ADR 0007).

Status: **órfãs**. Comentário em `V006:7-9` indica "remoção em migration futura". Mantidas por compatibilidade enquanto não houver migration de drop.

| Tabela | Colunas resumidas | Notas |
|---|---|---|
| `roles` | `id, tenant_id, code, description, is_system, created_at` | CHECK code IN (`ROOT`,`ADMIN`,`MANAGER`,`ANALYST`,`VIEWER`). Tenant_id NULL para roles globais |
| `user_roles` | `user_id, role_id, tenant_id, granted_at` | PK composta `(user_id, role_id)` |

> **Débito**: drop tables em migration futura. Nenhum código atual lê/escreve nelas.

---

### 2.4 `email_verification_tokens` — V003

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `user_id` | `UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `token_hash` | `TEXT NOT NULL UNIQUE` | SHA-256 do token plain |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | |
| `consumed_at` | `TIMESTAMPTZ` | NULL = não consumido |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_email_verif_tokens_user(user_id)`, `idx_email_verif_tokens_expires(expires_at)`.

**Propósito**: US02 (verificação por e-mail). Token plain só existe no e-mail; vazamento do banco não permite reuso.

---

### 2.5 `password_reset_tokens` — V003

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `user_id` | `UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `token_hash` | `TEXT NOT NULL UNIQUE` | SHA-256 |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | |
| `consumed_at` | `TIMESTAMPTZ` | |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_pwd_reset_tokens_user(user_id)`, `idx_pwd_reset_tokens_expires(expires_at)`.

**Propósito**: US04 (reset de senha).

---

### 2.6 `meetings` — V004 (+ alterações V007, V008)

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `owner_user_id` | `UUID NOT NULL REFERENCES users(id) ON DELETE RESTRICT` | |
| `title` | `TEXT NOT NULL` | |
| `started_at` | `TIMESTAMPTZ` | |
| `ended_at` | `TIMESTAMPTZ` | |
| `language` | `TEXT NOT NULL DEFAULT 'pt-BR'` | |
| `transcript_format` | `TEXT NOT NULL` | CHECK: `TXT`, `VTT`, `SRT` |
| `processing_status` | `TEXT NOT NULL DEFAULT 'PENDING'` | CHECK: `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` |
| `summary_snippet` | `TEXT` | preview curto na listagem |
| `attributes` | `JSONB NOT NULL DEFAULT '{}'::jsonb` | **V007**. Pares chave/valor arbitrários (`department`, `region`, etc.) usados em IAM conditions (ADR 0007) |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**:
- `idx_meetings_tenant_created(tenant_id, created_at DESC)`
- `idx_meetings_owner(owner_user_id)`
- `idx_meetings_status(tenant_id, processing_status)`
- `idx_meetings_attributes_gin USING GIN (attributes jsonb_path_ops)` — **V008**, acelera `attributes @>` para conditions IAM.

**Propósito**: reunião. `attributes` permite scoping fino sem schema rígido (ex.: `{"department":"Vendas"}` casa com condition `StringEquals nora:Department=Vendas`).

> **Drift removido** (vs. doc antigo): a coluna `tags TEXT[]` não existe. Tags vivem em tabela separada `meeting_tags` (ver 2.8).

---

### 2.7 `meeting_participants` — V004

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `meeting_id` | `UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `display_name` | `TEXT NOT NULL` | |
| `email` | `TEXT` | nullable |
| `is_internal` | `BOOLEAN NOT NULL DEFAULT FALSE` | |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_meeting_participants_meeting(meeting_id)`, `idx_meeting_participants_tenant(tenant_id)`.

---

### 2.8 `meeting_tags` — V004

> **Não estava no doc antigo.** Tabela real desde V004.

| Coluna | Tipo | Notas |
|---|---|---|
| `meeting_id` | `UUID NOT NULL REFERENCES meetings(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `tag` | `TEXT NOT NULL` | |
| **PK** | `(meeting_id, tag)` | |

**Indexes**: `idx_meeting_tags_tenant_tag(tenant_id, tag)`.

**Propósito**: N:N tag↔meeting. Substitui o `tags TEXT[]` previsto no doc antigo. Permite indexação por tag e queries eficientes (`WHERE tenant_id = ? AND tag = ?`).

---

### 2.9 `transcripts` — V004

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `meeting_id` | `UUID NOT NULL UNIQUE REFERENCES meetings(id) ON DELETE CASCADE` | 1:1 com meeting |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `format` | `TEXT NOT NULL` | CHECK: `TXT`, `VTT`, `SRT` |
| `raw_text` | `TEXT NOT NULL` | **conteúdo inline** — não há `storage_uri` |
| `char_count` | `INTEGER NOT NULL CHECK (char_count >= 0)` | |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_transcripts_tenant(tenant_id)`.

> **Drift removido**: o doc antigo previa `storage_uri TEXT NOT NULL` + `sha256 TEXT NOT NULL` + `word_count INTEGER`. A migration real (V004:52-63) armazena `raw_text` inline, sem `storage_uri`, sem `sha256`, sem `word_count`.

---

### 2.10 `tenant_contexts` — V005

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `tenant_id` | `UUID NOT NULL UNIQUE REFERENCES tenants(id) ON DELETE CASCADE` | 1:1 |
| `document` | `JSONB NOT NULL` | normalizado; validação estrutural fica no domain/Pydantic |
| `updated_by` | `UUID REFERENCES users(id) ON DELETE SET NULL` | |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_tenant_contexts_tenant(tenant_id)`.

> **Débito (US31)**: o doc antigo previa coluna `version INTEGER NOT NULL` para versionamento de contexto. **Não existe na realidade.** Hoje só `updated_at` permite ver "quando mudou", sem histórico. Migration V014+ trivial resolveria.

---

### 2.11 `meeting_analyses` — V005

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `meeting_id` | `UUID NOT NULL UNIQUE REFERENCES meetings(id) ON DELETE CASCADE` | 1:1 |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `summary` | `TEXT NOT NULL` | markdown |
| `sentiment_overall` | `TEXT NOT NULL` | CHECK: `POSITIVE`, `NEUTRAL`, `NEGATIVE`, `MIXED` |
| `topics` | `TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[]` | |
| `model_version` | `TEXT` | ex.: `gpt-4o-mini-2024-07-18` |
| `prompt_version` | `TEXT` | ex.: `meeting-analysis-v1` |
| `tokens_input` | `INTEGER NOT NULL DEFAULT 0 CHECK (>= 0)` | |
| `tokens_output` | `INTEGER NOT NULL DEFAULT 0 CHECK (>= 0)` | |
| `processing_millis` | `INTEGER NOT NULL DEFAULT 0 CHECK (>= 0)` | |
| `pii_redactions_applied` | `INTEGER NOT NULL DEFAULT 0` | |
| `generated_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_meeting_analyses_tenant(tenant_id)`, `idx_meeting_analyses_meeting(meeting_id)`.

**Propósito**: persistência da análise gerada pelo worker. Espelha `meeting-analysis-v1.schema.json` (ADR 0003).

---

### 2.12 `meeting_decisions` — V005

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `analysis_id` | `UUID NOT NULL REFERENCES meeting_analyses(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `text` | `TEXT NOT NULL` | |
| `confidence` | `NUMERIC(3,2) NOT NULL CHECK (0.0 <= x <= 1.0)` | |
| `position` | `INTEGER NOT NULL CHECK (>= 0)` | ordem da decisão na análise |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_meeting_decisions_analysis(analysis_id)`, `idx_meeting_decisions_tenant(tenant_id)`.

---

### 2.13 `meeting_action_items` — V005

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `analysis_id` | `UUID NOT NULL REFERENCES meeting_analyses(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `title` | `TEXT NOT NULL` | |
| `assignee` | `TEXT` | nome cru extraído da fala (pode ser ambíguo) |
| `due_date` | `DATE` | |
| `priority` | `TEXT NOT NULL` | CHECK: `LOW`, `MEDIUM`, `HIGH` |
| `source_quote` | `TEXT NOT NULL` | citação que sustenta o item |
| `status` | `TEXT NOT NULL DEFAULT 'OPEN'` | **CHECK: `OPEN`, `IN_PROGRESS`, `DONE`** (V005:92-93) |
| `position` | `INTEGER NOT NULL CHECK (>= 0)` | |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_meeting_action_items_analysis(analysis_id)`, `idx_meeting_action_items_tenant(tenant_id)`, `idx_meeting_action_items_status(tenant_id, status)`.

> **Drift corrigido**: o doc antigo listava `status IN (..., 'CANCELLED')`. **CANCELLED não existe** na CHECK constraint real (V005:92-93). Status válidos: apenas `OPEN`, `IN_PROGRESS`, `DONE`.

> **Drift removido**: a coluna `assignee_user_id UUID` não existe; apenas `assignee TEXT` (nome cru).

---

### 2.14 `meeting_risks` — V005

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `analysis_id` | `UUID NOT NULL REFERENCES meeting_analyses(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `text` | `TEXT NOT NULL` | |
| `severity` | `TEXT NOT NULL` | CHECK: `LOW`, `MEDIUM`, `HIGH` |
| `category` | `TEXT NOT NULL` | CHECK: `COMPETITION`, `PRICE`, `CHURN`, `TIMELINE`, `TECHNICAL`, `COMPLIANCE`, `OTHER` |
| `source_quote` | `TEXT NOT NULL` | |
| `position` | `INTEGER NOT NULL CHECK (>= 0)` | |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_meeting_risks_analysis(analysis_id)`, `idx_meeting_risks_tenant(tenant_id)`.

---

### 2.15 `meeting_opportunities` — V005

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `analysis_id` | `UUID NOT NULL REFERENCES meeting_analyses(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `text` | `TEXT NOT NULL` | |
| `estimated_value` | `TEXT NOT NULL` | CHECK: `LOW`, `MEDIUM`, `HIGH` |
| `category` | `TEXT NOT NULL` | CHECK: `UPSELL`, `CROSS_SELL`, `REFERRAL`, `EXPANSION`, `OTHER` |
| `source_quote` | `TEXT NOT NULL` | |
| `position` | `INTEGER NOT NULL CHECK (>= 0)` | |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_meeting_opportunities_analysis(analysis_id)`, `idx_meeting_opportunities_tenant(tenant_id)`.

---

### 2.16 `iam_groups` — V006

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `name` | `TEXT NOT NULL` | UNIQUE por `(tenant_id, name)` |
| `description` | `TEXT` | |
| `created_by` | `UUID REFERENCES users(id) ON DELETE SET NULL` | |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_iam_groups_tenant(tenant_id)`.

---

### 2.17 `iam_user_groups` — V006

| Coluna | Tipo | Notas |
|---|---|---|
| `user_id` | `UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE` | |
| `group_id` | `UUID NOT NULL REFERENCES iam_groups(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `attached_by` | `UUID REFERENCES users(id) ON DELETE SET NULL` | |
| `attached_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| **PK** | `(user_id, group_id)` | |

**Indexes**: `idx_iam_user_groups_tenant(tenant_id)`, `idx_iam_user_groups_group(group_id)`.

---

### 2.18 `iam_policies` — V006

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `name` | `TEXT NOT NULL` | UNIQUE por `(tenant_id, name)` |
| `description` | `TEXT` | |
| `document` | `JSONB NOT NULL` | statements estilo AWS IAM (Effect/Action/Resource/Condition) |
| `current_version` | `INTEGER NOT NULL DEFAULT 1 CHECK (>= 1)` | |
| `created_by` | `UUID REFERENCES users(id) ON DELETE SET NULL` | |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_iam_policies_tenant(tenant_id)`.

> **Drift removido**: o doc antigo previa `is_template BOOLEAN NOT NULL DEFAULT FALSE`. **Não existe** na migration real (V006:69-82). Templates oficiais (US41) são feature pendente.

Formato esperado de `document`:

```json
{
  "version": "2026-05-07",
  "statements": [
    {
      "effect": "Allow",
      "action": ["meeting:read"],
      "resource": ["nora:tenant/{tenantId}:meeting/*"],
      "condition": {
        "StringEquals": { "nora:Department": "Vendas" }
      }
    }
  ]
}
```

---

### 2.19 `iam_policy_versions` — V006

| Coluna | Tipo | Notas |
|---|---|---|
| `policy_id` | `UUID NOT NULL REFERENCES iam_policies(id) ON DELETE CASCADE` | |
| `version` | `INTEGER NOT NULL CHECK (>= 1)` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `document` | `JSONB NOT NULL` | snapshot imutável |
| `created_by` | `UUID REFERENCES users(id) ON DELETE SET NULL` | |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| **PK** | `(policy_id, version)` | |

**Indexes**: `idx_iam_policy_versions_tenant(tenant_id)`.

**Propósito**: histórico imutável de policies (auditoria e rollback).

---

### 2.20 `iam_group_policies` — V006

| Coluna | Tipo | Notas |
|---|---|---|
| `group_id` | `UUID NOT NULL REFERENCES iam_groups(id) ON DELETE CASCADE` | |
| `policy_id` | `UUID NOT NULL REFERENCES iam_policies(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `attached_by` | `UUID REFERENCES users(id) ON DELETE SET NULL` | |
| `attached_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| **PK** | `(group_id, policy_id)` | |

**Indexes**: `idx_iam_group_policies_tenant(tenant_id)`, `idx_iam_group_policies_policy(policy_id)`.

---

### 2.21 `iam_user_policies` — V006

| Coluna | Tipo | Notas |
|---|---|---|
| `user_id` | `UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE` | |
| `policy_id` | `UUID NOT NULL REFERENCES iam_policies(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `attached_by` | `UUID REFERENCES users(id) ON DELETE SET NULL` | |
| `attached_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| **PK** | `(user_id, policy_id)` | |

**Indexes**: `idx_iam_user_policies_tenant(tenant_id)`, `idx_iam_user_policies_policy(policy_id)`.

---

### 2.22 `iam_audit_events` — V006

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `actor_user_id` | `UUID REFERENCES users(id) ON DELETE SET NULL` | |
| `action` | `TEXT NOT NULL` | ex.: `iam:group:create`, `iam:policy:attach` |
| `target_type` | `TEXT NOT NULL` | ex.: `GROUP`, `POLICY`, `USER`, `MEMBERSHIP`, `ATTACHMENT` |
| `target_id` | `UUID` | |
| `payload` | `JSONB` | contexto adicional (nome, ids, etc.) |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_iam_audit_events_tenant_created(tenant_id, created_at DESC)`.

> **Drift relevante**: o doc antigo previa uma tabela `audit_events` **global** (cobrindo LOGIN, MEETING_UPLOAD, CONTEXT_UPDATE etc.). **Não existe.** Apenas `iam_audit_events` (escopo IAM). Demais ações não são auditadas em tabela.
>
> **Débito conhecido** (audit §6, severity Média): criar `audit_events` global para compliance LGPD.

---

### 2.23 `iam_user_invitations` e `iam_invitation_groups` — V010

#### `iam_user_invitations`

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `email` | `VARCHAR(255) NOT NULL` | |
| `token` | `VARCHAR(128) NOT NULL UNIQUE` | UUID em texto |
| `status` | `VARCHAR(20) NOT NULL DEFAULT 'PENDING'` | CHECK: `PENDING`, `ACCEPTED`, `EXPIRED`, `REVOKED` |
| `invited_by` | `UUID NOT NULL REFERENCES users(id)` | |
| `invited_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | |
| `accepted_at` | `TIMESTAMPTZ` | |
| `accepted_user_id` | `UUID REFERENCES users(id)` | |

**Indexes**: `idx_iam_invitations_tenant_status(tenant_id, status)`, `idx_iam_invitations_token(token)`, `idx_iam_invitations_email(tenant_id, email)`.

#### `iam_invitation_groups`

| Coluna | Tipo | Notas |
|---|---|---|
| `invitation_id` | `UUID NOT NULL REFERENCES iam_user_invitations(id) ON DELETE CASCADE` | |
| `group_id` | `UUID NOT NULL REFERENCES iam_groups(id) ON DELETE CASCADE` | |
| **PK** | `(invitation_id, group_id)` | |

**Propósito**: US06 (convite por e-mail), ADR 0011. Idempotência (mesmo email PENDING) é tratada em `InvitationService`.

---

### 2.24 `refresh_tokens` — V011

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK` | |
| `user_id` | `UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `token_hash` | `VARCHAR(255) NOT NULL UNIQUE` | SHA-256 do token plain |
| `expires_at` | `TIMESTAMPTZ NOT NULL` | |
| `revoked_at` | `TIMESTAMPTZ` | NULL = ativo |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| `last_used_at` | `TIMESTAMPTZ` | atualizado em cada `/auth/refresh` |

**Indexes**:
- `idx_refresh_tokens_user(user_id) WHERE revoked_at IS NULL` (lookup de ativos)
- `idx_refresh_tokens_hash(token_hash)` (validação em refresh)

**Propósito**: Sub-fase 1.3 (PR #59). Access JWT curto (15min) + refresh stateful (30 dias) revogável. Plain só existe no cookie `nora_refresh` httpOnly.

---

### 2.25 `meeting_goals` — V012

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `meeting_id` | `UUID NOT NULL UNIQUE REFERENCES meetings(id) ON DELETE CASCADE` | 1:1 |
| `purpose` | `TEXT NOT NULL` | descrição livre do objetivo |
| `project_state_snapshot` | `TEXT` | "o que está feito"; manual no MVP |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |
| `updated_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_meeting_goals_tenant(tenant_id)`.

**Propósito**: Productivity Score opt-in (ADR 0005). Sem MeetingGoal, nada é emitido.

> **Drift removido**: o doc antigo previa coluna `enabled BOOLEAN NOT NULL` e `expected_outcomes JSONB`. A migration real (V012) separa outcomes em tabela própria (ver 2.26) e dispensa `enabled` (existência do registro já indica opt-in).

---

### 2.26 `meeting_goal_expected_outcomes` — V012

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `meeting_goal_id` | `UUID NOT NULL REFERENCES meeting_goals(id) ON DELETE CASCADE` | |
| `outcome_text` | `TEXT NOT NULL` | |
| `position` | `INTEGER NOT NULL CHECK (>= 0)` | ordem do outcome |

**Indexes**: `idx_meeting_goal_outcomes_goal(meeting_goal_id)`.

**Propósito**: lista ordenada de outcomes esperados para o LLM avaliar.

> Nota: esta tabela **não tem `tenant_id` próprio** — é filha direta de `meeting_goals` que já está tenant-scoped, e cascade ON DELETE cobre limpeza.

---

### 2.27 `meeting_productivity_assessments` — V012

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `tenant_id` | `UUID NOT NULL REFERENCES tenants(id) ON DELETE CASCADE` | |
| `meeting_id` | `UUID NOT NULL UNIQUE REFERENCES meetings(id) ON DELETE CASCADE` | 1:1 |
| `score` | `INTEGER NOT NULL CHECK (BETWEEN 0 AND 100)` | |
| `band` | `VARCHAR(10) NOT NULL` | CHECK: `LOW`, `MEDIUM`, `HIGH` |
| `off_topic_ratio` | `NUMERIC(4,3)` | 0.000–1.000 |
| `decision_density` | `NUMERIC(4,3)` | 0.000–1.000 |
| `rationale` | `TEXT NOT NULL` | justificativa do LLM |
| `created_at` | `TIMESTAMPTZ NOT NULL DEFAULT NOW()` | |

**Indexes**: `idx_meeting_productivity_tenant(tenant_id)`.

> **Drift relevante**: o doc antigo amarrava o assessment a `meeting_analyses(id)` via FK. A migration real (V012:46) amarra diretamente a `meetings(id)` UNIQUE — 1:1 com meeting, não com analysis.

---

### 2.28 `meeting_outcome_coverage` — V012

| Coluna | Tipo | Notas |
|---|---|---|
| `id` | `UUID PK DEFAULT gen_random_uuid()` | |
| `assessment_id` | `UUID NOT NULL REFERENCES meeting_productivity_assessments(id) ON DELETE CASCADE` | |
| `expected_outcome` | `TEXT NOT NULL` | espelha o item do goal |
| `status` | `VARCHAR(20) NOT NULL` | CHECK: `ADDRESSED`, `PARTIAL`, `MISSED` |
| `evidence` | `TEXT` | citação que sustenta o status |
| `position` | `INTEGER NOT NULL CHECK (>= 0)` | |

**Indexes**: `idx_meeting_outcome_coverage_assessment(assessment_id)`.

> Nota: esta tabela também não tem `tenant_id` próprio (cascade via `assessment_id`).

---

## 3. Tabelas planejadas mas **não migradas**

Listadas em ADR 0006 e/ou `data-model.md` antigo, mas **sem migration correspondente** (V001–V012 não cobrem). Persistência é débito conhecido (audit §6, severity Alta para a narrativa Plano A).

| Tabela | Origem | Status |
|---|---|---|
| `customer_accounts` | ADR 0006 | sem migration |
| `customer_confidence_assessments` | ADR 0006 | sem migration |
| `customer_buying_signals` | ADR 0006 | sem migration |
| `customer_objections` | ADR 0006 | sem migration |
| `meeting_account_links` | ADR 0006 | sem migration |
| `account_health_snapshots` | ADR 0006 | sem migration |
| `audit_events` (global) | data-model antigo | sem migration; só `iam_audit_events` existe |

O schema LLM para Customer Confidence **já existe** (`meeting-analysis-v1.schema.json:117-167`), mas a persistência foi adiada. **ADR 0015** (a ser criado na Sub-fase 1.10/1.11) decide entre:

- **A** implementar mínimo na 1.11 (1 tabela `customer_confidence_assessments` + endpoint + UI básica)
- **B** remover Customer Health da landing até existir persistência

---

## 4. Regras de integridade

- Toda tabela tenant-bound carrega `tenant_id` explícito (ADR 0002). Queries da aplicação **sempre** filtram por `tenant_id` antes de `id`.
- `meeting_analyses` é 1:1 com `meeting`. Reprocessar **substitui** (não versiona — débito futuro se necessário).
- Deletar `meeting` cascateia `transcripts`, `meeting_participants`, `meeting_tags`, `meeting_analyses` (+ filhos), `meeting_goals` (+ outcomes), `meeting_productivity_assessments` (+ coverage).
- Deletar `tenant` em prod é proibido — usar `status = SUSPENDED`.
- Tokens (`email_verification_tokens`, `password_reset_tokens`, `refresh_tokens`) cascateiam por user.
- Convites cascateiam por tenant.

---

## 5. Inventário completo de migrations

| Migration | Conteúdo |
|---|---|
| **V001** | `tenants` |
| **V002** | `users`, `roles` (legado), `user_roles` (legado) |
| **V003** | `email_verification_tokens`, `password_reset_tokens`, `users.email_verified_at` |
| **V004** | `meetings`, `meeting_participants`, `meeting_tags`, `transcripts` |
| **V005** | `tenant_contexts`, `meeting_analyses`, `meeting_decisions`, `meeting_action_items`, `meeting_risks`, `meeting_opportunities` |
| **V006** | `users.is_root`, `iam_groups`, `iam_user_groups`, `iam_policies`, `iam_policy_versions`, `iam_group_policies`, `iam_user_policies`, `iam_audit_events` |
| **V007** | `meetings.attributes JSONB` |
| **V008** | `idx_meetings_attributes_gin` (GIN índice para attributes) |
| **V009** | `tenants.allowed_email_domain` |
| **V010** | `iam_user_invitations`, `iam_invitation_groups` |
| **V011** | `refresh_tokens` |
| **V012** | `meeting_goals`, `meeting_goal_expected_outcomes`, `meeting_productivity_assessments`, `meeting_outcome_coverage` |

---

## 6. Considerações acadêmicas (Oracle)

A rubrica FIAP de Database Design exige modelo em Oracle. O espelho está em **`docs/engineering/data-model-oracle.md`** — DDL Oracle equivalente para cada tabela documentada acima, mantendo Postgres como banco de produção.
