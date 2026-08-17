/**
 * Pure helpers for the trends panel (US21). No copy lives here on purpose: the pt-BR strings stay
 * in the route, which is where `scripts/check-language.sh` expects UI text to be. What this module
 * returns are dates and numbers, formatted but not narrated.
 */
import type { TrendsGranularity, TrendsPoint } from '@/lib/api/types';

const MONTH_SHORT = new Intl.DateTimeFormat('pt-BR', { month: 'short' });
const DAY_MONTH = new Intl.DateTimeFormat('pt-BR', { day: '2-digit', month: '2-digit' });

/**
 * Reads a `yyyy-MM-dd` bucket key as a LOCAL date.
 *
 * `new Date('2026-08-17')` is parsed as UTC midnight by the spec, so in any zone behind UTC —
 * every Brazilian one — it renders as the 16th. The bucket key is already a local date in the
 * reporting zone the API declares; re-interpreting it as an instant would shift the whole axis by
 * one day and silently disagree with the numbers inside the bars.
 */
export function parseBucketDate(bucketStart: string): Date {
  const [year, month, day] = bucketStart.split('-').map((part) => Number.parseInt(part, 10));
  return new Date(year, (month ?? 1) - 1, day ?? 1);
}

/** `ago` — the abbreviation without the trailing period Intl adds in pt-BR. */
function shortMonth(date: Date): string {
  return MONTH_SHORT.format(date).replace('.', '');
}

/** Short axis tick: `17/ago` for a week bucket, `ago/26` for a month one. */
export function bucketLabel(bucketStart: string, granularity: TrendsGranularity): string {
  const date = parseBucketDate(bucketStart);
  if (Number.isNaN(date.getTime())) return bucketStart;
  if (granularity === 'MONTH') {
    return `${shortMonth(date)}/${String(date.getFullYear()).slice(-2)}`;
  }
  return `${String(date.getDate()).padStart(2, '0')}/${shortMonth(date)}`;
}

/**
 * First and last day covered by one bucket, already formatted. Returned as two fields rather than
 * as one joined sentence so the word between them stays in the route with the rest of the copy.
 */
export function bucketRange(
  bucketStart: string,
  granularity: TrendsGranularity,
): { start: string; end: string } {
  const start = parseBucketDate(bucketStart);
  const end = new Date(start);
  if (granularity === 'MONTH') {
    end.setMonth(end.getMonth() + 1);
  } else {
    end.setDate(end.getDate() + 7);
  }
  end.setDate(end.getDate() - 1);
  return { start: DAY_MONTH.format(start), end: DAY_MONTH.format(end) };
}

/**
 * Tallest bar of the series, never below 1 — a zero denominator would divide every bar height by
 * zero, and a chart of NaN renders as nothing at all rather than as the flat line it should be.
 */
export function seriesMax(points: TrendsPoint[]): number {
  let max = 0;
  for (const point of points) {
    max = Math.max(max, point.opened, point.completed);
  }
  return Math.max(max, 1);
}

/** Totals over the visible range, used for the "opened vs completed" headline. */
export function seriesTotals(points: TrendsPoint[]): { opened: number; completed: number } {
  let opened = 0;
  let completed = 0;
  for (const point of points) {
    opened += point.opened;
    completed += point.completed;
  }
  return { opened, completed };
}

/** Bar height in percent of the plot area. Zero stays zero so an empty period reads as empty. */
export function barHeightPercent(value: number, max: number): number {
  if (value <= 0) return 0;
  return Math.max(2, Math.round((value / max) * 100));
}
