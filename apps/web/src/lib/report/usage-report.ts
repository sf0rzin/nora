/**
 * Consolidated period report (US34) — CSV and Markdown, pure helpers (no DOM, no fetch).
 *
 * US59/US60 export ONE meeting and US25 exports the task list; nothing aggregated a period. This
 * is the third consumer of the shape those two set — a pure function builds the text, a component
 * wraps it in a Blob and `<a download>` does the rest — and it reuses their pieces rather than
 * growing a second copy: `escapeCsvField` and `CSV_BOM` come from `tasks-export`, `slugify` from
 * `markdown`. The RFC 4180 escaping and the BOM decision were made and tested there; nothing here
 * re-litigates them.
 *
 * Client-side, for the same reason US25 recorded: the numbers are already in the browser because
 * the screen renders them, so the file needs no route, no new authorisation surface and no backend
 * line, and what comes out is exactly the period the reader is looking at. The three conditions
 * that would justify a `GET /usage/export` are all still false — the payload is bounded by the
 * range ceiling (52 weeks or 24 months), the export is not an audited artefact, and a non-browser
 * consumer would be served by the MCP server of ADR 0041 rather than by a download route.
 *
 * WHAT THE FILE MUST NOT DO IS FLATTER THE DATA. Three of the numbers it carries are not
 * measurements, and each one is labelled in the file itself rather than only on the screen:
 *
 * - the AI half can be absent (the control plane of ADR 0022 is optional infrastructure) or
 *   withheld (the caller's IAM position cannot be honoured over an aggregate that carries no
 *   meeting id), and an absent measurement is written as such, never as a zero;
 * - the cost is a catalog list price times measured tokens, not an invoice;
 * - transcription is counted in sessions issued and not minutes transcribed (ADR 0045), so its
 *   cost is structurally zero and the report says how many calls that covers.
 */

import type {
  TrendsGranularity,
  UsageAiBucket,
  UsageMeetingBucket,
  UsageResponse,
} from '@/lib/api/types';
import { slugify } from '@/lib/report/markdown';
import { CSV_BOM, escapeCsvField } from '@/lib/report/tasks-export';

/** pt-BR labels for the services `UsageRecorder` writes. Unknown services keep their raw name. */
export const SERVICE_LABEL: Record<string, string> = {
  analysis: 'Análise de reunião',
  chat: 'Chat',
  embedding: 'Indexação semântica',
  multimodal: 'Análise multimodal',
  stt: 'Transcrição ao vivo',
};

/** CSV column headers of the per-period sheet, in the order `usageToCsv` writes them. */
export const CSV_COLUMNS = [
  'Período',
  'Reuniões',
  'Reuniões analisadas',
  'Chamadas de IA',
  'Tokens de entrada',
  'Tokens de saída',
  'Custo estimado (USD)',
] as const;

export function serviceLabel(service: string): string {
  return SERVICE_LABEL[service] ?? service;
}

/**
 * `costUsd` arrives as a JSON number produced from a `BigDecimal`. It is read as `string | number`
 * so an eventual string-serialised decimal survives, and rendered with six decimals because the
 * catalog prices are per million tokens: a single analysis costs fractions of a cent, and two
 * decimals would print every honest figure as `0.00`.
 */
export function formatCost(value: string | number | null | undefined): string {
  const n = typeof value === 'string' ? Number.parseFloat(value) : (value ?? 0);
  if (!Number.isFinite(n)) return '0.000000';
  return n.toFixed(6);
}

/** `2026-08-01` → `ago/2026` for a month bucket, `01/08/2026` for a week one. */
export function periodLabel(bucketStart: string, granularity: TrendsGranularity): string {
  const [year, month, day] = bucketStart.split('-');
  if (!year || !month || !day) return bucketStart;
  if (granularity === 'MONTH') return `${month}/${year}`;
  return `${day}/${month}/${year}`;
}

