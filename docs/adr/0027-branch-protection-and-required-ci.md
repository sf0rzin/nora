# 0027 — `main` branch protection + mandatory CI gate

- Status: accepted
- Date: 2026-06-04
- Deciders: Architect (Opus) + Stratfy (PO/owner)
- Related: ADR 0018 (test coverage targets), `.github/workflows/ci.yml`, `.github/CODEOWNERS`, foundation audit 2026-06-03

## Context

The foundation audit (2026-06-03) found a mismatch between the quality of the pipeline and its enforcement: `ci.yml` already runs real tests (api `mvn verify`, worker `pytest`, web `lint/typecheck/build`), but the **`main` ruleset (id 16147673) only blocked `deletion` + `non_fast_forward`**. There was no requirement for a Pull Request, for a review, or for a green status check. Consequences:

- Any collaborator with write access could push directly to `main`.
- PRs could be merged with **red CI** and **zero review**.
- Code touching the non-negotiables (tenant isolation, PII, IAM) reached `main` with no automated or human gate at all.

Context constraint: the team has **2 humans** (Anthony/`@sys0xFF` on web+back+infra; Gabriel/`@pollotherunner` on desktop) and the PO frequently merges his own PR. A review requirement **with no escape hatch** would create a deadlock when one of the two is unavailable.

A technical detail that forces a specific design: the `ci.yml` jobs are **conditional by path** (`dorny/paths-filter`). Requiring a conditional job (e.g., `api`) directly as a required status check blocks the merge when the PR does not touch that area — GitHub treats a required check that never runs as eternally *pending*.

## Decision

1. **Aggregator CI gate.** New `ci-gate` job in `ci.yml`: always runs (`if: always()`), depends on all PR jobs (`changes, api, worker, web, desktop-sidecar, docs-link-check, infra`) and only fails if one of them **failed or was cancelled** (`skipped` by path-filter = ok). `desktop-bundle` is left out (it only runs on push to `main`, it is not a PR gate). `ci-gate` is the **only** required status check — it solves the path-filter problem with a single stable name.

2. **Hardened `main` ruleset** (same id 16147673), now requiring:
   - **Mandatory pull request** before merge (no direct push to `main`).
   - **1 approving review**, with *dismiss stale reviews on push* (approval drops when a new commit arrives).
   - **Green `ci-gate` status check**, in *strict* mode (branch up to date with the base).
   - **Linear history** (aligned with the squash-merge flow).
   - **Conversation resolution** before merge.
   - Keeps the `deletion` + `non_fast_forward` block.

3. **Pragmatic bypass for a team of 2.** The **repository admin** role (the 2 owners) can bypass the PR/review gate for a solo merge or an emergency. It is a conscious trade-off: without it, a lone owner cannot merge his own PR (he cannot self-approve). The review requirement is the **default**; the bypass is the exception. **When the team grows (3+), remove the bypass** and make review truly mandatory.

4. **CODEOWNERS** (`.github/CODEOWNERS`) routes review by area: backend/IAM/worker/web/infra/adr → `@sys0xFF`; desktop → `@pollotherunner`. Today it serves for reviewer *auto-request*; when the team grows, turn on `require_code_owner_review` in the ruleset.

## Consequences

**Positive:**
- `ci.yml`, which already runs real tests, becomes a **real gate**: nothing touches `main` with red CI.
- 4-eyes principle as the default; traceability of who reviewed what.
- Automatic review routing by domain (CODEOWNERS).
- A basis for turning on the coverage gate (ADR 0018) as part of `ci-gate` in the future.

**Negative / trade-offs:**
- There is no more direct push to `main` — all work goes through a PR (process cost accepted; it was already the de facto norm).
- The admin bypass makes the review requirement "soft" for the owners while the team is 2 people. Documented and with a removal trigger (3+ people).
- A legitimate force-push to `main` (e.g., the history cleanup of 2026-06-03) requires disabling the ruleset for a few seconds and re-enabling it — a known manual procedure.

## Alternatives Considered

1. **Requiring the conditional jobs directly as required checks** — rejected: it blocks the merge with eternal *pending* when the job is skipped by path-filter. The aggregator `ci-gate` is the correct pattern.
2. **Required review with no bypass** — rejected for a team of 2: it creates a deadlock when one owner is alone (he cannot self-approve).
3. **Only a status check, without requiring review** — less rigorous; it discards the 4-eyes principle, which is the most valuable part. Rejected as the default, kept as the effective behavior for the owners via bypass.
4. **Leaving it as it was** (only delete/force-push) — rejected: it was the #1 governance gap in the audit.

## History

| Date | Decider | Change |
|---|---|---|
| 2026-06-04 | Architect + Stratfy | Creation. Aggregator CI gate + branch protection (PR + review + ci-gate + linear history) with admin bypass for a team of 2 + CODEOWNERS. Addresses the governance gap from the 2026-06-03 audit. |
