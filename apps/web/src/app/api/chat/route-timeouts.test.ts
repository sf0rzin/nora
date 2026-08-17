import { readFileSync } from 'node:fs';
import { join } from 'node:path';

import { describe, expect, it } from 'vitest';

/**
 * Every network call this route makes must be bounded.
 *
 * `POST /api/chat` does six calls to our own backend before it flushes a single byte: the
 * session check, the control-plane model resolution, the tenant context, the meeting search,
 * the recents fallback and the task list. Each one is wrapped in `try/catch` with a documented
 * soft fallback — "on any failure, uses the env", "falls through to the recents fallback",
 * "returns null". None of them passed a signal.
 *
 * A HANG IS NOT A FAILURE. `fetch` without a signal waits forever, the `catch` never runs, and
 * the fallback that was supposed to keep the chat up never happens. What the user gets is the
 * gateway's 504 two minutes later; what the log shows is nothing at all, because execution never
 * reached the line that logs. Observed in production as `POST /api/chat` intermittently taking
 * 45s+ at the origin and 504 through the edge.
 *
 * This is a source-level assertion on purpose. The behaviour is a property of EVERY call site,
 * including ones added later, and a behavioural test would have to stall a real socket per call
 * to prove the same thing. What actually broke was someone adding a `fetch` without thinking
 * about the timeout, so that is what is checked.
 */
const ROUTE = readFileSync(join(__dirname, 'route.ts'), 'utf8');

/** Each `fetch(` and the options object that follows it, roughly. */
function fetchCallSites(): string[] {
  const sites: string[] = [];
  const re = /fetch\(/g;
  let m: RegExpExecArray | null;
  while ((m = re.exec(ROUTE)) !== null) {
    // Take from the call up to the first line that closes it at the same indentation. Crude,
    // but the file's formatting is stable and the alternative is a TS parser for one rule.
    sites.push(ROUTE.slice(m.index, m.index + 420));
  }
  return sites;
}

describe('every outbound call in the chat route is bounded', () => {
  const sites = fetchCallSites();

  it('finds the call sites at all', () => {
    // Without this the suite passes vacuously if the route is ever restructured.
    expect(sites.length).toBeGreaterThanOrEqual(6);
  });

  it.each(sites.map((s, i) => [i, s.split('\n')[0].trim()] as const))(
    'call site %i (%s) passes a signal',
    (index) => {
      const site = sites[index];
      expect(
        /signal:\s*(AbortSignal\.timeout|\w+\.signal)/.test(site),
        `fetch call site ${index} has no abort signal. An unbounded call here holds the ` +
          `response headers, so the request dies at the edge instead of falling back:\n` +
          site.slice(0, 300),
      ).toBe(true);
    },
  );

  it('keeps the internal budget well under the provider one', () => {
    const internal = Number(
      /INTERNAL_CALL_TIMEOUT_MS = ([\d_]+)/.exec(ROUTE)?.[1].replace(/_/g, ''),
    );
    const provider = Number(
      /PROVIDER_HEADER_TIMEOUT_MS = ([\d_]+)/.exec(ROUTE)?.[1].replace(/_/g, ''),
    );
    expect(internal).toBeGreaterThan(0);
    expect(provider).toBeGreaterThan(0);
    // Six internal calls plus the provider wait must still land inside the edge's own
    // patience (Caddy's response_header_timeout is 120s), with room to spare.
    expect(internal * 6 + provider).toBeLessThan(90_000);
  });
});
