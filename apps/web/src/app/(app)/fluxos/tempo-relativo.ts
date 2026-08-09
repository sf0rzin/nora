/**
 * NORA Flows — relative dates in PT-BR (no external lib, app standard).
 * "agora", "há 2 min", "há 3 h", "ontem", "há 4 dias", then short date.
 */

export function tempoRelativo(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const diffMs = Date.now() - d.getTime();
  if (diffMs < 45_000) return "agora";
  const min = Math.round(diffMs / 60_000);
  if (min < 60) return `há ${min} min`;
  const h = Math.round(min / 60);
  if (h < 24) return `há ${h} h`;

  const hoje = new Date();
  const diaHoje = new Date(hoje.getFullYear(), hoje.getMonth(), hoje.getDate());
  const dia = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const dias = Math.round((diaHoje.getTime() - dia.getTime()) / 86_400_000);
  if (dias === 1) return "ontem";
  if (dias <= 14) return `há ${dias} dias`;
  return d.toLocaleDateString("pt-BR", { day: "2-digit", month: "short" });
}

/** HH:mm time — used in the "Salvo às 14:32" feedback. */
export function horaCurta(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" });
}

/** HH:mm:ss time — used in the execution log lines. */
export function horaLog(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}
