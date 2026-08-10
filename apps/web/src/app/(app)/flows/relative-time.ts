/**
 * NORA Flows — relative dates. The wording lives in the locale module
 * (`@/lib/strings`); this file only decides which bucket a timestamp falls into:
 * now, minutes, hours, yesterday, days, then a short date.
 */
import { LOCALE, strings } from "@/lib/strings";

const copy = strings.relativeTime;

export function relativeTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  const diffMs = Date.now() - d.getTime();
  if (diffMs < 45_000) return copy.now;
  const min = Math.round(diffMs / 60_000);
  if (min < 60) return copy.minutesAgo(min);
  const h = Math.round(min / 60);
  if (h < 24) return copy.hoursAgo(h);

  const now = new Date();
  const startOfToday = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const startOfDay = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const days = Math.round((startOfToday.getTime() - startOfDay.getTime()) / 86_400_000);
  if (days === 1) return copy.yesterday;
  if (days <= 14) return copy.daysAgo(days);
  return d.toLocaleDateString(LOCALE, { day: "2-digit", month: "short" });
}

/** HH:mm time — used in the "saved at HH:mm" autosave feedback. */
export function shortTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleTimeString(LOCALE, { hour: "2-digit", minute: "2-digit" });
}

/** HH:mm:ss time — used in the execution log lines. */
export function logTime(iso: string): string {
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleTimeString(LOCALE, { hour: "2-digit", minute: "2-digit", second: "2-digit" });
}
