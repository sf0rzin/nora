import { headers } from "next/headers";

/**
 * Identidade do operador, lida do header `x-ms-client-principal` que o
 * Container Apps Easy Auth (Entra) injeta APÓS autenticar. O app público nunca
 * confia nesse header; aqui é seguro porque o Easy Auth strippa o que vier do
 * cliente e injeta o seu. Em dev (sem Easy Auth) cai num operador fake.
 *
 * O nora-admin repassa `operator.email` pra API Spring (header de auditoria)
 * pra registrar "quem trocou o modelo".
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

export function getOperator(): Operator {
  const raw = headers().get("x-ms-client-principal");
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
