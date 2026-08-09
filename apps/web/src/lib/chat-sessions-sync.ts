/**
 * Lightweight (same-tab) sync of the chat session list.
 *
 * Whoever changes sessions (create in the chat, rename/delete in the sidebar) fires
 * `notifySessionsChanged()`; whoever shows the list (desktop sidebar + mobile
 * drawer) listens to the event and redoes the fetch. Avoids global state/a new lib:
 * it is just a CustomEvent on the window.
 */

export const SESSIONS_CHANGED_EVENT = "nora:sessions-changed";

/** Tells the listeners (sidebar) that the session list changed. */
export function notifySessionsChanged(): void {
  if (typeof window === "undefined") return;
  window.dispatchEvent(new CustomEvent(SESSIONS_CHANGED_EVENT));
}
