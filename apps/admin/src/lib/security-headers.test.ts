/**
 * The console's security headers are configuration, and configuration silently stops being emitted:
 * a header deleted from `next.config.mjs` breaks nothing, fails no build and shows no symptom until
 * somebody goes looking. `apps/web` has Playwright specs for exactly this reason; `apps/admin` had
 * nothing, on the surface that edits the LLM catalog of the whole platform.
 *
 * This asserts the configuration rather than a live response, which is the honest scope: it cannot
 * prove a deployed instance sends the headers (a reverse proxy could strip them), only that the app
 * still asks for them. That is the regression this file is here to catch.
 *
 * The CSP assertion is deliberately about the KEY. The policy was delivered as
 * `Content-Security-Policy-Report-Only` until 2026-08-23 — a policy that reports and blocks nothing,
 * waiting on an observation period with no owner and no end date. Reverting to Report-Only is a
 * one-word edit with no visible effect, so a test is the only thing standing in front of it.
 */
import { describe, expect, it } from "vitest";

import nextConfig from "../../next.config.mjs";

interface HeaderRule {
  source: string;
  headers: Array<{ key: string; value: string }>;
}

// The config is plain JS annotated with `@type {NextConfig}`, where `headers` is optional. Naming
// the shape here keeps the assertions typed without pretending the config exports types it does not.
const config = nextConfig as unknown as { headers: () => Promise<HeaderRule[]> };

async function headersFor(path: string): Promise<Map<string, string>> {
  const rules = await config.headers();
  const matching = rules.filter((rule) => new RegExp(`^${rule.source}$`).test(path));
  const found = new Map<string, string>();
  for (const rule of matching) {
    for (const header of rule.headers) found.set(header.key, header.value);
  }
  return found;
}

describe("security headers", () => {
  it("applies to every path, not only the root", async () => {
    for (const path of ["/", "/models", "/telemetry", "/healthz"]) {
      expect((await headersFor(path)).size, `no headers configured for ${path}`).toBeGreaterThan(0);
    }
  });

  it.each([
    ["Strict-Transport-Security", /max-age=\d{7,}/],
    ["X-Frame-Options", /^DENY$/],
    ["X-Content-Type-Options", /^nosniff$/],
    ["Referrer-Policy", /strict-origin/],
    ["Permissions-Policy", /camera=\(\)/],
  ])("emits %s", async (key, shape) => {
    const value = (await headersFor("/models")).get(key);
    expect(value, `${key} is not configured`).toBeDefined();
    expect(value).toMatch(shape);
  });
});

describe("content security policy", () => {
  it("is enforcing, not Report-Only", async () => {
    const headers = await headersFor("/models");
    expect(headers.has("Content-Security-Policy")).toBe(true);
    expect(headers.has("Content-Security-Policy-Report-Only")).toBe(false);
  });

  // What an enforcing policy buys this app is origin confinement: it loads no third-party content,
  // so nothing legitimate lives outside 'self'. These are the directives that do that work.
  it.each([
    "default-src 'self'",
    "connect-src 'self'",
    "frame-ancestors 'none'",
    "object-src 'none'",
    "base-uri 'self'",
    "form-action 'self'",
  ])("confines %s", async (directive) => {
    const csp = (await headersFor("/models")).get("Content-Security-Policy") ?? "";
    expect(csp.split("; ")).toContain(directive);
  });

  it("ships no 'unsafe-eval' outside the dev server", async () => {
    // The dev server's HMR needs it; a production build does not, and leaving it in the shipped
    // policy hands an injected string a working evaluator. NODE_ENV is "test" here — that is, any
    // environment that is not "development", which is the condition the config branches on.
    const csp = (await headersFor("/models")).get("Content-Security-Policy") ?? "";
    expect(csp).not.toContain("'unsafe-eval'");
  });

  it("still declares 'unsafe-inline', and says so rather than pretending otherwise", async () => {
    // Next injects inline bootstrap scripts and this console styles everything with inline `style`
    // attributes. Removing it needs a nonce pipeline, not a flag flip; this assertion exists so
    // that whoever builds the pipeline finds a test to update instead of a silent claim to disprove.
    const csp = (await headersFor("/models")).get("Content-Security-Policy") ?? "";
    expect(csp).toContain("script-src 'self' 'unsafe-inline'");
    expect(csp).toContain("style-src 'self' 'unsafe-inline'");
  });
});
