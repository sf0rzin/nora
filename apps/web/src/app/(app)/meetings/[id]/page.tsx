import type { Route } from "next";
import { notFound } from "next/navigation";
import { getMeeting } from "@/lib/api/client";
import { formatDateTime } from "@/lib/utils";
import { MarkdownContent } from "@/components/markdown-content";
import MeetingProductivitySection from "@/components/meeting-productivity-section";
import CustomerConfidenceCard from "@/components/customer-confidence-card";
import {
  Badge,
  ButtonLink,
  Card,
  EmptyState,
  PageHeader,
  Section,
} from "@/components/core/ui";
import MeetingLiveStatus from "./MeetingLiveStatus";
import ExportReportButton from "./ExportReportButton";

export const dynamic = "force-dynamic";

export default async function MeetingDetailPage({
  params,
}: {
  params: { id: string };
}) {
  let meeting;
  try {
    meeting = await getMeeting(params.id);
  } catch {
    notFound();
  }
  if (!meeting) notFound();

  const a = meeting.analysis;

  const metaParts = [
    formatDateTime(meeting.startedAt),
    `Owner: ${meeting.owner.displayName}`,
    meeting.participants.length > 0
      ? `${meeting.participants.length} participantes`
      : null,
  ].filter(Boolean);

  return (
    <div>
      <div
        style={{
          marginBottom: 16,
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          gap: 10,
        }}
      >
        <ButtonLink href={"/dashboard" as Route} variant="ghost" size="sm">
          ← Voltar para reuniões
        </ButtonLink>
        {a && <ExportReportButton meeting={meeting} />}
      </div>

      <PageHeader title={meeting.title} subtitle={metaParts.join("  ·  ")} />

      <MeetingLiveStatus meetingId={meeting.id} status={meeting.processingStatus} />

      {!a && meeting.processingStatus !== "PENDING" && meeting.processingStatus !== "PROCESSING" && (
        <div style={{ marginBottom: 28 }}>
          <EmptyState>Esta reunião ainda não foi analisada.</EmptyState>
        </div>
      )}

      {a && (
        <>
          <Section title="Resumo">
            {a.summary && a.summary.trim().length > 0 ? (
              <Card>
                <MarkdownContent>{a.summary}</MarkdownContent>
              </Card>
            ) : (
              <EmptyState>Resumo não disponível.</EmptyState>
            )}
          </Section>

          <Section title={`Decisões (${a.decisions.length})`}>
            {a.decisions.length === 0 ? (
              <EmptyState>Nada identificado.</EmptyState>
            ) : (
              <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                {a.decisions.map((d, i) => (
                  <Card key={d.id ?? i}>
                    <div style={{ fontSize: 14, color: "var(--ink)" }}>{d.text}</div>
                    <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 6 }}>
                      Confiança: {(d.confidence * 100).toFixed(0)}%
                    </div>
                  </Card>
                ))}
              </div>
            )}
          </Section>

          <Section title={`Tarefas (${a.actionItems.length})`}>
            {a.actionItems.length === 0 ? (
              <EmptyState>Nada identificado.</EmptyState>
            ) : (
              <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                {a.actionItems.map((t, i) => (
                  <Card key={t.id ?? i}>
                    <div
                      style={{
                        display: "flex",
                        alignItems: "baseline",
                        justifyContent: "space-between",
                        gap: 8,
                      }}
                    >
                      <span style={{ fontSize: 14, fontWeight: 500, color: "var(--ink)" }}>
                        {t.title}
                      </span>
                      <Badge>{t.priority}</Badge>
                    </div>
                    <div style={{ fontSize: 12, color: "var(--muted)", marginTop: 6 }}>
                      {t.assignee ?? "Sem responsável"} ·{" "}
                      {t.dueDate ?? "Sem prazo"}
                    </div>
                  </Card>
                ))}
              </div>
            )}
          </Section>

          <Section title={`Riscos (${a.risks.length})`}>
            {a.risks.length === 0 ? (
              <EmptyState>Nada identificado.</EmptyState>
            ) : (
              <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                {a.risks.map((r, i) => (
                  <Card key={r.id ?? i}>
                    <div
                      style={{
                        display: "flex",
                        alignItems: "baseline",
                        justifyContent: "space-between",
                        gap: 8,
                      }}
                    >
                      <span style={{ fontSize: 14, color: "var(--ink)" }}>{r.text}</span>
                      <Badge tone="danger">
                        {r.severity} · {r.category}
                      </Badge>
                    </div>
                  </Card>
                ))}
              </div>
            )}
          </Section>

          <Section title={`Oportunidades (${a.opportunities.length})`}>
            {a.opportunities.length === 0 ? (
              <EmptyState>Nada identificado.</EmptyState>
            ) : (
              <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
                {a.opportunities.map((o, i) => (
                  <Card key={o.id ?? i}>
                    <div
                      style={{
                        display: "flex",
                        alignItems: "baseline",
                        justifyContent: "space-between",
                        gap: 8,
                      }}
                    >
                      <span style={{ fontSize: 14, color: "var(--ink)" }}>{o.text}</span>
                      <Badge tone="success">
                        {o.estimatedValue} · {o.category}
                      </Badge>
                    </div>
                  </Card>
                ))}
              </div>
            )}
          </Section>

          {a.topics.length > 0 && (
            <Section title="Tópicos">
              <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
                {a.topics.map((t) => (
                  <Badge key={t}>{t}</Badge>
                ))}
              </div>
            </Section>
          )}
        </>
      )}

      {meeting.customerConfidence && (
        <Section title="Confiança do cliente">
          <CustomerConfidenceCard confidence={meeting.customerConfidence ?? null} />
        </Section>
      )}

      <MeetingProductivitySection
        meetingId={meeting.id}
        goal={meeting.goal ?? null}
        productivity={meeting.productivity ?? null}
      />
    </div>
  );
}
