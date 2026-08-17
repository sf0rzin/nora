import { test, expect } from "./fixtures";

/**
 * `src/middleware.ts` is real access control: it is what stops an unauthenticated browser from
 * rendering the application shell. It had never had a test, and it is the kind of code where a
 * refactor of the matcher list silently drops a route — the page still renders, so nothing
 * looks broken, and the only symptom is that a signed-out visitor sees it.
 *
 * ONE REAL PAGE PER PROTECTED PREFIX. The first version of this file used the prefixes
 * themselves, and two of them did not exist: `/meetings` and `/settings` were 404s — only
 * `/meetings/[id]`, `/meetings/upload`, `/settings/context` and `/settings/iam` were real. The
 * signed-out assertions still held (the middleware redirects before the route resolves), but
 * `with a session cookie, /meetings is not redirected away` passed because a 404 is not a
 * redirect. Two vacuous tests, reported as coverage.
 *
 * `/settings` has since become a real route (it redirects to `/settings/context`); `/meetings`
 * has not. The table below still names one concrete page per prefix, which is the invariant
 * that keeps the authenticated case from being vacuous whichever prefixes happen to resolve.
 *
 * So the authenticated case now asserts a 200 as well. "The page did not redirect" and "the
 * page exists" are different claims and only one of them was being made.
 */
/**
 * This table mirrors the middleware's own list, which is why it could not catch `/usage`: that
 * page was in neither, so a test built from one of them never looked at it. A signed-out visitor
 * got a 200 on it in production for as long as it existed. The invariant "every page under
 * `app/(app)` is registered in the middleware" is now derived from the filesystem in
 * `src/middleware-covers-app-routes.test.ts`; this table stays for the behavioural half —
 * that the redirect and the `next` parameter actually work in a browser.
 */
const PROTECTED: ReadonlyArray<{ prefix: string; page: string }> = [
  { prefix: "/dashboard", page: "/dashboard" },
  { prefix: "/meetings", page: "/meetings/upload" },
  { prefix: "/settings", page: "/settings/context" },
  { prefix: "/tasks", page: "/tasks" },
  { prefix: "/trends", page: "/trends" },
  { prefix: "/usage", page: "/usage" },
  { prefix: "/chat", page: "/chat" },
  { prefix: "/projects", page: "/projects" },
  { prefix: "/integrations", page: "/integrations" },
  { prefix: "/flows", page: "/flows" },
];

test.describe("route protection", () => {
  for (const { page: path } of PROTECTED) {
    test(`signed out, ${path} redirects to the login page`, async ({ page }) => {
      await page.goto(path);
      await expect(page).toHaveURL(/\/auth\/login/);
    });

    test(`signed out, ${path} is preserved in the next parameter`, async ({ page }) => {
      // Not decoration: without it, a user who follows a deep link, signs in, and lands on the
      // dashboard has to find their way back by hand. It is also the parameter most likely to
      // be dropped in a refactor of the redirect, because nothing visibly breaks.
      await page.goto(path);
      expect(new URL(page.url()).searchParams.get("next")).toBe(path);
    });
  }

  for (const { page: path } of PROTECTED) {
    test(`with a session cookie, ${path} renders instead of redirecting`, async ({
      authedPage,
    }) => {
      const response = await authedPage.goto(path);
      await expect(authedPage).not.toHaveURL(/\/auth\/login/);
      expect(response!.status(), `${path} should exist — a 404 is not a passing case`).toBe(200);
    });
  }

  test("the landing page is public", async ({ page }) => {
    const response = await page.goto("/");
    expect(response!.status()).toBe(200);
    await expect(page).toHaveURL(/\/$/);
  });

  test("a signed-in visitor on the landing page is sent to the dashboard", async ({
    authedPage,
  }) => {
    // The other direction of the same middleware, and the one nobody thinks to check:
    // `pathname === '/' && token` redirects, so a logged-in user does not have to go back in
    // through the marketing page to reach the product.
    await authedPage.goto("/");
    await expect(authedPage).toHaveURL(/\/dashboard/);
  });

  test("the health endpoint stays reachable without a session", async ({ page }) => {
    // Not in the matcher, and must not be: a health check that requires a session reports the
    // wrong thing.
    const response = await page.goto("/healthz");
    expect(response!.status()).toBe(200);
  });
});
