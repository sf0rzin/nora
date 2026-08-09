/**
 * /healthz — Container App probe (same lesson as nora-web: ACA configures
 * Startup/Readiness/Liveness on /healthz; without this route the new revision fails
 * and falls back to the old one). Immediate 200, no dependencies.
 */
export const dynamic = "force-static";
export const revalidate = false;

export function GET() {
  return new Response("ok", { status: 200, headers: { "Content-Type": "text/plain; charset=utf-8" } });
}

export function HEAD() {
  return new Response(null, { status: 200 });
}
