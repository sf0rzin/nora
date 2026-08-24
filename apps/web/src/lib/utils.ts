import { clsx, type ClassValue } from "clsx";
import { twMerge } from "tailwind-merge";

export function cn(...inputs: ClassValue[]) {
  return twMerge(clsx(inputs));
}

/**
 * The instant a meeting is dated by: the declared start, or the upload when none was declared.
 *
 * The upload form promises exactly this in the help text under its start-time field — leave it
 * empty and the upload date is used — and nothing implemented it: the browser omits the key
 * (`JSON.stringify` drops `undefined`), the API has no default and the column is nullable, so the
 * row is stored with a null start while `created_at` holds the upload instant. This is the
 * read-side half of that promise.
 *
 * It is not a new convention. `TrendsRepositoryAdapter` and `ParticipantRepositoryAdapter` already
 * select `COALESCE(started_at, created_at)`; this is the same rule for the three places the browser
 * renders a meeting's date — detail, printable report and Markdown export — which otherwise
 * disagreed with those queries and would have drifted from each other.
 *
 * Structurally typed on purpose, so `lib/` need not import a DTO to state a rule about it.
 */
export function meetingInstant(meeting: {
  startedAt: string | null;
  createdAt: string;
}): string {
  return meeting.startedAt ?? meeting.createdAt;
}

/**
 * Formats an ISO instant for display, and refuses to invent one.
 *
 * The parameter used to be `iso: string`, which the API contract contradicts:
 * `docs/api/openapi.yaml` declares `startedAt` as `[string, 'null']` and the upload path leaves it
 * null whenever the start-time field is left empty. `new Date(null)` is not an invalid date — it is
 * the Unix epoch — so every such meeting rendered a date in January 1970 on its detail page and in
 * its report, and TypeScript never objected, because the type said the value could not be null.
 *
 * Two distinct nothings, kept distinct: no value at all yields an em dash, while a value that is
 * present but unparseable is echoed back, because a malformed instant from the API is a finding and
 * hiding it behind a dash would erase it.
 */
export function formatDateTime(
  iso: string | null | undefined,
  locale = "pt-BR",
): string {
  if (!iso) return "—";
  const at = new Date(iso);
  if (Number.isNaN(at.getTime())) return iso;
  return at.toLocaleString(locale, {
    dateStyle: "medium",
    timeStyle: "short",
  });
}
