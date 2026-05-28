/**
 * /healthz — probe do Container App (mesma lição do nora-web: o ACA configura
 * Startup/Readiness/Liveness em /healthz; sem essa rota a revisão nova falha
 * e cai na antiga). 200 imediato, sem dependências.
 */
export const dynamic = "force-static";
export const revalidate = false;

export function GET() {
  return new Response("ok", { status: 200, headers: { "Content-Type": "text/plain; charset=utf-8" } });
}

export function HEAD() {
  return new Response(null, { status: 200 });
}
