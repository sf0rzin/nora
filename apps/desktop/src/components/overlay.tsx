import { useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { emit } from "@tauri-apps/api/event";
import { useLiveTranscript, type LiveTranscriptLine } from "@/hooks/use-live-transcript";
import { ShaderOrb } from "@/components/brand/shader-orb";
import { Avatar } from "@/components/brand/avatar";

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

function relTime(ms: number): string {
  const m = Math.floor(ms / 60000);
  const s = Math.floor((ms / 1000) % 60);
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

function getDisplayName(line: LiveTranscriptLine, isMe: boolean): string {
  if (isMe) return "Você";
  if (line.speakerId === "UNKNOWN") return "Desconhecido";
  return line.speaker || line.speakerId || "Falante";
}

// Group consecutive lines from the same speaker (and within 12s) into one bubble
interface ChatGroup {
  id: string;
  isMe: boolean;
  speaker: string;
  texts: { id: string; text: string; ts: number }[];
  startTs: number;
}

function groupLines(lines: LiveTranscriptLine[]): ChatGroup[] {
  const groups: ChatGroup[] = [];
  let current: ChatGroup | null = null;
  const WINDOW = 12_000;
  for (const l of lines) {
    const isMe = l.track === "mic";
    const speaker = getDisplayName(l, isMe);
    const same =
      current &&
      current.isMe === isMe &&
      current.speaker === speaker &&
      l.timestamp - current.texts[current.texts.length - 1].ts < WINDOW;
    if (same && current) {
      current.texts.push({ id: l.id, text: l.text, ts: l.timestamp });
    } else {
      current = {
        id: l.id,
        isMe,
        speaker,
        texts: [{ id: l.id, text: l.text, ts: l.timestamp }],
        startTs: l.timestamp,
      };
      groups.push(current);
    }
  }
  return groups;
}

function ChatBubble({
  group,
  startedAt,
}: {
  group: ChatGroup;
  startedAt: number | null;
}) {
  const isMe = group.isMe;
  const ts = startedAt ? relTime(group.startTs - startedAt) : "";
  return (
    <div
      className={`flex items-end gap-2 ${isMe ? "justify-end" : "justify-start"}`}
      style={{ width: "100%" }}
    >
      {!isMe && (
        <div className="shrink-0" style={{ paddingBottom: 2 }}>
          <Avatar name={group.speaker} size={26} />
        </div>
      )}
      <div
        className="flex flex-col"
        style={{
          maxWidth: "min(72%, 540px)",
          alignItems: isMe ? "flex-end" : "flex-start",
        }}
      >
        <div
          className="flex items-baseline gap-2"
          style={{ marginBottom: 3, padding: "0 4px" }}
        >
          <span
            style={{
              fontSize: 11,
              fontWeight: 500,
              color: "var(--ink)",
              letterSpacing: "-0.005em",
            }}
          >
            {group.speaker}
          </span>
          {ts && (
            <span
              style={{
                fontSize: 10,
                color: "var(--muted)",
                fontVariantNumeric: "tabular-nums",
              }}
            >
              {ts}
            </span>
          )}
        </div>
        <div className="flex flex-col gap-1" style={{ alignSelf: "stretch" }}>
          {group.texts.map((t) => (
            <div
              key={t.id}
              style={{
                padding: "8px 13px",
                background: isMe ? "var(--ink)" : "var(--canvas)",
                color: isMe ? "var(--canvas)" : "var(--ink)",
                border: isMe ? "none" : "1px solid var(--border)",
                borderRadius: 14,
                borderTopRightRadius: isMe ? 6 : 14,
                borderTopLeftRadius: isMe ? 14 : 6,
                fontSize: 13.5,
                lineHeight: 1.45,
                letterSpacing: "-0.005em",
                textAlign: "left",
                whiteSpace: "pre-wrap",
                wordBreak: "break-word",
                boxShadow: isMe
                  ? "0 6px 16px -10px rgba(15, 23, 42, 0.40)"
                  : "0 2px 8px -6px rgba(15, 23, 42, 0.12)",
              }}
            >
              {t.text}
            </div>
          ))}
        </div>
      </div>
      {isMe && (
        <div
          className="shrink-0 grid place-items-center"
          style={{
            width: 26,
            height: 26,
            borderRadius: "50%",
            overflow: "hidden",
            background: "var(--canvas)",
            paddingBottom: 0,
            marginBottom: 2,
          }}
        >
          <ShaderOrb size={26} speed={1} intensity={0.95} />
        </div>
      )}
    </div>
  );
}

function PartialBubble({ text, isMe }: { text: string; isMe: boolean }) {
  return (
    <div
      className={`flex items-end gap-2 ${isMe ? "justify-end" : "justify-start"}`}
      style={{ width: "100%", opacity: 0.55 }}
    >
      {!isMe && (
        <div className="shrink-0">
          <span
            style={{
              width: 26,
              height: 26,
              borderRadius: "50%",
              background: "var(--chip)",
              display: "block",
            }}
          />
        </div>
      )}
      <div
        style={{
          padding: "8px 13px",
          background: isMe ? "var(--ink)" : "var(--canvas)",
          color: isMe ? "var(--canvas)" : "var(--ink)",
          border: isMe ? "none" : "1px solid var(--border)",
          borderRadius: 14,
          borderTopRightRadius: isMe ? 6 : 14,
          borderTopLeftRadius: isMe ? 14 : 6,
          fontSize: 13.5,
          lineHeight: 1.45,
          fontStyle: "italic",
          maxWidth: "min(72%, 540px)",
        }}
      >
        {text}
        <span
          style={{
            display: "inline-block",
            width: 6,
            height: 6,
            borderRadius: "50%",
            background: isMe ? "var(--canvas)" : "var(--muted)",
            marginLeft: 6,
            verticalAlign: "middle",
            animation: "dotPulse 1.0s ease-in-out infinite",
          }}
        />
      </div>
      {isMe && (
        <span
          style={{
            width: 26,
            height: 26,
            borderRadius: "50%",
            background: "var(--chip)",
            display: "block",
          }}
        />
      )}
    </div>
  );
}

function BrandBars({ active }: { active: boolean }) {
  return (
    <span className="inline-flex items-end gap-[2.5px]" style={{ height: 18 }}>
      {[0.4, 0.7, 1.0, 0.65, 0.5].map((h, i) => (
        <span
          key={i}
          style={{
            display: "block",
            width: 3,
            height: `${h * 100}%`,
            background: active ? "var(--danger)" : "var(--ink)",
            borderRadius: 2,
            animation: active
              ? `dotPulse 1.4s ease-in-out ${i * 0.12}s infinite`
              : undefined,
          }}
        />
      ))}
    </span>
  );
}

export function OverlayPage() {
  const {
    lines,
    partial,
    isRecording,
    startedAt,
    duration,
    micDevice,
  } = useLiveTranscript();

  const groups = useMemo(() => groupLines(lines), [lines]);

  // last partial speaker (use track of last line as a heuristic, fallback: not-me)
  const lastTrack = lines.length > 0 ? lines[lines.length - 1].track : "system";
  const partialIsMe = lastTrack === "mic";

  // auto scroll
  const scrollRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [groups.length, partial]);

  const [stopping, setStopping] = useState(false);
  const handleStop = async () => {
    if (stopping) return;
    setStopping(true);
    try {
      await emit("nora://stop-and-save");
    } catch (e) {
      console.error("[overlay] failed to emit stop:", e);
      setStopping(false);
    }
  };
  const handleCancel = async () => {
    if (stopping) return;
    try {
      await emit("nora://cancel-recording");
    } catch (e) {
      console.error("[overlay] failed to emit cancel:", e);
    }
  };
  const handleMinimize = () => {
    invoke("toggle_overlay", { show: false }).catch(() => {});
  };

  const empty = groups.length === 0 && !partial;

  return (
    <div
      className="h-screen w-screen flex flex-col select-none overflow-hidden"
      style={{
        background: "rgba(253, 253, 252, 0.94)",
        WebkitBackdropFilter: "saturate(160%) blur(28px)",
        backdropFilter: "saturate(160%) blur(28px)",
        color: "var(--ink)",
        borderRadius: 16,
        border: "1px solid var(--border)",
        boxShadow:
          "0 26px 60px -28px rgba(15, 23, 42, 0.34), 0 8px 20px rgba(15, 23, 42, 0.06)",
      }}
    >
      {/* Header */}
      <div
        data-tauri-drag-region
        className="flex items-center justify-between shrink-0"
        style={{
          padding: "12px 16px",
          borderBottom: "1px solid var(--border)",
          background: "rgba(247, 247, 245, 0.55)",
          cursor: "move",
        }}
      >
        <div className="flex items-center gap-3 min-w-0">
          <BrandBars active={isRecording} />
          <span
            className="truncate"
            style={{
              fontSize: 12,
              fontWeight: 500,
              letterSpacing: "-0.005em",
              color: isRecording ? "var(--danger-ink)" : "var(--muted)",
            }}
          >
            {isRecording ? "NORA · gravando" : "NORA Live"}
          </span>
          {isRecording && (
            <>
              <span
                style={{
                  fontSize: 11,
                  color: "var(--muted)",
                  fontVariantNumeric: "tabular-nums",
                  padding: "1px 8px",
                  border: "1px solid var(--border)",
                  borderRadius: 999,
                  background: "var(--canvas)",
                }}
              >
                {formatDuration(duration)}
              </span>
              {micDevice && (
                <span
                  className="truncate hidden md:inline"
                  style={{
                    fontSize: 11,
                    color: "var(--muted)",
                    letterSpacing: "-0.005em",
                  }}
                >
                  {micDevice}
                </span>
              )}
            </>
          )}
        </div>
        <div className="flex items-center gap-1.5">
          {isRecording && (
            <>
              <button
                onClick={handleCancel}
                disabled={stopping}
                aria-label="Descartar gravação"
                title="Descartar"
                className="inline-flex items-center gap-1.5 rounded-md transition-colors"
                style={{
                  padding: "5px 10px",
                  fontSize: 11.5,
                  background: "transparent",
                  border: "1px solid var(--border)",
                  color: "var(--muted)",
                  cursor: stopping ? "default" : "pointer",
                  letterSpacing: "-0.005em",
                  fontWeight: 500,
                }}
                onMouseEnter={(e) => {
                  if (!stopping) {
                    e.currentTarget.style.background = "var(--chip)";
                    e.currentTarget.style.color = "var(--ink)";
                  }
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = "transparent";
                  e.currentTarget.style.color = "var(--muted)";
                }}
              >
                Descartar
              </button>
              <button
                onClick={handleStop}
                disabled={stopping}
                aria-label="Parar e salvar"
                className="inline-flex items-center gap-1.5 rounded-md"
                style={{
                  padding: "5px 12px 5px 10px",
                  fontSize: 11.5,
                  background: "var(--ink)",
                  color: "var(--canvas)",
                  border: "1px solid var(--ink)",
                  cursor: stopping ? "default" : "pointer",
                  letterSpacing: "-0.005em",
                  fontWeight: 500,
                }}
                onMouseEnter={(e) => {
                  if (!stopping) e.currentTarget.style.background = "#000";
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = "var(--ink)";
                }}
              >
                {stopping ? (
                  <>
                    <span
                      style={{
                        width: 10,
                        height: 10,
                        border: "1.5px solid rgba(253,253,252,0.4)",
                        borderTopColor: "var(--canvas)",
                        borderRadius: "50%",
                        animation: "nora-spin 0.9s linear infinite",
                      }}
                    />
                    Salvando…
                  </>
                ) : (
                  <>
                    <span
                      style={{
                        width: 8,
                        height: 8,
                        background: "var(--canvas)",
                        borderRadius: 2,
                      }}
                    />
                    Parar e salvar
                  </>
                )}
              </button>
            </>
          )}
          <button
            onClick={handleMinimize}
            aria-label="Esconder overlay"
            title="Esconder"
            className="grid place-items-center rounded-md transition-colors"
            style={{
              width: 26,
              height: 26,
              background: "transparent",
              border: "none",
              color: "var(--muted)",
              cursor: "pointer",
              marginLeft: 4,
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
      <div
        ref={scrollRef}
        className="flex-1 overflow-y-auto"
        style={{ padding: "16px 20px 18px" }}
      >
        {empty ? (
          <div className="h-full flex flex-col items-center justify-center text-center gap-3">
            <ShaderOrb size={48} speed={isRecording ? 1.4 : 0.6} intensity={isRecording ? 0.95 : 0.45} />
            <div style={{ maxWidth: 380 }}>
              <div
                style={{
                  fontSize: 13.5,
                  color: "var(--ink)",
                  fontWeight: 500,
                  letterSpacing: "-0.012em",
                  marginBottom: 4,
                }}
              >
                {isRecording ? "Aguardando fala…" : "Inicie uma gravação"}
              </div>
              <div style={{ fontSize: 12, color: "var(--muted)", lineHeight: 1.5 }}>
                {isRecording
                  ? "Cada fala vira uma bolha: suas mensagens à direita com a bolinha azul, as dos outros à esquerda."
                  : "A overlay abre automaticamente quando você inicia uma nova reunião pelo NORA."}
              </div>
            </div>
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            {groups.map((g) => (
              <ChatBubble key={g.id} group={g} startedAt={startedAt} />
            ))}
            {partial && <PartialBubble text={partial} isMe={partialIsMe} />}
          </div>
        )}
      </div>
    </div>
  );
}
