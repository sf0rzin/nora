import { headers } from "next/headers";
import { createRemoteJWKSet, jwtVerify } from "jose";

/**
 * Tier 2 (ADR 0025) — Cloudflare Access JWT validation at the app edge.
 *
 * Defense in depth: the nora-admin origin is already unreachable from outside (internal ingress +
 * Cloudflare Tunnel) and Cloudflare Access gates `admin.nora.systems` at the network edge.
 * This is the 2nd layer — without a `Cf-Access-Jwt-Assertion` valid for our AUD, the request
 * is blocked even if someone reaches the origin from inside the environment.
 *
 * Runs in a server component (Node runtime), which reads `CF_ACCESS_*` at RUNTIME. Next 14
 * middleware runs in the edge runtime and would inline those envs at build-time (would not work,
 * since AUD is set by Container Apps on deploy) — that's why validation lives here, not at the edge.
 */

const TEAM_DOMAIN = process.env.CF_ACCESS_TEAM_DOMAIN ?? "";
const AUD = process.env.CF_ACCESS_AUD ?? "";
const USE_MOCKS = process.env.NORA_ADMIN_USE_MOCKS !== "false";

// Enforces only in production (mocks off) and with Access configured. No config = degrades to
// edge-only (Tunnel + Access at the edge keep protecting) — does not brick the app.
const ENFORCING = !USE_MOCKS && TEAM_DOMAIN !== "" && AUD !== "";

const JWKS = ENFORCING
  ? createRemoteJWKSet(new URL(`https://${TEAM_DOMAIN}/cdn-cgi/access/certs`))
  : null;

export interface AccessResult {
  /** true = JWT validation is active in this environment. */
  enforced: boolean;
  /** true = may render (in enforced mode, means a valid JWT). */
  ok: boolean;
  /**
   * E-mail extracted from the VERIFIED JWT. Only filled in when `enforced && ok` —
   * it is the only operator identity with cryptographic proof. The
   * `Cf-Access-Authenticated-User-Email` header that `getOperator()` reads carries the same
   * data, but unsigned: good for display, not for authorizing nor for
   * stamping audit records.
   */
  email?: string;
}

export async function checkAccess(): Promise<AccessResult> {
  if (!ENFORCING || JWKS === null) {
    if (!USE_MOCKS && (TEAM_DOMAIN === "" || AUD === "")) {
      console.warn(
        "[access] CF_ACCESS_TEAM_DOMAIN/CF_ACCESS_AUD ausentes — validação de JWT do Access desligada (edge-only).",
      );
    }
    return { enforced: false, ok: true };
  }

  const token = (await headers()).get("cf-access-jwt-assertion") ?? "";
  if (token === "") {
    console.warn("[access] Cf-Access-Jwt-Assertion ausente — bloqueando.");
    return { enforced: true, ok: false };
  }

  try {
    const { payload } = await jwtVerify(token, JWKS, {
      issuer: `https://${TEAM_DOMAIN}`,
      audience: AUD,
    });
    const email = typeof payload.email === "string" ? payload.email : undefined;
    return { enforced: true, ok: true, email };
  } catch (err) {
    console.warn("[access] JWT do Access inválido:", (err as Error).message);
    return { enforced: true, ok: false };
  }
}

/** Authorization error. Stable name so the caller can tell it apart from a network/backend failure. */
export class AccessDeniedError extends Error {
  constructor() {
    super("Acesso negado: requisição sem asserção válida do Cloudflare Access.");
    this.name = "AccessDeniedError";
  }
}

/**
 * Mandatory gate for **server actions and pages**. RootLayout's `checkAccess()` does not
 * cover either path:
 *
 * - **Server actions:** Next runs the action first and only then re-renders the tree,
 *   so the layout runs too late to prevent the side effect. Without this, a direct POST
 *   to the action endpoint — with the `Next-Action` id, which is public in the bundle —
 *   executes the mutation without going through Access at all.
 * - **Pages:** the App Router does partial rendering and does not reinvoke the layout of an
 *   unchanged parent segment on an RSC navigation. A request that already carries the router
 *   state tree renders the page and returns the payload without the layout gate running.
 *
 * Every new page must start with `await requireAccess()`. The layout keeps doing its
 * own check for the initial render and for the 403 screen, but it is the second line, not the only one.
 *
 * Returns the e-mail from the verified JWT when enforcing. Outside enforce (dev/mocks)
 * it returns `undefined` and the caller falls back to the development operator.
 */
export async function requireAccess(): Promise<string | undefined> {
  const access = await checkAccess();
  if (access.enforced && !access.ok) throw new AccessDeniedError();
  return access.email;
}
