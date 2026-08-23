import type { AccessDenialReason } from "@/lib/access";

/**
 * The 403 screen, in one place.
 *
 * It used to live inline in `app/layout.tsx`, which meant only the initial full render could show
 * it: a page that gated itself on an RSC navigation threw, and a throw becomes Next's generic error
 * page with a digest. Two callers now share this — the layout, for the first render, and each page,
 * for the navigations the layout does not see — so the operator reads the same sentence either way.
 *
 * Two different failures wear the same 403, and telling them apart is the whole payoff of
 * fail-closed being readable: a console that blocks because it was never configured looks exactly
 * like one rejecting an unauthenticated visitor, and whoever sees the wrong sentence goes debugging
 * Cloudflare instead of an unset environment variable.
 */
export function AccessDenied({ reason }: { reason: AccessDenialReason }) {
  return (
    <div style={{ padding: "4rem 2rem", fontFamily: "var(--font-sans)", maxWidth: "40rem" }}>
      <h1 style={{ fontSize: "1.5rem", marginBottom: "0.5rem" }}>403 — Acesso negado</h1>
      {reason === "unconfigured" ? (
        <p style={{ opacity: 0.7 }}>
          Este console está sem <code>CF_ACCESS_TEAM_DOMAIN</code> e/ou <code>CF_ACCESS_AUD</code>,
          então não consegue validar a asserção do Cloudflare Access e bloqueia tudo. Configure as
          duas variáveis, ou rode com <code>NORA_ADMIN_USE_MOCKS=true</code> para trabalhar com dados
          fictícios.
        </p>
      ) : (
        <p style={{ opacity: 0.7 }}>
          Esta requisição não traz uma asserção válida do Cloudflare Access. O console do operador só
          é acessível via <code>admin.nora.systems</code> após login autorizado.
          {reason === "invalid-assertion" && " A asserção recebida foi recusada na verificação — se você ficou muito tempo com a aba aberta, recarregue a página para renovar o login."}
        </p>
      )}
    </div>
  );
}
