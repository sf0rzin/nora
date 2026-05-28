import { useEffect, useState } from "react";
import { getCurrentWebviewWindow } from "@tauri-apps/api/webviewWindow";

/**
 * Acompanha se a janela atual está maximizada.
 *
 * Usado pela Titlebar (ícone maximizar/restaurar), pelo App (achatar os cantos
 * arredondados quando maximizado) e pelos resize handles (esconder ao maximizar).
 *
 * Detecção robusta: alguns WMs (KWin/X11) atualizam o estado de maximizado
 * DEPOIS de emitir o evento de resize/move, então um recheck único no resize
 * via vez não bastava — ao restaurar, a janela continuava "maximizada" e os
 * cantos ficavam retos. Aqui re-consultamos isMaximized() no resize, no move,
 * na mudança de foco E com um pequeno atraso, pra pegar o estado já assentado.
 */
export function useWindowMaximized(): boolean {
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    const win = getCurrentWebviewWindow();
    let cancelled = false;
    const unlisteners: Array<() => void> = [];
    const timers: Array<ReturnType<typeof setTimeout>> = [];

    const sync = () => {
      win
        .isMaximized()
        .then((m) => {
          if (!cancelled) setMaximized(m);
        })
        .catch(() => {});
    };

    // Recheck imediato + atrasado: o flag de maximizado pode só estabilizar
    // depois do evento de geometria em alguns WMs.
    const syncSoon = () => {
      sync();
      timers.push(setTimeout(sync, 120));
    };

    sync();

    const attach = (p: Promise<() => void>) => {
      p.then((fn) => {
        if (cancelled) fn();
        else unlisteners.push(fn);
      }).catch(() => {});
    };

    attach(win.onResized(syncSoon));
    attach(win.onMoved(syncSoon));
    attach(win.onFocusChanged(sync));

    return () => {
      cancelled = true;
      timers.forEach((t) => clearTimeout(t));
      unlisteners.forEach((fn) => fn());
    };
  }, []);

  return maximized;
}
