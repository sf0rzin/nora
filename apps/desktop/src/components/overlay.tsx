import { useLiveHighlights } from "@/hooks/use-live-highlights";
import { listen } from "@tauri-apps/api/event";
import { useState, useEffect } from "react";

function formatTimeAgo(ms: number): string {
  const seconds = Math.floor(ms / 1000);
  if (seconds < 5) return "agora";
  if (seconds < 60) return `${seconds}s atrás`;
  const minutes = Math.floor(seconds / 60);
  return `${minutes}min atrás`;
}

type CategoryKey = "decisions" | "nextSteps" | "observations" | "tasks";

interface CategoryDef {
  key: CategoryKey;
  label: string;
  accent: string;
  icon: JSX.Element;
}

const CATEGORIES: CategoryDef[] = [
  {
    key: "decisions",
    label: "Decisões",
    accent: "var(--accent-ink)",
    icon: (
      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="20 6 9 17 4 12" />
      </svg>
    ),
  },
  {
    key: "nextSteps",
    label: "Próximos passos",
    accent: "#3f8a5e",
    icon: (
      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <line x1="5" y1="12" x2="19" y2="12" />
        <polyline points="12 5 19 12 12 19" />
      </svg>
    ),
  },
  {
    key: "observations",
    label: "Observações",
    accent: "var(--muted)",
    icon: (
      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <circle cx="12" cy="12" r="10" />
        <line x1="12" y1="16" x2="12" y2="12" />
        <line x1="12" y1="8" x2="12.01" y2="8" />
      </svg>
    ),
  },
  {
    key: "tasks",
    label: "Tarefas",
    accent: "#a37528",
    icon: (
      <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
        <polyline points="9 11 12 14 22 4" />
        <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
      </svg>
    ),
  },
];

const PRIORITY: Record<string, { bg: string; fg: string; dot: string }> = {
  HIGH: { bg: "rgba(201,119,102,0.16)", fg: "#a04c3e", dot: "#a04c3e" },
  MEDIUM: { bg: "rgba(212,160,76,0.16)", fg: "#a37528", dot: "#a37528" },
  LOW: { bg: "var(--chip)", fg: "var(--muted)", dot: "var(--muted)" },
};

function OverlayBars({ active }: { active: boolean }) {
  return (
    <span className="inline-flex items-end gap-[2.5px]" style={{ height: 16 }}>
      {[0.4, 0.7, 1.0, 0.65, 0.5].map((h, i) => (
        <span
          key={i}
          style={{
            display: "block",
            width: 3,
            height: `${h * 100}%`,
            background: active ? "var(--danger)" : "var(--ink)",
            borderRadius: 2,
            animation: active ? `dotPulse 1.4s ease-in-out ${i * 0.12}s infinite` : undefined,
          }}
        />
      ))}
    </span>
  );
}

