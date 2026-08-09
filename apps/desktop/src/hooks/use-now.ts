import { useEffect, useState } from "react";

/**
 * Forces a re-render every `intervalMs` while `active` — to update
 * clocks/timers without keeping "now" in state. Replaces the scattered
 * forceTick/`void tick` hacks (dock-bar, use-live-transcript). Audit #56/#96.
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
 * Seconds elapsed since `startedAt` (ms epoch), re-rendering while
 * `active`. Returns 0 when `startedAt` is null (preserves the original gating).
 */
export function useElapsedSeconds(
  startedAt: number | null,
  active: boolean,
  intervalMs = 500,
): number {
  useNow(active && startedAt != null, intervalMs);
  return startedAt == null ? 0 : Math.floor((Date.now() - startedAt) / 1000);
}
