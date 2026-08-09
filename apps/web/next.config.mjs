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
    value: contentSecurityPolicy(),
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
