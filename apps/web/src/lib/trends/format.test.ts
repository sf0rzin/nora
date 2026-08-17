import { describe, expect, it } from 'vitest';

import {
  barHeightPercent,
  bucketLabel,
  bucketRange,
  parseBucketDate,
  seriesMax,
  seriesTotals,
} from './format';
import type { TrendsPoint } from '@/lib/api/types';

const point = (bucketStart: string, opened: number, completed: number): TrendsPoint => ({
  bucketStart,
  opened,
  completed,
});

describe('parseBucketDate', () => {
  it('reads the key as a local date, not as UTC midnight', () => {
    const date = parseBucketDate('2026-08-17');
    // The regression this guards: `new Date('2026-08-17')` is UTC midnight, which is the 16th in
    // every Brazilian zone, and the whole axis would render one day early.
    expect(date.getFullYear()).toBe(2026);
    expect(date.getMonth()).toBe(7);
    expect(date.getDate()).toBe(17);
  });
});

describe('bucketLabel', () => {
  it('labels a week bucket with the day and the abbreviated month', () => {
    expect(bucketLabel('2026-08-17', 'WEEK')).toBe('17/ago');
  });

  it('labels a month bucket with the month and a two-digit year', () => {
    expect(bucketLabel('2026-08-01', 'MONTH')).toBe('ago/26');
  });

  it('falls back to the raw key when the date is unreadable', () => {
    expect(bucketLabel('not-a-date', 'WEEK')).toBe('not-a-date');
  });
});

describe('bucketRange', () => {
  it('spans seven days for a week', () => {
    expect(bucketRange('2026-08-17', 'WEEK')).toEqual({ start: '17/08', end: '23/08' });
  });

  it('spans the calendar month for a month', () => {
    expect(bucketRange('2026-02-01', 'MONTH')).toEqual({ start: '01/02', end: '28/02' });
  });
});

describe('seriesMax', () => {
  it('takes the tallest of both series', () => {
    expect(seriesMax([point('2026-08-10', 3, 9), point('2026-08-17', 4, 1)])).toBe(9);
  });

  it('never returns zero, so no bar is ever divided by nothing', () => {
    expect(seriesMax([point('2026-08-10', 0, 0)])).toBe(1);
    expect(seriesMax([])).toBe(1);
  });
});

describe('seriesTotals', () => {
  it('sums each series over the range', () => {
    const totals = seriesTotals([point('2026-08-10', 3, 1), point('2026-08-17', 4, 6)]);
    expect(totals).toEqual({ opened: 7, completed: 7 });
  });
});

describe('barHeightPercent', () => {
  it('keeps zero at zero so an empty period reads as empty', () => {
    expect(barHeightPercent(0, 10)).toBe(0);
  });

  it('gives a non-zero value a visible floor', () => {
    expect(barHeightPercent(1, 500)).toBe(2);
  });

  it('fills the plot at the maximum', () => {
    expect(barHeightPercent(10, 10)).toBe(100);
  });
});
