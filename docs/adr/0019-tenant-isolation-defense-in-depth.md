# 0019 — Tenant isolation in depth: Postgres RLS + composite FK

- Status: accepted (retroactive ADR — decision already implemented and merged; formal record created in the 2026-05-21 audit)
- Date: 2026-05-21
- Related: ADR 0002 (multi-tenancy — app filter in the MVP, RLS in prod)

## Context

ADR 0002 established the multi-tenancy model: `tenant_id` in every tenant-owned table + a filter in the application layer (Spring), with **Postgres RLS promised for production** but not implemented. Isolation depended entirely on code discipline: every query has to filter `tenant_id` before `id`. Two holes were identified (audit follow-ups #5 and #7, 2026-05):

1. **Forgotten filter**: a new service/repository that forgets the `tenant_id` predicate leaks data between tenants. The backend had no safety net in the database.
2. **Cross-tenant forge via the ORM**: `meetings.owner_user_id REFERENCES users(id)` (a simple FK) allowed, via an unvalidated payload, creating a meeting with `owner_user_id` pointing to a user of **another** tenant — the meeting's `tenant_id` and the owner's could diverge.

ADR 0002 left the concrete form of RLS open (it sketched `current_setting('app.tenant_id')`, without defining the aspect/role/GUC). These implementation decisions are durable and deserved their own record — hence this ADR.

## Decision

Adopt **defense in depth** for tenant isolation in two layers in the schema, complementing the application filter (which remains the first line):

### 1. Row-Level Security (V016)

- `CREATE POLICY tenant_isolation` + `ENABLE ROW LEVEL SECURITY` on 10 tenant-owned tables: `meetings`, `tenants`, `tenant_contexts`, `users`, `refresh_tokens`, `iam_groups`, `iam_policies`, `iam_user_invitations`, `meeting_analyses`, `meeting_participants`.
- Predicate: `tenant_id = nora.current_tenant_id()` (on `tenants`, `id = nora.current_tenant_id()`), with `USING` + `WITH CHECK`.
- The function `nora.current_tenant_id()` reads the session GUC `nora.current_tenant_id` (schema `nora`), returning `NULL` when not set ⇒ **fail-closed**: a role without `BYPASSRLS` sees 0 rows.
- **The real GUC is `nora.current_tenant_id`** (not `app.tenant_id` as ADR 0002 sketched — this is the canonical form).
- `infrastructure/security/TenantRlsAspect` (`@ConditionalOnProperty(nora.security.rls.enforce=true)`, `@Order(LOWEST_PRECEDENCE)`) executes `SELECT set_config('nora.current_tenant_id', :tenantId, true)` (scope local to the transaction, auto-reset on commit) at the start of every `@Transactional`, reading the tenant from the `TenantContextHolder`.

**Enforcement is opt-in.** The Postgres owner/admin bypasses RLS by default — in dev and Testcontainers the app connects as the owner, so the policies remain inert and the tests continue unchanged. To activate real enforcement in prod: (1) `CREATE ROLE nora_app ... NOBYPASSRLS`; (2) grants on the tenant-owned tables; (3) the API's connection string using `nora_app`; (4) `nora.security.rls.enforce=true`.

### 2. Composite isolation FK (V015)

- `users` gains `UNIQUE (tenant_id, id)` (the PK `id` remains simple; the UNIQUE exists only as the FK's target).
- `meetings.owner_user_id` stops being `REFERENCES users(id)` and becomes a **composite FK** `(tenant_id, owner_user_id) REFERENCES users(tenant_id, id) ON DELETE RESTRICT`.
- Effect: Postgres rejects (`ForeignKeyViolation`) any meeting whose `(tenant_id, owner_user_id)` does not match a row in `users` — an owner from another tenant is impossible at the schema level.

## Consequences

**Positive:**

- Isolation stops depending on query discipline alone: the database is the last line of defense (RLS) and the owner↔tenant relationship is guaranteed by a constraint (composite FK).
- RLS is reversible/gradual: opt-in via flag + role, without breaking dev/tests.
- Fail-closed: absent GUC ⇒ 0 rows (not "all rows").

**Negative / trade-offs:**

- RLS only actually protects when the app runs as a `NOBYPASSRLS` role — in dev it stays inert (risk of "it passed the local test but the policy was switched off"). Mitigation: a CI/staging environment with `nora_app` exercising RLS for real (debt).
- The aspect adds one `SET LOCAL` per transaction (negligible cost) and requires the tenant to be in the `TenantContextHolder` before the tx.
- The composite FK requires the `UNIQUE (tenant_id, id)` on `users` (an extra object in the schema).

## Alternatives Considered

1. **Application filter only (ADR 0002's status quo)** — rejected: a single forgotten `WHERE` leaks a tenant; no safety net.
2. **Always-on RLS (no flag/opt-in)** — rejected for now: it would break Testcontainers/dev, which connect as the owner; it would require a dedicated role in every environment before it was worth it.
3. **Validating owner↔tenant only in the application (without the composite FK)** — rejected: it is exactly the kind of check a new endpoint can forget; the constraint in the schema is forgetting-proof.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-21 | Tech Lead | Retroactive ADR created in the doc×code audit. Decision already implemented: RLS in `V016__row_level_security.sql` + `TenantRlsAspect` (audit follow-up #5, PR #138); composite FK in `V015__composite_fk_meetings_owner.sql` (audit follow-up #7, PR #137) |
