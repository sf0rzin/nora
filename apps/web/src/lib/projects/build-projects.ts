/**
 * Projects are derived, not stored: a project IS a tag that appears on meetings, and everything
 * the screen shows about one is computed here from the meeting list.
 *
 * Extracted out of the page so the arithmetic can be tested. Two of the three numbers on a card
 * were wrong or unqualified before, and neither had any way of being caught: the count labelled
 * "abertos" was the raw `actionItemCount` sum, closed items included, and the whole screen was
 * built from whatever the first page of meetings happened to contain, with nothing saying so.
 *
 * The open count is now `openActionItemCount`, which the listing sends per meeting. It used to be
 * reconstructed here from a full read of `GET /tasks` — correct arithmetic over the wrong source:
 * it cost up to five extra paged requests per render, and it degraded to the raw total (with a
 * caveat on screen) whenever that read failed. The listing counts the same rows in the same
 * transaction that produced the meetings, so there is nothing left to degrade.
 */
import type { MeetingListItem } from '@/lib/api/types';

export interface Project {
  tag: string;
  name: string;
  meetings: MeetingListItem[];
  /** Action items still open across the group. */
  open: number;
  /** Every action item the analyses extracted, DONE included. */
  total: number;
  risks: number;
  /** ISO-8601 of the most recent meeting in the group. */
  last: string;
}

export interface BuildProjectsOptions {
  /**
   * Display name for a tag. Passed in because the prettifier carries pt-BR product copy and this
   * module is not where that lives.
   */
  formatName?: (tag: string) => string;
}

export function buildProjects(
  items: readonly MeetingListItem[],
  options: BuildProjectsOptions = {},
): Project[] {
  const formatName = options.formatName ?? ((tag: string) => tag);

  const byTag = new Map<string, MeetingListItem[]>();
  for (const m of items) {
    for (const tag of m.tags ?? []) {
      const key = tag.trim();
      if (!key) continue;
      let bucket = byTag.get(key);
      if (!bucket) {
        bucket = [];
        byTag.set(key, bucket);
      }
      bucket.push(m);
    }
  }

  const projects: Project[] = [];
  for (const [tag, meetings] of byTag) {
    // `?? 0` and not a bare read: an item from a backend older than the field, or from a fixture
    // written before it, must count as zero open rather than as `NaN` across the whole card.
    const open = meetings.reduce((a, m) => a + (m.openActionItemCount ?? 0), 0);
    const total = meetings.reduce((a, m) => a + (m.actionItemCount ?? 0), 0);
    const risks = meetings.reduce((a, m) => a + (m.riskCount ?? 0), 0);
    const last = meetings
      .map((m) => m.startedAt)
      .sort()
      .reverse()[0];
    projects.push({ tag, name: formatName(tag), meetings, open, total, risks, last });
  }

  // `localeCompare` and not `a.last < b.last ? 1 : -1`: the ternary answers -1 for two projects
  // whose last activity is the SAME meeting, which is not a valid comparator and let the order of
  // tied projects depend on the sort implementation. Ties now keep insertion order.
  return projects.sort((a, b) => b.last.localeCompare(a.last));
}
