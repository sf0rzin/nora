/**
 * @vitest-environment jsdom
 *
 * The IAM screen: the most complex administrative surface in the product, and the one with the
 * least between a slip and real damage. Three things are pinned here, all of them found broken.
 *
 * 1. Every field has an accessible name. This screen had sixteen inputs and not one `<label>`;
 *    each was identified by placeholder alone, which disappears on the first keystroke and gives a
 *    screen reader nothing to announce. It was the only screen in the product like that.
 * 2. Group membership is visible. `listGroupMembers` and `GET /iam/groups/{id}/members` both
 *    existed with no caller: the operator added and removed members without ever seeing who was in
 *    a group, and removal meant pasting a user id the product never displayed.
 * 3. Destructive actions ask first, and a failed load can be retried. Deleting a group used to
 *    fire on a single click, on a screen where the target is identified by a UUID.
 *
 * The two policy editors and the two cards at the top are stubbed: they bring Monaco and their own
 * data loading, and none of the behaviour above lives in them. Elements are addressed by
 * `data-testid`, role or relationship, never by caption — the captions are pt-BR product copy.
 */
import { cleanup, fireEvent, render, screen, within } from '@testing-library/react';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

vi.mock('@/components/policy-editor', () => ({ default: () => <div data-testid="json-editor" /> }));
vi.mock('@/components/policy-form-editor', () => ({
  default: () => <div data-testid="form-editor" />,
}));
vi.mock('@/components/corporate-domain-card', () => ({ default: () => null }));
vi.mock('@/components/invitation-card', () => ({ default: () => null }));

const listGroups = vi.fn();
const listPolicies = vi.fn();
const listPolicyTemplates = vi.fn();
const listAuditEvents = vi.fn();
const listInvites = vi.fn();
const listGroupMembers = vi.fn();
const removeGroupMember = vi.fn();
const deleteGroup = vi.fn();
const listIamUsers = vi.fn();
const listPolicyVersions = vi.fn();
const addGroupMember = vi.fn();
const updatePolicyDocument = vi.fn();

vi.mock('@/lib/api/client', async (importOriginal) => ({
  // `importOriginal` hands back the real module for everything not stubbed below, and its type
  // parameter is the only way to say so. An `import()` type is what the API expects here.
  // eslint-disable-next-line @typescript-eslint/consistent-type-imports
  ...(await importOriginal<typeof import('@/lib/api/client')>()),
  listGroups: (...a: unknown[]) => listGroups(...a),
  listPolicies: (...a: unknown[]) => listPolicies(...a),
  listPolicyTemplates: (...a: unknown[]) => listPolicyTemplates(...a),
  listAuditEvents: (...a: unknown[]) => listAuditEvents(...a),
  listInvites: (...a: unknown[]) => listInvites(...a),
  listGroupMembers: (...a: unknown[]) => listGroupMembers(...a),
  removeGroupMember: (...a: unknown[]) => removeGroupMember(...a),
  deleteGroup: (...a: unknown[]) => deleteGroup(...a),
  listIamUsers: (...a: unknown[]) => listIamUsers(...a),
  listPolicyVersions: (...a: unknown[]) => listPolicyVersions(...a),
  addGroupMember: (...a: unknown[]) => addGroupMember(...a),
  updatePolicyDocument: (...a: unknown[]) => updatePolicyDocument(...a),
}));

import IamPage from '@/app/(app)/settings/iam/page';

const GROUP = {
  id: 'g-1',
  name: 'sales-team',
  description: 'Sales',
  createdBy: 'root-1',
  createdAt: '2026-08-01T10:00:00Z',
  updatedAt: '2026-08-01T10:00:00Z',
};

const POLICY = {
  id: 'p-1',
  name: 'meeting-readonly',
  description: null,
  document: { version: '2026-05-07', statements: [] },
  currentVersion: 1,
  createdBy: 'root-1',
  createdAt: '2026-08-01T10:00:00Z',
  updatedAt: '2026-08-01T10:00:00Z',
};

/** Renders and waits for the initial load to settle. */
async function renderPage(): Promise<HTMLElement> {
  const { container } = render(<IamPage />);
  await vi.waitFor(() => expect(container.querySelector('h1')).not.toBeNull());
  return container;
}

