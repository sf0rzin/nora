import { readdirSync, statSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

import { PROTECTED_PREFIXES, config } from "./middleware";

/**
 * The middleware is real access control, and its two lists are a hand-maintained copy of a
 * directory listing. That is the part that broke: `/usage` shipped with US33 and was added to
 * neither `PROTECTED_PREFIXES` nor `config.matcher`, so a signed-out visitor got a 200 on it
 * while every sibling route redirected to the login page. Found against production, not here.
 *
 * `e2e/route-protection.spec.ts` could not have caught it either — it iterates its own table of
 * routes, which mirrors the middleware. A page in neither list is invisible to a test built from
 * one of them, and the spec's own header warns about exactly this ("a refactor of the matcher
 * list silently drops a route ... the only symptom is that a signed-out visitor sees it").
 *
 * So this test does not restate the list. It reads `src/app/(app)` and requires every route
 * group found there to be covered by both. A new page under `(app)` fails this until it is
 * registered, which is the property that was missing.
 */
const APP_GROUP = join(__dirname, "app", "(app)");

/** Top-level segments under `(app)` — the granularity both middleware lists work at. */
function appRouteSegments(): string[] {
  return readdirSync(APP_GROUP)
    .filter((entry) => {
      if (entry.startsWith("_") || entry.startsWith("(") || entry.startsWith("@")) return false;
      return statSync(join(APP_GROUP, entry)).isDirectory();
    })
    .sort();
}

describe("middleware covers every app route", () => {
  const segments = appRouteSegments();

  it("finds the app route group at all", () => {
    // Guards against the whole suite passing vacuously if the directory is ever moved: an empty
    // list would satisfy every assertion below without checking anything.
    expect(segments.length).toBeGreaterThan(5);
  });

  it.each(segments)("/%s is in PROTECTED_PREFIXES", (segment) => {
    expect(PROTECTED_PREFIXES).toContain(`/${segment}`);
  });

  it.each(segments)("/%s is in config.matcher", (segment) => {
    const covered = config.matcher.some(
      (pattern) => pattern === `/${segment}` || pattern === `/${segment}/:path*`,
    );
    expect(
      covered,
      `/${segment} has a page under app/(app) but the middleware never runs on it — ` +
        `a signed-out visitor would render it`,
    ).toBe(true);
  });

  it("every protected prefix still corresponds to a real route", () => {
    // The other direction: a prefix left behind after a page is deleted protects nothing and
    // makes the list harder to trust.
    for (const prefix of PROTECTED_PREFIXES) {
      expect(segments, `${prefix} is protected but has no page under app/(app)`).toContain(
        prefix.slice(1),
      );
    }
  });
});
