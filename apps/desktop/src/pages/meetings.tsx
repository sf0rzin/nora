import { useEffect, useState } from "react";
import { useAuth } from "@/hooks/use-auth";
import { listMeetings } from "@/lib/meetings";
import type { MeetingSummary, ApiError } from "@/lib/types";

export function MeetingsPage() {
  const { user } = useAuth();
  const [meetings, setMeetings] = useState<MeetingSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");

  useEffect(() => {
    loadMeetings();
  }, []);

  const loadMeetings = async () => {
    setLoading(true);
    setError("");
    try {
      const page = await listMeetings({ size: 50 });
      setMeetings(page.items);
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      setError(apiErr?.message || "Erro ao carregar reuniões");
    } finally {
      setLoading(false);
    }
  };

  const statusColor = (status: string) => {
    switch (status) {
      case "COMPLETED":
        return "text-green-400";
      case "PROCESSING":
        return "text-yellow-400";
      case "FAILED":
        return "text-red-400";
      default:
        return "text-zinc-400";
    }
  };

  return (
    <div className="flex-1 overflow-auto p-6">
      <div className="flex items-center justify-between mb-6">
        <div>
          <h1 className="text-xl font-bold">Reuniões</h1>
          <p className="text-sm text-zinc-400">
            Olá, {user?.displayName || "usuário"}
          </p>
        </div>
        <button className="px-4 py-2 bg-blue-600 hover:bg-blue-700 rounded text-sm font-medium transition-colors">
          Nova Reunião
        </button>
      </div>

      {error && (
        <div className="mb-4 p-3 bg-red-900/30 border border-red-800 rounded text-sm text-red-300">
          {error}
        </div>
      )}

      {loading ? (
        <div className="text-zinc-400 text-sm">Carregando...</div>
      ) : meetings.length === 0 ? (
        <div className="text-zinc-400 text-sm text-center py-12">
          Nenhuma reunião encontrada.
        </div>
      ) : (
        <div className="space-y-2">
          {meetings.map((m) => (
            <a
              key={m.id}
              href={`#/meetings/${m.id}`}
              className="block p-4 bg-zinc-800 border border-zinc-700 rounded hover:border-zinc-600 transition-colors"
            >
              <div className="flex items-center justify-between">
                <h3 className="font-medium">{m.title}</h3>
                <span className={`text-xs ${statusColor(m.processingStatus)}`}>
                  {m.processingStatus}
                </span>
              </div>
              <div className="flex items-center gap-4 mt-2 text-xs text-zinc-400">
                <span>{new Date(m.startedAt).toLocaleDateString("pt-BR")}</span>
                {m.durationSeconds && (
                  <span>{Math.round(m.durationSeconds / 60)} min</span>
                )}
                <span>{m.actionItemCount} tarefas</span>
                {m.tags.length > 0 && (
                  <span>{m.tags.join(", ")}</span>
                )}
              </div>
              {m.summarySnippet && (
                <p className="text-xs text-zinc-500 mt-2 line-clamp-2">
                  {m.summarySnippet}
                </p>
              )}
            </a>
          ))}
        </div>
      )}
    </div>
  );
}
