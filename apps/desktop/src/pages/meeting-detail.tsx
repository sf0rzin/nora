import { useEffect, useState } from "react";
import ReactMarkdown from "react-markdown";
import { getMeeting } from "@/lib/meetings";
import type { MeetingDetail, ApiError } from "@/lib/types";

export function MeetingDetailPage({ meetingId }: { meetingId: string }) {
  const [meeting, setMeeting] = useState<MeetingDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    loadMeeting();
  }, [meetingId]);

  const loadMeeting = async () => {
    setLoading(true);
    setError("");
    try {
      const data = await getMeeting(meetingId);
      setMeeting(data);
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      setError(apiErr?.message || "Erro ao carregar reunião");
    } finally {
      setLoading(false);
    }
  };

  if (loading) {
    return <div className="flex-1 flex items-center justify-center text-zinc-400">Carregando...</div>;
  }

  if (error || !meeting) {
    return (
      <div className="flex-1 flex items-center justify-center text-red-400">
        {error || "Reunião não encontrada"}
      </div>
    );
  }

  const analysis = meeting.analysis;

  return (
    <div className="flex-1 overflow-auto p-6">
      <a href="#/meetings" className="text-sm text-zinc-400 hover:text-zinc-200 mb-4 inline-block">
        ← Voltar
      </a>

      <div className="mb-6">
        <h1 className="text-xl font-bold">{meeting.title}</h1>
        <div className="flex items-center gap-4 mt-2 text-sm text-zinc-400">
          <span>{new Date(meeting.startedAt).toLocaleString("pt-BR")}</span>
          {meeting.durationSeconds && <span>{Math.round(meeting.durationSeconds / 60)} min</span>}
          <span>por {meeting.ownerName || meeting.owner?.displayName}</span>
        </div>
        {meeting.tags.length > 0 && (
          <div className="flex gap-2 mt-2">
            {meeting.tags.map((tag) => (
              <span key={tag} className="text-xs px-2 py-0.5 bg-zinc-700 rounded">
                {tag}
              </span>
            ))}
          </div>
        )}
      </div>

      {meeting.processingStatus === "COMPLETED" && analysis ? (
        <div className="space-y-6">
          <section>
            <h2 className="text-lg font-semibold mb-2">Resumo</h2>
            {analysis.summary ? (
              <div className="prose prose-invert prose-sm max-w-none text-zinc-300">
                <ReactMarkdown>{analysis.summary}</ReactMarkdown>
              </div>
            ) : (
              <p className="text-sm text-zinc-400 italic">Nenhum resumo disponível.</p>
            )}
            <div className="flex items-center gap-2 mt-2 text-xs text-zinc-500">
              <span>Sentimento: {analysis.sentimentOverall}</span>
              <span>·</span>
              <span>Tópicos: {analysis.topics.join(", ")}</span>
            </div>
          </section>

          {analysis.decisions.length > 0 && (
            <section>
              <h2 className="text-lg font-semibold mb-2">Decisões</h2>
              <ul className="space-y-1">
                {analysis.decisions.map((d) => (
                  <li key={d.id} className="text-sm text-zinc-300 flex items-start gap-2">
                    <span className="text-blue-400 mt-0.5">•</span>
                    {d.text}
                    {d.confidence !== undefined && (
                      <span className="text-xs text-zinc-500">({Math.round(d.confidence * 100)}%)</span>
                    )}
                  </li>
                ))}
              </ul>
            </section>
          )}

          {analysis.actionItems.length > 0 && (
            <section>
              <h2 className="text-lg font-semibold mb-2">Tarefas</h2>
              <div className="space-y-2">
                {analysis.actionItems.map((item) => (
                  <div key={item.id} className="p-3 bg-zinc-800 rounded border border-zinc-700">
                    <div className="flex items-center justify-between">
                      <span className="text-sm font-medium">{item.title}</span>
                      <span className={`text-xs px-2 py-0.5 rounded ${
                        item.priority === "HIGH"
                          ? "bg-red-900/40 text-red-300"
                          : item.priority === "MEDIUM"
                            ? "bg-yellow-900/40 text-yellow-300"
                            : "bg-zinc-700 text-zinc-300"
                      }`}>
                        {item.priority}
                      </span>
                    </div>
                    <div className="flex items-center gap-3 mt-1 text-xs text-zinc-400">
                      {item.assignee && <span>→ {item.assignee}</span>}
                      {item.dueDate && <span>Prazo: {item.dueDate}</span>}
                      <span className={`${
                        item.status === "DONE"
                          ? "text-green-400"
                          : item.status === "IN_PROGRESS"
                            ? "text-yellow-400"
                            : "text-zinc-400"
                      }`}>
                        {item.status}
                      </span>
                    </div>
                  </div>
                ))}
              </div>
            </section>
          )}

          {(analysis.risks.length > 0 || analysis.opportunities.length > 0) && (
            <div className="grid grid-cols-2 gap-4">
              {analysis.risks.length > 0 && (
                <section>
                  <h2 className="text-lg font-semibold mb-2">Riscos</h2>
                  <div className="space-y-2">
                    {analysis.risks.map((r) => (
                      <div key={r.id} className="p-2 bg-red-900/20 border border-red-900/40 rounded text-sm">
                        <span className="text-red-300">{r.severity}</span> — {r.text}
                      </div>
                    ))}
                  </div>
                </section>
              )}

              {analysis.opportunities.length > 0 && (
                <section>
                  <h2 className="text-lg font-semibold mb-2">Oportunidades</h2>
                  <div className="space-y-2">
                    {analysis.opportunities.map((o) => (
                      <div key={o.id} className="p-2 bg-green-900/20 border border-green-900/40 rounded text-sm">
                        <span className="text-green-300">{o.category}</span> — {o.text}
                      </div>
                    ))}
                  </div>
                </section>
              )}
            </div>
          )}
        </div>
      ) : meeting.processingStatus === "PROCESSING" ? (
        <div className="text-yellow-400">Processando análise...</div>
      ) : meeting.processingStatus === "FAILED" ? (
        <div className="text-red-400">Falha no processamento</div>
      ) : (
        <div className="text-zinc-400">Aguardando processamento...</div>
      )}
    </div>
  );
}
