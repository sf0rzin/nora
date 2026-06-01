import { useEffect, useState } from "react";

/**
 * Força re-render a cada `intervalMs` enquanto `active` — pra atualizar
 * relógios/timers sem guardar o "agora" em estado. Substitui os hacks de
 * forceTick/`void tick` espalhados (dock-bar, use-live-transcript). Auditoria #56/#96.
 */
export function useNow(active: boolean, intervalMs = 500): void {
  const [, force] = useState(0);
  useEffect(() => {
    if (!active) return;
    const id = setInterval(() => force((t) => t + 1), intervalMs);
    return () => clearInterval(id);
  }, [active, intervalMs]);
}

/**
 * Segundos decorridos desde `startedAt` (ms epoch), re-renderizando enquanto
 * `active`. Retorna 0 quando `startedAt` é null (preserva o gating original).
 */
export function useElapsedSeconds(
  startedAt: number | null,
  active: boolean,
  intervalMs = 500,
): number {
  useNow(active && startedAt != null, intervalMs);
  return startedAt == null ? 0 : Math.floor((Date.now() - startedAt) / 1000);
}
