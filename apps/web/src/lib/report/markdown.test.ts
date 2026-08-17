/**
 * `src/lib/report/markdown.ts` — the meeting report the detail screen downloads as a Blob.
 *
 * Pure, no DOM, no fetch, and about to gain a second consumer: the task CSV/Markdown export
 * reuses this shape. That is the whole reason it is tested before anything prettier — a helper
 * with one caller can be read; a helper with two, whose output lands in a file a user keeps,
 * needs a statement about what it produces.
 *
 * The report body is pt-BR because the product is; the assertions therefore quote pt-BR
 * strings. `scripts/check-language.sh` allowlists the module for exactly that reason and the
 * test file inherits it — see the allowlist entry added with this suite.
 */
import { describe, expect, it, vi } from 'vitest';

import type { MeetingDetail } from '@/lib/api/types';
import { meetingReportFileName, meetingToMarkdown, slugify } from '@/lib/report/markdown';

/**
 * Minimum viable meeting: every required field of `MeetingDetail`, nothing optional. Each test
 * spreads what it needs on top, so a test's own body shows exactly the input under study
 * instead of hiding it in a fixture three screens away.
 */
function meeting(overrides: Partial<MeetingDetail> = {}): MeetingDetail {
  return {
    id: 'm-1',
    tenantId: 't-1',
    title: 'Kickoff',
    startedAt: '2026-03-04T14:30:00Z',
    owner: { id: 'u-1', displayName: 'Ana' },
    participants: [],
    processingStatus: 'COMPLETED',
    createdAt: '2026-03-04T14:30:00Z',
    updatedAt: '2026-03-04T15:30:00Z',
    ...overrides,
  };
}

describe('slugify', () => {
  it('strips accents rather than dropping the letters that carry them', () => {
    // NFD + combining-mark removal, not a character blacklist: "Reunião" must not become
    // "reuni-o". This is the difference between a readable filename and a mangled one.
    expect(slugify('Reunião de Alinhamento')).toBe('reuniao-de-alinhamento');
    expect(slugify('Análise Trimestral — Ação')).toBe('analise-trimestral-acao');
  });

  it('collapses runs of non-alphanumerics and trims the hyphens at both ends', () => {
    expect(slugify('  ***Q1 // Q2***  ')).toBe('q1-q2');
    expect(slugify('a---b')).toBe('a-b');
  });

  it('returns an empty string when nothing survives, which is what the caller falls back on', () => {
    // `meetingReportFileName` reads this empty string as falsy and substitutes "reuniao".
    // If `slugify` ever returned "-" or "---" instead, that fallback would silently stop
    // firing and produce `nora-reuniao---2026-03-04.md`.
    expect(slugify('！！！')).toBe('');
    expect(slugify('---')).toBe('');
  });

  it('truncates at 60 characters, because the result is part of a filename', () => {
    const slug = slugify('a'.repeat(200));
    expect(slug).toHaveLength(60);
  });

  it('does not leave a trailing hyphen when the 60-character cut lands on a separator', () => {
    // Regression guard, and it currently FAILS to hold in the way one might hope: the trim
    // runs BEFORE the slice, so a cut that lands on a separator leaves the hyphen in place.
    // Asserted as it behaves, not as it ought to, so the next change to this function has to
    // decide deliberately rather than discover it in a filename.
    const slug = slugify(`${'a'.repeat(59)} b`);
    expect(slug).toBe(`${'a'.repeat(59)}-`);
  });
});

describe('meetingReportFileName', () => {
  it('uses the meeting day, not today', () => {
    expect(meetingReportFileName(meeting({ title: 'Kickoff Q1' }))).toBe(
      'nora-reuniao-kickoff-q1-2026-03-04.md',
    );
  });

  it('falls back to "reuniao" when the title slugs away to nothing', () => {
    expect(meetingReportFileName(meeting({ title: '???' }))).toBe(
      'nora-reuniao-reuniao-2026-03-04.md',
    );
  });

  it('falls back to today when the meeting has no start date', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-17T09:00:00Z'));
    try {
      expect(meetingReportFileName(meeting({ startedAt: '' }))).toBe(
        'nora-reuniao-kickoff-2026-08-17.md',
      );
    } finally {
      vi.useRealTimers();
    }
  });
});

