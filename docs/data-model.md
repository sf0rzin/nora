# Modelo de Dados — NORA (MVP)

> Postgres 16. Multi-tenant via coluna `tenant_id` em todas as tabelas tenant-bound.
> RLS (Row-Level Security) será habilitado em produção; no MVP, a regra é forçada por filtro de aplicação coberto por testes de isolamento.

---

## 1. Visão Geral (ER)

```mermaid
erDiagram
  TENANTS ||--o{ USERS : has
  TENANTS ||--o{ ROLES : defines
  TENANTS ||--|| TENANT_CONTEXTS : "1:1"
  TENANTS ||--o{ TEAMS : has
  TENANTS ||--o{ MEETINGS : owns
  USERS ||--o{ USER_ROLES : assigned
  ROLES ||--o{ USER_ROLES : grants
  USERS ||--o{ USER_TEAMS : member
  TEAMS ||--o{ USER_TEAMS : has
  USERS ||--o{ MEETINGS : owns
  MEETINGS ||--|| TRANSCRIPTS : has
  MEETINGS ||--o{ MEETING_PARTICIPANTS : has
  MEETINGS ||--|| MEETING_ANALYSES : produces
  MEETING_ANALYSES ||--o{ ACTION_ITEMS : extracts
  MEETING_ANALYSES ||--o{ DECISIONS : extracts
  MEETING_ANALYSES ||--o{ RISKS : extracts
  MEETING_ANALYSES ||--o{ OPPORTUNITIES : extracts
  TENANT_CONTEXTS ||--o{ TENANT_CONTEXT_CHUNKS : "indexed as"
```

---

## 2. Tabelas

### 2.1 `tenants`

| Coluna | Tipo | Notas |
|---|---|---|
| id | uuid PK | |
| name | text NOT NULL | |
| slug | text UNIQUE NOT NULL | usado em URLs |
| status | text NOT NULL | `ACTIVE`, `SUSPENDED` |
| plan | text NOT NULL | `FREE`, `PRO`, `ENTERPRISE` |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |

### 2.2 `users`

| Coluna | Tipo | Notas |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid NOT NULL FK→tenants(id) | |
| email | citext NOT NULL | UNIQUE por (tenant_id, email) |
| password_hash | text NOT NULL | bcrypt/argon2 |
| display_name | text NOT NULL | |
| status | text NOT NULL | `ACTIVE`, `INVITED`, `DISABLED` |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |

Índices: `(tenant_id, email)` UNIQUE.

### 2.3 `roles`

Roles fixas no MVP: `ADMIN`, `MANAGER`, `MEMBER`. Tabela existe para suportar custom roles pós-MVP.

| Coluna | Tipo | Notas |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid NULL | NULL = role global do sistema |
| code | text NOT NULL | `ADMIN`, `MANAGER`, `MEMBER` |
| description | text | |
| is_system | boolean NOT NULL DEFAULT true | |

### 2.4 `user_roles`

| Coluna | Tipo |
|---|---|
| user_id | uuid FK→users(id) |
| role_id | uuid FK→roles(id) |
| tenant_id | uuid NOT NULL |
| PK | (user_id, role_id) |

### 2.5 `teams`

| Coluna | Tipo |
|---|---|
| id | uuid PK |
| tenant_id | uuid NOT NULL |
| name | text NOT NULL |
| created_at | timestamptz NOT NULL |

### 2.6 `user_teams`

| Coluna | Tipo |
|---|---|
| user_id | uuid |
| team_id | uuid |
| tenant_id | uuid NOT NULL |
| PK | (user_id, team_id) |

### 2.7 `access_scopes`

Define o escopo de visibilidade de cada usuário (US19/US20/US36).

| Coluna | Tipo | Notas |
|---|---|---|
| user_id | uuid PK FK→users(id) | |
| tenant_id | uuid NOT NULL | |
| scope_type | text NOT NULL | `ALL_TENANT`, `OWN_MEETINGS`, `TEAMS`, `REGIONS` |
| team_ids | uuid[] | |
| region_ids | uuid[] | |

### 2.8 `meetings`

| Coluna | Tipo | Notas |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid NOT NULL | |
| owner_id | uuid NOT NULL FK→users(id) | |
| title | text NOT NULL | |
| started_at | timestamptz NOT NULL | |
| ended_at | timestamptz | |
| duration_seconds | integer | |
| language | text NOT NULL DEFAULT 'pt-BR' | |
| processing_status | text NOT NULL | `PENDING`, `PROCESSING`, `COMPLETED`, `FAILED` |
| processing_error | text | |
| tags | text[] | |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |

Índices: `(tenant_id, started_at DESC)`, `(tenant_id, owner_id, started_at DESC)`, `(tenant_id, processing_status)`.

### 2.9 `meeting_participants`

| Coluna | Tipo |
|---|---|
| id | uuid PK |
| tenant_id | uuid NOT NULL |
| meeting_id | uuid NOT NULL FK→meetings(id) ON DELETE CASCADE |
| display_name | text NOT NULL |
| email | text |
| is_internal | boolean NOT NULL DEFAULT false |

### 2.10 `transcripts`

| Coluna | Tipo | Notas |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid NOT NULL | |
| meeting_id | uuid UNIQUE NOT NULL FK→meetings(id) | |
| format | text NOT NULL | `TXT`, `VTT`, `SRT` |
| storage_uri | text NOT NULL | caminho local no MVP, blob URL depois |
| sha256 | text NOT NULL | dedup futura |
| char_count | integer NOT NULL | |
| word_count | integer NOT NULL | |
| created_at | timestamptz NOT NULL | |

