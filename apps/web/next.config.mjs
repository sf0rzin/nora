/** @type {import('next').NextConfig} */

// Security headers (defense in depth — B2 frontend audit).
//
// connect-src must cover the real API called by the browser. The web client
// (`src/lib/api/client.ts`, `src/lib/auth.ts`) fetches directly against
// NEXT_PUBLIC_API_BASE_URL, so we derive that env's origin at build time
// and include it in the policy. The browser's other fetches go to its own
// origin ('self'): the BFF at /api/chat and the rest of the RSC/route-handler routes.
//
// In production the API is served at https://api.nora.systems (same registrable
// domain as nora.systems) so that the auth cookies (Domain=nora.systems)
// are sent cross-subdomain. NEXT_PUBLIC_API_BASE_URL points at that host;
// calling the API through a host outside nora.systems would make the browser
// reject the cookies (Domain mismatch) and block login.
function apiOrigin() {
  const raw = process.env.NEXT_PUBLIC_API_BASE_URL;
  if (!raw) return null;
  try {
    return new URL(raw).origin;
  } catch {
    return null;
  }
}

// CSP delivered as **Report-Only** on purpose: the web app uses Tailwind +
// inline styles + Next hydration (which injects inline <style>/<script>), so an
// enforcing policy has to be observed before it blocks. Report-Only never
// blocks a request — it only reports violations in the browser console. Harden
// to `Content-Security-Policy` (enforcing) after validating there are no
// legitimate violations in production (ideally swapping 'unsafe-inline' for
// nonce/hash in script-src).
//
// THE CEILING OF A SINGLE REPORT-ONLY POLICY, and why `NORA_CSP_PROBE` exists. A policy
// that permits exactly what you would want to forbid cannot tell you what a stricter one
// would break: removing 'unsafe-eval' generates no reports until it is removed. So the
// header below is switchable — see `strictProbePolicy()`.
//
// THE OBVIOUS APPROACH DOES NOT WORK, and this is the part worth knowing before someone
// tries it again. Two CSP headers are legal and the spec evaluates each policy
// independently, so serving a second, stricter Report-Only header alongside the first
// should cost nothing. It does not survive Next: with two same-key entries in this array,
// exactly ONE header reaches the client, and it is the LAST entry.
//
// WHERE THE COLLAPSE HAPPENS MATTERS, and an earlier version of this comment got it wrong.
// `headers()` does NOT dedupe — `.next/routes-manifest.json` keeps BOTH entries. The
// collapse is at response-write time. That distinction is the trap: the natural way to check
// this is to read the manifest, where two entries appear and everything looks like it
// worked. Measured in both orderings against a real `next build && next start`: whichever
// entry is last is the one served, and `grep -ci content-security-policy` on the response
// returns 1 either way.
//
// So the baseline policy is silently REPLACED rather than supplemented — worse than the
// change being inert, and in a file where someone may later flip this key to enforcing, the
// sort of thing that has to be written down rather than rediscovered.
//
// Doing it properly needs `NextResponse.headers.append` in middleware, whose matcher today
// covers a handful of route prefixes rather than every response, or a `header` directive in
// the Caddyfile — which would split one policy across two files. Neither is worth it for a
// measurement that is a deliberate, bounded act anyway.
function contentSecurityPolicy() {
  const api = apiOrigin();
  const connectSrc = ["'self'", api].filter(Boolean).join(" ");
  return [
    "default-src 'self'",
    "script-src 'self' 'unsafe-inline' 'unsafe-eval'",
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    "img-src 'self' data: blob:",
    `connect-src ${connectSrc}`,
    "frame-ancestors 'none'",
    "base-uri 'self'",
    "object-src 'none'",
  ].join("; ");
}

