/**
 * Task list export (US25) — CSV and Markdown, pure helpers (no DOM, no fetch).
 *
 * This is the second consumer of the shape `src/lib/report/markdown.ts` set for the meeting
 * report: a pure function builds the text, a component wraps it in a Blob and `<a download>`
 * does the rest. `slugify` is imported from there rather than copied so both file names stay one
 * implementation. Nothing else is shared. The two modules are deliberately NOT merged into a
 * generic writer — one report and one table have almost nothing in common beyond the file name.
 *
 * The Markdown item line is the one part that IS the same on purpose: it repeats the action-item
 * line `meetingToMarkdown` already produces (checkbox, bold title, assignee, priority, due date),
 * plus the meeting the task came from, which a cross-meeting list needs and a single report does
 * not.
 *
 * The export is client-side. The list is already in the browser — the screen renders it — so
 * generating the file there needs no route, no new authorisation surface and no backend line,
 * and what comes out is exactly what the user is looking at, active status filter included. The
 * three conditions that would justify a `GET /tasks/export` endpoint (a volume that does not fit
 * the paginated response, an export that has to be audited, a non-browser client that wants it)
 * are all false today. If the third ever becomes true, it is the MCP server that should answer
 * it, not a file-download route.
 */

import type { TaskListItemDto, TaskPriority, TaskStatus } from '@/lib/api/client';
import { slugify } from '@/lib/report/markdown';

/** pt-BR status labels — shared with the tasks screen so file and UI cannot drift apart. */
export const STATUS_LABEL: Record<TaskStatus, string> = {
  OPEN: 'Aberta',
  IN_PROGRESS: 'Em andamento',
  DONE: 'Concluída',
};

/** pt-BR priority labels — same reason. */
export const PRIORITY_LABEL: Record<TaskPriority, string> = {
  LOW: 'Baixa',
  MEDIUM: 'Média',
  HIGH: 'Alta',
};

/**
 * UTF-8 byte order mark, prepended to the CSV.
 *
 * Without it Excel in pt-BR reads the file as Windows-1252 and renders `Concluída` as
 * `ConcluÃ­da`. With it, the accents survive. It does NOT fix the other half of the same
 * problem: Excel splits a CSV on the system list separator, which in a pt-BR install is `;`,
 * so a comma-separated file still opens in a single column until the user goes through
 * Data > From Text. That half is not fixable for both audiences at once — `;` would please
 * Excel and break every parser that defaults to a comma, which is the format's own name — so
 * the comma stays and the limitation is written down here instead of being traded for a
 * different one.
 */
export const CSV_BOM = '\uFEFF';

/** CSV column headers, in the order `tasksToCsv` writes them. */
export const CSV_COLUMNS = [
  'Título',
  'Responsável',
  'Prioridade',
  'Status',
  'Vencimento',
  'Reunião',
  'Atualizada em',
] as const;

/**
 * Escapes one CSV field, RFC 4180: a field containing a comma, a double quote, CR or LF is
 * wrapped in double quotes and its own quotes are doubled. Everything else goes out verbatim.
 *
 * This function is the reason the module exists. Task titles are extracted from meeting
 * transcripts by an LLM and routinely contain commas ("Revisar contrato, prazo e multa") and
 * quotes; a `join(',')` would shift every column to the right of the first one and produce a
 * file that looks fine until someone sorts it.
 *
 * What it deliberately does NOT do is defend against spreadsheet formula injection: a title
 * starting with `=`, `+`, `-` or `@` is still a formula to Excel. Every known mitigation
 * (prefixing an apostrophe, a leading tab) alters the text the user wrote, and quoting does not
 * help because Excel strips the quotes before evaluating. The data here is the tenant's own,
 * exported by a member of that tenant, so the trade lands on keeping the title intact — stated
 * rather than left for someone to discover.
 */
export function escapeCsvField(value: string | null | undefined): string {
  const text = value ?? '';
  if (!/[",\r\n]/.test(text)) return text;
  return `"${text.replace(/"/g, '""')}"`;
}

/**
 * Builds the CSV for the given tasks, BOM included, CRLF line endings (RFC 4180).
 *
 * Dates go out as the API returns them — `yyyy-MM-dd` for the due date, ISO 8601 for
 * `updatedAt` — because the consumer of a CSV is a spreadsheet or a script, and both sort an
 * ISO date correctly and a localised one wrongly. The Markdown export is where a human-readable
 * date belongs, and it has one.
 */
export function tasksToCsv(items: TaskListItemDto[]): string {
  const rows = [CSV_COLUMNS.map(escapeCsvField).join(',')];
  for (const t of items) {
    rows.push(
      [
        escapeCsvField(t.title),
        escapeCsvField(t.assignee),
        escapeCsvField(PRIORITY_LABEL[t.priority] ?? t.priority),
        escapeCsvField(STATUS_LABEL[t.status] ?? t.status),
        escapeCsvField(t.dueDate),
        escapeCsvField(t.meetingTitle),
        escapeCsvField(t.updatedAt),
      ].join(','),
    );
  }
  // Trailing CRLF: RFC 4180 allows the last record to end with one, and every parser accepts it.
  return `${CSV_BOM}${rows.join('\r\n')}\r\n`;
}

/**
 * Builds the Markdown document for the given tasks.
 *
 * `filterLabel` is the label of the status filter the screen has active ("Todas", "Abertas",
 * ...). It is written into the document because the export carries the filtered set: a file
 * with four of a tenant's forty tasks and no statement of why is a file that reads as a bug.
 */
export function tasksToMarkdown(items: TaskListItemDto[], filterLabel: string): string {
  const lines: string[] = ['# Action items', ''];

  lines.push([`**Filtro:** ${filterLabel}`, `**Total:** ${items.length}`].join('  \n'), '');

  if (items.length === 0) {
    lines.push('> Nenhum action item neste filtro.', '');
  } else {
    for (const t of items) {
      const parts = [
        `responsável: ${t.assignee ?? 'não definido'}`,
        `prioridade: ${PRIORITY_LABEL[t.priority] ?? t.priority}`,
      ];
      if (t.dueDate) parts.push(`vence: ${t.dueDate}`);
      parts.push(`reunião: ${t.meetingTitle}`);
      lines.push(`- [${t.status === 'DONE' ? 'x' : ' '}] **${t.title}** — ${parts.join(' · ')}`);
    }
    lines.push('');
  }

  lines.push('---', '', `_Gerado pelo Nora em ${nowInPtBr()}._`, '');

  return lines.join('\n');
}

/**
 * File name: `nora-tarefas-<filtro>-<yyyy-mm-dd>.<csv|md>`, on the model of
 * `meetingReportFileName`. The date is the export date — unlike the meeting report, whose date
 * belongs to the meeting, a task list has no date of its own.
 */
export function taskExportFileName(format: 'csv' | 'md', filterLabel: string): string {
  const slug = slugify(filterLabel) || 'todas';
  const day = new Date().toISOString().slice(0, 10);
  return `nora-tarefas-${slug}-${day}.${format}`;
}

function nowInPtBr(): string {
  return new Date().toLocaleString('pt-BR', { dateStyle: 'long', timeStyle: 'short' });
}
