/**
 * Reading a whole paged listing, for the screens that group instead of listing.
 *
 * The projects screen cannot render a page of anything: it derives lines of work from tags, so a
 * project either has all of its meetings or it has wrong numbers on it — or does not appear at
 * all. It used to call `listMeetings({ size: 100 })` once, which meant that past a hundred
 * meetings whole projects vanished and the survivors' counts were wrong, with nothing on screen
 * saying so. The same trap had just been armed a second time: `GET /tasks` became paged and the
 * client kept asking for it as if it were not, so the answer was silently the first twenty items.
 *
 * A ceiling still exists, because a client-side grouping cannot honestly page forever. The
 * difference is that it is explicit and the caller is TOLD when it was hit — `truncated` is what
 * the screen turns into a visible caveat instead of a wrong number.
 */

/** The pagination shape both `MeetingsListResponse` and `TaskListResponse` already answer with. */
export interface PagedResponse<T> {
  items: T[];
  totalItems: number;
  totalPages: number;
}

export type PageFetcher<T> = (params: { page: number; size: number }) => Promise<PagedResponse<T>>;

export interface LoadedPages<T> {
  items: T[];
  /** `true` when the backend holds more than what was read. */
  truncated: boolean;
  /** What the backend says exists, regardless of how much was read. */
  totalItems: number;
}

/** Page size per request. The cap both endpoints enforce server-side. */
export const MAX_PAGE_SIZE = 100;

/**
 * Hard stop, in items. Five requests at the page size above: past any real workspace today, and
 * low enough that one screen cannot turn into an unbounded fan-out.
 */
export const DEFAULT_ITEM_LIMIT = 500;

export async function loadAllPages<T>(
  fetchPage: PageFetcher<T>,
  limit: number = DEFAULT_ITEM_LIMIT,
  pageSize: number = MAX_PAGE_SIZE,
): Promise<LoadedPages<T>> {
  const first = await fetchPage({ page: 0, size: pageSize });
  const items: T[] = [...first.items];
  const totalPages = Number.isFinite(first.totalPages) ? first.totalPages : 1;

  for (let page = 1; page < totalPages && items.length < limit; page += 1) {
    const next = await fetchPage({ page, size: pageSize });
    // An empty page ends the loop rather than burning the remaining budget: the backend answers
    // one when the offset runs past the end, and a listing shrinking under us must not spin.
    if (next.items.length === 0) break;
    items.push(...next.items);
  }

  const totalItems = Number.isFinite(first.totalItems) ? first.totalItems : items.length;
  const read = Math.min(items.length, limit);
  return {
    items: items.slice(0, limit),
    truncated: totalItems > read,
    totalItems,
  };
}
