# 0019 — Tenant isolation em profundidade: RLS Postgres + FK composta

- Status: aceito (ADR retroativo — decisão já implementada e mergeada; registro formal criado na auditoria 2026-05-21)
- Data: 2026-05-21
- Decisores: Tech Lead
- Relacionado: ADR 0002 (multi-tenancy — filtro de app no MVP, RLS em prod)

## Contexto

ADR 0002 estabeleceu o modelo de multi-tenancy: `tenant_id` em toda tabela tenant-owned + filtro na camada de aplicação (Spring), com **RLS Postgres prometido para produção** mas não implementado. O isolamento dependia inteiramente da disciplina do código: toda query precisa filtrar `tenant_id` antes de `id`. Dois furos foram identificados (audit follow-ups #5 e #7, 2026-05):

1. **Filtro esquecido**: um service/repository novo que esqueça o predicado `tenant_id` vaza dados entre tenants. O backend não tinha rede de segurança no banco.
2. **Cross-tenant forge via ORM**: `meetings.owner_user_id REFERENCES users(id)` (FK simples) permitia, via payload não validado, criar um meeting com `owner_user_id` apontando para um user de **outro** tenant — o `tenant_id` do meeting e o do owner podiam divergir.

ADR 0002 deixou a forma concreta do RLS em aberto (esboçou `current_setting('app.tenant_id')`, sem definir aspecto/role/GUC). Estas decisões de implementação são duráveis e mereciam registro próprio — daí este ADR.

## Decisão

Adotar **defesa em profundidade** do isolamento por tenant em duas camadas no schema, complementando o filtro de aplicação (que continua sendo a primeira linha):

### 1. Row-Level Security (V016)

- `CREATE POLICY tenant_isolation` + `ENABLE ROW LEVEL SECURITY` em 10 tabelas tenant-owned: `meetings`, `tenants`, `tenant_contexts`, `users`, `refresh_tokens`, `iam_groups`, `iam_policies`, `iam_user_invitations`, `meeting_analyses`, `meeting_participants`.
- Predicado: `tenant_id = nora.current_tenant_id()` (em `tenants`, `id = nora.current_tenant_id()`), com `USING` + `WITH CHECK`.
- Função `nora.current_tenant_id()` lê o GUC de sessão `nora.current_tenant_id` (schema `nora`), retornando `NULL` quando não setado ⇒ **fail-closed**: role sem `BYPASSRLS` vê 0 rows.
- **GUC real é `nora.current_tenant_id`** (não `app.tenant_id` como o ADR 0002 esboçou — esta é a forma canônica).
- `infrastructure/security/TenantRlsAspect` (`@ConditionalOnProperty(nora.security.rls.enforce=true)`, `@Order(LOWEST_PRECEDENCE)`) executa `SELECT set_config('nora.current_tenant_id', :tenantId, true)` (escopo local à transação, auto-reset no commit) no início de cada `@Transactional`, lendo o tenant do `TenantContextHolder`.

**Enforcement é opt-in.** O owner/admin Postgres bypassa RLS por default — em dev e Testcontainers o app conecta como owner, então as policies ficam inertes e os testes seguem sem mudança. Para ativar enforcement real em prod: (1) `CREATE ROLE nora_app ... NOBYPASSRLS`; (2) grants nas tabelas tenant-owned; (3) connection string da API usando `nora_app`; (4) `nora.security.rls.enforce=true`.

### 2. FK composta de isolamento (V015)

- `users` ganha `UNIQUE (tenant_id, id)` (a PK `id` continua simples; o UNIQUE existe só como alvo da FK).
- `meetings.owner_user_id` deixa de ser `REFERENCES users(id)` e passa a **FK composta** `(tenant_id, owner_user_id) REFERENCES users(tenant_id, id) ON DELETE RESTRICT`.
- Efeito: o Postgres rejeita (`ForeignKeyViolation`) qualquer meeting cujo `(tenant_id, owner_user_id)` não case com uma linha de `users` — owner de outro tenant é impossível no nível do schema.

## Consequências

**Positivas:**

- Isolamento deixa de depender só da disciplina de query: o banco é a última linha de defesa (RLS) e o relacionamento owner↔tenant é garantido por constraint (FK composta).
- RLS é reversível/gradual: opt-in por flag + role, sem quebrar dev/testes.
- Fail-closed: GUC ausente ⇒ 0 rows (não "todos os rows").

**Negativas / trade-offs:**

- RLS só protege de fato quando o app roda como role `NOBYPASSRLS` — em dev fica inerte (risco de "passou no teste local mas a policy estava desligada"). Mitigação: um ambiente de CI/staging com `nora_app` exercitando RLS de verdade (débito).
- O aspect adiciona um `SET LOCAL` por transação (custo desprezível) e exige que o tenant esteja no `TenantContextHolder` antes da tx.
- FK composta exige o UNIQUE `(tenant_id, id)` em `users` (objeto extra no schema).

## Alternativas Consideradas

1. **Só filtro de aplicação (status quo do ADR 0002)** — rejeitado: um único `WHERE` esquecido vaza tenant; sem rede de segurança.
2. **RLS sempre-on (sem flag/opt-in)** — rejeitado para já: quebraria Testcontainers/dev que conectam como owner; exigiria role dedicado em todo ambiente antes de valer a pena.
3. **Validar owner↔tenant só na aplicação (sem FK composta)** — rejeitado: é exatamente o tipo de checagem que um endpoint novo pode esquecer; a constraint no schema é à prova de esquecimento.

## Histórico

| Data | Decisor | Mudança |
|---|---|---|
| 2026-05-21 | Tech Lead | ADR retroativo criado na auditoria doc×código. Decisão já implementada: RLS em `V016__row_level_security.sql` + `TenantRlsAspect` (audit follow-up #5, PR #138); FK composta em `V015__composite_fk_meetings_owner.sql` (audit follow-up #7, PR #137) |
