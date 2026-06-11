/**
 * Sincronização leve (mesma aba) da lista de sessões de chat.
 *
 * Quem muda sessões (criar no chat, renomear/apagar na sidebar) dispara
 * `notifySessionsChanged()`; quem exibe a lista (sidebar desktop + drawer
 * mobile) escuta o evento e refaz o fetch. Evita estado global/lib nova:
 * é só um CustomEvent no window.
 */

export const SESSIONS_CHANGED_EVENT = "nora:sessions-changed";

/** Avisa os listeners (sidebar) que a lista de sessões mudou. */
export function notifySessionsChanged(): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent(SESSIONS_CHANGED_EVENT));
}
