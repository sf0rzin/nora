import { useEffect, useState } from "react";
import { getCurrentWebviewWindow } from "@tauri-apps/api/webviewWindow";

/**
 * Acompanha se a janela atual está maximizada, reagindo a resize.
 *
 * Usado tanto pela Titlebar (ícone maximizar/restaurar) quanto pelo App pra
 * achatar os cantos arredondados quando maximizado — janela maximizada com
 * cantos redondos deixa buracos transparentes nos cantos da tela.
 */
export function useWindowMaximized(): boolean {
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    const win = getCurrentWebviewWindow();
    let cancelled = false;
    let unlisten: (() => void) | undefined;

    const sync = () =>
      win
        .isMaximized()
        .then((m) => !cancelled && setMaximized(m))
        .catch(() => {});

    sync();
    win
      .onResized(() => sync())
      .then((fn) => {
        if (cancelled) fn();
        else unlisten = fn;
      })
      .catch(() => {});

    return () => {
      cancelled = true;
      unlisten?.();
    };
  }, []);

  return maximized;
}