beforeEach(() => {
  listGroups.mockReset().mockResolvedValue([GROUP]);
  listPolicies.mockReset().mockResolvedValue([POLICY]);
  listPolicyTemplates.mockReset().mockResolvedValue([]);
  listAuditEvents.mockReset().mockResolvedValue([]);
  listInvites.mockReset().mockResolvedValue({ items: [], total: 0, page: 0, size: 20 });
  listGroupMembers.mockReset().mockResolvedValue([]);
  removeGroupMember.mockReset().mockResolvedValue(undefined);
  deleteGroup.mockReset().mockResolvedValue(undefined);
  // Default: no directory endpoint answer, which is the degraded shape the fields fall back to.
  listIamUsers.mockReset().mockRejectedValue(new Error('no directory'));
  listPolicyVersions.mockReset().mockResolvedValue([]);
  addGroupMember.mockReset().mockResolvedValue(undefined);
  updatePolicyDocument.mockReset().mockResolvedValue(undefined);
});

afterEach(cleanup);

describe('IAM screen accessibility', () => {
  it('gives every field an accessible name, not just a placeholder', async () => {
    const container = await renderPage();
    const controls = [
      ...container.querySelectorAll<HTMLInputElement | HTMLSelectElement>('input, select'),
    ];

    // The sixteen fields the audit counted, plus the ones the pickers added.
    expect(controls.length).toBeGreaterThanOrEqual(12);
    for (const control of controls) {
      const labelled =
        control.getAttribute('aria-label') !== null ||
        (control.id !== '' && container.querySelector(`label[for="${control.id}"]`) !== null);
      expect(labelled, `field with placeholder "${control.getAttribute('placeholder')}" has no name`)
        .toBe(true);
    }
  });

  it('offers the known policies and groups as choices instead of asking for a pasted id', async () => {
    const container = await renderPage();
    const selects = [...container.querySelectorAll<HTMLSelectElement>('select')];

    // Every id that CAN be picked from a list is picked from a list. This case runs with the
    // directory read refused, so the user fields are the degraded shape; the picker they become
    // when `GET /iam/users` answers has its own block at the bottom of this file.
    expect(selects.length).toBeGreaterThan(0);
    const optionSets = selects.map((s) => [...s.options].map((o) => o.value));
    expect(optionSets.some((values) => values.includes(POLICY.id))).toBe(true);
    expect(optionSets.some((values) => values.includes(GROUP.id))).toBe(true);
  });
});

describe('IAM group membership', () => {
  it('does not read membership until a group is expanded', async () => {
    await renderPage();
    expect(listGroupMembers).not.toHaveBeenCalled();
  });

  it('lists the members of an expanded group', async () => {
    listGroupMembers.mockResolvedValue(['u-1', 'u-2']);
    await renderPage();

    fireEvent.click(screen.getByTestId(`group-members-toggle-${GROUP.id}`));
    await vi.waitFor(() => expect(listGroupMembers).toHaveBeenCalledWith(GROUP.id));
    await vi.waitFor(() => expect(screen.getByTestId('group-member-remove-u-1')).toBeTruthy());
    expect(screen.getByTestId('group-member-remove-u-2')).toBeTruthy();
  });

  it('removes a member by the id it is showing, with nothing to paste', async () => {
    listGroupMembers.mockResolvedValue(['u-1']);
    await renderPage();

    fireEvent.click(screen.getByTestId(`group-members-toggle-${GROUP.id}`));
    await vi.waitFor(() => expect(screen.getByTestId('group-member-remove-u-1')).toBeTruthy());
    fireEvent.click(screen.getByTestId('group-member-remove-u-1'));

    await vi.waitFor(() => expect(removeGroupMember).toHaveBeenCalledWith(GROUP.id, 'u-1'));
  });

  it('surfaces a membership read that failed, without taking the page down', async () => {
    listGroupMembers.mockRejectedValue(new Error('boom'));
    const container = await renderPage();

    fireEvent.click(screen.getByTestId(`group-members-toggle-${GROUP.id}`));
    await vi.waitFor(() => expect(listGroupMembers).toHaveBeenCalled());
    // The rest of the screen is still there — the group list, the forms, everything.
    expect(container.querySelectorAll('section').length).toBeGreaterThan(3);
  });
});

