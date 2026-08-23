/**
 * What the people screen is allowed to claim about a person.
 *
 * `GET /meetings/participants` groups rosters with fuzzy name matching, so every person it returns
 * comes with the spellings that produced them. The row has to show the ones that ADD something —
 * repeating the name already printed above teaches nothing, and hiding a spelling that differs is
 * how a wrong merge stops being visible in the response that made it.
 */
import { describe, expect, it } from 'vitest';

import type { ParticipantIdentity } from '@/lib/api/types';
import { extraVariants, summarize } from '@/lib/people/identities';

function person(over: Partial<ParticipantIdentity> & { displayName: string }): ParticipantIdentity {
  return {
    id: `id-${over.displayName}`,
    email: null,
    isInternal: false,
    variants: [over.displayName],
    meetingCount: 1,
    firstSeenAt: '2026-05-01T10:00:00Z',
    lastSeenAt: '2026-05-01T10:00:00Z',
    meetings: [],
    ...over,
  };
}

describe('extraVariants', () => {
  it('drops the spelling already shown as the name', () => {
    const extras = extraVariants(person({ displayName: 'Ana Paula Silva', variants: ['Ana Paula Silva'] }));

    expect(extras).toEqual([]);
  });

  it('keeps a spelling that differs, which is what makes a bad merge visible', () => {
    const extras = extraVariants(
      person({ displayName: 'Ana Paula Silva', variants: ['Ana Paula Silva', 'Ana P. Silva', 'ana silva'] }),
    );

    expect(extras).toEqual(['Ana P. Silva', 'ana silva']);
  });

  it('treats case and surrounding space as the same spelling', () => {
    const extras = extraVariants(
      person({ displayName: 'Ana Paula Silva', variants: ['  ana paula silva  ', 'ANA PAULA SILVA'] }),
    );

    expect(extras).toEqual([]);
  });

  it('shows each distinct spelling once, in the order the API sent them', () => {
    const extras = extraVariants(
      person({ displayName: 'Ana', variants: ['Zeca', 'Ana', 'Zeca', 'Bia'] }),
    );

    expect(extras).toEqual(['Zeca', 'Bia']);
  });

  it('survives a person with no variants at all', () => {
    const extras = extraVariants({ ...person({ displayName: 'Ana' }), variants: [] });

    expect(extras).toEqual([]);
  });
});

describe('summarize', () => {
  it('splits the roster into internal and external', () => {
    const summary = summarize([
      person({ displayName: 'Ana', isInternal: true }),
      person({ displayName: 'Bia', isInternal: true }),
      person({ displayName: 'Cliente', isInternal: false }),
    ]);

    expect(summary).toEqual({ people: 3, internal: 2, external: 1 });
  });

  it('answers zeros for an empty tenant instead of dividing by nothing', () => {
    expect(summarize([])).toEqual({ people: 0, internal: 0, external: 0 });
  });
});