/** Joins the two series on the bucket key. Both are dense over the same axis when AI is present. */
function rows(data: UsageResponse): Array<{
  bucketStart: string;
  meetings: UsageMeetingBucket;
  ai: UsageAiBucket | null;
}> {
  const aiByBucket = new Map<string, UsageAiBucket>();
  for (const bucket of data.ai.buckets) aiByBucket.set(bucket.bucketStart, bucket);
  return data.meetingBuckets.map((bucket) => ({
    bucketStart: bucket.bucketStart,
    meetings: bucket,
    ai: aiByBucket.get(bucket.bucketStart) ?? null,
  }));
}

/**
 * Whether the AI columns hold a measurement. When they do not, the report writes an em dash in
 * every one of them instead of a zero — the difference between "no AI call in this month" and
 * "this deployment cannot tell you" is the whole reason `ai.state` exists.
 */
function aiMeasured(data: UsageResponse): boolean {
  return data.ai.state === 'AVAILABLE';
}

/**
 * Builds the CSV, BOM included, CRLF line endings (RFC 4180).
 *
 * An unknown AI column is written as an EMPTY field, not as `0`. A spreadsheet sums an empty cell
 * as nothing and charts it as a gap, which is what "we cannot tell you" should look like; a zero
 * would be summed, averaged and plotted as a fact.
 */
export function usageToCsv(data: UsageResponse): string {
  const measured = aiMeasured(data);
  const unknown = '';
  const lines = [CSV_COLUMNS.map(escapeCsvField).join(',')];
  for (const row of rows(data)) {
    lines.push(
      [
        escapeCsvField(periodLabel(row.bucketStart, data.granularity)),
        escapeCsvField(String(row.meetings.meetings)),
        escapeCsvField(String(row.meetings.analysedMeetings)),
        escapeCsvField(measured ? String(row.ai?.calls ?? 0) : unknown),
        escapeCsvField(measured ? String(row.ai?.promptTokens ?? 0) : unknown),
        escapeCsvField(measured ? String(row.ai?.completionTokens ?? 0) : unknown),
        escapeCsvField(measured ? formatCost(row.ai?.costUsd) : unknown),
      ].join(','),
    );
  }
  // Trailing CRLF: RFC 4180 allows the last record to end with one, and every parser accepts it.
  return `${CSV_BOM}${lines.join('\r\n')}\r\n`;
}