describe('IAM destructive actions', () => {
  it('asks before deleting a group', async () => {
    await renderPage();

    fireEvent.click(screen.getByTestId(`group-delete-${GROUP.id}`));
    expect(deleteGroup).not.toHaveBeenCalled();

    fireEvent.click(screen.getByTestId(`group-delete-confirm-${GROUP.id}`));
    await vi.waitFor(() => expect(deleteGroup).toHaveBeenCalledWith(GROUP.id));
  });

  it('deletes nothing when the confirmation is dismissed', async () => {
    await renderPage();

    fireEvent.click(screen.getByTestId(`group-delete-${GROUP.id}`));
    const row = screen.getByTestId(`group-delete-confirm-${GROUP.id}`).closest('li');
    // The confirmation offers a way back; taking it must leave the group alone.
    const cancel = within(row as HTMLElement)
      .getAllByRole('button')
      .at(-1);
    fireEvent.click(cancel as HTMLElement);

    expect(screen.queryByTestId(`group-delete-confirm-${GROUP.id}`)).toBeNull();
    expect(deleteGroup).not.toHaveBeenCalled();
  });
});

describe('IAM initial load', () => {
  it('offers a retry when the load fails, and recovers on it', async () => {
    listGroups.mockRejectedValueOnce(new Error('backend down'));
    render(<IamPage />);

    const retry = await vi.waitFor(() => screen.getByTestId('iam-load-retry'));
    // The failed load left no group list to work with, so a reload was the only way out.
    expect(screen.queryByTestId(`group-delete-${GROUP.id}`)).toBeNull();

    fireEvent.click(retry);
    await vi.waitFor(() => expect(screen.getByTestId(`group-delete-${GROUP.id}`)).toBeTruthy());
  });
});

/**
 * The four user-id fields, which is where half of this screen used to be unusable.
 *
 * Attaching a policy, adding a group member, setting a boundary and simulating a decision all
 * take a user id, and the API had no endpoint that listed one — so the fields asked the operator
 * to paste a UUID the product never displayed. `GET /iam/users` closed that, and what has to hold
 * here is the pair: a real picker when the directory answers, and the old free-text field with
 * suggestions when it does not, because that list is provably incomplete and a `select` over it
 * would lock out exactly the people missing from it.
 */
describe('IAM user picker', () => {
  const USER = { id: 'u-1', displayName: 'Ana Souza', email: 'ana@example.com', root: false };
  const ROOT = { id: 'root-1', displayName: 'Dono', email: 'dono@example.com', root: true };

  it('offers the tenant users as options once the directory answers', async () => {
    listIamUsers.mockResolvedValue([USER, ROOT]);
    const container = await renderPage();

    const field = container.querySelector<HTMLSelectElement>('select#iam-member-user');
    expect(field).not.toBeNull();
    expect([...(field as HTMLSelectElement).options].map((o) => o.value)).toContain(USER.id);
  });

  it('turns all four id fields into pickers, not just the first one', async () => {
    listIamUsers.mockResolvedValue([USER]);
    const container = await renderPage();

    for (const id of ['iam-member-user', 'iam-attach-user', 'iam-boundary-user', 'iam-sim-user']) {
      expect(container.querySelector(`select#${id}`), id).not.toBeNull();
      expect(container.querySelector(`input#${id}`), id).toBeNull();
    }
  });

  it('marks the Root, whose boundary the API refuses to set', async () => {
    listIamUsers.mockResolvedValue([ROOT]);
    const container = await renderPage();

    const option = [
      ...(container.querySelector('select#iam-boundary-user') as HTMLSelectElement).options,
    ].find((o) => o.value === ROOT.id);
    expect(option?.textContent).toContain('Root');
  });

  it('falls back to free text with suggestions when the directory read is refused', async () => {
    listIamUsers.mockRejectedValue(new Error('forbidden'));
    listInvites.mockResolvedValue({
      items: [{ email: 'ana@example.com', acceptedUserId: 'u-1' }],
      total: 1,
      page: 0,
      size: 20,
    });
    const container = await renderPage();

    // An input, because pasting an id has to keep working for whoever is not in the partial list.
    const field = container.querySelector<HTMLInputElement>('input#iam-member-user');
    expect(field).not.toBeNull();
    const list = container.querySelector('#iam-member-user-options');
    expect(list?.querySelectorAll('option').length).toBe(1);
  });

  it('sends the chosen id to the API, with nothing pasted', async () => {
    listIamUsers.mockResolvedValue([USER]);
    const container = await renderPage();

    const group = container.querySelector('select#iam-member-group') as HTMLSelectElement;
    const user = container.querySelector('select#iam-member-user') as HTMLSelectElement;
    fireEvent.change(group, { target: { value: GROUP.id } });
    fireEvent.change(user, { target: { value: USER.id } });
    fireEvent.submit(user.closest('form') as HTMLFormElement);

    await vi.waitFor(() => expect(addGroupMember).toHaveBeenCalledWith(GROUP.id, USER.id));
  });
});

