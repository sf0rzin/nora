import { describe, expect, it } from 'vitest';

import { formatDateTime, meetingInstant } from '@/lib/utils';

/**
 * Two rules about a meeting's date, both written after a production meeting detail rendered a date
 * in January 1970.
 *
 * The upload form promises, in the help text under its start-time field, that a blank start means
 * the upload date is used. Nothing implemented it: the browser drops the key (`JSON.stringify`
 * omits `undefined`), the API has no default, and the column is nullable — so the row is stored
 * with `startedAt` null and `createdAt` holding the upload instant. `formatDateTime` then received
 * that null, and `new Date(null)` is not an invalid date, it is the epoch, which is why the page
 * printed a real-looking 1970 date instead of failing visibly. `MeetingDetail.startedAt` was typed
 * `string`, so the compiler had nothing to say about any of it, even though `docs/api/openapi.yaml`
 * has always declared the field as `[string, 'null']`.
 *
 * The two halves are pinned separately because they fail for different reasons: one is about what a
 * missing instant should look like, the other about which instant to use.
 */
describe('formatDateTime', () => {
  it('renders an em dash for a missing instant instead of the Unix epoch', () => {
    // The regression itself. `new Date(null).getTime()` is 0, so the old implementation formatted
    // this as a real date in 1970 — a value the reader has no way to recognise as "we do not know".
    expect(formatDateTime(null)).toBe('—');
    expect(formatDateTime(undefined)).toBe('—');
    expect(formatDateTime('')).toBe('—');
  });

  it('echoes a value that is present but unparseable, rather than hiding it', () => {
    // A malformed instant coming back from the API is a finding. Collapsing it into the same em
    // dash as "absent" would erase the difference between a field nobody filled and a field the
    // server is corrupting.
    expect(formatDateTime('not-a-date')).toBe('not-a-date');
  });

  it('formats a real instant', () => {
    // Asserted on the parts rather than on the whole string: the exact separators Intl produces
    // differ between ICU versions, and a test that pins them fails on a Node upgrade for a reason
    // that has nothing to do with this module.
    const out = formatDateTime('2026-08-24T15:11:11.715333Z', 'pt-BR');
    expect(out).toContain('2026');
    expect(out).not.toContain('1970');
  });
});

describe('meetingInstant', () => {
  it('uses the declared start when there is one', () => {
    expect(
      meetingInstant({
        startedAt: '2026-08-10T13:00:00Z',
        createdAt: '2026-08-24T15:11:11Z',
      }),
    ).toBe('2026-08-10T13:00:00Z');
  });

  it('falls back to the upload instant when the start was not declared', () => {
    // The form's promise, kept on the read side — and the same rule `TrendsRepositoryAdapter` and
    // `ParticipantRepositoryAdapter` already apply as `COALESCE(started_at, created_at)`.
    expect(meetingInstant({ startedAt: null, createdAt: '2026-08-24T15:11:11Z' })).toBe(
      '2026-08-24T15:11:11Z',
    );
  });

  it('composes with formatDateTime so a null start never reaches 1970', () => {
    const out = formatDateTime(meetingInstant({ startedAt: null, createdAt: '2026-08-24T15:11:11Z' }));
    expect(out).not.toContain('1970');
    expect(out).toContain('2026');
  });
});
