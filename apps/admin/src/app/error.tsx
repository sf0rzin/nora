"use client";

import { useEffect } from "react";

/**
 * Error boundary for the console's pages.
 *
 * Until this file existed the console had none, and every read went straight to the Spring API with
 * no degradation between them: a 500, a 503 from the platform database or a timeout in any
 * `platformGet` took the whole page down to Next's built-in error screen — a digest and nothing
 * else. For an operator console that is the wrong failure: the person reading it is mid-incident
 * and needs to know whether the backend is down or the console is.
 *
 * The message is deliberately about the API and not about the error text. Next redacts a
 * server-thrown message before it reaches the client in production, so `error.message` here is a
 * placeholder in the only environment that matters; the digest is the handle that ties this screen
 * to the server log line, which is why it is printed instead of hidden.
 *
 * Authorization does NOT arrive here — pages gate with `guardPage()` and render the 403 themselves
 * (see lib/access.ts), precisely because that message must survive redaction.
 */
export default function ConsoleError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    // The server already logged the cause; this is the browser half of the same event, and it is
    // what makes an operator's screenshot of the console usable for diagnosis.
    console.error("[admin] falha ao renderizar a página:", error);
  }, [error]);

  return (
    <div style={{ padding: "4rem 2rem", fontFamily: "var(--font-sans)", maxWidth: "40rem" }}>
      <h1 style={{ fontSize: "1.5rem", marginBottom: "0.5rem" }}>Esta tela não carregou</h1>
      <p style={{ opacity: 0.7, lineHeight: 1.6 }}>
        O console não conseguiu ler os dados da plataforma. Isso quase sempre é a API do Nora fora do
        ar, o banco do plano de controle indisponível ou uma resposta que demorou demais — o console
        em si está de pé, já que esta mensagem veio dele.
      </p>
      {error.digest && (
        <p style={{ opacity: 0.55, fontSize: 12, marginTop: "0.75rem" }}>
          Referência para o log do servidor: <code>{error.digest}</code>
        </p>
      )}
      <button
        type="button"
        onClick={reset}
        style={{
          marginTop: "1.5rem",
          fontFamily: "inherit",
          fontSize: 13,
          background: "var(--canvas)",
          color: "var(--ink)",
          border: "1px solid var(--border)",
          borderRadius: 999,
          padding: "7px 14px",
          cursor: "pointer",
        }}
      >
        Tentar de novo
      </button>
    </div>
  );
}
