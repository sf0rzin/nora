/**
 * /healthz — endpoint de health check pro probe do Container App (ACA).
 *
 * O Bicep configura Startup/Readiness/Liveness probes em `/healthz` na porta 3000.
 * Sem essa rota, as revisões novas viravam ActivationFailed e o ACA caía na revisão
 * antiga (que serve o bundle errado).
 *
 * Mantém leve: status 200 imediato, sem dependências (DB, backend etc).
 * `force-static` evita revalidação por request.
 */
export const dynamic = "force-static";
export const revalidate = false;

export function GET() {
  return new Response("ok", {
    status: 200,
    headers: { "Content-Type": "text/plain; charset=utf-8" },
  });
}

export function HEAD() {
  return new Response(null, { status: 200 });
}
