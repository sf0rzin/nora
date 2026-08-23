/**
 * Presentation decisions for the people screen, kept out of the server component so they can be
 * tested.
 *
 * The order is NOT one of them. `GET /meetings/participants` sorts by how present a person is and
 * breaks every tie, precisely so two reads of unchanged data render the same list; re-sorting here
 * would replace a total order with a partial one and make the screen jitter for no gain.
 *
 * What does live here is the honesty of the row. The API's matching is fuzzy on the name side, so
 * each person arrives with the spellings that produced them — showing those spellings is the only
 * way a reader can catch a merge that grouped two different people, and it has to be the ones that
 * ADD information, not the name already printed at the top of the row.
 */
import type { ParticipantIdentity } from '@/lib/api/types';

/**
 * The spellings worth showing beside a person's name: every variant that is not the displayed
 * name, compared with case and surrounding space ignored, in the order the API sent them.
 */
export function extraVariants(identity: ParticipantIdentity): string[] {
  const shown = identity.displayName.trim().toLowerCase();
  const seen = new Set<string>([shown]);
  const extras: string[] = [];
  for (const variant of identity.variants ?? []) {
    const key = variant.trim().toLowerCase();
    if (!key || seen.has(key)) continue;
    seen.add(key);
    extras.push(variant);
  }
  return extras;
}

export interface PeopleSummary {
  people: number;
  internal: number;
  external: number;
}

/**
 * The header counts. Deliberately not a meeting total: `meetings` is capped at ten per person and
 * the same meeting is on several rosters, so anything summed over it would be wrong twice.
 */
export function summarize(items: readonly ParticipantIdentity[]): PeopleSummary {
  let internal = 0;
  for (const item of items) if (item.isInternal) internal += 1;
  return { people: items.length, internal, external: items.length - internal };
}
