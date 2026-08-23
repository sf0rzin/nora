/**
 * The list of users the IAM screen offers, and how good that list is.
 *
 * Four operations on that screen take a user id (attach a policy, add or remove a group member,
 * set a permission boundary, simulate a decision). `GET /iam/users` answers the tenant directory
 * and is the authoritative source: when it responds, the screen can offer a real picker, because
 * every user who exists is in the answer.
 *
 * The other three sources are what the screen had before that endpoint existed, and they stay
 * because the directory read is a separate permission from the rest of the page: accepted invites
 * pair a user id with an e-mail, group membership lists the ids in a group, and the audit feed's
 * `actorUserId` is the only place a tenant's Root ever appears (nobody invited them). Together
 * they make a good list that is provably incomplete — which is why a caller must know WHICH kind
 * of list it got before deciding between a `select` and a free-text field with suggestions.
 *
 * Assembled here rather than in the screen so the precedence is testable: the same person can turn
 * up in all four sources and only two of them know their name. An entry with a worse label must
 * never overwrite a better one.
 */

export type DirectoryUserSource = 'directory' | 'invite' | 'group' | 'audit';

export interface DirectoryUser {
  userId: string;
  /** What the operator reads: the person's name or e-mail when known, the id otherwise. */
  label: string;
  source: DirectoryUserSource;
  /**
   * Root of the tenant, when the authoritative directory said so. Worth carrying because it
   * changes the outcome: the API refuses to bound the Root and authorization bypasses them, so a
   * screen that offers Root as a target for those two is offering an operation that will fail.
   */
  root?: boolean;
}

export interface UserDirectoryInput {
  /** `GET /iam/users`. Present means the list is complete. */
  users?: readonly { id: string; displayName?: string | null; email?: string | null; root?: boolean }[];
  invites?: readonly { email: string; acceptedUserId?: string | null }[];
  /** Members of the groups whose membership has been loaded, keyed by group name for the label. */
  groupMembers?: readonly { groupName: string; userIds: readonly string[] }[];
  auditActors?: readonly string[];
}

/** Lower is better. An entry only replaces an existing one when it can label it better. */
const RANK: Record<DirectoryUserSource, number> = { directory: 0, invite: 1, group: 2, audit: 3 };

/** Name and e-mail together when both are known — an e-mail alone does not tell two Anas apart. */
function directoryLabel(user: {
  id: string;
  displayName?: string | null;
  email?: string | null;
}): string {
  const name = user.displayName?.trim();
  const email = user.email?.trim();
  if (name && email) return `${name} · ${email}`;
  return name || email || user.id;
}

export function buildUserDirectory(input: UserDirectoryInput): DirectoryUser[] {
  const byId = new Map<string, DirectoryUser>();

  const add = (
    userId: string | null | undefined,
    label: string,
    source: DirectoryUserSource,
    root?: boolean,
  ) => {
    const id = userId?.trim();
    if (!id) return;
    const existing = byId.get(id);
    if (existing && RANK[existing.source] <= RANK[source]) return;
    byId.set(id, { userId: id, label, source, root });
  };

  for (const user of input.users ?? []) {
    add(user.id, directoryLabel(user), 'directory', user.root === true);
  }
  for (const invite of input.invites ?? []) {
    // Only ACCEPTED invites carry a user id; a pending one names nobody yet.
    add(invite.acceptedUserId, invite.email, 'invite');
  }
  for (const group of input.groupMembers ?? []) {
    for (const userId of group.userIds) add(userId, `${userId} · ${group.groupName}`, 'group');
  }
  for (const actor of input.auditActors ?? []) add(actor, actor, 'audit');

  return [...byId.values()].sort((a, b) => a.label.localeCompare(b.label));
}
