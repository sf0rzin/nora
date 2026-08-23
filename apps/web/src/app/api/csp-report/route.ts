/**
 * Collector for the Content-Security-Policy reports the app's own policy points at.
 *
 * The policy is Report-Only, which is a deliberate and documented state — it is being measured
 * before it is enforced (see the long note in `next.config.mjs`). What made that state hollow is
 * that it named no reporting endpoint: violations existed only in the console of whoever happened
 * to have devtools open. The policy neither blocked anything nor collected anything, so the
 * measurement it exists for depended on a person watching.
 *
 * This is deliberately the smallest possible collector: it writes one line per report to the same
 * stdout everything else in this app logs to, and answers 204. No storage, no fan-out to a
 * third-party service, and no response body — a browser ignores whatever it is sent back.
 *
 * The e2e suite (`e2e/csp-violations.spec.ts`) stays the gate. It catches violations the
 * application causes ITSELF, in CI, before a deploy; this catches the ones only a real browser on
 * a real page produces — an extension injecting a script, a Cloudflare feature, a page nobody
 * walked through. Different questions, and neither replaces the other.
 */
export const runtime = "nodejs";
export const dynamic = "force-dynamic";

/**
 * Cap on a single report body. A report is a small JSON object; anything larger is either an
 * abusive client or a browser bug, and neither is worth reading into memory.
 */
const MAX_REPORT_BYTES = 32 * 1024;

/** Truncation applied to any single field before it is logged, so one report is one line. */
const MAX_FIELD_CHARS = 300;

function field(value: unknown): string {
  if (typeof value !== "string") return "?";
  const flat = value.replace(/\s+/g, " ").trim();
  return flat.length > MAX_FIELD_CHARS ? `${flat.slice(0, MAX_FIELD_CHARS)}…` : flat;
}

/**
 * Two wire formats, both alive.
 *
 * `report-uri` sends `{"csp-report": {...}}` with kebab-case keys; the Reporting API (`report-to`)
 * sends an ARRAY of `{type, body}` with camelCase keys. The policy declares both because browser
 * support is still split, so this normalises rather than picking a side.
 */
function describe(payload: unknown): string[] {
  const lines: string[] = [];

  const fromLegacy = (payload as { "csp-report"?: Record<string, unknown> })?.["csp-report"];
  if (fromLegacy) {
    lines.push(
      `directive=${field(fromLegacy["violated-directive"])} ` +
        `blocked=${field(fromLegacy["blocked-uri"])} ` +
        `document=${field(fromLegacy["document-uri"])}`,
    );
  }

  if (Array.isArray(payload)) {
    for (const entry of payload) {
      const body = (entry as { type?: string; body?: Record<string, unknown> })?.body;
      if (!body) continue;
      lines.push(
        `directive=${field(body.effectiveDirective)} ` +
          `blocked=${field(body.blockedURL)} ` +
          `document=${field(body.documentURL)}`,
      );
    }
  }

  return lines;
}

export async function POST(req: Request): Promise<Response> {
  // Answering 204 on every path, including a malformed body, is not laziness: a browser retries
  // nothing and reads nothing, so an error status only adds noise to somebody's console for a
  // report this endpoint has already decided to drop.
  try {
    const raw = await req.text();
    if (raw.length > MAX_REPORT_BYTES) return new Response(null, { status: 204 });
    const lines = describe(JSON.parse(raw) as unknown);
    for (const line of lines) console.warn(`[csp] ${line}`);
  } catch {
    // Not JSON, or not a shape this understands. Nothing to log and nothing to say.
  }
  return new Response(null, { status: 204 });
}
