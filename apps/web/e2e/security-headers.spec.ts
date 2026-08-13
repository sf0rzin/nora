import { test, expect } from "./fixtures";

/**
 * `next.config.mjs` sets six security headers on every route and nothing has ever checked
 * that they arrive. They are the kind of thing that disappears in a refactor without a
 * single test going red, and the disappearance is invisible until someone runs a scanner.
 *
 * These assertions are deliberately about PRESENCE AND SHAPE, not exact strings. Asserting
 * the full CSP verbatim would fail on every legitimate policy edit and teach whoever makes
 * the next one to update the test without reading it — which is how a test stops being a
 * check and becomes a formality.
 */
test.describe("security headers", () => {
  test("every documented header is served on a public route", async ({ page }) => {
    const response = await page.goto("/");
    expect(response, "the home page should respond").not.toBeNull();

    const headers = response!.headers();

    expect(headers["strict-transport-security"]).toContain("max-age=");
    expect(headers["strict-transport-security"]).toContain("includeSubDomains");
    expect(headers["x-frame-options"]).toBe("DENY");
    expect(headers["x-content-type-options"]).toBe("nosniff");
    expect(headers["referrer-policy"]).toBe("strict-origin-when-cross-origin");
    expect(headers["permissions-policy"]).toContain("camera=()");
    expect(headers["permissions-policy"]).toContain("microphone=()");
  });

  test("the CSP is served, and is still Report-Only", async ({ page }) => {
    const response = await page.goto("/");
    const headers = response!.headers();

    const reportOnly = headers["content-security-policy-report-only"];
    expect(reportOnly, "the Report-Only CSP should be served").toBeTruthy();

    // The three directives that carry the weight. `frame-ancestors 'none'` is what actually
    // stops framing on browsers that ignore X-Frame-Options, and `object-src 'none'` closes
    // the plugin surface X-Frame-Options never covered.
    expect(reportOnly).toContain("default-src 'self'");
    expect(reportOnly).toContain("frame-ancestors 'none'");
    expect(reportOnly).toContain("object-src 'none'");

    // Asserted as an ABSENCE on purpose. Flipping this key to enforcing is the eventual
    // goal (SECURITY-FINDINGS item 2) and it must be a deliberate act with the measurement
    // behind it — not something that arrives in a refactor. When it does happen, this line
    // is the one that has to be changed, which is exactly the conversation that should be
    // forced.
    expect(
      headers["content-security-policy"],
      "flipping the CSP to enforcing is a deliberate change — update this test with the measurement that justifies it",
    ).toBeUndefined();
  });

  test("the headers are on authenticated routes too, not only the landing", async ({
    authedPage,
  }) => {
    // The middleware matches `/dashboard/:path*`, so this response comes through a
    // different path than the static home page. Headers configured under `source: "/(.*)"`
    // should cover both, and "should" is the reason to check.
    const response = await authedPage.goto("/dashboard");
    const headers = response!.headers();

    expect(headers["x-frame-options"]).toBe("DENY");
    expect(headers["content-security-policy-report-only"]).toBeTruthy();
  });

  test("the framework version header is not advertised", async ({ page }) => {
    // `poweredByHeader: false` in next.config.mjs. Cheap to lose, free to check.
    const response = await page.goto("/");
    expect(response!.headers()["x-powered-by"]).toBeUndefined();
  });
});
