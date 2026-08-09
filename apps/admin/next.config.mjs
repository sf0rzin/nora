/** @type {import('next').NextConfig} */

// Security headers (defense in depth — B2 frontend audit).
//
// The admin console calls the Spring API server-side only (BFF in `src/lib/data.ts`
// via PLATFORM_API_BASE_URL — no NEXT_PUBLIC_ prefix, so never exposed to the
// browser). The browser therefore only connects to its own origin: connect-src 'self'.
//
// CSP delivered as **Report-Only** on purpose: the console uses Tailwind + inline
// styles + Next hydration (which injects inline <style>/<script>), so an
// enforcing policy has to be observed before it blocks. Report-Only never
// blocks a request — it only reports violations in the browser console. Harden to
// `Content-Security-Policy` (enforcing) after validating there are no legitimate
// violations in production (ideally swapping 'unsafe-inline' for nonce/hash).
const contentSecurityPolicy = [
  "default-src 'self'",
  "script-src 'self' 'unsafe-inline' 'unsafe-eval'",
  "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
  "font-src 'self' https://fonts.gstatic.com",
  "img-src 'self' data: blob:",
  "connect-src 'self'",
  "frame-ancestors 'none'",
  "base-uri 'self'",
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
    key: "Content-Security-Policy-Report-Only",
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
