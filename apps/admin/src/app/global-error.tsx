"use client";

/**
 * Last-resort boundary. `app/error.tsx` sits INSIDE the root layout and therefore cannot catch a
 * failure of the layout itself — and the layout is where `checkAccess()` and `getOperator()` run,
 * the two calls most likely to blow up in a misconfigured deployment. Without this file that class
 * of failure reaches Next's built-in screen with no styling and no explanation.
 *
 * It replaces the whole document, so it renders its own `<html>`/`<body>` and cannot rely on the
 * font variable or the token stylesheet the layout would have installed.
 */
export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  return (
    <html lang="pt-BR">
      <body style={{ margin: 0, fontFamily: "system-ui, sans-serif", color: "#1a1a1a", background: "#fff" }}>
        <main style={{ padding: "4rem 2rem", maxWidth: "40rem" }}>
          <h1 style={{ fontSize: "1.5rem", marginBottom: "0.5rem" }}>O console não subiu</h1>
          <p style={{ opacity: 0.7, lineHeight: 1.6 }}>
            A falha aconteceu antes de qualquer tela existir — normalmente configuração de ambiente
            do próprio console. Confira as variáveis <code>CF_ACCESS_*</code> e{" "}
            <code>PLATFORM_API_BASE_URL</code> do serviço <code>admin</code> e o log do contêiner.
          </p>
          {error.digest && (
            <p style={{ opacity: 0.55, fontSize: 12, marginTop: "0.75rem" }}>
              Referência para o log do servidor: <code>{error.digest}</code>
            </p>
          )}
          <button
            type="button"
            onClick={reset}
            style={{ marginTop: "1.5rem", fontFamily: "inherit", fontSize: 13, padding: "7px 14px", cursor: "pointer" }}
          >
            Tentar de novo
          </button>
        </main>
      </body>
    </html>
  );
}
