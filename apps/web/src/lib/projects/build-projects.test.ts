/**
 * How a project is derived from the meeting list.
 *
 * Two of the three numbers a project card shows were wrong, and the arithmetic was buried in a
 * server component where nothing could reach it. The one that mattered was the count labelled
 * "abertos": it summed `actionItemCount`, which is what the ANALYSIS EXTRACTED from the meeting
 * and never decreases when somebody finishes an item — so a project whose every task was done
 * still read as having twelve open ones. The listing now sends `openActionItemCount` beside it,
 * and the two are kept apart here because the screen makes two different claims about them.
 */
import { describe, expect, it } from 'vitest';

import type { MeetingListItem } from '@/lib/api/types';
import { buildProjects } from '@/lib/projects/build-projects';

function meeting(over: Partial<MeetingListItem> & { id: string }): MeetingListItem {
  return {
    title: `Meeting ${over.id}`,
    startedAt: '2026-05-01T10:00:00Z',
    processingStatus: 'COMPLETED',
    actionItemCount: 0,
    openActionItemCount: 0,
    riskCount: 0,
    opportunityCount: 0,
    tags: [],
    ...over,
  };
}

/** Projects come back ordered by activity, so the assertions name the one they mean. */
function byTag(projects: ReturnType<typeof buildProjects>, tag: string) {
  const found = projects.find((p) => p.tag === tag);
  if (!found) throw new Error(`no project for tag ${tag}`);
  return found;
}

describe('buildProjects', () => {
  const meetings = [
    meeting({
      id: 'm1',
      tags: ['alpha'],
      startedAt: '2026-05-01T10:00:00Z',
      actionItemCount: 5,
      openActionItemCount: 1,
    }),
    meeting({
      id: 'm2',
      tags: ['alpha', 'beta'],
      startedAt: '2026-05-03T10:00:00Z',
      actionItemCount: 4,
      openActionItemCount: 1,
      riskCount: 2,
    }),
  ];

  it('groups by tag, with a meeting in every tag it carries', () => {
    const projects = buildProjects(meetings);

    expect(projects.map((p) => p.tag)).toEqual(['alpha', 'beta']);
    expect(byTag(projects, 'alpha').meetings.map((m) => m.id)).toEqual(['m1', 'm2']);
    expect(byTag(projects, 'beta').meetings.map((m) => m.id)).toEqual(['m2']);
  });

  it('counts OPEN action items, not every action item ever extracted', () => {
    const alpha = byTag(buildProjects(meetings), 'alpha');

    // The meetings carry `actionItemCount` 5 and 4; only two of those nine are still open.
    expect(alpha.open).toBe(2);
  });

  it('keeps the raw total available, because the detail screen labels it as the total', () => {
    const alpha = byTag(buildProjects(meetings), 'alpha');

    expect(alpha.total).toBe(9);
  });

  it('reads a meeting from before the field as zero open, not as NaN across the card', () => {
    const legacy = [
      { ...meeting({ id: 'm1', tags: ['alpha'], actionItemCount: 3 }), openActionItemCount: undefined },
    ] as unknown as MeetingListItem[];

    const alpha = byTag(buildProjects(legacy), 'alpha');
    expect(alpha.open).toBe(0);
    expect(alpha.total).toBe(3);
  });

  it('sums risks and takes the most recent meeting as the last activity', () => {
    const alpha = byTag(buildProjects(meetings), 'alpha');

    expect(alpha.risks).toBe(2);
    expect(alpha.last).toBe('2026-05-03T10:00:00Z');
  });

  it('orders projects by their last activity, most recent first', () => {
    const projects = buildProjects([
      meeting({ id: 'old', tags: ['stale'], startedAt: '2026-01-01T10:00:00Z' }),
      meeting({ id: 'new', tags: ['live'], startedAt: '2026-06-01T10:00:00Z' }),
    ]);

    expect(projects.map((p) => p.tag)).toEqual(['live', 'stale']);
  });

  it('ignores blank tags and meetings with none', () => {
    const projects = buildProjects([
      meeting({ id: 'm1', tags: ['  ', ''] }),
      meeting({ id: 'm2', tags: [] }),
      meeting({ id: 'm3', tags: [' spaced '] }),
    ]);

    // A tag is trimmed before it becomes a project, so " spaced " and "spaced" are one project.
    expect(projects.map((p) => p.tag)).toEqual(['spaced']);
  });

  it('uses the caller-supplied display name and leaves the raw tag alone', () => {
    const projects = buildProjects([meeting({ id: 'm1', tags: ['sales-team'] })], {
      formatName: (tag) => tag.toUpperCase(),
    });

    expect(projects[0]).toMatchObject({ tag: 'sales-team', name: 'SALES-TEAM' });
  });
});