### 2.11 `meeting_analyses`

| Coluna | Tipo | Notas |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid NOT NULL | |
| meeting_id | uuid UNIQUE NOT NULL FK→meetings(id) | |
| summary | text NOT NULL | |
| sentiment_overall | text | |
| topics | text[] | |
| model_version | text NOT NULL | |
| prompt_version | text NOT NULL | |
| tokens_input | integer | |
| tokens_output | integer | |
| processing_millis | integer | |
| pii_redactions_applied | integer | |
| generated_at | timestamptz NOT NULL | |

### 2.12 `decisions`

| Coluna | Tipo |
|---|---|
| id | uuid PK |
| tenant_id | uuid NOT NULL |
| analysis_id | uuid NOT NULL FK→meeting_analyses(id) ON DELETE CASCADE |
| text | text NOT NULL |
| confidence | numeric(3,2) |
| ordinal | integer NOT NULL |

### 2.13 `action_items`

| Coluna | Tipo | Notas |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid NOT NULL | |
| analysis_id | uuid NOT NULL FK→meeting_analyses(id) ON DELETE CASCADE | |
| title | text NOT NULL | |
| assignee | text | nome cru extraído da fala |
| assignee_user_id | uuid | resolução opcional para `users` |
| due_date | date | |
| priority | text NOT NULL | `LOW`, `MEDIUM`, `HIGH` |
| status | text NOT NULL DEFAULT 'OPEN' | `OPEN`, `IN_PROGRESS`, `DONE`, `CANCELLED` |
| source_quote | text NOT NULL | |
| created_at | timestamptz NOT NULL | |
| updated_at | timestamptz NOT NULL | |

Índices: `(tenant_id, status, due_date)`, `(tenant_id, assignee_user_id)`.

### 2.14 `risks`

| Coluna | Tipo |
|---|---|
| id | uuid PK |
| tenant_id | uuid NOT NULL |
| analysis_id | uuid NOT NULL FK→meeting_analyses(id) ON DELETE CASCADE |
| text | text NOT NULL |
| severity | text NOT NULL |
| category | text NOT NULL |
| source_quote | text NOT NULL |

### 2.15 `opportunities`

| Coluna | Tipo |
|---|---|
| id | uuid PK |
| tenant_id | uuid NOT NULL |
| analysis_id | uuid NOT NULL FK→meeting_analyses(id) ON DELETE CASCADE |
| text | text NOT NULL |
| estimated_value | text NOT NULL |
| category | text NOT NULL |
| source_quote | text NOT NULL |

### 2.16 `tenant_contexts`

| Coluna | Tipo | Notas |
|---|---|---|
| tenant_id | uuid PK FK→tenants(id) | |
| version | integer NOT NULL | incrementa a cada `PUT` |
| company_name | text NOT NULL | |
| industry | text | |
| value_proposition | text NOT NULL | |
| products | jsonb NOT NULL DEFAULT '[]' | |
| competitors | text[] NOT NULL DEFAULT '{}' | |
| ideal_customer_profile | text | |
| commercial_playbook | text[] NOT NULL DEFAULT '{}' | |
| glossary | jsonb NOT NULL DEFAULT '[]' | |
| updated_at | timestamptz NOT NULL | |
| updated_by | uuid FK→users(id) | |

### 2.17 `tenant_context_chunks`

| Coluna | Tipo | Notas |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid NOT NULL | |
| chunk_type | text NOT NULL | `COMPANY_OVERVIEW`, `PRODUCT`, `PLAYBOOK`, `GLOSSARY`, `COMPETITOR`, `ICP` |
| title | text | |
| content | text NOT NULL | |
| version | integer NOT NULL | |
| updated_at | timestamptz NOT NULL | |

> O índice vetorial vive no Azure AI Search; esta tabela é a fonte da verdade para reconstrução.

### 2.18 `audit_events`

| Coluna | Tipo | Notas |
|---|---|---|
| id | uuid PK | |
| tenant_id | uuid | NULL para eventos de plataforma |
| actor_user_id | uuid | |
| action | text NOT NULL | `LOGIN`, `MEETING_UPLOAD`, `CONTEXT_UPDATE`, `ROLE_CHANGE`, etc. |
| resource_type | text | |
| resource_id | uuid | |
| metadata | jsonb | |
| occurred_at | timestamptz NOT NULL | |

---

## 3. Regras de Integridade

- Toda tabela tenant-bound tem FK composta lógica via `tenant_id`. As queries da aplicação **sempre** filtram por `tenant_id` antes de `id`.
- `meeting_analyses` é 1:1 com `meeting`. Reprocessar substitui (ou versiona em iteração futura).
- Deletar uma `meeting` cascateia transcript, participants, analysis e filhos.
- Deletar um `tenant` é proibido em produção; usar `status = SUSPENDED`.

---

## 4. Próximas Migrations Sugeridas (Flyway)

```
V001__create_tenants.sql
V002__create_users_and_roles.sql
V003__create_teams_and_scopes.sql
V004__create_meetings_and_transcripts.sql
V005__create_meeting_analyses_and_children.sql
V006__create_tenant_contexts.sql
V007__create_audit_events.sql
V008__indexes_and_partial_constraints.sql
V009__enable_rls.sql  -- pós-MVP
```

---

## 5. Considerações Acadêmicas (Oracle)

A entrega de Database Design da FIAP exige modelo Oracle. Recomenda-se **espelhar** este modelo em Oracle (`NUMBER`/`VARCHAR2`/`TIMESTAMP WITH TIME ZONE`) e versionar em `docs/data-model-oracle.md` para a disciplina, mantendo Postgres como banco de produção.
