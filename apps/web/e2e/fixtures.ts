import { test as base, type Page } from "@playwright/test";

/**
 * The auth cookie the middleware gates on. `src/lib/auth.ts` documents it as
 * `nora_access` (Path=/, SameSite=Lax), and `src/middleware.ts` reads exactly
 * `req.cookies.get('nora_access')?.value` — presence only, no verification.
 */
export const SESSION_COOKIE = "nora_access";

/**
 * WHAT THE LOGIN FIXTURE IS, AND IS NOT.
 *
 * The middleware decides route access on the PRESENCE of the cookie, not on the token being
 * valid — it runs at the edge and cannot verify a signature without a round trip. So a
 * fixture that sets an arbitrary value exercises the real gate, unchanged, and reaches the
 * authenticated shell without a backend, a database or a user.
 *
 * It does NOT establish a session in any deeper sense. Nothing behind these routes that
 * calls the API with this token will get data back. That is the honest scope: these tests
 * are about ROUTING, HEADERS and what the BROWSER objects to — the three things that can be
 * checked without standing up the stack, and the three that nothing checks today.
 *
 * The value is deliberately not a well-formed JWT. A fake-but-plausible token invites the
 * reader to believe more is being tested than is; a value that says what it is does not.
 */
const FIXTURE_TOKEN = "e2e-fixture-not-a-real-token";

type Fixtures = {
  /** A page that the middleware treats as signed in. */
  authedPage: Page;
};

/**
 * A SEPARATE BROWSER CONTEXT, and the first version of this file got it wrong in a way worth
 * recording. It called `page.context().addCookies(...)` and then `use(page)` — so `page` and
 * `authedPage` were THE SAME OBJECT, and the cookie was set on it either way.
 *
 * Playwright instantiates every fixture a test destructures, used or not. So a test written
 * `async ({ page, authedPage })` to cover the anonymous case got a signed-in page, and
 * `middleware.ts` redirected `/` straight to `/dashboard`. The public landing page was never
 * loaded by anything, while the suite reported it green.
 *
 * That is the same defect as a false-positive pool that cannot fail, in a different costume:
 * a test that passes without ever reaching its subject. A separate context makes the two
 * genuinely independent, so the anonymous fixture is anonymous.
 */
// `react-hooks/rules-of-hooks` is turned off for `e2e/**` in .eslintrc.json, and this is the
// line that needs it: the rule matches any identifier starting with `use` called outside a
// component, and Playwright's fixture callback argument is named `use`. There is no React in
// this directory. The alternative — an eslint-disable comment on every fixture anyone ever
// writes — is worse, and the override is scoped so the rule keeps protecting the real
// component code. (`.eslintrc.json` cannot carry this note itself: eslint rejects an unknown
// key, including `comment`, which is how the first version of the override failed CI.)
export const test = base.extend<Fixtures>({
  authedPage: async ({ browser, baseURL }, use) => {
    const url = new URL(baseURL ?? "http://127.0.0.1:3100");
    const context = await browser.newContext({ baseURL });
    await context.addCookies([
      {
        name: SESSION_COOKIE,
        value: FIXTURE_TOKEN,
        domain: url.hostname,
        path: "/",
        httpOnly: true,
        sameSite: "Lax",
      },
    ]);
    const authed = await context.newPage();
    await use(authed);
    await context.close();
  },
});

export { expect } from "@playwright/test";

/**
 * Collects CSP violations the browser reports, via the `securitypolicyviolation` DOM event.
 *
 * Deliberately NOT scraping the console. Chromium's console wording for a Report-Only
 * violation is not a contract and has changed between versions; the DOM event is specified,
 * carries the directive and the blocked URI as structured fields, and fires for Report-Only
 * policies as well as enforcing ones — which is the whole point here, since every policy
 * this application serves is Report-Only.
 *
 * Must be installed BEFORE navigation: `addInitScript` runs on every document before any
 * page script, which is the only way to catch a violation raised by the first inline script.
 */
export type CspViolation = {
  directive: string;
  blockedURI: string;
  policy: string;
};

export async function collectCspViolations(page: Page): Promise<() => Promise<CspViolation[]>> {
  await page.addInitScript(() => {
    (window as unknown as { __csp: CspViolation[] }).__csp = [];
    document.addEventListener("securitypolicyviolation", (e) => {
      (window as unknown as { __csp: CspViolation[] }).__csp.push({
        directive: e.effectiveDirective || e.violatedDirective,
        blockedURI: e.blockedURI,
        policy: e.originalPolicy,
      });
    });
  });

  return async () =>
    page.evaluate(() => (window as unknown as { __csp: CspViolation[] }).__csp ?? []);
}
