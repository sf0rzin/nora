/** @type {import('next').NextConfig} */

// Security headers (defesa em profundidade — auditoria frontend B2).
//
// O console admin chama a API Spring apenas server-side (BFF em `src/lib/data.ts`
// via PLATFORM_API_BASE_URL — sem prefixo NEXT_PUBLIC_, logo nunca exposto ao
// browser). Portanto o browser só conecta na própria origin: connect-src 'self'.
//
// CSP entregue em **Report-Only** de propósito: o console usa Tailwind + estilos
// inline + hidratação do Next (que injeta <style>/<script> inline), então uma
// policy enforcing precisa ser observada antes de bloquear. Report-Only nunca
// bloqueia request — só reporta violações no console do browser. Endurecer para
// `Content-Security-Policy` (enforcing) depois de validar que não há violações
// legítimas em produção (idealmente trocando 'unsafe-inline' por nonce/hash).
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
  // Console interno — standalone facilita imagem Docker enxuta.
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
