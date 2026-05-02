import Link from "next/link";
import { notFound } from "next/navigation";
import { getMeeting } from "@/lib/api/client";
import { formatDateTime } from "@/lib/utils";

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

  return (
    <div className="space-y-8">
      <div>
        <Link
          href="/dashboard"
          className="text-sm text-muted-foreground hover:underline"
        >
          ← Voltar para reuniões
        </Link>
      </div>

      <header className="space-y-2">
        <h1 className="text-2xl font-semibold tracking-tight">{meeting.title}</h1>
        <div className="flex flex-wrap gap-3 text-sm text-muted-foreground">
          <span>{formatDateTime(meeting.startedAt)}</span>
          <span>·</span>
          <span>Owner: {meeting.owner.displayName}</span>
          <span>·</span>
          <span>{meeting.participants.length} participantes</span>
        </div>
      </header>

      {!a && (
        <p className="rounded-md border border-border bg-muted/30 p-4 text-sm">
          Esta reunião ainda não foi analisada.
        </p>
      )}

      {a && (
        <>
          <Section title="Resumo">
            <p className="text-sm leading-relaxed">{a.summary}</p>
          </Section>

          <Section title={`Decisões (${a.decisions.length})`}>
            {a.decisions.length === 0 ? (
              <Empty />
            ) : (
              <ul className="space-y-2">
                {a.decisions.map((d, i) => (
                  <li
                    key={d.id ?? i}
                    className="rounded-md border border-border p-3 text-sm"
                  >
                    <div>{d.text}</div>
                    <div className="text-xs text-muted-foreground mt-1">
                      Confiança: {(d.confidence * 100).toFixed(0)}%
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Section>

          <Section title={`Tarefas (${a.actionItems.length})`}>
            {a.actionItems.length === 0 ? (
              <Empty />
            ) : (
              <ul className="space-y-2">
                {a.actionItems.map((t, i) => (
                  <li
                    key={t.id ?? i}
                    className="rounded-md border border-border p-3 text-sm"
                  >
                    <div className="flex items-baseline justify-between gap-2">
                      <span className="font-medium">{t.title}</span>
                      <span className="text-xs uppercase text-muted-foreground">
                        {t.priority}
                      </span>
                    </div>
                    <div className="text-xs text-muted-foreground mt-1">
                      {t.assignee ?? "Sem responsável"} ·{" "}
                      {t.dueDate ?? "Sem prazo"}
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Section>

          <Section title={`Riscos (${a.risks.length})`}>
            {a.risks.length === 0 ? (
              <Empty />
            ) : (
              <ul className="space-y-2">
                {a.risks.map((r, i) => (
                  <li
                    key={r.id ?? i}
                    className="rounded-md border border-border p-3 text-sm"
                  >
                    <div className="flex items-baseline justify-between gap-2">
                      <span>{r.text}</span>
                      <span className="text-xs uppercase text-muted-foreground">
                        {r.severity} · {r.category}
                      </span>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Section>

          <Section title={`Oportunidades (${a.opportunities.length})`}>
            {a.opportunities.length === 0 ? (
              <Empty />
            ) : (
              <ul className="space-y-2">
                {a.opportunities.map((o, i) => (
                  <li
                    key={o.id ?? i}
                    className="rounded-md border border-border p-3 text-sm"
                  >
                    <div className="flex items-baseline justify-between gap-2">
                      <span>{o.text}</span>
                      <span className="text-xs uppercase text-muted-foreground">
                        {o.estimatedValue} · {o.category}
                      </span>
                    </div>
                  </li>
                ))}
              </ul>
            )}
          </Section>

          {a.topics.length > 0 && (
            <Section title="Tópicos">
              <div className="flex flex-wrap gap-2">
                {a.topics.map((t) => (
                  <span
                    key={t}
                    className="rounded-full bg-muted px-3 py-1 text-xs text-muted-foreground"
                  >
                    {t}
                  </span>
                ))}
              </div>
            </Section>
          )}
        </>
      )}
    </div>
  );
}

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <section className="space-y-3">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-muted-foreground">
        {title}
      </h2>
      {children}
    </section>
  );
}

function Empty() {
  return <p className="text-sm text-muted-foreground">Nada identificado.</p>;
}
