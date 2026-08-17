import { defineConfig, devices } from "@playwright/test";

/**
 * First test suite `apps/web` has ever had; a Vitest unit suite joined it on 2026-08-17
 * (`vitest.config.mts`, ADR 0042) and the two never see each other's files. This one is a thin
 * suite over three things that are cheap to check and expensive to get wrong:
 *
 *   1. the security headers, which nothing has ever regression-tested;
 *   2. the middleware's route protection, which is real access control;
 *   3. CSP violations, which SECURITY-FINDINGS item 2 could only measure by a human
 *      opening a browser console — and which therefore had never been measured on an
 *      authenticated page at all.
 *
 * WHAT THIS DOES NOT DO, so a green run is not read as more than it is: it runs against a
 * build with `NEXT_PUBLIC_USE_MOCKS=true`, so no API, no database and no real session are
 * involved. It tests the shell — routing, headers, and what the browser objects to. Product
 * behaviour behind those routes is out of scope and stays untested.
 */
export default defineConfig({
  testDir: "./e2e",

  // CI runs this on a shared runner alongside a Next build; a per-test timeout that is
  // generous costs nothing when things pass, and the alternative is the flake class that
  // finding 13 documents in the Rust suite.
  timeout: 60_000,
  expect: { timeout: 10_000 },

  // No retries. A retry would hide exactly the intermittency this repository has already
  // been bitten by once — `desktop-rust`'s sidecar test, where "just re-run it" is what let
  // a real defect sit for weeks. If a test here is flaky, that is a finding, not a nuisance.
  retries: 0,
  fullyParallel: true,
  forbidOnly: !!process.env.CI,

  reporter: process.env.CI ? [["list"], ["html", { open: "never" }]] : [["list"]],

  use: {
    baseURL: "http://127.0.0.1:3100",
    trace: "on-first-retry",
    screenshot: "only-on-failure",
  },

  projects: [{ name: "chromium", use: { ...devices["Desktop Chrome"] } }],

  // `next start`, not `next dev`. The headers under test are baked into
  // `.next/routes-manifest.json` at build time (see next.config.mjs), and dev mode injects
  // its own scripts and websocket, which would produce CSP violations that do not exist in
  // any deployed build. Testing dev would measure the wrong thing.
  //
  // NO `env:` HERE, deliberately, and the first version of this file had one. Every variable
  // this suite depends on — `NEXT_PUBLIC_USE_MOCKS` and `NEXT_PUBLIC_API_BASE_URL` — is
  // `NEXT_PUBLIC_*`, which Next inlines at BUILD time. Setting them on `next start` cannot
  // change the client bundle, so an `env:` block here reads as a guarantee and provides none.
  // What matters is the build; see the note in `next.config.mjs` and the CI job.
  //
  // `npx next start`, not `npm run start -- --port 3100`: the package script already pins
  // `-p 3000`, so the npm form expands to `next start -p 3000 --port 3100` and works only
  // because Next happens to take the last flag.
  webServer: {
    command: "npx next start --port 3100",
    url: "http://127.0.0.1:3100/healthz",
    reuseExistingServer: !process.env.CI,
    timeout: 120_000,
  },
});
