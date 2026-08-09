# 0029 — Operational LGPD: right to be forgotten + retention

- Status: accepted
- Date: 2026-06-05
- Deciders: Architect + Stratfy (PO)
- Related: makes concrete the hard-delete foreseen in ADR 0021 (soft-delete); operates under the RLS enforce of ADR 0028; complements the PII Shield (ADR 0012)

## Context

The foundation audit pointed out a compliance gap: NORA positions itself as **LGPD-first**, but
had neither **formal retention** nor a **right to be forgotten**. The concrete risk: `transcripts.raw_text`
stores the **raw** transcription (PII at rest) indefinitely — the PII Shield (ADR 0012) only redacts what
goes to the LLM, not what stays in the database.

ADR 0021 (soft-delete) had already foreseen the way out: "hard-delete remains possible via an explicit
native query, for LGPD (right to be forgotten) and retention. It is the conscious exception, not the default."
This ADR makes that exception concrete.

## Decision

Two capabilities, both via **physical hard-delete** (not soft): a native `DELETE FROM meetings` query
ignores the entity's `@SQLDelete`/`@SQLRestriction`, and the `ON DELETE CASCADE` FK (V004) propagates to
`transcripts` (raw PII), `meeting_participants`, `meeting_tags` and `meeting_analyses` (+ children).

### 1. Right to be forgotten — `DELETE /privacy/meetings/{id}`

Authenticated endpoint, scoped by the JWT's tenant, gated by `meeting:update` (the same as the destructive
removal of a goal). It PERMANENTLY deletes the meeting and all PII in cascade. 204 on success; **404 if it does
not exist in the tenant** (does not leak cross-tenant existence). Auditable via log (ids only, never content).

### 2. Retention — scheduled sweeper

`RetentionSweeper` (`@Scheduled`, configurable cron) purges meetings older than
`nora.privacy.retention-days`. **Off by default (`0`)**: retention is destructive, so it is only turned on by
explicit opt-in from the environment (`NORA_PRIVACY_RETENTION_DAYS`). It iterates **per tenant** because, under RLS
enforce (ADR 0028), the scheduler thread has no JWT — it propagates the tenant via `TenantRlsContext` so that
the aspect applies the GUC in the purge transaction. Listing tenants works without a GUC (`tenants` is exempt
from RLS in V020).

### 3. Mandatory proof

`PrivacyFlowIntegrationTest` (Testcontainers) boots the app and validates end to end: erasure removes the
meeting **and the transcript (raw PII) physically** (direct assert on `TranscriptRepository`); 404 on a
nonexistent meeting; tenant B does not delete a meeting of A (and A's remains); it requires authentication.

## Consequences

- Closes the compliance gap: PII at rest now has an on-demand removal path + opt-in retention.
- Hard-delete is **irreversible** (no trash bin) — intentional: the right to be forgotten requires real removal.
- Under RLS enforce, the FK cascade runs at the database level (bypassing the children's RLS), so deleting the meeting
  (which goes through the tenant's RLS) purges the enforced children correctly.

**Negative / trade-offs:**
- Erasure today is **per meeting**, not per **data subject** (email). Data-subject erasure (sweeping all the
  meetings a person participated in) is the next increment — it depends on deciding the semantics (delete the
  entire meeting vs. anonymize the participant in a shared transcript).
- Retention is **global** (one window for all tenants). Per-tenant/per-plan retention requires a config
  table — deferred until billing exists.
- The gate is `meeting:update`; a dedicated `privacy:erase` permission (more restrictive) is future hardening.

## Alternatives Considered

- **Anonymizing instead of deleting** (replacing PII with placeholders in `raw_text`): preserves aggregates, but
  is more fragile (it depends on the redactor's coverage) and is not true "forgetting". Rejected for the
  right-to-be-forgotten case; it may complement retention in the future.
- **Soft-delete + purge later**: adds latency to forgetting with no gain — ADR 0021 already covers
  soft-delete for the normal flow; LGPD wants immediate physical removal.
