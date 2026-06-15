/**
 * Relatório de reunião em Markdown — helper puro (sem DOM, sem fetch).
 *
 * `meetingToMarkdown(detail)` monta o relatório completo a partir do
 * MeetingDetail já carregado: título, data, tags, resumo, decisões, action
 * items, riscos, oportunidades, tópicos, Productivity Score e Confiança do
 * cliente (quando houver) + rodapé "Gerado pelo NORA". Usado pelo export
 * client-side do detalhe da reunião (download via Blob).
 */

import type { MeetingDetail, Severity } from "@/lib/api/types";

const BAND_LABEL: Record<string, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

const SEVERITY_LABEL: Record<Severity, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

const TREND_LABEL: Record<string, string> = {
  IMPROVING: "em melhora",
  STABLE: "estável",
  DECLINING: "em queda",
};

const SENTIMENT_LABEL: Record<string, string> = {
  POSITIVE: "Positivo",
  NEUTRAL: "Neutro",
  NEGATIVE: "Negativo",
  MIXED: "Misto",
};

function formatDateTimePt(iso?: string): string {
  if (!iso) return "";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString("pt-BR", { dateStyle: "long", timeStyle: "short" });
}

function durationLabel(seconds?: number): string | null {
  if (!seconds || seconds <= 0) return null;
  return `${Math.round(seconds / 60)}min`;
}

/** Slug seguro pra nome de arquivo: minúsculas, sem acentos, hífens. */
export function slugify(text: string): string {
  return text
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "")
    .slice(0, 60);
}

/** Nome do arquivo: `nora-reuniao-<slug>-<yyyy-mm-dd>.md` (data da reunião). */
export function meetingReportFileName(detail: MeetingDetail): string {
  const slug = slugify(detail.title) || "reuniao";
  const day =
    (detail.startedAt ?? "").slice(0, 10) || new Date().toISOString().slice(0, 10);
  return `nora-reuniao-${slug}-${day}.md`;
}

/** Gera o relatório completo da reunião em Markdown (GFM). */
export function meetingToMarkdown(detail: MeetingDetail): string {
  const a = detail.analysis;
  const lines: string[] = [];

  lines.push(`# ${detail.title}`, "");

  const meta: string[] = [];
  const when = formatDateTimePt(detail.startedAt);
  if (when) meta.push(`**Data:** ${when}`);
  const duration = durationLabel(detail.durationSeconds);
  if (duration) meta.push(`**Duração:** ${duration}`);
  if (detail.participants.length > 0) {
    meta.push(`**Participantes:** ${detail.participants.map((p) => p.displayName).join(", ")}`);
  }
  const tags = detail.tags ?? [];
  if (tags.length > 0) meta.push(`**Tags:** ${tags.map((t) => `\`${t}\``).join(" ")}`);
  if (a?.sentimentOverall) {
    meta.push(`**Sentimento geral:** ${SENTIMENT_LABEL[a.sentimentOverall] ?? a.sentimentOverall}`);
  }
  if (meta.length > 0) lines.push(meta.join("  \n"), "");

  if (!a) {
    lines.push("> Esta reunião ainda não foi analisada pela Nora.", "");
  } else {
    if (a.summary?.trim()) {
      lines.push("## Resumo", "", a.summary.trim(), "");
    }

    if (a.decisions.length > 0) {
      lines.push("## Decisões", "");
      a.decisions.forEach((d, i) => {
        const conf =
          typeof d.confidence === "number" ? ` _(confiança ${(d.confidence * 100).toFixed(0)}%)_` : "";
        lines.push(`${i + 1}. ${d.text}${conf}`);
      });
      lines.push("");
    }

    if (a.actionItems.length > 0) {
      lines.push("## Action items", "");
      for (const t of a.actionItems) {
        const done = t.status === "DONE";
        const parts = [
          `responsável: ${t.assignee ?? "não definido"}`,
          `prioridade: ${SEVERITY_LABEL[t.priority] ?? t.priority}`,
        ];
        if (t.dueDate) parts.push(`vence: ${t.dueDate}`);
        lines.push(`- [${done ? "x" : " "}] **${t.title}** — ${parts.join(" · ")}`);
      }
      lines.push("");
    }

    if (a.risks.length > 0) {
      lines.push("## Riscos", "");
      for (const r of a.risks) {
        lines.push(`- **${SEVERITY_LABEL[r.severity] ?? r.severity} · ${r.category}** — ${r.text}`);
        if (r.sourceQuote) lines.push(`  > ${r.sourceQuote}`);
      }
      lines.push("");
    }

    if (a.opportunities.length > 0) {
      lines.push("## Oportunidades", "");
      for (const o of a.opportunities) {
        lines.push(
          `- **${SEVERITY_LABEL[o.estimatedValue] ?? o.estimatedValue} · ${o.category}** — ${o.text}`,
        );
        if (o.sourceQuote) lines.push(`  > ${o.sourceQuote}`);
      }
      lines.push("");
    }

    if (a.topics.length > 0) {
      lines.push("## Tópicos", "", a.topics.map((t) => `\`${t}\``).join(" · "), "");
    }
  }

  if (detail.productivity) {
    const p = detail.productivity;
    lines.push(
      "## Productivity Score",
      "",
      `**${p.score}/100 — Produtividade ${BAND_LABEL[p.band] ?? p.band}**`,
      "",
    );
    if (p.rationale?.trim()) lines.push(p.rationale.trim(), "");
  }

  if (detail.customerConfidence) {
    const c = detail.customerConfidence;
    const trend = c.trend ? ` (${TREND_LABEL[c.trend] ?? c.trend})` : "";
    const account = c.accountName ? ` — conta: ${c.accountName}` : "";
    lines.push(
      "## Confiança do cliente",
      "",
      `**${c.score}/100 — Confiança ${BAND_LABEL[c.band] ?? c.band}${trend}**${account}`,
      "",
    );
    if (c.rationale?.trim()) lines.push(c.rationale.trim(), "");
  }

  lines.push("---", "", `_Gerado pelo Nora em ${formatDateTimePt(new Date().toISOString())}._`, "");

  return lines.join("\n");
}
