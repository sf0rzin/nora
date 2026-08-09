/**
 * Shared time formatting helpers (overlay + dock).
 * Extracted to eliminate divergent duplication between overlay.tsx and dock-bar.tsx
 * — the dock version broke on recordings > 1h. Desktop audit #35/#38/#53.
 */

/** Duration in seconds → "M:SS", or "H:MM:SS" when it goes past 1h. */
export function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

/** Relative time in milliseconds → "MM:SS" (overlay feed). */
export function relTime(ms: number): string {
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms / 1000) % 60);
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}
