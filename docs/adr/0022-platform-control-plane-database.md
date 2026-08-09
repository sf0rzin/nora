# 0022 — Separate platform database + 2nd datasource (control plane)

- Status: accepted
- Date: 2026-05-28
- Deciders: Co-architects (Opus) + Stratfy (PO/owner)
- Related: ADR 0002 / 0019 (multi-tenancy + RLS), ADR 0016 (rg-nora-prod separation)

## Context

NORA is going to gain an **operator control plane** (only for the platform owners — no customer
accesses it) with an LLM model catalog, per-service model selection at runtime and **AI cost
telemetry** (tokens/cost per tenant/model/service). This is **net-new** scope — it did not exist in
the backlog or in the product vocabulary.

There is a structural tension with the already established per-tenant isolation:

1. **Every tenant-owned table carries `tenant_id` + RLS** (ADR 0002/0019). The `TenantRlsAspect` does
   `SET LOCAL nora.current_tenant_id` in **every** `@Transactional`, and `nora.current_tenant_id()`
   returns `NULL` when not set ⇒ fail-closed (0 rows). A **model catalog is global** (no
   tenant); putting it in the same schema/role would make control plane queries return 0 rows or
   would require breaking the "every table is tenant-owned" invariant.
2. **Cost telemetry is cross-tenant by nature** (the owner's view): aggregating cost per tenant
   conflicts with RLS, which exists precisely to hide cross-tenant data.
3. **Blast radius**: platform config and telemetry have a different lifecycle and criticality from
   the customer's transactional data. Isolating them protects one from the other — and it is a pitch argument.

The API today is **single datasource by pure Spring Boot autoconfig** (zero `@Configuration` for
`DataSource`/`EntityManagerFactory`/`@EnableJpaRepositories`).

## Decision

1. **A physically separate Postgres database** for the platform (`nora-pg-platform-*`), its own B1ms
   Burstable Flexible Server in `centralus` (the same Azure for Students restrictions). It is not a schema
   nor an additional database on the existing server — it is a separate server, for real blast radius.
2. **A second datasource in the Spring API accessed via `JdbcTemplate`** (not a 2nd JPA
   `EntityManagerFactory`). Since there is no explicit datasource config today, introducing a 2nd EMF
   would force making the primary one `@Primary` + segmenting `@EnableJpaRepositories` by package — touching
   what already runs and risking the Testcontainers ITs. A dedicated `NamedParameterJdbcTemplate`
   (`PLATFORM_DATASOURCE_*`), in the style of `IamRepositoryAdapter` (native SQL), has **zero blast
   radius** on the primary JPA.
3. **Only the Spring API opens a connection to the platform database.** The worker and BFF never connect
   directly; they consume it via HTTP contracts (`/internal/platform/*`, `/admin/platform/*`). This centralizes access,
   avoids N pools and N copies of the credential.
4. **Gated module + soft-fail.** The platform module is `@ConditionalOnProperty(nora.platform.enabled)`
   (default `false` in local/test/CI — it does not connect, it does not run Flyway). The platform database's Flyway
   is run in an `ApplicationRunner` with `try/catch`: if the database is down, the **API keeps
   starting up** (degraded mode) — the customer's path (primary datasource) cannot go down because of the
   control plane.
5. **Its own Flyway** (`classpath:db/platform`, its own history table in the separate database), wired by
   a dedicated bean — Boot's autoconfig only runs Flyway on the primary datasource.
6. The platform tables **do not have `tenant_id` as a security boundary**. `usage_events`
   carries `tenant_id` only as a **telemetry dimension** (a loose UUID, no FK, no RLS).

## Consequences

**Positive:**
- Real isolation (blast radius) between customer data and the owner's config/telemetry.
- The primary database's "every tenant-owned table has `tenant_id`+RLS" invariant remains intact.
- JdbcTemplate-only does not touch the primary JPA ⇒ existing ITs do not regress.
- Soft-fail: control plane down ≠ NORA down.

**Negative / trade-offs:**
- **~2× the DB cost** (a second Flexible Server). Accepted as a pitch argument; the Azure for
  Students credit is monitored — if it gets tight before 12/06, whatever is non-essential is paused.
- Two schema sources of truth (two Flyways). Mitigated: independent history tables, distinct
  locations.
- Cross-tenant aggregation of "business metrics" (telemetry c) reads the **primary** database without tenant
  context (RLS bypass) — a dedicated read-path, operator-only, explicitly commented. Cuttable.

## Alternatives Considered

1. **Same database, `platform` schema** — rejected: the `TenantRlsAspect` runs on every tx; tables without
   `tenant_id` would require policy exceptions, breaking the invariant. And no blast radius.
2. **A 2nd database on the same Flexible Server** — cheaper, but without real operational isolation (one
   server = one point of failure/credential). Rejected because of the blast radius decision.
3. **A 2nd JPA EntityManagerFactory** — rejected: it forces reconfiguring the primary datasource (today
   implicit), a high risk to the ITs. JdbcTemplate covers the control plane's simple CRUD.
4. **Each service (worker/BFF) connecting directly to the platform database** — rejected: N pools, N
   copies of the credential, coupling. Centralized in the API.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-28 | Co-architects + Stratfy | Creation. A conscious exception to ADR 0014 (scope freeze), authorized by Stratfy: control plane + telemetry go in pre-pitch. |
