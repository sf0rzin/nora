/**
 * @vitest-environment jsdom
 *
 * The two removals on the meeting detail, and the distance between them.
 *
 * Until `DELETE /meetings/{id}` existed, this screen offered only the LGPD erasure — so somebody
 * who uploaded the wrong file had to choose between living with it and permanently destroying the
 * transcript, participants and analyses of everybody who was in the meeting. What is pinned here
 * is that the two are different controls calling different endpoints, that the reversible one
 * never reaches the erasure, and that the erasure keeps the typed-confirm that guards it.
 *
 * Controls are addressed by `data-testid`, never by caption: the captions are pt-BR product copy.
 */
import { cleanup, fireEvent, render, screen } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

const push = vi.fn();
const refresh = vi.fn();
vi.mock('next/navigation', () => ({ useRouter: () => ({ push, refresh }) }));

const removeMeeting = vi.fn();
const eraseMeeting = vi.fn();
vi.mock('@/lib/api/client', async (importOriginal) => ({
  // eslint-disable-next-line @typescript-eslint/consistent-type-imports
  ...(await importOriginal<typeof import('@/lib/api/client')>()),
  removeMeeting: (...a: unknown[]) => removeMeeting(...a),
  eraseMeeting: (...a: unknown[]) => eraseMeeting(...a),
}));

import { MeetingDangerZone } from '@/components/meeting-actions';
import { ApiRequestError } from '@/lib/api/client';

const TITLE = 'Discovery Acme — Renovação 2026';

function renderZone() {
  return render(<MeetingDangerZone meetingId="m-1" title={TITLE} canReprocess={false} />);
}

beforeEach(() => {
  push.mockReset();
  refresh.mockReset();
  removeMeeting.mockReset().mockResolvedValue(undefined);
  eraseMeeting.mockReset().mockResolvedValue(undefined);
});

afterEach(cleanup);

describe('reversible removal', () => {
  it('asks once before removing, and removes nothing on the first click', () => {
    renderZone();

    fireEvent.click(screen.getByTestId('meeting-remove'));

    expect(removeMeeting).not.toHaveBeenCalled();
    expect(screen.getByTestId('meeting-remove-confirm')).toBeTruthy();
  });

  it('calls the reversible endpoint and never the erasure', async () => {
    renderZone();

    fireEvent.click(screen.getByTestId('meeting-remove'));
    fireEvent.click(screen.getByTestId('meeting-remove-confirm'));

    await vi.waitFor(() => expect(removeMeeting).toHaveBeenCalledWith('m-1'));
    expect(eraseMeeting).not.toHaveBeenCalled();
    expect(push).toHaveBeenCalled();
  });

  it('treats a meeting that is already gone as done, not as an error', async () => {
    removeMeeting.mockRejectedValue(new ApiRequestError(404, 'Reunião não encontrada.'));
    renderZone();

    fireEvent.click(screen.getByTestId('meeting-remove'));
    fireEvent.click(screen.getByTestId('meeting-remove-confirm'));

    await vi.waitFor(() => expect(push).toHaveBeenCalled());
  });

  it('keeps the user on the page when the removal fails for any other reason', async () => {
    removeMeeting.mockRejectedValue(new ApiRequestError(403, 'Você não tem permissão para isso.'));
    renderZone();

    fireEvent.click(screen.getByTestId('meeting-remove'));
    fireEvent.click(screen.getByTestId('meeting-remove-confirm'));

    await vi.waitFor(() => expect(removeMeeting).toHaveBeenCalled());
    expect(push).not.toHaveBeenCalled();
  });
});

describe('permanent erasure', () => {
  it('stays disabled until the exact title is typed', () => {
    renderZone();

    fireEvent.click(screen.getByTestId('meeting-erase'));
    const confirm = screen.getByTestId('meeting-erase-confirm') as HTMLButtonElement;
    expect(confirm.disabled).toBe(true);

    fireEvent.change(document.querySelector('input.input') as HTMLInputElement, {
      target: { value: 'Discovery Acme' },
    });
    expect((screen.getByTestId('meeting-erase-confirm') as HTMLButtonElement).disabled).toBe(true);
  });

  it('calls the LGPD endpoint once the title matches, and never the reversible one', async () => {
    renderZone();

    fireEvent.click(screen.getByTestId('meeting-erase'));
    fireEvent.change(document.querySelector('input.input') as HTMLInputElement, {
      target: { value: TITLE },
    });
    fireEvent.click(screen.getByTestId('meeting-erase-confirm'));

    await vi.waitFor(() => expect(eraseMeeting).toHaveBeenCalledWith('m-1'));
    expect(removeMeeting).not.toHaveBeenCalled();
  });
});
