import Link from "next/link";
import type { Route } from "next";
import { notFound } from "next/navigation";

import { getMeeting } from "@/lib/api/client";
import type { ActionItem, Decision, MeetingDetail, Opportunity, Risk } from "@/lib/api/types";
import { formatDateTime } from "@/lib/utils";
import { MarkdownContent } from "@/components/markdown-content";
import MeetingProductivitySection from "@/components/meeting-productivity-section";
import CustomerConfidenceCard from "@/components/customer-confidence-card";
import { ShaderOrb } from "@/components/brand/shader-orb";

export const dynamic = "force-dynamic";

const STATUS_LABEL: Record<string, string> = {
  PENDING: "Na fila",
  PROCESSING: "Analisando…",
  COMPLETED: "Analisada",
  FAILED: "Falhou",
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

export default async function MeetingDetailPage({ params }: { params: { id: string } }) {
  let meeting: MeetingDetail | undefined;
  try {
    meeting = await getMeeting(params.id);
  } catch {
    notFound();
  }
  if (!meeting) notFound();

  const a = meeting.analysis;
  const duration = durationLabel(meeting.durationSeconds);
  const orbIntensity = meeting.productivity ? 0.55 + (meeting.productivity.score / 100) * 0.45 : 0.8;

  return (
    <div style={{ maxWidth: 760, margin: "0 auto", padding: "48px 40px 80px" }}>
      <div style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12, color: "var(--muted)", marginBottom: 22 }}>
        <Link href={"/dashboard" as Route} style={{ color: "var(--muted)" }}>
          Reuniões
        </Link>
        <span style={{ opacity: 0.5 }}>/</span>
        <span style={{ color: "var(--accent-ink)" }}>{STATUS_LABEL[meeting.processingStatus] ?? meeting.processingStatus}</span>
      </div>

      <div style={{ display: "flex", alignItems: "flex-start", gap: 32, marginBottom: 28 }}>
        <div style={{ flex: 1, minWidth: 0 }}>
          <h1 style={{ fontFamily: "var(--display)", fontWeight: 500, fontSize: 26, letterSpacing: "-0.022em", lineHeight: 1.2, color: "var(--ink)", margin: "0 0 14px" }}>
            {meeting.title}
          </h1>
          <div style={{ display: "flex", alignItems: "center", gap: 6, flexWrap: "wrap" }}>
            {meeting.participants.map((p, i) => (
              <span key={i} style={{ fontSize: 12, padding: "3px 9px", borderRadius: 999, background: "var(--chip)", color: "var(--ink)" }}>
                {p.displayName}
              </span>
            ))}
            {meeting.participants.length === 0 && (
              <span style={{ fontSize: 12, color: "var(--muted)" }}>Owner: {meeting.owner.displayName}</span>
            )}
          </div>
        </div>
        <div style={{ display: "flex", flexDirection: "column", alignItems: "flex-end", gap: 14, flexShrink: 0 }}>
          <div style={{ fontSize: 12, color: "var(--muted)", textAlign: "right", whiteSpace: "nowrap" }}>
            {formatDateTime(meeting.startedAt)}
            {duration ? ` · ${duration}` : ""}
          </div>
          <ShaderOrb size={72} speed={1} intensity={orbIntensity} />
        </div>
      </div>

      {!a && (
        <div style={{ padding: "16px 18px", borderRadius: 12, border: "1px solid var(--border)", background: "var(--chip)", fontSize: 14, color: "var(--muted)" }}>
          {meeting.processingStatus === "PROCESSING"
            ? "A NORA está analisando esta reunião. Volte em instantes."
            : meeting.processingStatus === "FAILED"
              ? "A análise desta reunião falhou. Tente reprocessar."
              : "Esta reunião ainda não foi analisada."}
        </div>
      )}

      {a && (
        <>
          <Section label="Resumo">
            {a.summary && a.summary.trim().length > 0 ? (
              <MarkdownContent className="nora-prose">{a.summary}</MarkdownContent>
            ) : (
              <p style={{ color: "var(--muted)", margin: 0 }}>Resumo não disponível.</p>
            )}
          </Section>

          {a.decisions.length > 0 && (
            <Section label="Decisões" right={`${a.decisions.length}`}>
              <ul style={{ margin: 0, padding: 0, listStyle: "none", display: "flex", flexDirection: "column", gap: 10 }}>
                {a.decisions.map((d: Decision, i) => (
                  <li key={d.id ?? i} style={{ display: "flex", gap: 12, fontSize: 14.5, lineHeight: 1.55, color: "var(--ink)" }}>
                    <span style={{ fontFamily: "var(--mono)", color: "var(--accent-ink)", fontSize: 11.5, marginTop: 3, minWidth: 18 }}>
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
                {a.actionItems.map((t: ActionItem, i) => (
                  <div key={t.id ?? i} style={{ borderTop: i === 0 ? "none" : "1px solid var(--border)", padding: "12px 0" }}>
                    <div style={{ fontSize: 14, color: "var(--ink)", lineHeight: 1.4 }}>{t.title}</div>
                    <div style={{ display: "flex", alignItems: "center", gap: 12, marginTop: 4, fontSize: 11.5, color: "var(--muted)", flexWrap: "wrap" }}>
                      <span>{t.assignee ?? "Sem responsável"}</span>
                      {t.dueDate && <span>Vence {t.dueDate}</span>}
                      <span style={{ color: PRIORITY_COLOR[t.priority] ?? "var(--muted)", letterSpacing: "0.04em" }}>{t.priority}</span>
                    </div>
                    {t.sourceQuote && (
                      <div style={{ marginTop: 8, padding: "8px 12px", background: "var(--chip)", borderRadius: 6, fontSize: 12.5, color: "var(--muted)", fontStyle: "italic", lineHeight: 1.5, borderLeft: "2px solid var(--accent)" }}>
                        {t.sourceQuote}
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </Section>
          )}

          {a.risks.length > 0 && (
            <Section label="Riscos" right={`${a.risks.length}`}>
              <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                {a.risks.map((r: Risk, i) => (
                  <SignalRow key={r.id ?? i} text={r.text} tag={`${r.severity} · ${r.category}`} color="var(--danger)" quote={r.sourceQuote} />
                ))}
              </div>
            </Section>
          )}

          {a.opportunities.length > 0 && (
            <Section label="Oportunidades" right={`${a.opportunities.length}`}>
              <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
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
                  <span key={t} style={{ fontSize: 12, padding: "3px 10px", borderRadius: 999, background: "var(--chip)", color: "var(--ink)" }}>
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

      <div style={{ marginTop: 48, paddingTop: 18, borderTop: "1px solid var(--border)", display: "flex", alignItems: "center", gap: 14, fontFamily: "var(--mono)", fontSize: 11, color: "var(--muted)" }}>
        <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
          <span style={{ width: 6, height: 6, borderRadius: "50%", background: "var(--success)" }} />
          PII Shield aplicado
        </span>
      </div>
    </div>
  );
}

function Section({ label, right, children }: { label: string; right?: string; children: React.ReactNode }) {
  return (
    <section style={{ marginBottom: 36 }}>
      <div style={{ display: "flex", alignItems: "baseline", justifyContent: "space-between", marginBottom: 14, paddingBottom: 8, borderBottom: "1px solid var(--border)" }}>
        <h2 style={{ fontFamily: "var(--mono)", fontSize: 10.5, fontWeight: 500, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--muted)", margin: 0 }}>
          {label}
        </h2>
        {right && <span style={{ fontFamily: "var(--mono)", fontSize: 11, color: "var(--muted)" }}>{right}</span>}
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
        <span style={{ fontFamily: "var(--mono)", fontSize: 10.5, color, letterSpacing: "0.04em", whiteSpace: "nowrap", flexShrink: 0, marginTop: 2 }}>{tag}</span>
      </div>
      {quote && (
        <div style={{ marginTop: 6, fontSize: 12.5, color: "var(--muted)", fontStyle: "italic", lineHeight: 1.5 }}>“{quote}”</div>
      )}
    </div>
  );
}
