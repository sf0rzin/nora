import { useEffect, useState } from "react";
import { getCurrentWebviewWindow } from "@tauri-apps/api/webviewWindow";

/**
 * Acompanha se a janela atual está maximizada.
 *
 * Usado pela Titlebar (ícone maximizar/restaurar), pelo App (achatar os cantos
 * arredondados quando maximizado) e pra esconder os resize handles.
 *
 * Por que polling em vez de só eventos: em alguns WMs (KWin/X11) o flag de
 * maximizado é atualizado DEPOIS do evento de resize, e com atraso variável —
 * então consultar isMaximized() no onResized às vezes lia o estado velho e os
 * cantos ficavam retos depois de restaurar ("às vezes volta, às vezes não").
 * Um poll leve (isMaximized é uma chamada barata; setState só re-renderiza
 * quando o booleano muda de fato) converge SEMPRE, independente do timing do WM.
 * Os listeners de evento ficam só pra resposta imediata no caso comum.
 */
export function useWindowMaximized(): boolean {
  const [maximized, setMaximized] = useState(false);

  useEffect(() => {
    const win = getCurrentWebviewWindow();
    let cancelled = false;
    const unlisteners: Array<() => void> = [];

    const sync = () => {
      win
        .isMaximized()
        .then((m) => {
          if (!cancelled) setMaximized(m);
        })
        .catch(() => {});
    };

    sync();

    // Backstop determinístico: reconcilia o estado a cada 200ms. Sem isso o
    // restore às vezes não re-arredondava por causa do atraso do flag no WM.
    const pollId = setInterval(sync, 200);

    const attach = (p: Promise<() => void>) => {
      p.then((fn) => {
        if (cancelled) fn();
        else unlisteners.push(fn);
      }).catch(() => {});
    };

    // Resposta imediata no caso comum (quando o flag já está fresco).
    attach(win.onResized(sync));
    attach(win.onMoved(sync));
    attach(win.onFocusChanged(sync));

    return () => {
      cancelled = true;
      clearInterval(pollId);
      unlisteners.forEach((fn) => fn());
    };
  }, []);

  return maximized;
}
