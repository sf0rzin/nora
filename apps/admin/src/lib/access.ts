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

  const token = headers().get("cf-access-jwt-assertion") ?? "";
  if (token === "") {
    console.warn("[access] Cf-Access-Jwt-Assertion ausente — bloqueando.");
    return { enforced: true, ok: false };
  }

  try {
    await jwtVerify(token, JWKS, {
      issuer: `https://${TEAM_DOMAIN}`,
      audience: AUD,
    });
    return { enforced: true, ok: true };
  } catch (err) {
    console.warn("[access] JWT do Access inválido:", (err as Error).message);
    return { enforced: true, ok: false };
  }
}
