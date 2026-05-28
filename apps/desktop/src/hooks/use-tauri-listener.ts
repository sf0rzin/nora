import { useEffect } from "react";
import { listen, type EventCallback, type UnlistenFn } from "@tauri-apps/api/event";

/**
 * Escuta um evento Tauri respeitando o ciclo de vida React.
 *
 * Padrão original espalhado pelo codebase tinha race no unmount:
 *
 *   useEffect(() => {
 *     const unlisten = listen("event", handler);
 *     return () => { unlisten.then((fn) => fn()); };
 *   }, []);
 *
 * Se o componente desmontar ANTES da Promise de listen() resolver, o
 * callback de cleanup só agenda a desinscrição via .then — mas a
 * referência ao `unlisten` é capturada no momento do unmount, não no
 * resolve da Promise, então o handler fica preso (leak) e nunca é
 * removido. Esse hook captura o resolve numa flag `cancelled` e dispara
 * o unlisten imediatamente se já desmontou.
 *
 * @param event Nome do evento Tauri
 * @param handler Callback chamado pra cada payload
 * @param deps Dependências adicionais (handler já é capturado por closure
 *   — passe deps que devem rearmar o listener)
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