function Column({
  cat,
  items,
}: {
  cat: CategoryDef;
  items: { kind: "text" | "task"; text: string; priority?: string }[];
}) {
  const count = items.length;
  return (
    <section
      className="flex flex-col min-w-0"
      style={{
        flex: 1,
        background: "rgba(253, 253, 252, 0.55)",
        borderRadius: 10,
        border: "1px solid var(--border)",
        padding: 0,
        minWidth: 0,
        overflow: "hidden",
      }}
    >
      <header
        className="flex items-center justify-between shrink-0"
        style={{
          padding: "8px 11px",
          background: "rgba(247, 247, 245, 0.55)",
          borderBottom: "1px solid var(--border)",
        }}
      >
        <span
          className="inline-flex items-center gap-1.5"
          style={{
            fontSize: 10.5,
            fontWeight: 500,
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            color: cat.accent,
          }}
        >
          {cat.icon}
          {cat.label}
        </span>
        <span
          style={{
            fontSize: 10,
            color: "var(--muted)",
            fontVariantNumeric: "tabular-nums",
            padding: "1px 7px",
            background: "var(--chip)",
            borderRadius: 999,
            fontWeight: 500,
          }}
        >
          {count}
        </span>
      </header>
      <div
        className="overflow-y-auto"
        style={{ padding: count === 0 ? "10px 12px" : "8px 4px 10px 4px", flex: 1, minHeight: 0 }}
      >
        {count === 0 ? (
          <div
            style={{
              fontSize: 11,
              color: "var(--muted)",
              fontStyle: "italic",
              lineHeight: 1.55,
              opacity: 0.7,
            }}
          >
            Aguardando…
          </div>
        ) : (
          <div className="flex flex-col">
            {items.map((it, i) => {
              if (it.kind === "task") {
                const c = PRIORITY[it.priority ?? "MEDIUM"] ?? PRIORITY.MEDIUM;
                return (
                  <div
                    key={i}
                    className="flex items-start gap-2"
                    style={{ padding: "6px 8px", borderRadius: 7 }}
                  >
                    <span
                      className="inline-flex items-center gap-1 shrink-0 whitespace-nowrap"
                      style={{
                        padding: "1px 6px",
                        borderRadius: 999,
                        background: c.bg,
                        color: c.fg,
                        fontSize: 9.5,
                        fontWeight: 500,
                        letterSpacing: "0.04em",
                        marginTop: 1,
                      }}
                    >
                      <span style={{ width: 4, height: 4, borderRadius: "50%", background: c.dot }} />
                      {it.priority}
                    </span>
                    <span
                      style={{
                        fontSize: 12,
                        color: "var(--ink)",
                        lineHeight: 1.45,
                      }}
                    >
                      {it.text}
                    </span>
                  </div>
                );
              }
              return (
                <div
                  key={i}
                  style={{
                    padding: "6px 8px 6px 10px",
                    borderRadius: 7,
                    fontSize: 12,
                    color: "var(--ink)",
                    lineHeight: 1.5,
                    borderLeft: `2px solid ${cat.accent}`,
                    marginLeft: 4,
                    background: "transparent",
                  }}
                >
                  {it.text}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </section>
  );
}

export function OverlayPage() {
  const { highlights, lastUpdatedAt, lastLatencyMs, isAnalyzing } = useLiveHighlights();
  const [recordingStatus, setRecordingStatus] = useState<{ isRecording: boolean } | null>(null);
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
    invokeOverlay("get_recording_status")
      .then((status) => setRecordingStatus(status as { isRecording: boolean }))
      .catch(() => {});
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
  const isRecording = recordingStatus?.isRecording ?? false;

  const columnsData: { cat: CategoryDef; items: { kind: "text" | "task"; text: string; priority?: string }[] }[] = CATEGORIES.map((cat) => {
    if (cat.key === "tasks") {
      return {
        cat,
        items: highlights.tasks.map((t) => ({
          kind: "task" as const,
          text: t.title,
          priority: t.priority,
        })),
      };
    }
    const arr =
      cat.key === "decisions"
        ? highlights.decisions
        : cat.key === "nextSteps"
          ? highlights.nextSteps
          : highlights.observations;
    return {
      cat,
      items: arr.map((it) => ({ kind: "text" as const, text: it.text })),
    };
  });

  return (
    <div
      className="h-screen w-screen flex flex-col select-none overflow-hidden"
      style={{
        background: "rgba(253, 253, 252, 0.92)",
        WebkitBackdropFilter: "saturate(160%) blur(24px)",
        backdropFilter: "saturate(160%) blur(24px)",
        color: "var(--ink)",
        borderRadius: 16,
        border: "1px solid var(--border)",
        boxShadow:
          "0 24px 60px -28px rgba(15, 23, 42, 0.32), 0 6px 18px rgba(15, 23, 42, 0.06)",
      }}
    >
      {/* Header (drag region) */}
      <div
        data-tauri-drag-region
        className="flex items-center justify-between shrink-0"
        style={{
          padding: "10px 14px",
          background: "rgba(247, 247, 245, 0.5)",
          borderBottom: "1px solid var(--border)",
          cursor: "move",
        }}
      >
        <div className="flex items-center gap-3">
          <OverlayBars active={isRecording} />
          <span
            style={{
              fontSize: 11.5,
              fontWeight: 500,
              letterSpacing: "-0.005em",
            }}
          >
            {isRecording ? (
              <span style={{ color: "var(--danger-ink)" }}>NORA · gravando</span>
            ) : (
              <span style={{ color: "var(--muted)" }}>NORA Live</span>
            )}
          </span>
          {totalItems > 0 && (
            <span
              style={{
                fontSize: 11,
                color: "var(--muted)",
                fontVariantNumeric: "tabular-nums",
              }}
            >
              · {totalItems} {totalItems === 1 ? "destaque" : "destaques"}
            </span>
          )}
        </div>
        <div className="flex items-center gap-2.5">
          {isAnalyzing ? (
            <span
              className="inline-flex items-center gap-1.5"
              style={{ fontSize: 10.5, color: "var(--accent-ink)" }}
            >
              <span
                style={{
                  width: 9,
                  height: 9,
                  border: "1.5px solid var(--accent-ink)",
                  borderTopColor: "transparent",
                  borderRadius: "50%",
                  animation: "nora-spin 0.9s linear infinite",
                }}
              />
              analisando
            </span>
          ) : timeAgo ? (
            <span
              style={{
                fontSize: 10.5,
                color: "var(--muted)",
                fontVariantNumeric: "tabular-nums",
              }}
            >
              {timeAgo}
            </span>
          ) : null}
          {lastLatencyMs !== null && (
            <span
              style={{
                fontSize: 10,
                color: "var(--muted)",
                fontVariantNumeric: "tabular-nums",
                opacity: 0.7,
              }}
            >
              {lastLatencyMs}ms
            </span>
          )}
          <button
            onClick={() => invokeOverlay("toggle_overlay", { show: false })}
            className="grid place-items-center rounded-md transition-colors"
            aria-label="Fechar overlay"
            style={{
              width: 24,
              height: 24,
              background: "transparent",
              border: "none",
              color: "var(--muted)",
              cursor: "pointer",
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.background = "rgba(0,0,0,0.05)";
              e.currentTarget.style.color = "var(--ink)";
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.background = "transparent";
              e.currentTarget.style.color = "var(--muted)";
            }}
          >
            <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>
      </div>

      {/* Body */}
      <div className="flex-1 min-h-0 overflow-hidden" style={{ padding: 10 }}>
        {!hasContent ? (
          <div className="h-full grid grid-cols-4 gap-2.5">
            {CATEGORIES.map((cat) => (
              <Column key={cat.key} cat={cat} items={[]} />
            ))}
          </div>
        ) : (
          <div className="h-full grid grid-cols-4 gap-2.5">
            {columnsData.map((c) => (
              <Column key={c.cat.key} cat={c.cat} items={c.items} />
            ))}
          </div>
        )}
      </div>
    </div>
  );
}

async function invokeOverlay(cmd: string, args?: Record<string, unknown>) {
  const { invoke } = await import("@tauri-apps/api/core");
  return invoke(cmd, args);
}
