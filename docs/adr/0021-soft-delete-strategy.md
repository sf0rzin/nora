# 0021 — Soft-delete strategy on tenant-owned entities

- Status: accepted (retroactive ADR — decision already implemented and merged; formal record created in the 2026-05-21 audit)
- Date: 2026-05-21
- Related: ADR 0002 (multi-tenancy); LGPD (right to be forgotten)

## Context

Up to V012, deleting a tenant-owned entity was a hard-delete (physical DELETE + cascades). That brings problems for a multi-tenant SaaS under LGPD:

- **No reversibility**: accidentally deleting a meeting/tenant is unrecoverable outside of a backup.
- **No trail**: there is no way to distinguish "never existed" from "was removed".
- **Tension with uniqueness**: if a `user` is removed and the same email tries to sign up again, the full UNIQUE `(tenant_id, email)` would block it forever (or would require an immediate hard-delete).

At the same time, LGPD requires a **real hard-delete** for the right to be forgotten — so soft-delete cannot be the only path.

## Decision

Adopt **soft-delete by default** on the main tenant-owned entities, with hard-delete preserved as an explicit operation (V013).

### Model (V013)

- Column `deleted_at TIMESTAMPTZ NULL` on `tenants`, `users`, `tenant_contexts`, `meetings` (`NULL` = active).
- Each corresponding `@Entity` uses Hibernate `@SQLDelete(sql = "UPDATE <t> SET deleted_at = NOW(), updated_at = NOW() WHERE id = ?")` + `@SQLRestriction("deleted_at IS NULL")` — every Spring Data query now **filters for live records by default**, and `repository.delete()` becomes an UPDATE.
- **Full UNIQUEs become partial** `WHERE deleted_at IS NULL`: `tenants.slug`, `users(tenant_id, email)`, `tenant_contexts.tenant_id`. This way slug/email can be reused after a soft-delete (a removed user does not block a new signup with the same email).
- The `*_deleted_at_idx` indexes support the filter.

### Hard-delete

Still possible via an explicit **native query**, for LGPD (right to be forgotten) and retention. It is the conscious exception, not the default path.

## Consequences

**Positive:**

- Reversible deletion by default; a trail of "when it was removed".
- Reuse of slug/email after removal without a UNIQUE collision.
- `@SQLRestriction` makes the filter transparent — existing query code does not need to add `AND deleted_at IS NULL`.

**Negative / trade-offs:**

- **Uniqueness gotcha**: the filter lives in the Hibernate layer (`@SQLRestriction`), not in the database. Native queries/reports that do not go through Hibernate **do see** soft-deleted rows — watch out in jobs/ad-hoc SQL.
- "Removed" data remains in the database until a hard-delete — relevant for LGPD (retention needs a process) and for table size.
- Child tables (e.g., `meeting_analyses`) today do not have their own `deleted_at`; they depend on the parent's soft-delete + logical cascade. An inconsistency to watch if a child is queried directly.
- `UserJpaEntity` applies `@SQLRestriction` but does not map the `deletedAt` property (it only reads it via SQL) — a minor mapping debt.

## Alternatives Considered

1. **Hard-delete only (status quo ≤V012)** — rejected: irreversible, no trail, UNIQUE collision on re-signup.
2. **Soft-delete only (with no hard-delete path)** — rejected: incompatible with LGPD (the right to be forgotten requires real removal).
3. **A separate audit/archive table** (moving the deleted row to `*_archive`) — rejected for now: more schema/migration complexity than `deleted_at`; reconsider if retention requires physical separation.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-21 | Tech Lead | Retroactive ADR created in the doc×code audit. Decision already implemented in `V013__add_soft_delete.sql` + `@SQLDelete`/`@SQLRestriction` on the entities (audit follow-up, PR #114) |
