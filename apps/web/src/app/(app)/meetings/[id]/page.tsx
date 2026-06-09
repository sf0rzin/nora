import Link from "next/link";
import type { Route } from "next";
import { notFound } from "next/navigation";

import { ApiRequestError, getMeeting } from "@/lib/api/client";
import type { ActionItem, Decision, MeetingDetail, Opportunity, Risk } from "@/lib/api/types";
import { formatDateTime } from "@/lib/utils";
import { MarkdownContent } from "@/components/markdown-content";
import MeetingProductivitySection from "@/components/meeting-productivity-section";
import CustomerConfidenceCard from "@/components/customer-confidence-card";
import { MeetingDangerZone, ReprocessButton } from "@/components/meeting-actions";

export const dynamic = "force-dynamic";

const STATUS_META: Record<string, { label: string; color: string }> = {
  PENDING: { label: "Na fila", color: "var(--muted)" },
  PROCESSING: { label: "Analisando…", color: "var(--accent-ink)" },
  COMPLETED: { label: "Analisada", color: "var(--success)" },
  FAILED: { label: "Falhou", color: "var(--danger)" },
};

const PRIORITY_COLOR: Record<string, string> = {
  HIGH: "var(--danger)",
  MEDIUM: "var(--warn)",
  LOW: "var(--muted)",
};

function durationLabel(seconds?: number): string | null {
  if (!seconds || seconds <= 0) return null;
  return `${Math.round(seconds / 60)}min`;
}

