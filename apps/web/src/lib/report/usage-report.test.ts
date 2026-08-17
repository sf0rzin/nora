/**
 * `src/lib/report/usage-report.ts` — the consolidated period report (US34).
 *
 * The escaping and the BOM are not retested here: they belong to `tasks-export`, which owns them
 * and has an RFC 4180 round trip over them. What this suite pins is the thing this report can get
 * wrong and the other two cannot — writing a number the API did not measure.
 *
 * `ai.state` has three values and only one of them is a measurement. A file that prints `0` for
 * the other two is worse than a file that prints nothing: a spreadsheet sums a zero, averages it
 * and plots it, and nothing downstream ever learns it was invented. So the CSV leaves the cell
 * empty and the Markdown says why, and both are asserted below.
 *
 * The exported text is pt-BR because the product is, so the assertions quote pt-BR strings — same
 * reason `tasks-export.test.ts` is on the `scripts/check-language.sh` allowlist, and this file is
 * listed there beside it.
 */
import { describe, expect, it } from 'vitest';

import type { UsageResponse } from '@/lib/api/types';
import {
  formatCost,
  periodLabel,
  serviceLabel,
  usageReportFileName,
  usageToCsv,
  usageToMarkdown,
} from '@/lib/report/usage-report';

function usage(overrides: Partial<UsageResponse> = {}): UsageResponse {
  return {
    granularity: 'MONTH',
    timezone: 'America/Sao_Paulo',
    from: '2026-07-01T00:00:00-03:00',
    to: '2026-08-17T09:00:00-03:00',
    scopeStrategy: 'TENANT_UNIFORM',
    dataState: 'OK',
    meetings: 12,
    analysedMeetings: 9,
    meetingBuckets: [
      { bucketStart: '2026-07-01', meetings: 5, analysedMeetings: 4 },
      { bucketStart: '2026-08-01', meetings: 7, analysedMeetings: 5 },
    ],
    ai: {
      state: 'AVAILABLE',
      costBasis: 'CATALOG_LIST_PRICE',
      currency: 'USD',
      calls: 30,
      unmeteredCalls: 12,
      promptTokens: 40000,
      completionTokens: 12000,
      costUsd: 0.0132,
      byService: [
        {
          service: 'analysis',
          metered: true,
          calls: 18,
          promptTokens: 40000,
          completionTokens: 12000,
          costUsd: 0.0132,
        },
        {
          service: 'stt',
          metered: false,
          calls: 12,
          promptTokens: 0,
          completionTokens: 0,
          costUsd: 0,
        },
      ],
      buckets: [
        {
          bucketStart: '2026-07-01',
          calls: 10,
          promptTokens: 15000,
          completionTokens: 4000,
          costUsd: 0.005,
        },
        {
          bucketStart: '2026-08-01',
          calls: 20,
          promptTokens: 25000,
          completionTokens: 8000,
          costUsd: 0.0082,
        },
      ],
    },
    ...overrides,
  };
}

/** Splits one CSV line, honouring quoted fields. Enough for the shapes this module writes. */
function parseCsvLine(line: string): string[] {
  const out: string[] = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < line.length; i += 1) {
    const ch = line[i];
    if (quoted) {
      if (ch === '"' && line[i + 1] === '"') {
        field += '"';
        i += 1;
      } else if (ch === '"') {
        quoted = false;
      } else {
        field += ch;
      }
    } else if (ch === '"') {
      quoted = true;
    } else if (ch === ',') {
      out.push(field);
      field = '';
    } else {
      field += ch;
    }
  }
  out.push(field);
  return out;
}

describe('usageToCsv', () => {
  it('writes one row per bucket, joined to the AI series on the bucket key', () => {
    const rows = usageToCsv(usage()).split('\r\n');

    // The BOM belongs to the file, not to the first column name.
    expect(parseCsvLine(rows[0].replace(/^﻿/, ''))).toEqual([
      'Período',
      'Reuniões',
      'Reuniões analisadas',
      'Chamadas de IA',
      'Tokens de entrada',
      'Tokens de saída',
      'Custo estimado (USD)',
    ]);
    expect(parseCsvLine(rows[1])).toEqual([
      '07/2026',
      '5',
      '4',
      '10',
      '15000',
      '4000',
      '0.005000',
    ]);
    expect(parseCsvLine(rows[2])).toEqual([
      '08/2026',
      '7',
      '5',
      '20',
      '25000',
      '8000',
      '0.008200',
    ]);
  });

  it('leaves the AI columns EMPTY when the control plane could not answer', () => {
    // The failure this pins: a zero here is summed and charted as "no consumption", which is a
    // claim. An empty cell is the absence of one.
    const data = usage({
      ai: { ...usage().ai, state: 'UNAVAILABLE', calls: 0, buckets: [] },
    });

    const rows = usageToCsv(data).split('\r\n');

    expect(parseCsvLine(rows[1]).slice(3)).toEqual(['', '', '', '']);
    expect(parseCsvLine(rows[1]).slice(0, 3)).toEqual(['07/2026', '5', '4']);
  });

  it('keeps the meeting counts when the AI half is withheld for a restricted caller', () => {
    const data = usage({
      scopeStrategy: 'PER_MEETING_FILTER',
      ai: { ...usage().ai, state: 'WITHHELD_RESTRICTED_SCOPE', calls: 0, buckets: [] },
    });

    const rows = usageToCsv(data).split('\r\n');

    expect(parseCsvLine(rows[2]).slice(0, 3)).toEqual(['08/2026', '7', '5']);
    expect(parseCsvLine(rows[2])[3]).toBe('');
  });

  it('starts with the BOM and ends with CRLF', () => {
    const csv = usageToCsv(usage());

    expect(csv.startsWith('﻿')).toBe(true);
    expect(csv.endsWith('\r\n')).toBe(true);
  });
});

