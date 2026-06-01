/**
 * Extrai uma mensagem amigável de um erro `unknown` pra exibir na UI.
 * Nunca faz JSON.stringify do erro cru (vazava estrutura interna na tela).
 * Ordem: Error.message → objeto com `message` string → fallback. Auditoria #63.
 */
export function toUserMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message.trim()) return err.message;
  if (err && typeof err === "object" && "message" in err) {
    const m = (err as { message: unknown }).message;
    if (typeof m === "string" && m.trim()) return m;
  }
  return fallback;
}
