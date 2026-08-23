/**
 * @vitest-environment jsdom
 *
 * The action catalogue the policy form offers.
 *
 * The field is free text, so a missing suggestion never blocks a grant — but it is the only place
 * in the product that tells an operator which actions exist, and the two newest ones were the two
 * that most needed telling apart. `meeting:delete` is the reversible removal and `meeting:erase`
 * destroys the transcript, the participants and the analyses of everybody who was in the meeting;
 * neither was listed, so a policy separating them had to be written by hand in the JSON editor.
 *
 * Pinned as a set rather than a snapshot: the list is allowed to grow, and what must not happen is
 * an action shipping in the backend and never reaching this list.
 */
import { cleanup, render } from '@testing-library/react';
import { afterEach, describe, expect, it } from 'vitest';

import PolicyFormEditor from '@/components/policy-form-editor';

const DOCUMENT = JSON.stringify({
  version: '2026-05-07',
  statements: [{ effect: 'Allow', action: ['meeting:read'], resource: ['nora:tenant/*:meeting/*'] }],
});

function suggestions(): string[] {
  render(<PolicyFormEditor value={DOCUMENT} onChange={() => undefined} />);
  return [...document.querySelectorAll('#policy-form-actions option')].map((o) =>
    o.getAttribute('value') ?? '',
  );
}

afterEach(cleanup);

describe('action suggestions', () => {
  it('offers both meeting removals, which is what lets one be granted without the other', () => {
    const values = suggestions();

    expect(values).toContain('meeting:delete');
    expect(values).toContain('meeting:erase');
  });

  it('offers the permission-boundary actions the IAM screen already writes', () => {
    const values = suggestions();

    expect(values).toEqual(
      expect.arrayContaining(['iam:boundary:read', 'iam:boundary:set', 'iam:boundary:delete']),
    );
  });

  it('lists every action once, so a duplicate cannot creep in unnoticed', () => {
    const values = suggestions();

    expect(new Set(values).size).toBe(values.length);
  });

  it('keeps the wildcard first, because it is the one nobody should reach for by accident', () => {
    expect(suggestions()[0]).toBe('*');
  });
});
