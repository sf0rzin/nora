import { useEffect } from "react";
import { listen, type EventCallback, type UnlistenFn } from "@tauri-apps/api/event";

/**
 * Listens to a Tauri event respecting the React lifecycle.
 *
 * The original pattern scattered across the codebase had a race on unmount:
 *
 *   useEffect(() => {
 *     const unlisten = listen("event", handler);
 *     return () => { unlisten.then((fn) => fn()); };
 *   }, []);
 *
 * If the component unmounts BEFORE the listen() Promise resolves, the
 * cleanup callback only schedules the unsubscribe via .then — but the
 * reference to `unlisten` is captured at unmount time, not at the
 * Promise's resolve, so the handler stays stuck (leak) and is never
 * removed. This hook captures the resolve in a `cancelled` flag and fires
 * the unlisten immediately if it already unmounted.
 *
 * @param event Tauri event name
 * @param handler Callback called for each payload
 * @param deps Additional dependencies (handler is already captured by closure
 *   — pass deps that should rearm the listener)
 */
export function useTauriListener<T>(
  event: string,
  handler: EventCallback<T>,
  deps: ReadonlyArray<unknown> = [],
) {
  useEffect(() => {
    let cancelled = false;
    let stored: UnlistenFn | null = null;
    listen<T>(event, handler)
      .then((fn) => {
        if (cancelled) {
          fn();
        } else {
          stored = fn;
        }
      })
      .catch((err) => {
        if (!cancelled) console.warn(`[useTauriListener] ${event} failed:`, err);
      });
    return () => {
      cancelled = true;
      if (stored) stored();
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [event, ...deps]);
}
