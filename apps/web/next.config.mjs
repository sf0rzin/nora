/** @type {import('next').NextConfig} */

// Security headers (defesa em profundidade — auditoria frontend B2).
//
// connect-src precisa cobrir a API real chamada pelo browser. O client web
// (`src/lib/api/client.ts`, `src/lib/auth.ts`) faz fetch direto contra
// NEXT_PUBLIC_API_BASE_URL, então derivamos a origin desse env em build-time
// e a incluímos na policy. Os demais fetches do browser são contra a própria
// origin ('self'): o BFF em /api/chat e o restante das rotas RSC/route-handler.
//
// Em produção a API é servida em https://api.nora.systems (mesmo registrable
// domain que nora.systems) para que os cookies de auth (Domain=nora.systems)
// sejam enviados cross-subdomínio. NEXT_PUBLIC_API_BASE_URL aponta pra esse host;
// chamar a API por um host fora de nora.systems faria o navegador rejeitar os
// cookies (mismatch de Domain) e bloquear o login.
function apiOrigin() {
  const raw = process.env.NEXT_PUBLIC_API_BASE_URL;
  if (!raw) return null;
  try {
    return new URL(raw).origin;
  } catch {
    return null;
  }
}

// CSP entregue em **Report-Only** de propósito: o web usa Tailwind + estilos
// inline + hidratação do Next (que injeta <style>/<script> inline), então uma
// policy enforcing precisa ser observada antes de bloquear. Report-Only nunca
// bloqueia request — só reporta violações no console do browser. Endurecer
// para `Content-Security-Policy` (enforcing) depois de validar que não há
// violações legítimas em produção (idealmente trocando 'unsafe-inline' por
// nonce/hash no script-src).
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
