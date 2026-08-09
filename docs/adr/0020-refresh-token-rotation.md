# 0020 — Refresh token rotation + reuse detection (token families)

- Status: accepted (retroactive ADR — decision already implemented and merged; formal record created in the 2026-05-21 audit)
- Date: 2026-05-21
- Deciders: Tech Lead
- Related: Sub-phase 1.3 (stateful refresh tokens, PR #59); `data-model.md §2.24`

## Context

Sub-phase 1.3 (PR #59) introduced stateful refresh tokens: a short access JWT (15 min) + a long-lived opaque refresh token (30 days, UUID, SHA-256 hash persisted in `refresh_tokens`, httpOnly cookie `nora_refresh`). Each `/auth/refresh` renewed the access token without rotating the refresh token.

**Problem (audit follow-up #3):** without rotation, a refresh token was valid until it expired (30 days). If an attacker exfiltrated the cookie (residual XSS, malware on the device, a proxy), they could renew access tokens freely for up to 30 days — **without detection** — until the victim logged out manually. The blast radius of a leaked cookie was enormous and silent.

## Decision

Adopt **refresh token rotation with reuse detection based on token families** (V014).

### Model (V014)

- `refresh_tokens.family_id UUID NOT NULL` — tokens in the same rotation chain share a `family_id` (backfill: existing tokens become `family_id = id`).
- `refresh_tokens.replaced_by_id UUID NULL REFERENCES refresh_tokens(id)` — when rotated, it points to the successor; `NULL` = the chain's active token or revoked without a successor (logout).
- Index `idx_refresh_tokens_family(family_id)` to revoke the entire chain.

### Behavior

- **Rotation:** each `/auth/refresh` validates the presented token, issues a **new** token in the **same `family_id`**, marks the previous one as revoked and sets its `replaced_by_id` to the new one.
- **Reuse detection:** if an **already revoked** token is presented, compromise is assumed (the legitimate one has already rotated; someone is using an old copy). Response: **revoke the entire family** via `RefreshTokenRepository.revokeAllByFamilyId(familyId, now)` — attacker **and** victim are logged out, forcing a re-login.

## Consequences

**Positive:**

- The blast radius of a leaked cookie drops from ~30 days to a usage window: as soon as either the legitimate user or the attacker rotates, the other side triggers detection and kills the family.
- Active compromise detection (vs. passive expiration): reuse of a revoked token is a strong and actionable signal.
- An industry standard (OAuth 2.0 BCP / refresh token rotation) — familiar to anyone reviewing B2B security.

**Negative / trade-offs:**

- False positives are possible in legitimate races (two tabs/devices rotating "at the same time" with the same old token) ⇒ family logout. An acceptable mitigation for the risk profile; the window is short.
- Extra state per token (family + replaced_by). Cleanup of old tokens in the chain is debt (hard-delete by retention).
- Clients need to handle `REFRESH_TOKEN_INVALID` by re-logging in (already covered in `error-codes.md`).

## Alternatives Considered

1. **Keep refresh without rotation (1.3 status quo)** — rejected: a leaked cookie = 30 days of silent access.
2. **Rotation without reuse detection** (just issue a new one + revoke the old one) — rejected: it rotates but does not react to reuse of the old one; it loses compromise detection, which is the biggest gain.
3. **Shorten the refresh validity** (e.g., 24h) — rejected on its own: it worsens UX (frequent re-login) without detecting compromise; rotation resolves the security×UX trade-off better.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-05-21 | Tech Lead | Retroactive ADR created in the doc×code audit. Decision already implemented in `V014__refresh_token_rotation.sql` (audit follow-up #3, PR #116) + `RefreshTokenRepositoryAdapter.revokeAllByFamilyId` |
