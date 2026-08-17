/**
 * `src/lib/report/tasks-export.ts` — the CSV and Markdown the tasks screen downloads (US25).
 *
 * The CSV half is the reason this suite exists. Task titles come out of an LLM reading a meeting
 * transcript, so "Revisar contrato, prazo e multa" and a quoted phrase are ordinary inputs, and a
 * writer that does not escape them produces a file that opens without complaint and has every
 * column after the first one shifted. The round-trip test below therefore parses the output back
 * with an RFC 4180 reader rather than asserting on a substring: a `toContain` passes on a file
 * whose columns are misaligned.
 *
 * The exported text is pt-BR because the product is, so the assertions quote pt-BR strings —
 * same reason `markdown.test.ts` is on the `scripts/check-language.sh` allowlist, and this file
 * is listed there beside it.
 */
import { describe, expect, it, vi } from 'vitest';

import type { TaskListItemDto } from '@/lib/api/client';
import {
  CSV_BOM,
  escapeCsvField,
  taskExportFileName,
  tasksToCsv,
  tasksToMarkdown,
} from '@/lib/report/tasks-export';

/**
 * Minimum viable task: every required field of `TaskListItemDto`, nothing optional. Each test
 * spreads what it needs on top, so the input under study is visible in the test itself.
 */
function task(overrides: Partial<TaskListItemDto> = {}): TaskListItemDto {
  return {
    id: 't-1',
    title: 'Enviar proposta',
    priority: 'HIGH',
    status: 'OPEN',
    meetingId: 'm-1',
    meetingTitle: 'Kickoff',
    updatedAt: '2026-03-04T15:30:00Z',
    ...overrides,
  };
}

/**
 * A deliberately small RFC 4180 reader, used only to check that what `tasksToCsv` writes can be
 * read back field for field. Handles quoted fields, doubled quotes inside them and separators
 * (comma, CR, LF) that appear inside quotes. It is not a general-purpose parser and does not
 * need to be — it is the other half of the format, written independently of the writer.
 */
function parseCsv(text: string): string[][] {
  const rows: string[][] = [];
  let row: string[] = [];
  let field = '';
  let quoted = false;

  for (let i = 0; i < text.length; i += 1) {
    const c = text[i];
    if (quoted) {
      if (c === '"') {
        if (text[i + 1] === '"') {
          field += '"';
          i += 1;
        } else {
          quoted = false;
        }
      } else {
        field += c;
      }
      continue;
    }
    if (c === '"' && field === '') {
      quoted = true;
    } else if (c === ',') {
      row.push(field);
      field = '';
    } else if (c === '\r' || c === '\n') {
      if (c === '\r' && text[i + 1] === '\n') i += 1;
      row.push(field);
      rows.push(row);
      row = [];
      field = '';
    } else {
      field += c;
    }
  }
  if (field !== '' || row.length > 0) {
    row.push(field);
    rows.push(row);
  }
  return rows;
}

describe('escapeCsvField', () => {
  it('leaves a field that needs no quoting exactly as it came', () => {
    expect(escapeCsvField('Enviar proposta')).toBe('Enviar proposta');
    // Semicolons, accents and the em dash are not separators in this format and must not be
    // quoted "just in case" — the file would grow quotes around most of its cells.
    expect(escapeCsvField('Reunião — parte 1; parte 2')).toBe('Reunião — parte 1; parte 2');
  });

  it('quotes a field containing a comma', () => {
    expect(escapeCsvField('Revisar contrato, prazo e multa')).toBe(
      '"Revisar contrato, prazo e multa"',
    );
  });

  it('doubles the quotes inside a field and wraps the result', () => {
    expect(escapeCsvField('Definir o "go/no-go"')).toBe('"Definir o ""go/no-go"""');
  });

  it('quotes a field containing CR or LF instead of ending the record early', () => {
    expect(escapeCsvField('linha 1\nlinha 2')).toBe('"linha 1\nlinha 2"');
    expect(escapeCsvField('linha 1\r\nlinha 2')).toBe('"linha 1\r\nlinha 2"');
  });

  it('turns a missing value into an empty field, never the string "undefined"', () => {
    expect(escapeCsvField(undefined)).toBe('');
    expect(escapeCsvField(null)).toBe('');
    expect(escapeCsvField('')).toBe('');
  });
});

