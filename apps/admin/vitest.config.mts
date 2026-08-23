import { fileURLToPath } from "node:url";
import { defineConfig } from "vitest/config";

/**
 * Unit-test runner for `apps/admin`.
 *
 * The console shipped with no test of any kind: CI ran install, lint, typecheck and build, and the
 * repository said so out loud rather than pretend otherwise. That honesty did not change the
 * consequence — `src/lib/access.ts` is the only authentication boundary of the control plane, it
 * decides fail-open versus fail-closed, and nothing verified the decision. The missing `baseUrl` in
 * the "add model" form proved the rest of the argument empirically: an entire mutation path was
 * broken and lint, typecheck and build all passed.
 *
 * Scope, stated so nobody reads more into a green run than is there: this suite covers the pure
 * server-side modules — access, data, operator — and the security headers. It renders nothing. The
 * screens have no unit tests, exactly as in `apps/web`.
 */
export default defineConfig({
  resolve: {
    // Mirrors `compilerOptions.paths` in tsconfig.json. Without it every `@/...` import fails to
    // resolve under the runner, which reads like a broken module graph rather than a missing alias.
    alias: {
      "@": fileURLToPath(new URL("./src", import.meta.url)),
    },
  },
  test: {
    include: ["src/**/*.test.ts"],
    exclude: ["node_modules/**", ".next/**"],

    // `node`, and no DOM anywhere: every module under test runs server-side by construction (they
    // read `next/headers`, `process.env` and `fetch`). A jsdom default would buy nothing and hide
    // an accidental browser-only dependency behind a global that production does not have.
    environment: "node",

    // Both flags pinned OFF because both gate a module-level constant read at import time.
    // `NORA_ADMIN_USE_MOCKS` decides whether `access.ts` enforces at all and whether `data.ts`
    // returns fixtures instead of calling the API — a suite that inherited a developer's `true`
    // from the shell would report green while testing neither. Tests that need the other value set
    // it themselves and re-import the module.
    env: {
      NORA_ADMIN_USE_MOCKS: "false",
      PLATFORM_API_BASE_URL: "http://platform.test.invalid",
    },

    coverage: {
      provider: "v8",
      reporter: ["text", "json-summary"],
      reportsDirectory: "./coverage",

      // The denominator is everything the app ships, not just the tested modules — a number in the
      // nineties for an app whose screens have no unit tests is the overstatement this repository
      // keeps removing from its own documents.
      include: ["src/**/*.{ts,tsx}"],
      exclude: ["src/**/*.test.ts", "src/lib/contracts.ts", "src/lib/mock.ts", "next-env.d.ts"],

      // FLOORS, not targets, and only on the module whose failure mode is silent. `access.ts` can
      // regress from fail-closed to fail-open without a single visible symptom — the console keeps
      // rendering, which is the bug. Everything else is reported and gated by nothing; a global
      // threshold here would either be symbolic or block every UI change.
      thresholds: {
        "src/lib/access.ts": { statements: 90, branches: 85, functions: 100, lines: 90 },
      },
    },
  },
});
