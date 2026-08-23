/**
 * @vitest-environment jsdom
 *
 * The command palette's handling of a search that was REFUSED rather than answered.
 *
 * `GET /meetings/search` bills an embedding per call and the backend caps it per principal, so
 * `MEETING_RATE_LIMITED` is reachable by typing quickly — this component fires one call per pause
 * in typing. Every failure used to collapse into an empty result list, which the palette renders
 * as "nothing found for X": a refusal to search read as an answer, and the answer was wrong.
 *
 * Elements are addressed by `data-testid` and class, never by caption — the captions are pt-BR
 * product copy, and matching on them would put that copy in a file the language guard checks.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('next/navigation', () => ({ useRouter: () => ({ push: vi.fn() }) }));

const searchMeetings = vi.fn();
vi.mock('@/lib/api/client', async (importOriginal) => ({
  // eslint-disable-next-line @typescript-eslint/consistent-type-imports
  ...(await importOriginal<typeof import('@/lib/api/client')>()),
  searchMeetings: (...a: unknown[]) => searchMeetings(...a),
}));

import { CommandPalette } from '@/components/core/command-palette';
import { ApiRequestError } from '@/lib/api/client';
import { errorCopy } from '@/lib/strings';

const HIT = { id: 'm-1', title: 'Discovery Acme', startedAt: '2026-05-02T14:00:00Z' };

beforeEach(() => {
  searchMeetings.mockReset().mockResolvedValue({ items: [HIT] });
});

afterEach(cleanup);

/** Types into the palette input and lets the debounce fire. */
async function type(text: string) {
  fireEvent.change(screen.getByRole('dialog').querySelector('.palette-input') as HTMLInputElement, {
    target: { value: text },
  });
  await vi.waitFor(() => expect(searchMeetings).toHaveBeenCalled());
}

describe('command palette — a rate-limited search', () => {
  it('says the search was refused instead of reporting an empty result', async () => {
    render(<CommandPalette open onClose={() => undefined} />);
    await vi.waitFor(() => expect(searchMeetings).toHaveBeenCalled());

    searchMeetings.mockRejectedValue(
      new ApiRequestError(429, 'too many', { code: 'MEETING_RATE_LIMITED', message: 'too many' }),
    );
    await type('acme');

    const notice = await vi.waitFor(() => screen.getByTestId('palette-notice'));
    expect(notice.textContent).toBe(errorCopy.MEETING_RATE_LIMITED);
    // The "nada encontrado" line must be gone: nothing was searched, so nothing was not found.
    expect(document.querySelectorAll('.palette-empty').length).toBe(1);
  });

  it('keeps the hits it already had, so typing faster does not empty the list', async () => {
    render(<CommandPalette open onClose={() => undefined} />);
    await vi.waitFor(() => expect(screen.getByText(HIT.title)).toBeTruthy());

    searchMeetings.mockRejectedValue(new ApiRequestError(429, 'too many'));
    await type('acme');

    await vi.waitFor(() => screen.getByTestId('palette-notice'));
    expect(screen.getByText(HIT.title)).toBeTruthy();
  });

  it('clears the notice as soon as a search succeeds again', async () => {
    render(<CommandPalette open onClose={() => undefined} />);
    searchMeetings.mockRejectedValueOnce(new ApiRequestError(429, 'too many'));
    await type('acme');
    await vi.waitFor(() => screen.getByTestId('palette-notice'));

    await type('acme corp');

    await vi.waitFor(() => expect(screen.queryByTestId('palette-notice')).toBeNull());
  });

  it('still falls back to the empty state for any other failure', async () => {
    render(<CommandPalette open onClose={() => undefined} />);
    searchMeetings.mockRejectedValue(new ApiRequestError(500, 'boom'));
    await type('acme');

    await vi.waitFor(() => expect(screen.queryByText(HIT.title)).toBeNull());
    expect(screen.queryByTestId('palette-notice')).toBeNull();
  });
});
