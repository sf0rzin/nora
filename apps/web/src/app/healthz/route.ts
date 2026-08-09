/**
 * /healthz — health check endpoint for the Container App (ACA) probe.
 *
 * The Bicep configures Startup/Readiness/Liveness probes on `/healthz` at port 3000.
 * Without this route, new revisions turned into ActivationFailed and ACA fell back to
 * the old revision (which serves the wrong bundle).
 *
 * Keep it light: immediate 200 status, no dependencies (DB, backend etc).
 * `force-static` avoids revalidation per request.
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