export default async function MeetingDetailPage({ params }: { params: Promise<{ id: string }> }) {
  const { id } = await params;
  let meeting: MeetingDetail | undefined;
  try {
    meeting = await getMeeting(id);
  } catch (err) {
    // §3.4 — só some pra "não existe" em 404 real; outros erros (ex: 500)
    // sobem pro error boundary em vez de mascarar a falha como ausência.
    if (err instanceof ApiRequestError && err.status === 404) {
      notFound();
    }
    throw err;
  }
  if (!meeting) notFound();

  const a = meeting.analysis;
  const duration = durationLabel(meeting.durationSeconds);
  const statusMeta = STATUS_META[meeting.processingStatus] ?? {
    label: meeting.processingStatus,
    color: "var(--accent-ink)",
  };

  // PII Shield: se o worker reportar a contagem de redações aplicadas, mostra o
  // número real; senão mantém o selo genérico de "aplicado". (Campo opcional,
  // ainda não tipado em MeetingAnalysis.)
  const piiCount =
    a && typeof (a as { metadata?: { piiRedactionsApplied?: number } }).metadata?.piiRedactionsApplied === "number"
      ? (a as { metadata?: { piiRedactionsApplied?: number } }).metadata!.piiRedactionsApplied!
      : null;

  return (
    <div className="page page--narrow">
      <div style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12, color: "var(--muted)", marginBottom: 22 }}>
        <Link href={"/dashboard" as Route} style={{ color: "var(--muted)" }}>
          Reuniões
        </Link>
        <span style={{ opacity: 0.5 }}>/</span>
        <span style={{ color: statusMeta.color }}>{statusMeta.label}</span>
      </div>

      <div style={{ display: "flex", alignItems: "flex-start", gap: 32, marginBottom: 28 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <h1
            style={{
              fontFamily: "var(--display)",
              fontWeight: 500,
              fontSize: 26,
              letterSpacing: "-0.022em",
              lineHeight: 1.2,
              color: "var(--ink)",
              margin: "0 0 14px",
            }}
          >
            {meeting.title}
          </h1>
          <div style={{ display: "flex", alignItems: "center", gap: 6, flexWrap: "wrap" }}>
            {meeting.participants.map((p, i) => (
              <span key={i} className="chip chip--lg">
                {p.displayName}
              </span>
            ))}
            {meeting.participants.length === 0 && (
              <span style={{ fontSize: 12, color: "var(--muted)" }}>Owner: {meeting.owner.displayName}</span>
            )}
          </div>
        </div>
        <div style={{ fontSize: 12, color: "var(--muted)", textAlign: "right", whiteSpace: "nowrap", flexShrink: 0, paddingTop: 6 }}>
          {formatDateTime(meeting.startedAt)}
          {duration ? ` · ${duration}` : ""}
        </div>
      </div>

      {!a && (
        <div className="card card--pad" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          {meeting.processingStatus === "PROCESSING" ? (
            <>
              <div style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 14, color: "var(--ink)" }}>
                <span className="status-dot status-dot--processing" />A NORA está analisando esta reunião.
              </div>
              <p style={{ fontSize: 13, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
                Resumo, decisões e action items aparecem aqui assim que a análise terminar.
              </p>
              <div style={{ display: "flex", flexDirection: "column", gap: 10, marginTop: 6 }}>
                <div className="skel" style={{ height: 14, width: "88%" }} />
                <div className="skel" style={{ height: 14, width: "72%" }} />
                <div className="skel" style={{ height: 14, width: "80%" }} />
              </div>
            </>
          ) : meeting.processingStatus === "FAILED" ? (
            <>
              <div style={{ fontSize: 14, color: "var(--ink)" }}>A análise desta reunião falhou.</div>
              <p style={{ fontSize: 13, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
                A transcrição foi recebida, mas o processamento não terminou. Você pode reprocessar agora — nada foi perdido.
              </p>
              <div>
                <ReprocessButton meetingId={meeting.id} label="Reprocessar análise" />
              </div>
            </>
          ) : (
            <>
              <div style={{ fontSize: 14, color: "var(--ink)" }}>Esta reunião ainda não foi analisada.</div>
              <p style={{ fontSize: 13, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
                A NORA vai processar a transcrição e gerar resumo, decisões e action items. Você pode disparar a análise agora.
              </p>
              <div>
                <ReprocessButton meetingId={meeting.id} label="Analisar agora" />
              </div>
            </>
          )}
        </div>
      )}

      {a && (
        <>
          <Section label="Resumo">
            {a.summary && a.summary.trim().length > 0 ? (
              <div style={{ fontSize: 14.5, lineHeight: 1.65, color: "var(--ink)" }}>
                <MarkdownContent className="nora-prose">{a.summary}</MarkdownContent>
              </div>
            ) : (
              <p style={{ color: "var(--muted)", margin: 0 }}>Resumo não disponível.</p>
            )}
          </Section>

          {a.decisions.length > 0 && (
            <Section label="Decisões" right={`${a.decisions.length}`}>
              <ul style={{ margin: 0, padding: 0, listStyle: "none", display: "flex", flexDirection: "column", gap: 10 }}>
                {a.decisions.map((d: Decision, i) => (
                  <li key={d.id ?? i} style={{ display: "flex", gap: 12, fontSize: 14.5, lineHeight: 1.55, color: "var(--ink)" }}>
                    <span
                      style={{
                        color: "var(--accent-ink)",
                        fontSize: 11.5,
                        fontWeight: 500,
                        letterSpacing: "0.04em",
                        marginTop: 3,
                        minWidth: 18,
                        fontVariantNumeric: "tabular-nums",
                      }}
                    >
                      {String(i + 1).padStart(2, "0")}
                    </span>
                    <span style={{ flex: 1 }}>
                      {d.text}
                      {typeof d.confidence === "number" && (
                        <span style={{ marginLeft: 8, fontSize: 11, color: "var(--muted)" }}>· conf. {(d.confidence * 100).toFixed(0)}%</span>
                      )}
                    </span>
                  </li>
                ))}
              </ul>
            </Section>
          )}

          {a.actionItems.length > 0 && (
            <Section label="Action items" right={`${a.actionItems.length} detectados`}>
              <div style={{ display: "flex", flexDirection: "column" }}>
                {a.actionItems.map((t: ActionItem, i) => {
                  const done = t.status === "DONE";
                  return (
                    <div key={t.id ?? i} style={{ borderTop: i === 0 ? "none" : "1px solid var(--border)", padding: "12px 0" }}>
                      <div
                        style={{
                          fontSize: 14,
                          color: done ? "var(--muted)" : "var(--ink)",
                          lineHeight: 1.4,
                          textDecoration: done ? "line-through" : "none",
                        }}
                      >
                        {t.title}
                      </div>
                      <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 4, fontSize: 11.5, color: "var(--muted)", flexWrap: "wrap" }}>
                        <span>{t.assignee ?? "Sem responsável"}</span>
                        {t.dueDate && <span>vence {t.dueDate}</span>}
                        <span style={{ color: PRIORITY_COLOR[t.priority] ?? "var(--muted)", letterSpacing: "0.04em" }}>{t.priority}</span>
                      </div>
                      {t.sourceQuote && (
                        <div className="quote" style={{ marginTop: 8 }}>
                          {t.sourceQuote}
                        </div>
                      )}
                    </div>
                  );
                })}
                <div style={{ paddingTop: 12, borderTop: "1px solid var(--border)" }}>
                  <Link
                    href={"/tasks" as Route}
                    style={{ fontSize: 12.5, color: "var(--accent-ink)", display: "inline-flex", alignItems: "center", gap: 5 }}
                  >
                    Ver em Action items
                    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                      <line x1="5" y1="12" x2="19" y2="12" />
                      <polyline points="12 5 19 12 12 19" />
                    </svg>
                  </Link>
                </div>
              </div>
            </Section>
          )}

          {a.risks.length > 0 && (
            <Section label="Riscos" right={`${a.risks.length}`}>
              <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
                {a.risks.map((r: Risk, i) => (
                  <SignalRow key={r.id ?? i} text={r.text} tag={`${r.severity} · ${r.category}`} color="var(--danger)" quote={r.sourceQuote} />
                ))}
              </div>
            </Section>
          )}

          {a.opportunities.length > 0 && (
            <Section label="Oportunidades" right={`${a.opportunities.length}`}>
              <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
                {a.opportunities.map((o: Opportunity, i) => (
                  <SignalRow key={o.id ?? i} text={o.text} tag={`${o.estimatedValue} · ${o.category}`} color="var(--success)" quote={o.sourceQuote} />
                ))}
              </div>
            </Section>
          )}

          {a.topics.length > 0 && (
            <Section label="Tópicos">
              <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
                {a.topics.map((t) => (
                  <span key={t} className="chip chip--lg">
                    {t}
                  </span>
                ))}
              </div>
            </Section>
          )}
        </>
      )}

      {meeting.customerConfidence && (
        <Section label="Confiança do cliente">
          <CustomerConfidenceCard confidence={meeting.customerConfidence ?? null} />
        </Section>
      )}

      <div style={{ marginTop: 8 }}>
        <MeetingProductivitySection meetingId={meeting.id} goal={meeting.goal ?? null} productivity={meeting.productivity ?? null} />
      </div>

      <div style={{ marginTop: 40, paddingTop: 18, borderTop: "1px solid var(--border)" }}>
        <h2 className="sec-label" style={{ marginBottom: 14 }}>
          Ações
        </h2>
        <MeetingDangerZone meetingId={meeting.id} title={meeting.title} canReprocess={Boolean(a)} />
      </div>

      <div style={{ marginTop: 40, paddingTop: 18, borderTop: "1px solid var(--border)", display: "flex", alignItems: "center", gap: 14, fontSize: 11, color: "var(--muted)" }}>
        <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
          <span className="status-dot" style={{ background: "var(--success)", width: 6, height: 6 }} />
          {piiCount !== null
            ? `PII Shield · ${piiCount} ${piiCount === 1 ? "dado sensível redigido" : "dados sensíveis redigidos"}`
            : "PII Shield aplicado"}
        </span>
      </div>
    </div>
  );
}

function Section({ label, right, children }: { label: string; right?: string; children: React.ReactNode }) {
  return (
    <section className="section">
      <div className="section-head">
        <h2 className="sec-label">{label}</h2>
        {right && <span className="section-count">{right}</span>}
      </div>
      {children}
    </section>
  );
}

function SignalRow({ text, tag, color, quote }: { text: string; tag: string; color: string; quote?: string }) {
  return (
    <div>
      <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 12 }}>
        <span style={{ fontSize: 14, color: "var(--ink)", lineHeight: 1.5 }}>{text}</span>
        <span
          style={{
            fontSize: 10.5,
            fontWeight: 500,
            color,
            letterSpacing: "0.04em",
            whiteSpace: "nowrap",
            flexShrink: 0,
            marginTop: 2,
            textTransform: "uppercase",
          }}
        >
          {tag}
        </span>
      </div>
      {quote && (
        <div className="quote" style={{ marginTop: 6 }}>
          {quote}
        </div>
      )}
    </div>
  );
}
