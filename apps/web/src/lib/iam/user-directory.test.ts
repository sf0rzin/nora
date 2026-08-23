/**
 * Who the IAM screen can name, given that the backend has no endpoint that lists users.
 *
 * Four operations on that screen take a user id and every one of them used to ask the operator to
 * paste a UUID the product never shows anywhere. The list below is assembled from three places
 * that do hold ids — accepted invites, group membership and the audit feed — and the property
 * worth pinning is the PRECEDENCE between them: only the invite carries an e-mail, and an entry
 * labelled with a raw id must never overwrite one labelled with the person's address.
 */
import { describe, expect, it } from 'vitest';

import { buildUserDirectory } from '@/lib/iam/user-directory';

describe('buildUserDirectory', () => {
  it('labels an accepted invite with the e-mail it was sent to', () => {
    const directory = buildUserDirectory({
      invites: [{ email: 'ana@example.com', acceptedUserId: 'u-1' }],
    });

    expect(directory).toEqual([{ userId: 'u-1', label: 'ana@example.com', source: 'invite' }]);
  });

  it('skips an invite nobody has accepted yet', () => {
    const directory = buildUserDirectory({
      invites: [
        { email: 'pending@example.com', acceptedUserId: null },
        { email: 'nofield@example.com' },
      ],
    });

    expect(directory).toEqual([]);
  });

  it('keeps the e-mail label when the same person also shows up as a group member', () => {
    const directory = buildUserDirectory({
      invites: [{ email: 'ana@example.com', acceptedUserId: 'u-1' }],
      groupMembers: [{ groupName: 'sales', userIds: ['u-1'] }],
      auditActors: ['u-1'],
    });

    expect(directory).toHaveLength(1);
    expect(directory[0].label).toBe('ana@example.com');
    expect(directory[0].source).toBe('invite');
  });

  it('does not care which order the sources are merged in', () => {
    const first = buildUserDirectory({
      auditActors: ['u-1'],
      groupMembers: [{ groupName: 'sales', userIds: ['u-1'] }],
      invites: [{ email: 'ana@example.com', acceptedUserId: 'u-1' }],
    });

    expect(first[0].source).toBe('invite');
  });

  it('names the group a member came from, so a raw id is at least placed', () => {
    const directory = buildUserDirectory({
      groupMembers: [{ groupName: 'sales', userIds: ['u-9'] }],
    });

    expect(directory[0].label).toBe('u-9 · sales');
  });

  it('picks up the tenant Root from the audit feed, which is the only place it appears', () => {
    // The Root created the tenant, so no invite names them and they may be in no group.
    const directory = buildUserDirectory({ auditActors: ['root-1', 'root-1'] });

    expect(directory).toEqual([{ userId: 'root-1', label: 'root-1', source: 'audit' }]);
  });

  it('ignores blank and whitespace-only ids', () => {
    const directory = buildUserDirectory({
      invites: [{ email: 'a@example.com', acceptedUserId: '   ' }],
      auditActors: ['', '  '],
    });

    expect(directory).toEqual([]);
  });

  it('sorts by the label the operator reads, not by id', () => {
    const directory = buildUserDirectory({
      invites: [
        { email: 'zoe@example.com', acceptedUserId: 'u-1' },
        { email: 'ana@example.com', acceptedUserId: 'u-2' },
      ],
    });

    expect(directory.map((u) => u.label)).toEqual(['ana@example.com', 'zoe@example.com']);
  });
});

/**
 * `GET /iam/users` arrived and changed what the list IS: the other three sources are guesses at
 * a directory, this one is the directory. What has to hold is that it wins every label conflict
 * and that its Root flag survives, because the API refuses to bound the Root and the screen has
 * to be able to say so before somebody tries.
 */
describe('buildUserDirectory with the tenant directory', () => {
  it('labels a user with their name and e-mail together', () => {
    const directory = buildUserDirectory({
      users: [{ id: 'u-1', displayName: 'Ana Souza', email: 'ana@example.com', root: false }],
    });

    expect(directory).toEqual([
      { userId: 'u-1', label: 'Ana Souza · ana@example.com', source: 'directory', root: false },
    ]);
  });

  it('outranks the invite label, which knows the address and not the person', () => {
    const directory = buildUserDirectory({
      users: [{ id: 'u-1', displayName: 'Ana Souza', email: 'ana@example.com' }],
      invites: [{ email: 'ana@example.com', acceptedUserId: 'u-1' }],
      groupMembers: [{ groupName: 'sales', userIds: ['u-1'] }],
      auditActors: ['u-1'],
    });

    expect(directory).toHaveLength(1);
    expect(directory[0].source).toBe('directory');
    expect(directory[0].label).toBe('Ana Souza · ana@example.com');
  });

  it('carries the Root flag, which changes whether an operation can succeed at all', () => {
    const directory = buildUserDirectory({
      users: [{ id: 'root-1', displayName: 'Dono', email: 'dono@example.com', root: true }],
    });

    expect(directory[0].root).toBe(true);
  });

  it('falls back to whatever the row does carry, and to the id when it carries neither', () => {
    const directory = buildUserDirectory({
      users: [
        { id: 'u-1', displayName: null, email: 'only-email@example.com' },
        { id: 'u-2', displayName: '  ', email: '  ' },
      ],
    });

    expect(directory.find((u) => u.userId === 'u-1')?.label).toBe('only-email@example.com');
    expect(directory.find((u) => u.userId === 'u-2')?.label).toBe('u-2');
  });

  it('still merges the other sources, for ids the directory read did not return', () => {
    const directory = buildUserDirectory({
      users: [{ id: 'u-1', displayName: 'Ana', email: 'ana@example.com' }],
      auditActors: ['u-9'],
    });

    expect(directory.map((u) => u.userId).sort()).toEqual(['u-1', 'u-9']);
  });
});