describe('tasksToCsv', () => {
  it('starts with the UTF-8 BOM, so Excel in pt-BR does not mangle the accents', () => {
    // Without it "Concluída" reaches the user as "ConcluÃ­da". The BOM is one character and it
    // is the whole fix for that half of the problem; the delimiter half is documented in the
    // module and deliberately not traded away.
    const csv = tasksToCsv([]);
    expect(csv.startsWith(CSV_BOM)).toBe(true);
    expect(CSV_BOM.charCodeAt(0)).toBe(0xfeff);
  });

  it('writes the header even when there is nothing to export', () => {
    const rows = parseCsv(tasksToCsv([]).slice(CSV_BOM.length));
    expect(rows).toHaveLength(1);
    expect(rows[0]).toEqual([
      'Título',
      'Responsável',
      'Prioridade',
      'Status',
      'Vencimento',
      'Reunião',
      'Atualizada em',
    ]);
  });

  it('separates records with CRLF, as the format specifies', () => {
    const csv = tasksToCsv([task()]);
    expect(csv).toMatch(/\r\n/);
    expect(csv.endsWith('\r\n')).toBe(true);
    // No lone LF outside a quoted field: every LF must be preceded by a CR.
    expect(/(^|[^\r])\n/.test(csv)).toBe(false);
  });

  it('round-trips a title carrying a comma, a quote and a line break', () => {
    // The defect this module exists to avoid. Read back field by field: a substring assertion
    // passes on a file whose columns have all shifted one to the right.
    const csv = tasksToCsv([
      task({
        title: 'Revisar contrato, prazo e multa',
        assignee: 'Ana "Aninha" Souza',
        dueDate: '2026-03-10',
      }),
      task({ id: 't-2', title: 'Fechar escopo\ne comunicar', priority: 'LOW', status: 'DONE' }),
    ]);
    const rows = parseCsv(csv.slice(CSV_BOM.length));

    expect(rows).toHaveLength(3);
    expect(rows[1]).toEqual([
      'Revisar contrato, prazo e multa',
      'Ana "Aninha" Souza',
      'Alta',
      'Aberta',
      '2026-03-10',
      'Kickoff',
      '2026-03-04T15:30:00Z',
    ]);
    expect(rows[2]).toEqual([
      'Fechar escopo\ne comunicar',
      '',
      'Baixa',
      'Concluída',
      '',
      'Kickoff',
      '2026-03-04T15:30:00Z',
    ]);
  });

  it('prints the raw enum when a label is missing rather than an empty cell', () => {
    // The API types say the two enums are closed, but the rows are built from JSON over the
    // wire. A cell that silently empties is worse than one showing the code that arrived.
    const rows = parseCsv(
      tasksToCsv([
        task({ priority: 'URGENT' as never, status: 'BLOCKED' as never }),
      ]).slice(CSV_BOM.length),
    );
    expect(rows[1][2]).toBe('URGENT');
    expect(rows[1][3]).toBe('BLOCKED');
  });
});

describe('tasksToMarkdown', () => {
  it('states the active filter and the count, because the export is the filtered set', () => {
    const md = tasksToMarkdown([task(), task({ id: 't-2' })], 'Abertas');
    expect(md).toContain('# Action items');
    expect(md).toContain('**Filtro:** Abertas');
    expect(md).toContain('**Total:** 2');
  });

  it('reuses the action-item line of the meeting report and adds the meeting', () => {
    const md = tasksToMarkdown(
      [
        task({
          title: 'Enviar proposta',
          assignee: 'Ana',
          dueDate: '2026-03-10',
          status: 'DONE',
          meetingTitle: 'Kickoff Q1',
        }),
      ],
      'Todas',
    );
    expect(md).toContain(
      '- [x] **Enviar proposta** — responsável: Ana · prioridade: Alta · vence: 2026-03-10 · reunião: Kickoff Q1',
    );
  });

  it('leaves the checkbox empty for anything that is not DONE', () => {
    const md = tasksToMarkdown([task({ status: 'IN_PROGRESS' })], 'Em andamento');
    expect(md).toContain('- [ ] **Enviar proposta**');
  });

  it('omits the due date fragment entirely when there is none, and names a missing assignee', () => {
    const md = tasksToMarkdown([task({ priority: 'MEDIUM' })], 'Todas');
    expect(md).toContain('responsável: não definido · prioridade: Média · reunião: Kickoff');
    expect(md).not.toContain('vence:');
    expect(md).not.toContain('undefined');
    expect(md).not.toContain('null');
  });

  it('prints the raw enum when a priority has no label, same as the CSV', () => {
    const md = tasksToMarkdown([task({ priority: 'URGENT' as never })], 'Todas');
    expect(md).toContain('prioridade: URGENT');
    expect(md).not.toContain('undefined');
  });

  it('says the filter is empty instead of emitting a list with no items', () => {
    const md = tasksToMarkdown([], 'Concluídas');
    expect(md).toContain('**Total:** 0');
    expect(md).toContain('> Nenhum action item neste filtro.');
    expect(md).not.toContain('- [');
  });

  it('always ends with the generation footer', () => {
    expect(tasksToMarkdown([task()], 'Todas')).toMatch(/_Gerado pelo Nora em .+\._\n$/);
  });
});

describe('taskExportFileName', () => {
  it('names the file after the filter and the export day, with the right extension', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-17T09:00:00Z'));
    try {
      expect(taskExportFileName('csv', 'Todas')).toBe('nora-tarefas-todas-2026-08-17.csv');
      expect(taskExportFileName('md', 'Em andamento')).toBe(
        'nora-tarefas-em-andamento-2026-08-17.md',
      );
      // Accents are stripped by `slugify`, shared with the meeting report so both file names
      // are produced by one implementation.
      expect(taskExportFileName('csv', 'Concluídas')).toBe('nora-tarefas-concluidas-2026-08-17.csv');
    } finally {
      vi.useRealTimers();
    }
  });

  it('falls back to "todas" when the label slugs away to nothing', () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date('2026-08-17T09:00:00Z'));
    try {
      expect(taskExportFileName('md', '???')).toBe('nora-tarefas-todas-2026-08-17.md');
    } finally {
      vi.useRealTimers();
    }
  });
});
