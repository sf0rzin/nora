"use client";

import type { MeetingDetail } from "@/lib/api/types";
import { Button } from "@/components/core/ui";

function downloadText(filename: string, content: string, mime: string) {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
}

function buildReportMarkdown(meeting: MeetingDetail): string {
  const lines: string[] = [];

  lines.push(`# ${meeting.title}`, "");

  const metaParts = [
    new Date(meeting.startedAt).toLocaleString("pt-BR"),
    `Owner: ${meeting.owner.displayName}`,
    meeting.participants.length > 0
      ? `${meeting.participants.length} participantes`
      : null,
  ].filter(Boolean);
  lines.push(`_${metaParts.join("  ·  ")}_`, "");

  const a = meeting.analysis;
  if (a) {
    if (a.summary && a.summary.trim().length > 0) {
      lines.push("## Resumo", "", a.summary.trim(), "");
    }

    if (a.decisions.length > 0) {
      lines.push("## Decisões", "");
      for (const d of a.decisions) {
        lines.push(`- ${d.text} (confiança ${(d.confidence * 100).toFixed(0)}%)`);
      }
      lines.push("");
    }

    if (a.actionItems.length > 0) {
      lines.push("## Action items", "");
      for (const t of a.actionItems) {
        const extra = [
          `prioridade ${t.priority}`,
          t.assignee ? t.assignee : null,
          t.dueDate ? `vence ${t.dueDate}` : null,
        ].filter(Boolean);
        lines.push(`- [ ] ${t.title} — ${extra.join(", ")}`);
      }
      lines.push("");
    }

    if (a.risks.length > 0) {
      lines.push("## Riscos", "");
      for (const r of a.risks) {
        lines.push(`- ${r.text} (${r.severity} · ${r.category})`);
      }
      lines.push("");
    }

    if (a.opportunities.length > 0) {
      lines.push("## Oportunidades", "");
      for (const o of a.opportunities) {
        lines.push(`- ${o.text} (${o.estimatedValue} · ${o.category})`);
      }
      lines.push("");
    }

    if (a.topics.length > 0) {
      lines.push("## Tópicos", "", a.topics.join(", "), "");
    }
  }

  const p = meeting.productivity;
  if (p) {
    lines.push("## Produtividade", "");
    lines.push(`Score: ${p.score} (${p.band})`);
    if (p.rationale) lines.push("", p.rationale);
    if (p.coverage.length > 0) {
      lines.push("");
      for (const c of p.coverage) {
        const ev = c.evidence ? ` — ${c.evidence}` : "";
        lines.push(`- ${c.expectedOutcome}: ${c.status}${ev}`);
      }
    }
    lines.push("", "_Indicador da reunião, não dos participantes._", "");
  }

  const c = meeting.customerConfidence;
  if (c) {
    lines.push("## Confiança do cliente", "");
    const trend = c.trend ? ` · tendência ${c.trend}` : "";
    lines.push(`Score: ${c.score} (${c.band})${trend}`);
    if (c.rationale) lines.push("", c.rationale);
    if (c.buyingSignals.length > 0) {
      lines.push("", "### Sinais de compra");
      for (const s of c.buyingSignals) {
        lines.push(`- ${s.type}: ${s.quote}`);
      }
    }
    if (c.objections.length > 0) {
      lines.push("", "### Objeções");
      for (const o of c.objections) {
        const comp = o.competitor ? ` · ${o.competitor}` : "";
        lines.push(`- ${o.type} (${o.severity}${comp}): ${o.quote}`);
      }
    }
    lines.push("");
  }

  return lines.join("\n");
}

export default function ExportReportButton({ meeting }: { meeting: MeetingDetail }) {
  function onExport() {
    downloadText(
      `relatorio-${meeting.id.slice(0, 8)}.md`,
      buildReportMarkdown(meeting),
      "text/markdown;charset=utf-8",
    );
  }

  return (
    <Button size="sm" onClick={onExport} title="Exportar relatório da reunião em Markdown">
      Exportar relatório
    </Button>
  );
}
