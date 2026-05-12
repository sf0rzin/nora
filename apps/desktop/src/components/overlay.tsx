import { useLiveHighlights } from "@/hooks/use-live-highlights";
import { listen } from "@tauri-apps/api/event";
import { useState, useEffect } from "react";

function formatTimeAgo(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  if (seconds < 5) return "agora";
  if (seconds < 60) return `${seconds}s atr\u00e1s`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}min atr\u00e1s`;
}

const priorityColors: Record<string, string> = {
  HIGH: "bg-red-500/20 text-red-300 border-red-800",
  MEDIUM: "bg-yellow-500/20 text-yellow-300 border-yellow-800",
  LOW: "bg-zinc-500/20 text-zinc-300 border-zinc-700",
};

function HighlightSection({
  title,
  items,
  defaultOpen = true,
}: {
  title: string;
  items: { text: string; confidence: number }[];
  defaultOpen?: boolean;
}) {
  const [open, setOpen] = useState(defaultOpen);
  if (items.length === 0) return null;

  return (
    <div className="border-b border-zinc-800 last:border-b-0">
      <button
        onClick={() => setOpen(!open)}
        className="w-full px-3 py-2 flex items-center justify-between text-xs font-medium text-zinc-300 hover:bg-zinc-800/50 transition-colors"
      >
        <span className="flex items-center gap-2">
          <span>{open ? "\u25BC" : "\u25B6"}</span>
          {title}
          <span className="bg-zinc-700 text-zinc-400 px-1.5 py-0.5 rounded-full text-[10px]">
            {items.length}
          </span>
        </span>
      </button>
      {open && (
        <div className="px-3 pb-2 space-y-1">
          {items.map((item, i) => (
            <div
              key={i}
              className="text-xs text-zinc-300 leading-relaxed pl-2 border-l-2 border-zinc-700"
            >
              {item.text}
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

function TaskSection({
  tasks,
}: {
  tasks: { title: string; priority: string; assignee: string | null }[];
}) {
  const [open, setOpen] = useState(true);
  if (tasks.length === 0) return null;

  return (
    <div className="border-b border-zinc-800 last:border-b-0">
      <button
        onClick={() => setOpen(!open)}
        className="w-full px-3 py-2 flex items-center justify-between text-xs font-medium text-zinc-300 hover:bg-zinc-800/50 transition-colors"
      >
        <span className="flex items-center gap-2">
          <span>{open ? "\u25BC" : "\u25B6"}</span>
          Tarefas
          <span className="bg-zinc-700 text-zinc-400 px-1.5 py-0.5 rounded-full text-[10px]">
            {tasks.length}
          </span>
        </span>
      </button>
      {open && (
        <div className="px-3 pb-2 space-y-1.5">
          {tasks.map((task, i) => (
            <div key={i} className="flex items-start gap-2 text-xs">
              <span
                className={`shrink-0 px-1.5 py-0.5 rounded text-[10px] font-medium border ${
                  priorityColors[task.priority] || priorityColors.MEDIUM
                }`}
              >
                {task.priority}
              </span>
              <span className="text-zinc-300 leading-relaxed">{task.title}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}

export function OverlayPage() {
  const { highlights, lastUpdatedAt, lastLatencyMs, isAnalyzing } = useLiveHighlights();
  const [recordingStatus, setRecordingStatus] = useState<{
    isRecording: boolean;
  } | null>(null);
  const [timeAgo, setTimeAgo] = useState<string>("");
  const totalItems =
    highlights.decisions.length +
    highlights.nextSteps.length +
    highlights.observations.length +
    highlights.tasks.length;

  useEffect(() => {
    const unlisten = listen<{ isRecording: boolean }>("recording-status", (event) => {
      setRecordingStatus(event.payload);
    });

    invoke("get_recording_status").then((status) => {
      setRecordingStatus(status as { isRecording: boolean });
    }).catch(() => {});

    return () => {
      unlisten.then((fn) => fn());
    };
  }, []);

  useEffect(() => {
    if (!lastUpdatedAt) {
      setTimeAgo("");
      return;
    }
    const update = () => setTimeAgo(formatTimeAgo(Date.now() - lastUpdatedAt));
    update();
    const interval = setInterval(update, 5000);
    return () => clearInterval(interval);
  }, [lastUpdatedAt]);

  const hasContent = totalItems > 0 || isAnalyzing;

  return (
    <div
      data-tauri-drag-region
      className="h-screen flex flex-col bg-zinc-900/95 backdrop-blur-sm text-zinc-100 select-none overflow-hidden rounded-xl border border-zinc-700/50"
    >
      <div
        data-tauri-drag-region
        className="flex items-center justify-between px-3 py-2 bg-zinc-900 border-b border-zinc-800 cursor-move"
      >
        <div className="flex items-center gap-2 text-xs font-medium" data-tauri-drag-region>
          {recordingStatus?.isRecording ? (
            <>
              <span className="w-2 h-2 rounded-full bg-red-500 animate-pulse" />
              <span className="text-red-400">GRAVANDO</span>
            </>
          ) : (
            <span className="text-zinc-500">NORA Live</span>
          )}
        </div>
        <button
          onClick={() => invoke("toggle_overlay", { show: false })}
          className="w-5 h-5 flex items-center justify-center rounded hover:bg-zinc-700 text-zinc-500 hover:text-zinc-300 transition-colors text-xs"
        >
          {"\u2715"}
        </button>
      </div>

      <div className="flex-1 overflow-y-auto min-h-0">
        {isAnalyzing && !hasContent && (
          <div className="flex items-center justify-center py-8">
            <div className="flex items-center gap-2 text-xs text-zinc-500">
              <span className="w-3 h-3 border-2 border-zinc-600 border-t-zinc-400 rounded-full animate-spin" />
              Analisando...
            </div>
          </div>
        )}

        {!hasContent && !isAnalyzing && (
          <div className="flex items-center justify-center py-12 text-xs text-zinc-600">
            Aguardando transcri\u00e7\u00e3o...
          </div>
        )}

        {hasContent && (
          <div className="divide-y divide-zinc-800/50">
            <HighlightSection title="Decis\u00f5es" items={highlights.decisions} />
            <HighlightSection
              title="Pr\u00f3ximos Passos"
              items={highlights.nextSteps}
            />
            <HighlightSection title="Observa\u00e7\u00f5es" items={highlights.observations} />
            <TaskSection tasks={highlights.tasks} />
          </div>
        )}
      </div>

      <div className="px-3 py-1.5 border-t border-zinc-800 flex items-center justify-between text-[10px] text-zinc-600">
        <span>
          {isAnalyzing ? (
            <span className="text-blue-400 flex items-center gap-1">
              <span className="w-2 h-2 border border-blue-400 border-t-transparent rounded-full animate-spin" />
              Analisando...
            </span>
          ) : timeAgo ? (
            `Atualizado ${timeAgo}`
          ) : null}
        </span>
        {lastLatencyMs !== null && (
          <span>{lastLatencyMs}ms</span>
        )}
      </div>
    </div>
  );
}

async function invoke(cmd: string, args?: Record<string, unknown>) {
  const { invoke: tauriInvoke } = await import("@tauri-apps/api/core");
  return tauriInvoke(cmd, args);
}
