import { test, expect, collectCspViolations } from "./fixtures";

/**
 * THIS IS THE MEASUREMENT SECURITY-FINDINGS ITEM 2 ASKED FOR, automated.
 *
 * That finding could only measure CSP violations by having a human open a browser console on
 * the production site — which is why it says the authenticated pages "were never loaded" and
 * that "anyone finishing this must start there". A human measurement nobody repeats is a
 * snapshot; this makes it a check.
 *
 * WHAT IT ASSERTS, and the direction matters: ZERO violations of the application's own policy.
 * It is not asserting the policy is strict — it is not, it still permits 'unsafe-inline' and
 * 'unsafe-eval', which is the whole subject of finding 2. It asserts that whatever the policy
 * currently is, the application does not violate it. That is the precondition for ever flipping
 * the header to enforcing: a policy the app already breaks cannot be enforced no matter how
 * carefully anyone reads it.
 *
 * THE FIRST VERSION OF THIS FILE REPORTED ZERO VIOLATIONS AND WAS WRONG THREE TIMES OVER, and
 * all three are the same class of error — a test that passes without reaching its subject:
 *
 *   1. It ran green locally only because an untracked `.env.local` set
 *      NEXT_PUBLIC_API_BASE_URL, which put `http://localhost:8080` into `connect-src`. CI has
 *      no such file, so the policy shipped `connect-src 'self'` while `client.ts` still fetched
 *      its own hardcoded `?? 'http://localhost:8080'` default. Four of six pages violated their
 *      own policy. The test was right and the claim was wrong — see the note on the page list.
 *   2. `page` and `authedPage` were the same object (fixed in fixtures.ts), so the "landing
 *      (public)" case was measuring the dashboard.
 *   3. Two of the six routes did not exist. `/meetings` and `/settings` are 404s — only
 *      `/meetings/[id]`, `/meetings/upload`, `/settings/context` and `/settings/iam` are real —
 *      and a 404 page fetches nothing, so those two cases passed vacuously. They were, in fact,
 *      the only two that passed.
 *
 * Of six page tests, four failed for a real reason and two could not fail at all. The status
 * assertion below exists so a route that stops existing can never again be mistaken for a
 * route that behaves.
 */
const PAGES = [
  { path: "/", label: "landing (public)", authed: false },
  { path: "/dashboard", label: "dashboard", authed: true },
  { path: "/tasks", label: "tasks", authed: true },
  { path: "/settings/context", label: "settings / tenant context", authed: true },
  { path: "/settings/iam", label: "settings / IAM (mounts Monaco)", authed: true },
  { path: "/chat", label: "chat", authed: true },
  { path: "/flows", label: "flows (visual builder)", authed: true },
  { path: "/meetings/upload", label: "meetings / upload", authed: true },
] as const;

test.describe("CSP violations", () => {
  for (const { path, label, authed } of PAGES) {
    // NOT `async ({ page, authedPage })`. Playwright instantiates every fixture a test
    // destructures, so naming both would give the anonymous case a signed-in context — which
    // is exactly how the landing page went unmeasured for a whole review round.
    if (authed) {
      test(`${label} raises no CSP violation`, async ({ authedPage }) => {
        await assertNoViolations(authedPage, path);
      });
    } else {
      test(`${label} raises no CSP violation`, async ({ page }) => {
        await assertNoViolations(page, path);
      });
    }
  }

  /**
   * The pool has to be able to fail, or a passing run means nothing. Same rule as
   * `test_the_false_positive_pool_is_not_inert` in the worker's PII corpus.
   *
   * A deliberate violation: an inline <script> is permitted by 'unsafe-inline', so that would
   * prove nothing — it has to be something the policy actually forbids. An image from a foreign
   * origin is refused by `img-src 'self' data: blob:` and needs no network round trip.
   *
   * Its limit, stated because the sibling rule it invokes is stronger: this proves the
   * COLLECTOR works. It does not prove each page in the list above is reachable — the status
   * and body assertions in `assertNoViolations` are what do that, and they exist because two
   * unreachable pages sat in this list undetected.
   */
  test("the collector is not inert — a deliberate violation is caught", async ({ page }) => {
    const read = await collectCspViolations(page);
    await page.goto("/");

    await page.evaluate(() => {
      const img = document.createElement("img");
      img.src = "https://example.invalid/deliberate-csp-probe.png";
      document.body.appendChild(img);
    });

    await expect
      .poll(async () => (await read()).length, {
        message: "the securitypolicyviolation listener should have caught the probe",
      })
      .toBeGreaterThan(0);

    const violations = await read();
    expect(violations.some((v) => v.directive.startsWith("img-src"))).toBe(true);
  });
});

async function assertNoViolations(page: import("@playwright/test").Page, path: string) {
  const read = await collectCspViolations(page);

  const response = await page.goto(path);

  // A 404 renders `not-found.tsx`, which fetches nothing and therefore violates nothing. Two
  // such routes were in this list and passed for that reason. Asserting the status is what
  // stops "the page behaved" and "the page does not exist" from looking identical.
  expect(response, `${path} should respond`).not.toBeNull();
  expect(response!.status(), `${path} should exist — a 404 cannot violate a policy`).toBe(200);

  // Wait on the document being settled rather than on `networkidle`, which Playwright's own
  // docs discourage. Here it was worse than merely discouraged: it depended on the app's
  // failing API fetches being REFUSED quickly. A runner with something listening on that port,
  // or a hang instead of a refusal, would turn it into a 60s timeout on an unrelated PR.
  await page.waitForLoadState("domcontentloaded");
  await expect(page.locator("body")).not.toBeEmpty();

  const violations = await read();
  expect(
    violations,
    `${path} violated its own Content-Security-Policy. Each entry names the directive and the ` +
      `blocked URI. If the blocked URI is a third party injected at the edge, the fix is the ` +
      `policy; if it is our own origin, the app and the policy disagree about where the API ` +
      `lives — check NEXT_PUBLIC_API_BASE_URL against apiOrigin() in next.config.mjs.`,
  ).toEqual([]);
}