describe('usageToMarkdown', () => {
  it('states the range, the grouping and the reporting zone', () => {
    const md = usageToMarkdown(usage());

    expect(md).toContain('# Relatório de uso do período');
    expect(md).toContain('**Período:** 2026-07-01 a 2026-08-17');
    expect(md).toContain('**Fuso do relatório:** America/Sao_Paulo');
    expect(md).toContain('**Reuniões no período:** 12 (9 analisadas)');
  });

  it('calls the cost an estimate and says what it is an estimate of', () => {
    const md = usageToMarkdown(usage());

    expect(md).toContain('estimativa');
    expect(md).toContain('preço de tabela');
    expect(md).toContain('Não é uma fatura');
  });

  it('explains the transcription sessions instead of showing them as free calls', () => {
    const md = usageToMarkdown(usage());

    expect(md).toContain('12 das 30 chamadas são sessões de transcrição ao vivo');
    expect(md).toContain('nunca um minuto transcrito');
    // The per-service table refuses a money figure for it rather than printing US$ 0.
    expect(md).toContain('| Transcrição ao vivo | 12 | — | não medido |');
  });

  it('says the AI half is unavailable rather than reporting zero consumption', () => {
    const md = usageToMarkdown(
      usage({ ai: { ...usage().ai, state: 'UNAVAILABLE', calls: 0, buckets: [] } }),
    );

    expect(md).toContain('Não disponível');
    expect(md).not.toContain('**Chamadas:** 0');
  });

  it('says the AI half is withheld, and why, for a caller whose policy filters meetings', () => {
    const md = usageToMarkdown(
      usage({
        scopeStrategy: 'PER_MEETING_FILTER',
        ai: { ...usage().ai, state: 'WITHHELD_RESTRICTED_SCOPE', calls: 0, buckets: [] },
      }),
    );

    expect(md).toContain('não guarda a qual reunião cada chamada pertence');
    expect(md).toContain('apenas as reuniões que você tem permissão para abrir');
  });

  it('separates "nothing yet" from "a period of zero activity"', () => {
    const empty = usageToMarkdown(
      usage({
        dataState: 'NO_DATA',
        meetings: 0,
        analysedMeetings: 0,
        ai: { ...usage().ai, state: 'AVAILABLE', calls: 0, buckets: [] },
      }),
    );
    const unanalysed = usageToMarkdown(usage({ dataState: 'NO_ANALYSED_MEETINGS' }));

    expect(empty).toContain('Ainda não há dado');
    expect(unanalysed).toContain('nenhuma foi analisada ainda');
  });
});

describe('helpers', () => {
  it('formats a fraction-of-a-cent cost with six decimals instead of rounding it to zero', () => {
    expect(formatCost(0.0000132)).toBe('0.000013');
    expect(formatCost('0.75')).toBe('0.750000');
    expect(formatCost(null)).toBe('0.000000');
    expect(formatCost('not a number')).toBe('0.000000');
  });

  it('labels a period by its grouping', () => {
    expect(periodLabel('2026-08-01', 'MONTH')).toBe('08/2026');
    expect(periodLabel('2026-08-17', 'WEEK')).toBe('17/08/2026');
  });

  it('keeps an unknown service name rather than inventing a label for it', () => {
    expect(serviceLabel('stt')).toBe('Transcrição ao vivo');
    expect(serviceLabel('some-future-service')).toBe('some-future-service');
  });

  it('names the file after the range, so two ranges exported today do not collide', () => {
    expect(usageReportFileName('csv', usage())).toBe('nora-uso-2026-07-01-a-2026-08-17.csv');
    expect(usageReportFileName('md', usage())).toBe('nora-uso-2026-07-01-a-2026-08-17.md');
  });
});