// The measurement that did not exist. Identical to the policy above except that
// 'unsafe-eval' is gone from script-src — one variable, so a report names one cause.
//
// HOW TO RUN IT — and the variable is BUILD-TIME, not runtime. Next evaluates `headers()`
// during `next build` and writes the result into `.next/routes-manifest.json`, so setting
// it for `next start` does nothing. Measured, because it is the natural mistake:
//
//     npm run build                              -> script-src 'self' 'unsafe-inline' 'unsafe-eval'
//     NORA_CSP_PROBE=strict npm run build        -> script-src 'self' 'unsafe-inline'
//     next start with NO variable, after that
//       second build                             -> still the strict one
//
// So it belongs in the image build, and turning it off needs a rebuild too. `ARG
// NORA_CSP_PROBE` is declared in ../Dockerfile for exactly this — Docker DISCARDS a
// --build-arg for an undeclared ARG, silently, so before that line the documented procedure
// produced an ordinary image and said nothing. `build-images.yml` does not pass it; a probe
// image is built by hand:
//
//     docker build -f apps/web/Dockerfile --build-arg NORA_CSP_PROBE=strict .
//
// Then deploy it, browse the application INCLUDING the authenticated pages, read the
// console, and deploy an ordinary build.
//
// It is Report-Only either way, so a forgotten probe cannot block anything. What it does do
// is stop the baseline policy from being reported, which is why it is off by default and
// why this is a measurement session rather than a setting.
//
// WHY THAT VARIABLE AND NOT 'unsafe-inline'. Dropping 'unsafe-inline' would report every
// hydration script Next injects on every page, which is a fact already known and would
// drown the channel. Whether anything genuinely needs eval() is NOT known, has a crisp
// answer, and decides the shape of the next step: with no eval, a nonce on the inline
// scripts is the whole remaining distance to an enforcing policy.
//
// HOW TO READ IT. Load a page, open the console, and look for a violation naming
// `script-src 'self' 'unsafe-inline'` — the strict policy — as the one violated. Reports
// naming the permissive policy above are a different (and more serious) finding.
//
// WHERE TO LOOK FIRST: the IAM policy editor, because it mounts Monaco and is the page most
// likely to need eval. NOT, as an earlier version of this said, because Monaco is "the likely
// reason 'unsafe-eval' is in the policy at all" — that is refutable from git. 'unsafe-eval'
// entered apps/web AND apps/admin in the same commit (0d785eb, "chore(web/admin): HTTP
// security headers"), and apps/admin has no Monaco: no dependency, no import, and a
// byte-identical `script-src`. It is boilerplate applied to two apps, one of which happens to
// have Monaco. Right page, wrong reason.
//
// apps/admin carries the same policy and has no probe. Same unanswered question, second
// surface.
//
// Measured on production 2026-08-12, decompressed and with a browser User-Agent: every
// `src=` on the public home page is same-origin, so the strict policy's `script-src
// 'self'` is not violated by anything loaded there. (This also retires an older note
// saying the Cloudflare Web Analytics beacon at static.cloudflareinsights.com was the one
// standing violation — it is no longer injected. What Cloudflare does inject is
// `/cdn-cgi/scripts/.../email-decode.min.js`, which is same-origin and satisfies 'self'.)
// DERIVED, not copied, and the first version of this was a copy. A duplicated policy makes
// the "one variable" guarantee a matter of care: add a directive to the baseline, or change
// `connect-src`, and the probe quietly measures two changes at once — which is the exact
// ambiguity this switch exists to avoid, and nothing would have caught the drift.
function strictProbePolicy() {
  const strict = contentSecurityPolicy().replace(" 'unsafe-eval'", "");
  if (strict === contentSecurityPolicy()) {
    // Fail loudly rather than serve a "strict" policy identical to the baseline, which would
    // report clean and mean nothing. If 'unsafe-eval' is already gone, the probe has done its
    // job and this function should be deleted along with NORA_CSP_PROBE.
    throw new Error(
      "NORA_CSP_PROBE=strict, but the baseline policy has no 'unsafe-eval' to remove. " +
        "Either the probe already succeeded and should be deleted, or the string it looks " +
        "for has changed.",
    );
  }
  return strict;
}

const securityHeaders = [
  {
    key: "Strict-Transport-Security",
    value: "max-age=63072000; includeSubDomains; preload",
  },
  { key: "X-Frame-Options", value: "DENY" },
  { key: "X-Content-Type-Options", value: "nosniff" },
  { key: "Referrer-Policy", value: "strict-origin-when-cross-origin" },
  {
    key: "Permissions-Policy",
    value: "camera=(), microphone=(), geolocation=()",
  },
  {
    key: "Content-Security-Policy-Report-Only",
    value:
      process.env.NORA_CSP_PROBE === "strict"
        ? strictProbePolicy()
        : contentSecurityPolicy(),
  },
];

const nextConfig = {
  reactStrictMode: true,
  poweredByHeader: false,
  experimental: {
    typedRoutes: true,
  },
  async headers() {
    return [
      {
        source: "/(.*)",
        headers: securityHeaders,
      },
    ];
  },
};

export default nextConfig;
