# 0042 — Vitest in `apps/web`: which parts of the first unit suite are a gate

- Status: accepted
- Date: 2026-08-17
- Related: ADR 0018 (test coverage targets — this delivers the runner it asked for and, explicitly,
  not its web threshold table), ADR 0038 (the realignment that scheduled this), ADR 0040 (the PII
  scope that makes `redact.ts` the BFF's only redaction), ADR 0012 and ADR 0033 (the PII Shield
  the BFF module mirrors)

## Context

ADR 0018 measured `apps/web` at "0% effective coverage — no `test` script and no runner" on
2026-05-14 and planned Vitest for Sub-phase 1.12. Sub-phase 1.12 came and went. Three months and
roughly seventy merged pull requests later, `package.json` still had no `test` script and no
vitest, jest or testing-library dependency.

A Playwright suite arrived in the meantime and is good at what it does — security headers, route
protection, CSP violations — and honest about what it does not do: its own fixture file states
that nothing behind the protected routes receives data, because the fixture only satisfies the
middleware's cookie-presence gate. So no line of pure logic in this application was executed by a
test, on the largest untested surface in the repository.

Two of the untested modules are not ordinary application code:

- `src/lib/pii/redact.ts` is, per ADR 0040, the **only** redaction on the chat path. Its header
  promises a 1:1 mirror of the structured patterns in the worker's PII Shield, and its own
  comments record that the mirror had already broken once, silently, on the one pattern reachable
  from a nearly unauthenticated path.
- `src/lib/password-policy.ts` is seven lines whose entire purpose is not to diverge from the
  backend, and nothing measured the divergence.

The remaining question was never "should there be tests". It was which of them the build refuses
to merge without, given a solo maintainer and an application whose screens will stay untested for
a long time.

## Decision

**1. `npm test` is a gate from the first day.** The `web` job runs the unit suite as its own step,
after Typecheck and before Build — Vitest needs neither a production build nor a browser, so a
broken pure function fails in seconds rather than after `next build` and a chromium download.

**2. Coverage is measured on every run and reported in two scopes.** `scripts/report-coverage.sh
web` prints the whole-app rate beside the per-module rates, the same shape and the same script the
`api` and `worker` jobs already use. Whole-app coverage is around 5%. Publishing only the
per-module figure would describe an application that does not exist.

**3. A coverage threshold IS a gate, but only per module, and only as a floor.** Thresholds live in
`apps/web/vitest.config.mts` and apply to `src/lib/pii/redact.ts`, `src/lib/report/markdown.ts` and
`src/lib/password-policy.ts`. Each number sits **below** the rate measured when it was written, so
the gate fires on a regression rather than on the next commit.

**4. There is no whole-app threshold, and none on `src/lib/api/client.ts`.** A global number on an
application at 5% would either be symbolic — a floor at 5% gates nothing — or block every UI pull
request. ADR 0018 rejected the same idea in its own Alternatives Considered. `client.ts` is
excluded for a different reason: it is one `request()` function plus 66 one-line wrappers around
it, so a file-level percentage counts wrappers rather than describing how well the shared function
is tested, and adding twenty endpoints would fail the build while changing nothing.

**5. A threshold is a floor to raise, never a rate to stay under.** This repository has already made
the other mistake once, in the PII corpus, where a ceiling pinned at the measured leak rate turned
today's defect count into the definition of acceptable; DEC-16 of the realignment converts it into
a decreasing target. Coverage floors point the other way by construction, and the rule that keeps
them pointing that way is: whoever raises coverage raises the floor with it, in the same pull
request.

**6. The two mirror tests read the other half of the mirror off disk.** `redact.test.ts` compares
its pattern literals, character for character, with `services/nlp-worker/src/nora_nlp/services/
pii_shield.py`; `password-policy.test.ts` compares its two constants with `PasswordPolicy.java` and
the three DTOs carrying the same `@Size` bounds. Neither restates the other side as a literal — a
test that hardcodes the value on both sides passes forever after somebody changes one of them. The
`web` filter in `ci.yml` therefore watches those five backend and worker files, because drift has
historically arrived from them and not from this app.

### What this does not claim

ADR 0018's web table — auth flow pages >50%, dashboard/meeting-detail/tasks pages >40%, shared
components >60% — is **not** met and **not** enforced. Not one page or component has a unit test.
Those targets stay exactly what ADR 0018's backend and worker targets already are in practice: an
aspiration, not a gate. This ADR does not supersede them; it records that the runner they depend on
now exists and that the numbers still do not.

## Consequences

**Positive:**

- The PII redaction that ADR 0040 makes the chat path's last gate has a regression suite, and the
  mirror it promises is checked rather than asserted in a comment.
- The report builder is pinned before task D06 makes it a shared helper with a second consumer.
- The rubric line "technical validation (tests)" stops having an empty cell for the largest
  surface, and the figure behind it is produced by CI rather than quoted from a document.
- The `web` filter in `ci.yml` was not watching `.github/workflows/ci.yml` — the fifth instance of
  a hole this file has documented four times — so a pull request editing only the `web` job ran no
  job that verified it. Fixed here because these steps would have been its next victim.

**Negative:**

- Coverage figures now exist for `apps/web`, and a 5% whole-app figure is a number somebody will
  quote out of context. The two-scope report is the mitigation, not a cure.
- The mirror tests couple a web unit test to files in two other services. That coupling is the
  point, but it means a deliberate change to the worker's PII patterns now requires a matching
  change here, in the same pull request, or CI stays red.
- Three modules gated out of an application of several hundred files is a narrow net, and it stays
  narrow until somebody widens it.

## Alternatives Considered

1. **A whole-app coverage threshold set at today's rate.** Rejected. At 5% it gates nothing, and a
   threshold that tracks the current number is a ratchet against change rather than a floor —
   exactly the ceiling-not-goal shape the PII corpus already had to be rescued from.
2. **Report coverage and gate nothing.** Rejected. ADR 0018's own Alternatives Considered rejected
   "coverage as an optional-only gate" on the grounds that critical areas regress silently, and the
   PII redaction is precisely such an area.
3. **Enforce ADR 0018's per-page web thresholds now.** Rejected as dishonest: the pages have no
   tests at all, so the thresholds could only be met by writing a large suite this task does not
   contain, or by declaring victory on numbers nobody measured.
4. **Testing Library plus component tests as the starting point.** Rejected as a starting point,
   not on the merits. The audit put the risk in the pure modules — a redaction control, a report
   builder about to gain a consumer, a policy mirror, a shared HTTP function — and a component
   suite would have cost more and covered less of it. It stays open as the obvious next step.
5. **`jest` instead of `vitest`.** Rejected. ADR 0018 named Vitest, the app is already on a Vite-
   compatible toolchain, and nothing in the intervening three months argues for reopening it.
