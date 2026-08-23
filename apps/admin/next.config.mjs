/** @type {import('next').NextConfig} */

// Security headers (defense in depth — B2 frontend audit).
//
// The admin console calls the Spring API server-side only (BFF in `src/lib/data.ts`
// via PLATFORM_API_BASE_URL — no NEXT_PUBLIC_ prefix, so never exposed to the
// browser). The browser therefore only connects to its own origin: connect-src 'self'.
//
// The CSP is **enforcing**. It was Report-Only until 2026-08-23, waiting on an observation period
// that never had an owner or an end date, which is how a policy that blocks nothing becomes
// permanent. Report-Only on the surface that edits the LLM catalog of the whole platform and holds
// the bridge token server-side had the priority backwards: this app loads no third-party content, so
// the directives that actually matter here — `default-src 'self'`, `connect-src 'self'`,
// `object-src 'none'`, `frame-ancestors 'none'` — have nothing legitimate to break.
//
// `'unsafe-inline'` stays in script-src and style-src, and the honesty about that matters: Next
// injects inline bootstrap/hydration scripts and this console styles everything with inline `style`
// attributes, so removing it needs a nonce pipeline, not a flag flip. What enforcing buys today is
// origin confinement (no script, connection, frame or object from anywhere else), not inline-script
// immunity.
//
// `'unsafe-eval'` is development-only: the dev server's HMR needs it, a production build does not,
// and leaving it in the shipped policy would hand an injected string a working evaluator.
const isDev = process.env.NODE_ENV === "development";

const contentSecurityPolicy = [
  "default-src 'self'",
  `script-src 'self' 'unsafe-inline'${isDev ? " 'unsafe-eval'" : ""}`,
  "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
  "font-src 'self' https://fonts.gstatic.com",
  "img-src 'self' data: blob:",
  "connect-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'self'",
  "form-action 'self'",
  "object-src 'none'",
].join("; ");

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
    key: "Content-Security-Policy",
    value: contentSecurityPolicy,
  },
];

const nextConfig = {
  reactStrictMode: true,
  // Internal console — standalone makes for a lean Docker image.
  output: "standalone",
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
