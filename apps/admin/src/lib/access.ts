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
 * Runs in a server component (Node runtime), which reads `CF_ACCESS_*` at RUNTIME. Middleware
 * runs in the edge runtime and would inline those envs at build-time, while the values are set
 * per environment on deploy (`infra/host/docker-compose.yml`, `admin` service) — that's why
 * validation lives here and not at the edge.
 */

const TEAM_DOMAIN = process.env.CF_ACCESS_TEAM_DOMAIN ?? "";
const AUD = process.env.CF_ACCESS_AUD ?? "";

/**
 * Opt-IN, not opt-out. This read `!== "false"` until 2026-08-16, which made the safe state the
 * insecure one: an environment that simply forgot the variable served fabricated data with JWT
 * validation switched off, and nothing said so. Now the variable has to spell `true`; unset, empty
 * or misspelled all land on the real data layer with the gate on.
 */
const USE_MOCKS = process.env.NORA_ADMIN_USE_MOCKS === "true";

/**
 * Both halves of the Access configuration, or none of it. An issuer without an audience does not
 * validate anything useful — the AUD is what stops an Access JWT minted for ANOTHER application in
 * the same Cloudflare organization from being accepted here.
 */
const ACCESS_CONFIGURED = TEAM_DOMAIN !== "" && AUD !== "";

/**
 * Enforcement no longer depends on the configuration being complete. Missing `CF_ACCESS_*` used to
 * degrade to "edge-only" and RENDER, which is fail-open by another name: the Tunnel and the edge
 * Access Application are exactly what somebody reaching the origin from inside the environment has
 * already gone around, so "the edge still protects us" was an argument about the attacker we were
 * not worried about. Outside mock mode this gate is always on, and an incomplete configuration
 * blocks (see `checkAccess`) instead of standing aside.
 */
const ENFORCING = !USE_MOCKS;

const JWKS =
  ENFORCING && ACCESS_CONFIGURED
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
  /**
   * Why `ok` is false, when it is. `"unconfigured"` is not an authentication failure at all: it is
   * this deployment missing `CF_ACCESS_TEAM_DOMAIN`/`CF_ACCESS_AUD`. The callers distinguish it
   * because the two look identical from the outside — both are a 403 — and an operator who is told
   * "your request carries no Access assertion" when the real cause is an unset variable spends the
   * next hour debugging Cloudflare.
   */
  reason?: AccessDenialReason;
}

export type AccessDenialReason = "unconfigured" | "no-assertion" | "invalid-assertion";

export async function checkAccess(): Promise<AccessResult> {
  if (!ENFORCING) return { enforced: false, ok: true };

  if (JWKS === null) {
    console.warn(
      "[access] CF_ACCESS_TEAM_DOMAIN and/or CF_ACCESS_AUD are missing - blocking every request. " +
        "Set both, or set NORA_ADMIN_USE_MOCKS=true to run the console on mock data.",
    );
    return { enforced: true, ok: false, reason: "unconfigured" };
  }

  const token = (await headers()).get("cf-access-jwt-assertion") ?? "";
  if (token === "") {
    console.warn("[access] Cf-Access-Jwt-Assertion missing - blocking.");
    return { enforced: true, ok: false, reason: "no-assertion" };
  }

  try {
    const { payload } = await jwtVerify(token, JWKS, {
      issuer: `https://${TEAM_DOMAIN}`,
      audience: AUD,
    });
    const email = typeof payload.email === "string" ? payload.email : undefined;
    return { enforced: true, ok: true, email };
  } catch (err) {
    console.warn("[access] invalid Access JWT:", (err as Error).message);
    return { enforced: true, ok: false, reason: "invalid-assertion" };
  }
}

/** Authorization error. Stable name so the caller can tell it apart from a network/backend failure. */
export class AccessDeniedError extends Error {
  constructor(reason?: AccessDenialReason) {
    super(
      reason === "unconfigured"
        ? "Acesso negado: o console está sem CF_ACCESS_TEAM_DOMAIN/CF_ACCESS_AUD e por isso bloqueia tudo."
        : "Acesso negado: requisição sem asserção válida do Cloudflare Access.",
    );
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
 * Returns the e-mail from the verified JWT when enforcing. Outside enforce (mocks only, which is
 * now an explicit `NORA_ADMIN_USE_MOCKS=true`) it returns `undefined` and the caller falls back to
 * the development operator.
 */
export async function requireAccess(): Promise<string | undefined> {
  const access = await checkAccess();
  if (access.enforced && !access.ok) throw new AccessDeniedError(access.reason);
  return access.email;
}
