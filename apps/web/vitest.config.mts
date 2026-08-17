import { fileURLToPath } from 'node:url';
import { defineConfig } from 'vitest/config';

/**
 * Unit-test runner for `apps/web` — the runner ADR 0018 planned for Sub-phase 1.12 and that
 * never arrived. ADR 0042 records what this suite does and does not claim.
 *
 * It sits BESIDE the Playwright suite, not on top of it, and the two never see each other's
 * files: Playwright owns `e2e/`, Vitest owns `src/**\/*.test.ts`. Both packages export a global
 * named `test` and a global named `expect`, so a glob that reached `e2e/` would have Vitest
 * collect Playwright specs and fail in a way that reads like a bug in the specs. `include` below
 * is what keeps them apart; `exclude` restates it so a future widening of `include` still cannot
 * reach `e2e/`.
 */
export default defineConfig({
  resolve: {
    // Mirrors `compilerOptions.paths` in tsconfig.json. Without it every `@/...` import in the
    // app fails to resolve under the test runner, which looks like a broken module graph rather
    // than a missing alias.
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  test: {
    include: ['src/**/*.test.ts'],
    exclude: ['node_modules/**', '.next/**', 'e2e/**'],

    // `node` by default because the modules worth testing first are pure. The one file that
    // needs a DOM (`src/lib/api/client.test.ts`, which has to take the browser branch of
    // `serverCookieHeader`) asks for jsdom with a `@vitest-environment` docblock, so the cost
    // of a DOM is paid by the one file that needs it instead of by the whole suite.
    environment: 'node',

    // `NEXT_PUBLIC_*` are inlined by the bundler at BUILD time; under Vitest they are ordinary
    // `process.env` reads resolved when the module is first imported. Pinning the value here is
    // therefore not decoration: `src/lib/api/client.ts` falls back to `http://localhost:8080`
    // when it is unset, and a test that asserts on a URL would be asserting on a default it did
    // not choose. `USE_MOCKS` is pinned OFF for the same reason — the fixture branch of
    // `listMeetings`/`getMeeting` must not swallow a request the test is trying to observe.
    env: {
      NEXT_PUBLIC_API_BASE_URL: 'https://api.test.invalid',
      NEXT_PUBLIC_USE_MOCKS: 'false',
    },

    coverage: {
      provider: 'v8',
      // `text` for the job log, `json-summary` for `scripts/report-coverage.sh web`, which is
      // what prints the two scopes side by side. The script reads this file; it measures
      // nothing itself, exactly as it already does for JaCoCo and coverage.py.
      reporter: ['text', 'json-summary'],
      reportsDirectory: './coverage',

      // The denominator is every source file the app ships, NOT just the ones with tests.
      // Restricting this to the tested modules would produce a number in the nineties for an
      // application whose screens have no unit tests at all — the overstatement this repository
      // has spent several passes removing from its own documents.
      include: ['src/**/*.{ts,tsx}'],
      exclude: [
        'src/**/*.test.ts',
        // Type-only modules: no statements to execute, so they would report as 0% covered
        // files that cannot be covered by anything.
        'src/lib/api/types.ts',
        'src/types/**',
        'src/fixtures/**',
        'next-env.d.ts',
      ],

      // FLOORS, not targets. Each number sits below what the suite measures today, so the gate
      // fires on a REGRESSION and not on the next commit; measured on 2026-08-17, redact.ts was
      // 96.6/87.5/100/100 and markdown.ts 97.7/91.3/100/100 (statements/branches/functions/
      // lines). Read them as "nobody takes these modules below this line". Raising coverage is
      // the work; whoever raises it should raise the floor with it.
      //
      // No global threshold, on purpose. `apps/web` is at roughly 5% overall, and a global
      // number would either be symbolic (a floor at 5% gates nothing) or block every UI pull
      // request a solo maintainer opens. ADR 0018 itself rejected "mandatory total coverage, no
      // distinction by area" (Alternatives Considered, item 1), and both coverage gates this
      // repository actually enforces are scoped to a single unit: JaCoCo on `PolicyEvaluator`,
      // `--cov-fail-under` on `pii_shield.py`.
      //
      // No threshold on `src/lib/api/client.ts` either, and that omission is deliberate rather
      // than an oversight. The file is one `request()` plus 74 one-line wrappers around it, so a
      // file-level percentage measures how many wrappers exist, not how well the shared function
      // is tested: adding twenty endpoints would drop the number and fail the build while
      // changing nothing about the tested behaviour. `request` is covered by
      // `src/lib/api/client.test.ts`; the file's rate is reported by
      // `scripts/report-coverage.sh web` and gated by nothing.
      thresholds: {
        'src/lib/pii/redact.ts': { statements: 90, branches: 80, functions: 100, lines: 95 },
        'src/lib/report/markdown.ts': { statements: 90, branches: 80, functions: 100, lines: 95 },
        // Measured 100/100/100/100 when US25 shipped it. The floor is set below that rather
        // than at it: the point of the module is that a task title carrying a comma or a quote
        // survives the CSV, and a gate that fires on the next unexercised branch would get
        // lowered by whoever it inconveniences instead of defending that.
        'src/lib/report/tasks-export.ts': {
          statements: 95,
          branches: 90,
          functions: 100,
          lines: 95,
        },
        // Measured 98.4/88.4/100/100 when US34 shipped it. Gated rather than only reported —
        // unlike the trends helpers — because it is the second module in this repository whose
        // job is to not lie in a downloaded file: it decides when an AI figure is written as an
        // empty CSV field instead of as a zero, and that branch has no visible symptom.
        'src/lib/report/usage-report.ts': {
          statements: 95,
          branches: 85,
          functions: 100,
          lines: 95,
        },
        // Two exported constants and no branches: 100% is what "the mirror test imports both of
        // them" means. A third constant nobody asserts on drops this below the line, which is
        // the intent — the module exists to not diverge from the backend.
        'src/lib/password-policy.ts': {
          statements: 100,
          branches: 100,
          functions: 100,
          lines: 100,
        },
        // Measured 89.9/83.5/100/97.7 when US42 shipped it. What the floor defends is the
        // refusal path: the module's job is to REJECT a document the form cannot represent
        // exactly, and every one of those branches is a test. Dropping the branch number is how
        // a refusal quietly becomes a silent rewrite of somebody's policy.
        'src/lib/iam/policy-document.ts': {
          statements: 85,
          branches: 75,
          functions: 100,
          lines: 90,
        },
      },
    },
  },
});
