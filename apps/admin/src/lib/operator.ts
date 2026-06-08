import { headers } from "next/headers";

/**
 * Identidade do operador. Em produção vem do Cloudflare Access (ADR 0025), que injeta
 * `Cf-Access-Authenticated-User-Email` nas requisições que passam pelo login. A asserção
 * (`Cf-Access-Jwt-Assertion`) já foi validada pelo gate em `lib/access.ts`, e a origem é
 * inalcançável de fora (Tunnel + ingress internal) — então confiar neste header é seguro.
 * Em dev (sem Cloudflare) cai num operador fake.
 *
 * O nora-admin repassa `operator.email` pra API Spring (header de auditoria) pra registrar
 * "quem trocou o modelo". Legado: ainda lê `x-ms-client-principal` (Easy Auth) caso volte.
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

  // Caminho atual: Cloudflare Access (ADR 0025).
  const cfEmail = h.get("cf-access-authenticated-user-email");
  if (cfEmail) {
    return { email: cfEmail, name: cfEmail, authenticated: true };
  }

  // Legado: Easy Auth (Entra) — inerte hoje, mantido por robustez.
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
