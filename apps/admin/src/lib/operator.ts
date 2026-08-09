import { headers } from "next/headers";

/**
 * Operator identity for **display**. In production it comes from Cloudflare Access (ADR 0025),
 * which injects `Cf-Access-Authenticated-User-Email` into requests that go through the login.
 * In dev (no Cloudflare) it falls back to a fake operator.
 *
 * This header is NOT signed. It is trustworthy in page render, where the RootLayout has
 * already validated the `Cf-Access-Jwt-Assertion` before rendering. It is not trustworthy in a
 * server action: there Next runs the action before the layout, and the action endpoint is
 * reachable by a direct POST from inside the network. To authorize or to stamp
 * audit records, use `requireAccess()` from `lib/access.ts`, which returns the e-mail from the
 * verified JWT.
 *
 * nora-admin forwards `operator.email` to the Spring API (audit header) to record
 * "who switched the model". Legacy: it still reads `x-ms-client-principal` (Easy Auth) in case it comes back.
 */
export interface Operator {
  email: string;
  name: string;
  authenticated: boolean;
}

const DEV_OPERATOR: Operator = {
  email: "operador-dev@nora.local",
  name: "Operador (dev)",
  authenticated: false,
};

interface PrincipalClaim {
  typ: string;
  val: string;
}

export async function getOperator(): Promise<Operator> {
  const h = await headers();

  // Current path: Cloudflare Access (ADR 0025).
  const cfEmail = h.get("cf-access-authenticated-user-email");
  if (cfEmail) {
    return { email: cfEmail, name: cfEmail, authenticated: true };
  }

  // Legacy: Easy Auth (Entra) — inert today, kept for robustness.
  const raw = h.get("x-ms-client-principal");
  if (!raw) return DEV_OPERATOR;
  try {
    const decoded = JSON.parse(Buffer.from(raw, "base64").toString("utf-8")) as {
      claims?: PrincipalClaim[];
      userDetails?: string;
    };
    const claims = decoded.claims ?? [];
    const find = (...types: string[]) =>
      claims.find((c) => types.includes(c.typ))?.val ?? undefined;
    const email =
      find(
        "preferred_username",
        "emails",
        "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/emailaddress",
      ) ??
      decoded.userDetails ??
      "operador@nora.app";
    const name =
      find("name", "http://schemas.xmlsoap.org/ws/2005/05/identity/claims/name") ?? email;
    return { email, name, authenticated: true };
  } catch {
    return { ...DEV_OPERATOR, authenticated: false };
  }
}
