import { headers } from "next/headers";
import { createRemoteJWKSet, jwtVerify } from "jose";

/**
 * Tier 2 (ADR 0025) — validação do JWT do Cloudflare Access na borda do app.
 *
 * Defense in depth: a origem do nora-admin já é inalcançável de fora (ingress internal +
 * Cloudflare Tunnel) e o Cloudflare Access gateia `admin.nora.systems` na borda da rede.
 * Esta é a 2ª camada — sem um `Cf-Access-Jwt-Assertion` válido pro nosso AUD, a requisição
 * é barrada mesmo que alguém alcance a origem por dentro do environment.
 *
 * Roda em server component (Node runtime), que lê `CF_ACCESS_*` em RUNTIME. Middleware do
 * Next 14 roda em edge runtime e inlinaria essas envs em build-time (não funcionaria, já que
 * o AUD é setado pelo Container Apps no deploy) — por isso a validação fica aqui, não no edge.
 */

const TEAM_DOMAIN = process.env.CF_ACCESS_TEAM_DOMAIN ?? "";
const AUD = process.env.CF_ACCESS_AUD ?? "";
const USE_MOCKS = process.env.NORA_ADMIN_USE_MOCKS !== "false";

// Enforça só em produção (mocks off) e com Access configurado. Sem config = degrada pra
// edge-only (Tunnel + Access na borda continuam protegendo) — não trava o app.
const ENFORCING = !USE_MOCKS && TEAM_DOMAIN !== "" && AUD !== "";

const JWKS = ENFORCING
  ? createRemoteJWKSet(new URL(`https://${TEAM_DOMAIN}/cdn-cgi/access/certs`))
  : null;

export interface AccessResult {
  /** true = a validação de JWT está ativa neste ambiente. */
  enforced: boolean;
  /** true = pode renderizar (em modo enforced, significa JWT válido). */
  ok: boolean;
  /**
   * E-mail extraído do JWT VERIFICADO. Só vem preenchido quando `enforced && ok` —
   * é a única identidade de operador com prova criptográfica. O header
   * `Cf-Access-Authenticated-User-Email` que o `getOperator()` lê carrega o mesmo
   * dado, mas sem assinatura: serve para exibir, não para autorizar nem para
   * carimbar auditoria.
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

/** Erro de autorização. Nome estável para o caller distinguir de falha de rede/backend. */
export class AccessDeniedError extends Error {
  constructor() {
    super("Acesso negado: requisição sem asserção válida do Cloudflare Access.");
    this.name = "AccessDeniedError";
  }
}

/**
 * Gate para **server actions**. O `checkAccess()` do RootLayout NÃO cobre esse caminho:
 * numa server action o Next executa a action primeiro e só depois re-renderiza a árvore,
 * então o layout roda tarde demais para impedir o efeito colateral. Sem isto, um POST
 * direto no endpoint da action — com o `Next-Action` id, que é público no bundle —
 * executa a mutação sem passar por Access nenhum.
 *
 * Retorna o e-mail do JWT verificado quando está enforçando. Fora de enforce (dev/mocks)
 * devolve `undefined` e o caller cai no operador de desenvolvimento.
 */
export async function requireAccess(): Promise<string | undefined> {
  const access = await checkAccess();
  if (access.enforced && !access.ok) throw new AccessDeniedError();
  return access.email;
}