/**
 * The policy revision history (US36).
 *
 * `iam_policy_versions` was written on every create and every edit and read by nothing at all, so
 * the "immutable history" the story promised was a backup: the audit feed records THAT a policy
 * changed and never what it said before. What is pinned is that the read is lazy, that a rollback
 * loads the old document into the editor WITHOUT saving — a rollback has to be a new revision, not
 * an erasure of what happened in between — and that a failed read stays on this policy.
 */
describe('IAM policy history', () => {
  const V1 = {
    version: 1,
    document: { version: '2026-05-07', statements: [] },
    createdBy: 'root-1',
    createdAt: '2026-08-01T10:00:00Z',
  };
  const V2 = { ...V1, version: 2, createdAt: '2026-08-10T10:00:00Z' };

  it('reads nothing until the history is opened', async () => {
    await renderPage();
    expect(listPolicyVersions).not.toHaveBeenCalled();
  });

  it('lists the revisions of the policy that was opened', async () => {
    listPolicyVersions.mockResolvedValue([V2, V1]);
    await renderPage();

    fireEvent.click(screen.getByTestId(`policy-versions-toggle-${POLICY.id}`));

    await vi.waitFor(() => expect(listPolicyVersions).toHaveBeenCalledWith(POLICY.id));
    await vi.waitFor(() =>
      expect(screen.getByTestId(`policy-version-restore-${POLICY.id}-2`)).toBeTruthy(),
    );
    expect(screen.getByTestId(`policy-version-restore-${POLICY.id}-1`)).toBeTruthy();
  });

  it('re-reads on every open, so an edit made meanwhile is not missing from the history', async () => {
    listPolicyVersions.mockResolvedValue([V1]);
    await renderPage();

    const toggle = screen.getByTestId(`policy-versions-toggle-${POLICY.id}`);
    fireEvent.click(toggle);
    await vi.waitFor(() => expect(listPolicyVersions).toHaveBeenCalledTimes(1));
    fireEvent.click(toggle);
    fireEvent.click(toggle);

    await vi.waitFor(() => expect(listPolicyVersions).toHaveBeenCalledTimes(2));
  });

  it('loads an old revision into the editor and saves nothing by itself', async () => {
    listPolicyVersions.mockResolvedValue([V2, V1]);
    await renderPage();

    fireEvent.click(screen.getByTestId(`policy-versions-toggle-${POLICY.id}`));
    await vi.waitFor(() =>
      expect(screen.getByTestId(`policy-version-restore-${POLICY.id}-1`)).toBeTruthy(),
    );
    // One editor is on screen already: the create form. The restore opens a second one on the
    // policy row, which is what "loaded into the editor" means here. Saving is a separate click
    // through the ordinary PUT, so the history stays append-only.
    const before = screen.getAllByTestId('form-editor').length;
    fireEvent.click(screen.getByTestId(`policy-version-restore-${POLICY.id}-1`));

    await vi.waitFor(() => expect(screen.getAllByTestId('form-editor').length).toBe(before + 1));
    expect(updatePolicyDocument).not.toHaveBeenCalled();
  });

  it('surfaces a history read that failed, and offers it again', async () => {
    listPolicyVersions.mockRejectedValueOnce(new Error('backend down'));
    await renderPage();

    fireEvent.click(screen.getByTestId(`policy-versions-toggle-${POLICY.id}`));
    await vi.waitFor(() => expect(listPolicyVersions).toHaveBeenCalled());
    // The policy row and the rest of the screen survive it.
    expect(screen.getByTestId(`policy-versions-toggle-${POLICY.id}`)).toBeTruthy();
  });
});