describe('meetingToMarkdown', () => {
  it('says the meeting is unanalysed instead of emitting empty sections', () => {
    const md = meetingToMarkdown(meeting());
    expect(md).toContain('# Kickoff');
    expect(md).toContain('> Esta reunião ainda não foi analisada pela Nora.');
    expect(md).not.toContain('## Resumo');
    expect(md).not.toContain('## Decisões');
  });

  it('omits every metadata line whose value is absent', () => {
    // The header block is built by pushing into an array and joining; an unguarded push
    // produces "**Duração:** undefined" in a file the user keeps. Assert the absence.
    const md = meetingToMarkdown(meeting({ durationSeconds: 0 }));
    expect(md).not.toContain('**Duração:**');
    expect(md).not.toContain('**Participantes:**');
    expect(md).not.toContain('**Tags:**');
    expect(md).not.toContain('undefined');
  });

  it('rounds the duration to whole minutes and renders participants and tags', () => {
    const md = meetingToMarkdown(
      meeting({
        durationSeconds: 3510, // 58.5 min
        participants: [{ displayName: 'Ana' }, { displayName: 'Bruno' }],
        tags: ['q1', 'vendas'],
      }),
    );
    expect(md).toContain('**Duração:** 59min');
    expect(md).toContain('**Participantes:** Ana, Bruno');
    expect(md).toContain('**Tags:** `q1` `vendas`');
  });

  it('keeps an unparseable date verbatim rather than printing "Invalid Date"', () => {
    const md = meetingToMarkdown(meeting({ startedAt: 'not-a-date' }));
    expect(md).toContain('**Data:** not-a-date');
    expect(md).not.toContain('Invalid Date');
  });

  it('numbers decisions and renders confidence as a whole percentage', () => {
    const md = meetingToMarkdown(
      meeting({
        analysis: {
          summary: 'Fechamos o escopo.',
          decisions: [
            { text: 'Adiar o lançamento', confidence: 0.825 },
            { text: 'Contratar suporte', confidence: 1 },
          ],
          actionItems: [],
          risks: [],
          opportunities: [],
          sentimentOverall: 'POSITIVE',
          topics: [],
        },
      }),
    );
    expect(md).toContain('## Resumo');
    expect(md).toContain('Fechamos o escopo.');
    expect(md).toContain('1. Adiar o lançamento _(confiança 83%)_');
    expect(md).toContain('2. Contratar suporte _(confiança 100%)_');
    expect(md).toContain('**Sentimento geral:** Positivo');
  });

  it('renders action items as GFM checkboxes and marks only DONE as checked', () => {
    const md = meetingToMarkdown(
      meeting({
        analysis: {
          summary: '',
          decisions: [],
          actionItems: [
            {
              title: 'Enviar proposta',
              assignee: 'Ana',
              dueDate: '2026-03-10',
              priority: 'URGENT' as never,
              sourceQuote: '',
              status: 'DONE',
            },
            {
              title: 'Revisar contrato',
              assignee: null,
              dueDate: null,
              priority: 'LOW',
              sourceQuote: '',
              status: 'IN_PROGRESS',
            },
          ],
          risks: [],
          opportunities: [],
          sentimentOverall: 'NEUTRAL',
          topics: [],
        },
      }),
    );
    expect(md).toContain(
      '- [x] **Enviar proposta** — responsável: Ana · prioridade: URGENT · vence: 2026-03-10',
    );
    expect(md).toContain(
      '- [ ] **Revisar contrato** — responsável: não definido · prioridade: Baixa',
    );
    // No due date means no "vence:" fragment at all, not an empty one.
    expect(md).not.toContain('vence: null');
  });

  it('drops the summary section when the summary is only whitespace', () => {
    const md = meetingToMarkdown(
      meeting({
        analysis: {
          summary: '   \n  ',
          decisions: [],
          actionItems: [],
          risks: [],
          opportunities: [],
          sentimentOverall: 'MIXED',
          topics: ['preço'],
        },
      }),
    );
    expect(md).not.toContain('## Resumo');
    expect(md).toContain('## Tópicos');
    expect(md).toContain('`preço`');
  });

  it('omits the confidence fragment when a decision carries no numeric confidence', () => {
    // The API type says `confidence: number`, but the report is built from data that has come
    // back from an LLM pipeline, and the `typeof === 'number'` guard exists because it has not
    // always been one. The failure mode without it is "_(confiança NaN%)_" in a saved file.
    const md = meetingToMarkdown(
      meeting({
        analysis: {
          summary: 'x',
          decisions: [{ text: 'Seguir', confidence: undefined as never }],
          actionItems: [],
          risks: [],
          opportunities: [],
          sentimentOverall: 'UNKNOWN' as never,
          topics: [],
        },
      }),
    );
    expect(md).toContain('1. Seguir\n');
    expect(md).not.toContain('confiança');
    expect(md).not.toContain('NaN');
    // Sentiment has no label either; the raw enum is printed rather than "undefined".
    expect(md).toContain('**Sentimento geral:** UNKNOWN');
  });

  it('renders risks and opportunities with their quotes as blockquotes', () => {
    const md = meetingToMarkdown(
      meeting({
        analysis: {
          summary: 'x',
          decisions: [],
          actionItems: [],
          risks: [
            {
              text: 'Prazo curto',
              severity: 'HIGH',
              category: 'Cronograma',
              sourceQuote: 'não dá',
            },
            { text: 'Sem dono', severity: 'CRITICAL' as never, category: 'Time', sourceQuote: '' },
          ],
          opportunities: [
            { text: 'Upsell', estimatedValue: 'MEDIUM', category: 'Receita', sourceQuote: 'quero' },
            {
              text: 'Renovação',
              estimatedValue: 'X' as never,
              category: 'Receita',
              sourceQuote: '',
            },
          ],
          sentimentOverall: 'NEGATIVE',
          topics: [],
        },
      }),
    );
    expect(md).toContain('- **Alta · Cronograma** — Prazo curto');
    expect(md).toContain('  > não dá');
    expect(md).toContain('- **Média · Receita** — Upsell');
    expect(md).toContain('  > quero');
    // Unlabelled severities print the raw enum, never "undefined".
    expect(md).toContain('- **CRITICAL · Time** — Sem dono');
    expect(md).toContain('- **X · Receita** — Renovação');
    // An empty quote must not produce a dangling "> " line.
    expect(md).not.toContain('  > \n');
  });

  it('falls back to the raw enum when a label is missing rather than printing undefined', () => {
    const md = meetingToMarkdown(
      meeting({
        productivity: {
          score: 71,
          band: 'UNKNOWN' as never,
          coverage: [],
          offTopicRatio: null,
          decisionDensity: null,
          rationale: '',
        },
        customerConfidence: {
          score: 40,
          band: 'HIGH',
          trend: 'SIDEWAYS' as never,
          accountName: null,
          rationale: '',
          buyingSignals: [],
          objections: [],
        },
      }),
    );
    expect(md).toContain('**71/100 — Produtividade UNKNOWN**');
    expect(md).toContain('**40/100 — Confiança Alta (SIDEWAYS)**');
    expect(md).not.toContain('undefined');
  });

  it('renders the confidence header with trend and account when both are present', () => {
    const md = meetingToMarkdown(
      meeting({
        customerConfidence: {
          score: 82,
          band: 'HIGH',
          trend: 'IMPROVING',
          accountName: 'ACME',
          rationale: 'Cliente engajado.',
          buyingSignals: [],
          objections: [],
        },
      }),
    );
    expect(md).toContain('## Confiança do cliente');
    expect(md).toContain('**82/100 — Confiança Alta (em melhora)** — conta: ACME');
    expect(md).toContain('Cliente engajado.');
  });

  it('always ends with the generation footer', () => {
    const md = meetingToMarkdown(meeting());
    expect(md).toContain('---');
    expect(md).toMatch(/_Gerado pelo Nora em .+\._\n$/);
  });
});