/** Builds the consolidated Markdown report for the resolved range. */
export function usageToMarkdown(data: UsageResponse): string {
  const measured = aiMeasured(data);
  const unit = data.granularity === 'MONTH' ? 'mês' : 'semana';
  const lines: string[] = ['# Relatório de uso do período', ''];

  lines.push(
    [
      `**Período:** ${data.from.slice(0, 10)} a ${data.to.slice(0, 10)}`,
      `**Agrupamento:** por ${unit}`,
      `**Fuso do relatório:** ${data.timezone}`,
      `**Reuniões no período:** ${data.meetings} (${data.analysedMeetings} analisadas)`,
    ].join('  \n'),
    '',
  );

  if (data.dataState !== 'OK') {
    lines.push(
      data.dataState === 'NO_DATA'
        ? '> Nenhuma reunião e nenhuma chamada de IA no período. Ainda não há dado — o que não é o mesmo que atividade igual a zero.'
        : '> Há reuniões no período, mas nenhuma foi analisada ainda. Os números abaixo são consequência disso, não de inatividade.',
      '',
    );
  }

  lines.push('## Por período', '');
  lines.push(`| Período | Reuniões | Analisadas | Chamadas de IA | Tokens | Custo estimado |`);
  lines.push(`| --- | ---: | ---: | ---: | ---: | ---: |`);
  for (const row of rows(data)) {
    const tokens = measured
      ? String((row.ai?.promptTokens ?? 0) + (row.ai?.completionTokens ?? 0))
      : '—';
    lines.push(
      [
        '',
        periodLabel(row.bucketStart, data.granularity),
        String(row.meetings.meetings),
        String(row.meetings.analysedMeetings),
        measured ? String(row.ai?.calls ?? 0) : '—',
        tokens,
        measured ? `US$ ${formatCost(row.ai?.costUsd)}` : '—',
        '',
      ].join(' | '),
    );
  }
  lines.push('');

  lines.push('## Consumo de IA', '');
  if (!measured) {
    lines.push(
      data.ai.state === 'WITHHELD_RESTRICTED_SCOPE'
        ? '> Não informado neste relatório. Suas permissões distinguem reuniões, e o registro de consumo de IA não guarda a qual reunião cada chamada pertence — um total do workspace incluiria reuniões que você não pode abrir. Zero aqui seria uma resposta errada, então não há resposta.'
        : '> Não disponível. O plano de controle que registra as chamadas de IA está desligado ou indisponível nesta instalação, então este relatório não pode afirmar nada sobre consumo — nem que houve, nem que não houve.',
      '',
    );
  } else {
    lines.push(
      [
        `**Chamadas:** ${data.ai.calls}`,
        `**Tokens de entrada:** ${data.ai.promptTokens}`,
        `**Tokens de saída:** ${data.ai.completionTokens}`,
        `**Custo estimado:** US$ ${formatCost(data.ai.costUsd)}`,
      ].join('  \n'),
      '',
    );
    if (data.ai.byService.length > 0) {
      lines.push(`| Serviço | Chamadas | Tokens | Custo estimado |`);
      lines.push(`| --- | ---: | ---: | ---: |`);
      for (const s of data.ai.byService) {
        const tokens = s.promptTokens + s.completionTokens;
        lines.push(
          [
            '',
            serviceLabel(s.service),
            String(s.calls),
            s.metered ? String(tokens) : '—',
            s.metered ? `US$ ${formatCost(s.costUsd)}` : 'não medido',
            '',
          ].join(' | '),
        );
      }
      lines.push('');
    }
  }

  lines.push('## Como ler estes números', '');
  const notes: string[] = [
    'O custo é uma **estimativa**: preço de tabela do catálogo de modelos multiplicado pelos tokens medidos. Não é uma fatura, e não inclui o que a NORA paga por infraestrutura.',
    'As semanas e os meses são fechados no fuso indicado acima, e não em UTC — uma reunião de sexta à noite conta no período em que aconteceu.',
  ];
  if (measured && data.ai.unmeteredCalls > 0) {
    notes.push(
      `${data.ai.unmeteredCalls} das ${data.ai.calls} chamadas são sessões de transcrição ao vivo. O áudio não passa pela infraestrutura da NORA, então o que se registra é uma **sessão aberta**, nunca um minuto transcrito: essas chamadas não têm tokens nem custo medido, e entram no total de chamadas e em nenhum outro número.`,
    );
  }
  if (data.scopeStrategy === 'PER_MEETING_FILTER') {
    notes.push(
      'As contagens de reuniões cobrem apenas as reuniões que você tem permissão para abrir, e não o workspace inteiro.',
    );
  }
  for (const note of notes) lines.push(`- ${note}`);

  lines.push('', '---', '', `_Gerado pelo Nora em ${nowInPtBr()}._`, '');

  return lines.join('\n');
}

/**
 * File name: `nora-uso-<inicio>-a-<fim>.<csv|md>`, on the model of `taskExportFileName`. The dates
 * are the resolved range and not the export date, because unlike a task list this report IS a
 * period — two files downloaded on the same day for different ranges must not collide.
 */
export function usageReportFileName(format: 'csv' | 'md', data: UsageResponse): string {
  const start = slugify(data.from.slice(0, 10)) || 'inicio';
  const end = slugify(data.to.slice(0, 10)) || 'fim';
  return `nora-uso-${start}-a-${end}.${format}`;
}

function nowInPtBr(): string {
  return new Date().toLocaleString('pt-BR', { dateStyle: 'long', timeStyle: 'short' });
}
