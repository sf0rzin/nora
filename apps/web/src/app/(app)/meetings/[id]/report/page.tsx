import Link from "next/link";
import type { Route } from "next";
import { notFound } from "next/navigation";

import { ApiRequestError, getMeeting } from "@/lib/api/client";
import type { MeetingDetail, Severity } from "@/lib/api/types";
import { formatDateTime } from "@/lib/utils";
import { MarkdownContent } from "@/components/markdown-content";
import PrintButton from "./print-button";

export const dynamic = "force-dynamic";

/**
 * Paper version of the meeting report — print route (GOAL item 14).
 *
 * Same data as the detail, clean A4 layout with print CSS: the user clicks
 * "Imprimir / salvar como PDF" and saves via the native dialog. The shell
 * chrome (.side/.mhead/.drawer) is hidden via a <style> scoped to the route —
 * on screen and in print — for a clean render without restructuring the (app)
 * layout.
 */

const SEVERITY_LABEL: Record<Severity, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

const BAND_LABEL: Record<string, string> = {
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

const REPORT_CSS = `
/* Rota de impressão: esconde o chrome do shell (em tela e no print). */
.side, .mhead, .drawer, .drawer-veil { display: none !important; }
.app { height: auto !important; min-height: 0 !important; }
.app-main { height: auto !important; overflow: visible !important; }

@media print {
  @page { size: A4; margin: 16mm; }
  .report-toolbar { display: none !important; }
  .report-root { max-width: none !important; padding: 0 !important; }
  .report-paper { border: none !important; box-shadow: none !important; border-radius: 0 !important; padding: 0 !important; }
  .report-sec { break-inside: avoid; }
}
`;

function durationLabel(seconds?: number): string | null {
  if (!seconds || seconds <= 0) return null;
  return `${Math.round(seconds / 60)}min`;
}

function ReportSection({ label, children }: { label: string; children: React.ReactNode }) {
  return (
    <section className="report-sec" style={{ marginTop: 28 }}>
      <h2 className="sec-label" style={{ marginBottom: 10, paddingBottom: 6, borderBottom: "1px solid var(--border)" }}>
        {label}
      </h2>
      {children}
    </section>
  );
}

export default async function MeetingReportPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  let meeting: MeetingDetail | undefined;
  try {
    meeting = await getMeeting(id);
  } catch (err) {
    if (err instanceof ApiRequestError && err.status === 404) {
      notFound();
    }
    throw err;
  }
  if (!meeting) notFound();

  const a = meeting.analysis;
  const tags = meeting.tags ?? [];
  const duration = durationLabel(meeting.durationSeconds);
  const generatedAt = formatDateTime(new Date().toISOString());

  const metaParts: string[] = [formatDateTime(meeting.startedAt)];
  if (duration) metaParts.push(duration);
  if (meeting.participants.length > 0) {
    metaParts.push(meeting.participants.map((p) => p.displayName).join(", "));
  }

  return (
    <>
      <style dangerouslySetInnerHTML={{ __html: REPORT_CSS }} />

      <div className="report-root" style={{ maxWidth: 860, margin: "0 auto", padding: "28px 24px 64px" }}>
        <div
          className="report-toolbar"
          style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, marginBottom: 18 }}
        >
          <Link href={`/meetings/${meeting.id}` as Route} className="btn btn-ghost btn-sm">
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <line x1="19" y1="12" x2="5" y2="12" />
              <polyline points="12 19 5 12 12 5" />
            </svg>
            Voltar ao detalhe
          </Link>
          <PrintButton />
        </div>

        <article
          className="report-paper"
          style={{
            background: "var(--surface)",
            border: "1px solid var(--border)",
            borderRadius: 12,
            padding: "44px 52px 40px",
            boxShadow: "0 10px 32px -18px rgba(15, 23, 42, 0.18)",
          }}
        >
          {/* Discreet NORA header */}
          <div
            style={{
              display: "flex",
              alignItems: "baseline",
              justifyContent: "space-between",
              gap: 12,
              paddingBottom: 12,
              marginBottom: 24,
              borderBottom: "1px solid var(--border)",
            }}
          >
            <span style={{ fontFamily: "var(--sans)", fontWeight: 500, fontSize: 14, letterSpacing: "-0.02em", color: "var(--ink)" }}>
              Nora <span style={{ fontWeight: 400, color: "var(--muted)" }}>· Relatório de reunião</span>
            </span>
            <span style={{ fontSize: 10.5, color: "var(--muted)" }}>{generatedAt}</span>
          </div>

          <h1
            style={{
              fontFamily: "var(--display)",
              fontWeight: 500,
              fontSize: 24,
              letterSpacing: "-0.022em",
              lineHeight: 1.25,
              color: "var(--ink)",
              margin: "0 0 8px",
            }}
          >
            {meeting.title}
          </h1>
          <div style={{ fontSize: 12, color: "var(--muted)", lineHeight: 1.6 }}>{metaParts.join(" · ")}</div>
          {tags.length > 0 && (
            <div style={{ display: "flex", flexWrap: "wrap", gap: 6, marginTop: 10 }}>
              {tags.map((t) => (
                <span key={t} className="chip">
                  {t}
                </span>
              ))}
            </div>
          )}

          {!a && (
            <p style={{ marginTop: 28, fontSize: 13.5, color: "var(--muted)", lineHeight: 1.6 }}>
              Esta reunião ainda não foi analisada pela Nora — o relatório completo fica disponível assim que a
              análise terminar.
            </p>
          )}

          {a && (
            <>
              {a.summary?.trim() && (
                <ReportSection label="Resumo">
                  <div style={{ fontSize: 13.5, lineHeight: 1.65, color: "var(--ink)" }}>
                    <MarkdownContent className="nora-prose">{a.summary}</MarkdownContent>
                  </div>
                  {a.sentimentOverall && (
                    <div style={{ marginTop: 8, fontSize: 11.5, color: "var(--muted)" }}>
                      Sentimento geral: {SENTIMENT_LABEL[a.sentimentOverall] ?? a.sentimentOverall}
                    </div>
                  )}
                </ReportSection>
              )}

              {a.decisions.length > 0 && (
                <ReportSection label={`Decisões (${a.decisions.length})`}>
                  <ol style={{ margin: 0, paddingLeft: 20, display: "flex", flexDirection: "column", gap: 6 }}>
                    {a.decisions.map((d, i) => (
                      <li key={d.id ?? i} style={{ fontSize: 13.5, lineHeight: 1.55, color: "var(--ink)" }}>
                        {d.text}
                        {typeof d.confidence === "number" && (
                          <span style={{ marginLeft: 6, fontSize: 11, color: "var(--muted)" }}>
                            (confiança {(d.confidence * 100).toFixed(0)}%)
                          </span>
                        )}
                      </li>
                    ))}
                  </ol>
                </ReportSection>
              )}

              {a.actionItems.length > 0 && (
                <ReportSection label={`Action items (${a.actionItems.length})`}>
                  <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                    {a.actionItems.map((t, i) => (
                      <div key={t.id ?? i}>
                        <div style={{ fontSize: 13.5, color: "var(--ink)", lineHeight: 1.45 }}>
                          {t.status === "DONE" ? "☑" : "☐"} {t.title}
                        </div>
                        <div style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 2 }}>
                          {t.assignee ?? "Sem responsável"} · prioridade {SEVERITY_LABEL[t.priority] ?? t.priority}
                          {t.dueDate ? ` · vence ${t.dueDate}` : ""}
                        </div>
                      </div>
                    ))}
                  </div>
                </ReportSection>
              )}

              {a.risks.length > 0 && (
                <ReportSection label={`Riscos (${a.risks.length})`}>
                  <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                    {a.risks.map((r, i) => (
                      <div key={r.id ?? i}>
                        <div style={{ fontSize: 13.5, color: "var(--ink)", lineHeight: 1.5 }}>
                          <strong style={{ fontWeight: 500 }}>
                            {SEVERITY_LABEL[r.severity] ?? r.severity} · {r.category}
                          </strong>{" "}
                          — {r.text}
                        </div>
                        {r.sourceQuote && (
                          <div className="quote" style={{ marginTop: 5 }}>
                            {r.sourceQuote}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </ReportSection>
              )}

              {a.opportunities.length > 0 && (
                <ReportSection label={`Oportunidades (${a.opportunities.length})`}>
                  <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                    {a.opportunities.map((o, i) => (
                      <div key={o.id ?? i}>
                        <div style={{ fontSize: 13.5, color: "var(--ink)", lineHeight: 1.5 }}>
                          <strong style={{ fontWeight: 500 }}>
                            {SEVERITY_LABEL[o.estimatedValue] ?? o.estimatedValue} · {o.category}
                          </strong>{" "}
                          — {o.text}
                        </div>
                        {o.sourceQuote && (
                          <div className="quote" style={{ marginTop: 5 }}>
                            {o.sourceQuote}
                          </div>
                        )}
                      </div>
                    ))}
                  </div>
                </ReportSection>
              )}

              {a.topics.length > 0 && (
                <ReportSection label="Tópicos">
                  <div style={{ display: "flex", flexWrap: "wrap", gap: 6 }}>
                    {a.topics.map((t) => (
                      <span key={t} className="chip">
                        {t}
                      </span>
                    ))}
                  </div>
                </ReportSection>
              )}
            </>
          )}

          {meeting.productivity && (
            <ReportSection label="Productivity Score">
              <div style={{ fontSize: 13.5, color: "var(--ink)" }}>
                <strong style={{ fontWeight: 500 }}>
                  {meeting.productivity.score}/100 — Produtividade {BAND_LABEL[meeting.productivity.band] ?? meeting.productivity.band}
                </strong>
              </div>
              {meeting.productivity.rationale?.trim() && (
                <p style={{ margin: "6px 0 0", fontSize: 12.5, color: "var(--muted)", lineHeight: 1.6 }}>
                  {meeting.productivity.rationale}
                </p>
              )}
            </ReportSection>
          )}

          {meeting.customerConfidence && (
            <ReportSection label="Confiança do cliente">
              <div style={{ fontSize: 13.5, color: "var(--ink)" }}>
                <strong style={{ fontWeight: 500 }}>
                  {meeting.customerConfidence.score}/100 — Confiança {BAND_LABEL[meeting.customerConfidence.band] ?? meeting.customerConfidence.band}
                  {meeting.customerConfidence.trend
                    ? ` (${TREND_LABEL[meeting.customerConfidence.trend] ?? meeting.customerConfidence.trend})`
                    : ""}
                </strong>
                {meeting.customerConfidence.accountName ? (
                  <span style={{ color: "var(--muted)" }}> · conta: {meeting.customerConfidence.accountName}</span>
                ) : null}
              </div>
              {meeting.customerConfidence.rationale?.trim() && (
                <p style={{ margin: "6px 0 0", fontSize: 12.5, color: "var(--muted)", lineHeight: 1.6 }}>
                  {meeting.customerConfidence.rationale}
                </p>
              )}
            </ReportSection>
          )}

          <div style={{ marginTop: 36, paddingTop: 12, borderTop: "1px solid var(--border)", fontSize: 10.5, color: "var(--muted)" }}>
            Gerado pelo Nora em {generatedAt} · PII Shield aplicado a esta análise.
          </div>
        </article>
      </div>
    </>
  );
}
