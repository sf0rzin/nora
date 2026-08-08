import { headers } from "next/headers";

/**
 * Identidade do operador para **exibição**. Em produção vem do Cloudflare Access (ADR 0025),
 * que injeta `Cf-Access-Authenticated-User-Email` nas requisições que passam pelo login.
 * Em dev (sem Cloudflare) cai num operador fake.
 *
 * Este header NÃO é assinado. Ele é confiável no render de página, onde o RootLayout já
 * validou o `Cf-Access-Jwt-Assertion` antes de renderizar. Não é confiável em server
 * action: ali o Next executa a action antes do layout, e o endpoint da action é
 * alcançável por POST direto de dentro da rede. Para autorizar ou para carimbar
 * auditoria, use `requireAccess()` de `lib/access.ts`, que devolve o e-mail do JWT
 * verificado.
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
