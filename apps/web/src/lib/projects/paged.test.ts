/**
 * Reading a whole paged listing, and admitting when it could not be read whole.
 *
 * The projects screen asked for one page of a hundred meetings and rendered as if that were the
 * workspace. The failure mode is the quiet kind: past a hundred meetings, entire lines of work
 * simply were not on the page and the counts on the ones that were shown were wrong, with nothing
 * distinguishing that from a small workspace. `truncated` is the whole point of this module.
 */
import { describe, expect, it, vi } from 'vitest';

import { loadAllPages, type PagedResponse } from '@/lib/projects/paged';

/** A backend holding `total` numbered items, answering pages of whatever size is asked for. */
function backendWith(total: number) {
  return vi.fn(async ({ page, size }: { page: number; size: number }): Promise<
    PagedResponse<number>
  > => {
    const from = page * size;
    return {
      items: Array.from({ length: Math.max(0, Math.min(size, total - from)) }, (_, i) => from + i),
      totalItems: total,
      totalPages: Math.ceil(total / size),
    };
  });
}

describe('loadAllPages', () => {
  it('reads a single page and reports nothing missing', async () => {
    const fetchPage = backendWith(40);
    const result = await loadAllPages(fetchPage, 500, 100);

    expect(fetchPage).toHaveBeenCalledTimes(1);
    expect(result.items).toHaveLength(40);
    expect(result.truncated).toBe(false);
    expect(result.totalItems).toBe(40);
  });

  it('keeps paging until the listing is exhausted', async () => {
    const fetchPage = backendWith(250);
    const result = await loadAllPages(fetchPage, 500, 100);

    expect(fetchPage).toHaveBeenCalledTimes(3);
    expect(result.items).toHaveLength(250);
    expect(result.items[0]).toBe(0);
    expect(result.items[249]).toBe(249);
    expect(result.truncated).toBe(false);
  });

  it('stops at the ceiling and says the listing was cut', async () => {
    const fetchPage = backendWith(1_000);
    const result = await loadAllPages(fetchPage, 250, 100);

    expect(result.items).toHaveLength(250);
    expect(result.truncated).toBe(true);
    // The total is what the backend reports, not what was read — the caveat on screen needs both.
    expect(result.totalItems).toBe(1_000);
  });

  it('reports truncation when the backend claims more than it actually returned', async () => {
    // A listing shrinking under us, or a page that answers empty past the end.
    const fetchPage = vi.fn(async (): Promise<PagedResponse<number>> => ({
      items: [1, 2],
      totalItems: 9,
      totalPages: 1,
    }));

    const result = await loadAllPages(fetchPage, 500, 100);
    expect(result.items).toHaveLength(2);
    expect(result.truncated).toBe(true);
  });

  it('stops on an empty page instead of burning the remaining budget', async () => {
    const fetchPage = vi.fn(async ({ page }: { page: number }): Promise<PagedResponse<number>> => ({
      items: page === 0 ? [1, 2, 3] : [],
      totalItems: 3,
      totalPages: 9,
    }));

    const result = await loadAllPages(fetchPage, 500, 100);
    // Page 0 plus the one empty page that ends the loop — not nine.
    expect(fetchPage).toHaveBeenCalledTimes(2);
    expect(result.items).toEqual([1, 2, 3]);
  });

  it('propagates a failure instead of silently returning a partial listing', async () => {
    const boom = new Error('backend down');
    await expect(
      loadAllPages(
        vi.fn(async () => {
          throw boom;
        }),
      ),
    ).rejects.toBe(boom);
  });
});
