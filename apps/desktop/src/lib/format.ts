/**
 * Helpers de formatação de tempo compartilhados (overlay + dock).
 * Extraído pra eliminar duplicação divergente entre overlay.tsx e dock-bar.tsx
 * — a versão do dock estourava em gravações > 1h. Auditoria desktop #35/#38/#53.
 */

/** Duração em segundos → "M:SS", ou "H:MM:SS" quando passa de 1h. */
export function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

/** Tempo relativo em milissegundos → "MM:SS" (feed da overlay). */
export function relTime(ms: number): string {
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms / 1000) % 60);
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}
