# 0002 — Multi-tenancy strategy

- Status: accepted
- Date: 2026-05-02
- Deciders: NORA Team

## Context

NORA is multi-tenant from day 1. Each client company is a tenant; no data may leak between tenants. There are three viable approaches:

1. **Database per tenant** (physical separation).
2. **Schema per tenant** within the same database.
3. **Single shared schema with `tenant_id` in every tenant-bound table**, optionally with Row-Level Security.

The team is small and the MVP needs to ship fast without compromising security.

## Decision

Adopt a **single shared schema with a mandatory `tenant_id` column** in every tenant-bound table, with the following progression:

- **MVP**: the `tenant_id = ?` filter is applied by an application layer (a Spring interceptor/aspect) that retrieves the tenant from the authenticated JWT. Every repository exposes only methods that receive the `tenantId` explicitly. Mandatory integration tests cover cross-tenant scenarios.
- **Production**: enable **Postgres Row-Level Security** on all tenant-bound tables, with a policy based on `current_setting('app.tenant_id')` set at the start of each connection/transaction.

Global tables (`system_plans`, etc.) have no `tenant_id`.

## Consequences

- Low operational cost, scales well to thousands of tenants.
- Zero risk of "forgetting" the filter in production (RLS prevents it).
- Single set of backups and migrations.
- Does not serve clients whose contract requires physical data separation — those move to a dedicated deployment in the future.
- Requires discipline in the repositories and specific isolation tests.

## Alternatives Considered

- **Database per tenant.** Rejected: provisioning cost, migration complexity and the Azure cost per database.
- **Schema per tenant.** Rejected: explosion of database objects and migration complexity multiplied by the number of tenants.

## Accompanying Rules

- Never fetch a tenant-bound entity by `id` alone. Always `tenant_id + id`.
- An endpoint returning a cross-tenant 404 **must not** distinguish "does not exist" from "not authorized" for users without elevated privilege, to avoid enumeration.
- Every new table goes through a PR checklist: does it have `tenant_id`? A composite index? An isolation test?
