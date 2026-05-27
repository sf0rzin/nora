import { useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { emit, listen } from "@tauri-apps/api/event";
import { useLiveTranscript, type LiveTranscriptLine } from "@/hooks/use-live-transcript";
import { useLiveHighlights, type LiveHighlightItem, type LiveTaskItem } from "@/hooks/use-live-highlights";
import { ShaderOrb } from "@/components/brand/shader-orb";
import { Avatar } from "@/components/brand/avatar";

type SpeakerMap = Record<string, string>;

const SPEAKER_OVERRIDES_KEY = "nora.overlay.speaker-overrides";
const DOCK_STORAGE_KEY = "nora.dock.visible";
const HIGHLIGHTS_STORAGE_KEY = "nora.overlay.highlights-visible";

function loadHighlightsPref(): boolean {
  try {
    const v = localStorage.getItem(HIGHLIGHTS_STORAGE_KEY);
    return v == null ? true : v === "1";
  } catch {
    return true;
  }
}
function saveHighlightsPref(v: boolean) {
  try {
    localStorage.setItem(HIGHLIGHTS_STORAGE_KEY, v ? "1" : "0");
  } catch {
    // ignore
  }
}

function loadOverrides(): SpeakerMap {
  try {
    const raw = localStorage.getItem(SPEAKER_OVERRIDES_KEY);
    return raw ? (JSON.parse(raw) as SpeakerMap) : {};
  } catch {
    return {};
  }
}
function saveOverrides(map: SpeakerMap) {
  try {
    localStorage.setItem(SPEAKER_OVERRIDES_KEY, JSON.stringify(map));
  } catch {
    // ignore
  }
}
function loadDockPref(): boolean {
  try {
    const v = localStorage.getItem(DOCK_STORAGE_KEY);
    return v == null ? true : v === "1";
  } catch {
    return true;
  }
}
function saveDockPref(v: boolean) {
  try {
    localStorage.setItem(DOCK_STORAGE_KEY, v ? "1" : "0");
  } catch {
    // ignore
  }
}

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

function getDisplayName(line: LiveTranscriptLine, isMe: boolean, overrides: SpeakerMap): string {
  if (isMe) return "Você";
  if (line.speakerId && overrides[line.speakerId]) return overrides[line.speakerId];
  if (line.speakerId === "UNKNOWN") return "Desconhecido";
  return line.speaker || line.speakerId || "Falante";
}

// ── Group consecutive lines from same speaker (12s window) ─────────────
interface ChatGroup {
  id: string;
  isMe: boolean;
  speaker: string;
  texts: { id: string; text: string; ts: number }[];
  startTs: number;
}

function groupLines(lines: LiveTranscriptLine[], overrides: SpeakerMap): ChatGroup[] {
  const groups: ChatGroup[] = [];
  let current: ChatGroup | null = null;
  const WINDOW = 12_000;
  for (const l of lines) {
    const isMe = l.track === "mic";
    const speaker = getDisplayName(l, isMe, overrides);
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

// ── Chat bubble ────────────────────────────────────────────────────────
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
          <Avatar name={group.speaker} size={22} />
        </div>
      )}
      <div
        className="flex flex-col"
        style={{
          maxWidth: "82%",
          alignItems: isMe ? "flex-end" : "flex-start",
        }}
      >
        <div
          className="flex items-baseline gap-1.5"
          style={{ marginBottom: 2, padding: "0 4px" }}
        >
          <span
            style={{
              fontSize: 10.5,
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
                fontSize: 9.5,
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
                padding: "7px 11px",
                background: isMe ? "var(--ink)" : "var(--canvas)",
                color: isMe ? "var(--canvas)" : "var(--ink)",
                border: isMe ? "none" : "1px solid var(--border)",
                borderRadius: 12,
                borderTopRightRadius: isMe ? 5 : 12,
                borderTopLeftRadius: isMe ? 12 : 5,
                fontSize: 12.5,
                lineHeight: 1.45,
                letterSpacing: "-0.005em",
                textAlign: "left",
                whiteSpace: "pre-wrap",
                wordBreak: "break-word",
                boxShadow: isMe
                  ? "0 4px 14px -10px rgba(15, 23, 42, 0.40)"
                  : "0 2px 6px -4px rgba(15, 23, 42, 0.10)",
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
            width: 22,
            height: 22,
            borderRadius: "50%",
            overflow: "hidden",
            background: "var(--canvas)",
            marginBottom: 2,
          }}
        >
          <ShaderOrb size={22} speed={1} intensity={0.95} />
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
        <span
          style={{
            width: 22,
            height: 22,
            borderRadius: "50%",
            background: "var(--chip)",
            display: "block",
          }}
        />
      )}
      <div
        style={{
          padding: "7px 11px",
          background: isMe ? "var(--ink)" : "var(--canvas)",
          color: isMe ? "var(--canvas)" : "var(--ink)",
          border: isMe ? "none" : "1px solid var(--border)",
          borderRadius: 12,
          borderTopRightRadius: isMe ? 5 : 12,
          borderTopLeftRadius: isMe ? 12 : 5,
          fontSize: 12.5,
          lineHeight: 1.45,
          fontStyle: "italic",
          maxWidth: "82%",
        }}
      >
        {text}
        <span
          style={{
            display: "inline-block",
            width: 5,
            height: 5,
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
            width: 22,
            height: 22,
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
            animation: active
              ? `dotPulse 1.4s ease-in-out ${i * 0.12}s infinite`
              : undefined,
          }}
        />
      ))}
    </span>
  );
}

// ── Highlights column (right panel) ────────────────────────────────────
const PRIORITY: Record<string, { bg: string; fg: string; dot: string }> = {
  HIGH: { bg: "rgba(201,119,102,0.16)", fg: "#a04c3e", dot: "#a04c3e" },
  MEDIUM: { bg: "rgba(212,160,76,0.16)", fg: "#a37528", dot: "#a37528" },
  LOW: { bg: "var(--chip)", fg: "var(--muted)", dot: "var(--muted)" },
};

function HighlightItem({
  text,
  accent,
}: {
  text: string;
  accent: string;
}) {
  return (
    <div
      style={{
        fontSize: 11.5,
        color: "var(--ink)",
        lineHeight: 1.4,
        padding: "5px 8px 5px 9px",
        borderLeft: `2px solid ${accent}`,
        marginTop: 3,
        letterSpacing: "-0.005em",
      }}
    >
      {text}
    </div>
  );
}

function TaskItem({ task }: { task: LiveTaskItem }) {
  const c = PRIORITY[task.priority] ?? PRIORITY.MEDIUM;
  return (
    <div
      className="flex items-start gap-2"
      style={{ marginTop: 4, padding: "5px 8px", fontSize: 11.5 }}
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
        {task.priority}
      </span>
      <span style={{ color: "var(--ink)", lineHeight: 1.4 }}>{task.title}</span>
    </div>
  );
}

function HighlightSection({
  title,
  count,
  accent,
  empty,
  children,
}: {
  title: string;
  count: number;
  accent: string;
  empty?: boolean;
  children: React.ReactNode;
}) {
  return (
    <section
      style={{
        marginBottom: 10,
      }}
    >
      <div
        className="flex items-center justify-between"
        style={{
          padding: "0 4px",
          marginBottom: 4,
        }}
      >
        <span
          className="inline-flex items-center gap-1.5"
          style={{
            fontSize: 9.5,
            fontWeight: 500,
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            color: accent,
          }}
        >
          <span style={{ width: 5, height: 5, borderRadius: "50%", background: accent }} />
          {title}
        </span>
        <span
          style={{
            fontSize: 10,
            color: "var(--muted)",
            fontVariantNumeric: "tabular-nums",
            padding: "0 6px",
            background: "var(--chip)",
            borderRadius: 999,
            fontWeight: 500,
          }}
        >
          {count}
        </span>
      </div>
      {empty ? (
        <div
          style={{
            fontSize: 11,
            color: "var(--muted)",
            fontStyle: "italic",
            opacity: 0.7,
            padding: "2px 8px 4px 9px",
            borderLeft: `2px solid var(--border)`,
            marginTop: 3,
          }}
        >
          Aguardando…
        </div>
      ) : (
        children
      )}
    </section>
  );
}

function HighlightsColumn({ onCollapse }: { onCollapse: () => void }) {
  const { highlights, isAnalyzing, lastUpdatedAt, lastLatencyMs } = useLiveHighlights();
  const total =
    highlights.decisions.length +
    highlights.nextSteps.length +
    highlights.observations.length +
    highlights.tasks.length;

  return (
    <aside
      className="flex flex-col shrink-0 min-h-0"
      style={{
        width: 244,
        borderLeft: "1px solid var(--border)",
        background: "var(--sidebar)",
      }}
    >
      <div
        className="flex items-center justify-between shrink-0"
        style={{
          padding: "8px 8px 8px 14px",
          borderBottom: "1px solid var(--border)",
        }}
      >
        <span
          className="inline-flex items-center gap-2"
          style={{
            fontSize: 10.5,
            fontWeight: 500,
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            color: "var(--muted)",
          }}
        >
          NORA detectou
          <span
            style={{
              padding: "0 7px",
              background: "var(--chip)",
              color: "var(--ink)",
              borderRadius: 999,
              fontSize: 10,
              fontWeight: 500,
              letterSpacing: 0,
            }}
          >
            {total}
          </span>
        </span>
        <div className="flex items-center gap-1">
          {isAnalyzing && (
            <span
              className="inline-flex items-center gap-1"
              style={{ fontSize: 10, color: "var(--accent-ink)" }}
            >
              <span
                style={{
                  width: 8,
                  height: 8,
                  border: "1.5px solid var(--accent-ink)",
                  borderTopColor: "transparent",
                  borderRadius: "50%",
                  animation: "nora-spin 0.9s linear infinite",
                }}
              />
            </span>
          )}
          <button
            onClick={onCollapse}
            aria-label="Esconder painel de detecções"
            title="Esconder painel"
            className="grid place-items-center rounded-md transition-colors"
            style={{
              width: 22,
              height: 22,
              background: "transparent",
              border: "none",
              color: "var(--muted)",
              cursor: "pointer",
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.background = "var(--chip)";
              e.currentTarget.style.color = "var(--ink)";
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.background = "transparent";
              e.currentTarget.style.color = "var(--muted)";
            }}
          >
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="13 17 18 12 13 7" />
              <polyline points="6 17 11 12 6 7" />
            </svg>
          </button>
        </div>
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto" style={{ padding: "10px 12px" }}>
        <HighlightSection
          title="Decisões"
          count={highlights.decisions.length}
          accent="var(--accent-ink)"
          empty={highlights.decisions.length === 0}
        >
          {highlights.decisions.map((d: LiveHighlightItem, i: number) => (
            <HighlightItem key={i} text={d.text} accent="var(--accent-ink)" />
          ))}
        </HighlightSection>
        <HighlightSection
          title="Próximos passos"
          count={highlights.nextSteps.length}
          accent="#3f8a5e"
          empty={highlights.nextSteps.length === 0}
        >
          {highlights.nextSteps.map((d: LiveHighlightItem, i: number) => (
            <HighlightItem key={i} text={d.text} accent="#3f8a5e" />
          ))}
        </HighlightSection>
        <HighlightSection
          title="Observações"
          count={highlights.observations.length}
          accent="var(--muted)"
          empty={highlights.observations.length === 0}
        >
          {highlights.observations.map((d: LiveHighlightItem, i: number) => (
            <HighlightItem key={i} text={d.text} accent="var(--muted)" />
          ))}
        </HighlightSection>
        <HighlightSection
          title="Tarefas"
          count={highlights.tasks.length}
          accent="#a37528"
          empty={highlights.tasks.length === 0}
        >
          {highlights.tasks.map((t, i) => (
            <TaskItem key={i} task={t} />
          ))}
        </HighlightSection>
      </div>
      <div
        className="flex items-center justify-between shrink-0"
        style={{
          padding: "6px 12px",
          borderTop: "1px solid var(--border)",
          fontSize: 9.5,
          color: "var(--muted)",
          letterSpacing: "0.02em",
        }}
      >
        <span>
          {lastUpdatedAt
            ? `há ${Math.max(1, Math.floor((Date.now() - lastUpdatedAt) / 1000))}s`
            : isAnalyzing
              ? "analisando…"
              : "aguardando"}
        </span>
        {lastLatencyMs !== null && (
          <span style={{ fontVariantNumeric: "tabular-nums", opacity: 0.7 }}>
            {lastLatencyMs}ms
          </span>
        )}
      </div>
    </aside>
  );
}

// ── Audio config section ──────────────────────────────────────────────
interface AudioPrerequisites {
  platform: string;
  available: boolean;
  missingDriver: string | null;
}

function AudioConfigSection({
  currentMic,
  currentSysAudio,
}: {
  currentMic: string;
  currentSysAudio: string | null;
}) {
  const [devices, setDevices] = useState<string[]>([]);
  const [mic, setMic] = useState<string>(currentMic || "");
  const [sysEnabled, setSysEnabled] = useState<boolean>(currentSysAudio !== null);
  const [sysDevice, setSysDevice] = useState<string>(currentSysAudio ?? "");
  const [platform, setPlatform] = useState<string | null>(null);
  const [needsBlackHole, setNeedsBlackHole] = useState(false);
  const [applying, setApplying] = useState(false);

  useEffect(() => {
    invoke<string[]>("list_audio_devices")
      .then(setDevices)
      .catch((e) => console.error("[overlay] list_audio_devices:", e));
    invoke<AudioPrerequisites>("check_system_audio_prerequisites")
      .then((p) => {
        setPlatform(p.platform);
        if (p.platform === "macos" && !p.available) setNeedsBlackHole(true);
      })
      .catch(() => {});
  }, []);

  // Re-sync local state when the recording status changes
  useEffect(() => {
    setMic(currentMic || "");
  }, [currentMic]);
  useEffect(() => {
    setSysDevice(currentSysAudio ?? "");
    setSysEnabled(currentSysAudio !== null);
  }, [currentSysAudio]);

  const dirty =
    (mic || null) !== (currentMic || null) ||
    sysEnabled !== (currentSysAudio !== null) ||
    (sysEnabled && (sysDevice || null) !== (currentSysAudio || null));

  const apply = async () => {
    if (!dirty || applying) return;
    setApplying(true);
    try {
      await emit("nora://restart-recording", {
        deviceName: mic || null,
        captureSystemAudio: sysEnabled,
        systemAudioDevice: sysEnabled ? sysDevice || null : null,
      });
    } catch (e) {
      console.error("[overlay] emit restart failed:", e);
    } finally {
      // Wait for status events to ripple back before clearing
      setTimeout(() => setApplying(false), 600);
    }
  };

  const selectStyle: React.CSSProperties = {
    width: "100%",
    padding: "5px 26px 5px 9px",
    fontSize: 12,
    background: "var(--canvas)",
    border: "1px solid var(--border)",
    borderRadius: 7,
    color: "var(--ink)",
    outline: "none",
    letterSpacing: "-0.005em",
    appearance: "none",
    backgroundImage:
      'url("data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'10\' height=\'10\' viewBox=\'0 0 24 24\' fill=\'none\' stroke=\'%236E7178\' stroke-width=\'2\' stroke-linecap=\'round\'><polyline points=\'6 9 12 15 18 9\'/></svg>")',
    backgroundRepeat: "no-repeat",
    backgroundPosition: "right 8px center",
    fontFamily: "var(--sans)",
  };

  return (
    <div>
      <div className="flex items-center justify-between mb-2">
        <h3
          style={{
            fontSize: 10.5,
            fontWeight: 500,
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            color: "var(--muted)",
            margin: 0,
          }}
        >
          Áudio
        </h3>
        {dirty && (
          <button
            type="button"
            onClick={apply}
            disabled={applying}
            className="inline-flex items-center gap-1.5"
            style={{
              padding: "3px 10px",
              background: "var(--ink)",
              color: "var(--canvas)",
              border: "none",
              borderRadius: 6,
              fontSize: 10.5,
              fontWeight: 500,
              letterSpacing: "-0.005em",
              cursor: applying ? "default" : "pointer",
              opacity: applying ? 0.55 : 1,
              fontFamily: "var(--sans)",
            }}
          >
            {applying ? (
              <>
                <span
                  style={{
                    width: 9,
                    height: 9,
                    border: "1.5px solid rgba(253,253,252,0.4)",
                    borderTopColor: "var(--canvas)",
                    borderRadius: "50%",
                    animation: "nora-spin 0.9s linear infinite",
                  }}
                />
                Reiniciando…
              </>
            ) : (
              "Aplicar e reiniciar"
            )}
          </button>
        )}
      </div>
      <div className="grid grid-cols-2 gap-2 mb-2">
        <label className="flex flex-col gap-1">
          <span style={{ fontSize: 10, color: "var(--muted)", letterSpacing: "0.04em", textTransform: "uppercase" }}>
            Microfone
          </span>
          <select
            value={mic}
            onChange={(e) => setMic(e.target.value)}
            style={selectStyle}
          >
            <option value="">Padrão do sistema</option>
            {devices.map((d) => (
              <option key={d} value={d}>
                {d}
              </option>
            ))}
          </select>
        </label>
        <label className="flex flex-col gap-1">
          <span style={{ fontSize: 10, color: "var(--muted)", letterSpacing: "0.04em", textTransform: "uppercase" }}>
            Áudio do sistema
          </span>
          {sysEnabled ? (
            <select
              value={sysDevice}
              onChange={(e) => setSysDevice(e.target.value)}
              style={selectStyle}
            >
              <option value="">Auto-detectar monitor</option>
              {devices.map((d) => (
                <option key={d} value={d}>
                  {d}
                </option>
              ))}
            </select>
          ) : (
            <div
              style={{
                ...selectStyle,
                color: "var(--muted)",
                background: "var(--sidebar)",
                cursor: "default",
                backgroundImage: "none",
                padding: "5px 9px",
              }}
            >
              Desativado
            </div>
          )}
        </label>
      </div>
      <label className="flex items-center gap-2 cursor-pointer" style={{ fontSize: 11.5 }}>
        <input
          type="checkbox"
          checked={sysEnabled}
          onChange={(e) => setSysEnabled(e.target.checked)}
          style={{ accentColor: "var(--accent)" }}
        />
        <span style={{ color: "var(--ink)" }}>Capturar áudio do sistema</span>
      </label>
      {needsBlackHole && platform === "macos" && (
        <div
          className="mt-2"
          style={{
            padding: "7px 10px",
            background: "var(--accent-soft)",
            border: "1px solid var(--border)",
            borderRadius: 8,
            fontSize: 11.5,
            color: "var(--accent-ink)",
            lineHeight: 1.5,
          }}
        >
          No macOS, instale{" "}
          <a
            href="https://existential.audio/blackhole/"
            target="_blank"
            rel="noopener noreferrer"
            style={{
              color: "var(--accent-ink)",
              textDecoration: "underline",
            }}
          >
            BlackHole
          </a>{" "}
          pra capturar áudio do sistema.
        </div>
      )}
    </div>
  );
}

// ── Drawer (config) ────────────────────────────────────────────────────
function ConfigDrawer({
  currentMic,
  currentSysAudio,
  detectedSpeakers,
  overrides,
  onRename,
  dockVisible,
  onToggleDock,
}: {
  currentMic: string;
  currentSysAudio: string | null;
  detectedSpeakers: { speakerId: string; fallback: string }[];
  overrides: SpeakerMap;
  onRename: (id: string, name: string) => void;
  dockVisible: boolean;
  onToggleDock: (v: boolean) => void;
}) {
  return (
    <div
      className="shrink-0 overflow-y-auto"
      style={{
        background: "var(--sidebar)",
        borderBottom: "1px solid var(--border)",
        padding: "12px 18px",
        maxHeight: 280,
        animation: "overlayDrawerIn 180ms cubic-bezier(.2,.8,.2,1)",
        display: "flex",
        flexDirection: "column",
        gap: 14,
      }}
    >
      {/* Áudio */}
      <AudioConfigSection
        currentMic={currentMic}
        currentSysAudio={currentSysAudio}
      />

      {/* Janelas */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <h3
            style={{
              fontSize: 10.5,
              fontWeight: 500,
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              color: "var(--muted)",
              margin: 0,
            }}
          >
            Janelas
          </h3>
        </div>
        <label
          className="flex items-start justify-between gap-3 cursor-pointer"
          style={{
            padding: "8px 12px",
            background: "var(--canvas)",
            border: "1px solid var(--border)",
            borderRadius: 9,
          }}
        >
          <div className="flex-1 min-w-0">
            <div
              style={{
                fontSize: 12.5,
                color: "var(--ink)",
                fontWeight: 500,
                letterSpacing: "-0.005em",
              }}
            >
              Mostrar dock no rodapé
            </div>
            <div
              style={{
                fontSize: 11,
                color: "var(--muted)",
                marginTop: 3,
                lineHeight: 1.5,
              }}
            >
              Barra flutuante com atalhos pra alternar entre NORA Desktop e a overlay.
            </div>
          </div>
          <button
            type="button"
            role="switch"
            aria-checked={dockVisible}
            onClick={() => onToggleDock(!dockVisible)}
            style={{
              width: 34,
              height: 20,
              borderRadius: 999,
              background: dockVisible ? "var(--accent)" : "var(--chip)",
              border: "none",
              cursor: "pointer",
              padding: 0,
              position: "relative",
              flexShrink: 0,
              marginTop: 2,
              transition: "background 160ms ease",
            }}
          >
            <span
              style={{
                position: "absolute",
                top: 2,
                left: dockVisible ? 16 : 2,
                width: 16,
                height: 16,
                borderRadius: "50%",
                background: "white",
                boxShadow: "0 1px 2px rgba(0,0,0,0.18)",
                transition: "left 160ms ease",
              }}
            />
          </button>
        </label>
      </div>

      {/* Falantes */}
      <div>
        <div className="flex items-center justify-between mb-2">
          <h3
            style={{
              fontSize: 10.5,
              fontWeight: 500,
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              color: "var(--muted)",
              margin: 0,
            }}
          >
            Falantes detectados
          </h3>
          <span style={{ fontSize: 11, color: "var(--muted)" }}>
            {detectedSpeakers.length}{" "}
            {detectedSpeakers.length === 1 ? "pessoa" : "pessoas"}
          </span>
        </div>
        {detectedSpeakers.length === 0 ? (
          <div style={{ fontSize: 12, color: "var(--muted)", lineHeight: 1.5 }}>
            Ainda não detectei outros falantes. Você pode renomear cada voz aqui
            em tempo real.
          </div>
        ) : (
          <div className="flex flex-col gap-1.5">
            {detectedSpeakers.map((s) => (
              <div key={s.speakerId} className="flex items-center gap-2">
                <Avatar
                  name={overrides[s.speakerId] || s.fallback}
                  size={22}
                />
                <input
                  type="text"
                  value={overrides[s.speakerId] ?? ""}
                  onChange={(e) => onRename(s.speakerId, e.target.value)}
                  placeholder={s.fallback}
                  className="flex-1 outline-none"
                  style={{
                    padding: "5px 9px",
                    background: "var(--canvas)",
                    border: "1px solid var(--border)",
                    borderRadius: 7,
                    fontSize: 12,
                    color: "var(--ink)",
                    letterSpacing: "-0.005em",
                    fontFamily: "var(--sans)",
                  }}
                  onFocus={(e) => {
                    e.currentTarget.style.borderColor = "var(--accent)";
                    e.currentTarget.style.boxShadow =
                      "0 0 0 3px var(--accent-soft)";
                  }}
                  onBlur={(e) => {
                    e.currentTarget.style.borderColor = "var(--border)";
                    e.currentTarget.style.boxShadow = "none";
                  }}
                />
                <span
                  style={{
                    fontSize: 10,
                    color: "var(--muted)",
                    fontVariantNumeric: "tabular-nums",
                    minWidth: 50,
                    textAlign: "right",
                  }}
                >
                  {s.speakerId}
                </span>
              </div>
            ))}
          </div>
        )}
      </div>

      <style>{`
        @keyframes overlayDrawerIn {
          from { opacity: 0; transform: translateY(-4px); }
          to   { opacity: 1; transform: translateY(0); }
        }
      `}</style>
    </div>
  );
}

// ── Main ───────────────────────────────────────────────────────────────
export function OverlayPage() {
  const {
    lines,
    partial,
    isRecording,
    startedAt,
    duration,
    micDevice,
    systemAudioDevice,
  } = useLiveTranscript();

  const [overrides, setOverrides] = useState<SpeakerMap>(loadOverrides);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [dockVisible, setDockVisible] = useState<boolean>(loadDockPref);
  const [highlightsVisible, setHighlightsVisible] = useState<boolean>(
    loadHighlightsPref,
  );
  const [stopping, setStopping] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [closeConfirm, setCloseConfirm] = useState(false);

  const toggleHighlights = (next: boolean) => {
    setHighlightsVisible(next);
    saveHighlightsPref(next);
  };

  // Reset overrides when a new recording session begins
  useEffect(() => {
    if (isRecording && lines.length === 0) {
      setOverrides({});
      saveOverrides({});
    }
  }, [isRecording, lines.length]);

  // Listen for save flow result from main window
  useEffect(() => {
    const unlisten = listen<{ ok: boolean; error?: string; meetingId?: string }>(
      "nora://save-result",
      (e) => {
        setStopping(false);
        if (!e.payload?.ok) {
          setSaveError(e.payload?.error || "Falha ao salvar a reunião.");
        } else {
          setSaveError(null);
        }
      },
    );
    return () => {
      unlisten.then((fn) => fn()).catch(() => {});
    };
  }, []);

  // Clear save-error when a fresh recording starts
  useEffect(() => {
    if (isRecording && lines.length === 0) setSaveError(null);
  }, [isRecording, lines.length]);

  // Sync dock visibility when changed by another window (dock's own X button)
  useEffect(() => {
    const unlisten = listen<{ visible: boolean }>(
      "nora://dock-visibility-changed",
      (e) => {
        if (typeof e.payload?.visible === "boolean") {
          setDockVisible(e.payload.visible);
          saveDockPref(e.payload.visible);
        }
      },
    );
    return () => {
      unlisten.then((fn) => fn()).catch(() => {});
    };
  }, []);

  const renameSpeaker = (speakerId: string, name: string) => {
    setOverrides((prev) => {
      const next = { ...prev, [speakerId]: name };
      saveOverrides(next);
      return next;
    });
    emit("nora://rename-speaker", { speakerId, name }).catch(() => {});
  };

  const toggleDock = (next: boolean) => {
    setDockVisible(next);
    saveDockPref(next);
    invoke("toggle_dock", { show: next }).catch(() => {});
  };

  const detectedSpeakers = useMemo(() => {
    const seen = new Set<string>();
    const out: { speakerId: string; fallback: string }[] = [];
    for (const l of lines) {
      if (l.track === "mic") continue;
      const id = l.speakerId || l.speaker;
      if (!id || seen.has(id)) continue;
      seen.add(id);
      out.push({
        speakerId: id,
        fallback: l.speaker || l.speakerId || "Falante",
      });
    }
    return out;
  }, [lines]);

  const groups = useMemo(() => groupLines(lines, overrides), [lines, overrides]);

  const lastTrack = lines.length > 0 ? lines[lines.length - 1].track : "system";
  const partialIsMe = lastTrack === "mic";

  const scrollRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [groups.length, partial]);

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
  const handleCloseRequest = () => {
    if (isRecording) {
      // Tem gravação rodando — não dá pra só esconder, vai vazar áudio
      // capturando pra sempre. Pede confirmação.
      setCloseConfirm(true);
    } else {
      invoke("toggle_overlay", { show: false }).catch(() => {});
    }
  };

  const empty = groups.length === 0 && !partial;

  return (
    <div
      className="h-screen w-screen flex flex-col select-none overflow-hidden"
      style={{
        // Sólido — sem rgba/backdrop-filter. WebKitGTK no Linux deixava
        // pixels stale fora do raio dos cantos arredondados e blureava de
        // forma inconsistente. Solid + box-shadow ainda dá o "flutuante".
        background: "var(--canvas)",
        color: "var(--ink)",
        borderRadius: 10,
        border: "1px solid var(--border)",
        boxShadow:
          "0 18px 42px -20px rgba(15, 23, 42, 0.28), 0 4px 12px rgba(15, 23, 42, 0.06)",
      }}
    >
      {/* Header */}
      <div
        data-tauri-drag-region
        className="flex items-center justify-between shrink-0"
        style={{
          padding: "9px 14px",
          borderBottom: "1px solid var(--border)",
          background: "var(--sidebar)",
          cursor: "move",
        }}
      >
        <div className="flex items-center gap-2.5 min-w-0">
          <BrandBars active={isRecording} />
          <span
            className="truncate"
            style={{
              fontSize: 11.5,
              fontWeight: 500,
              letterSpacing: "-0.005em",
              color: isRecording ? "var(--danger-ink)" : "var(--muted)",
            }}
          >
            {isRecording ? "NORA · gravando" : "NORA Live"}
          </span>
          {isRecording && (
            <span
              style={{
                fontSize: 11,
                color: "var(--ink)",
                fontVariantNumeric: "tabular-nums",
                padding: "1px 8px",
                border: "1px solid var(--border)",
                borderRadius: 999,
                background: "var(--canvas)",
                fontWeight: 500,
              }}
            >
              {formatDuration(duration)}
            </span>
          )}
        </div>
        <div className="flex items-center gap-1.5">
          {isRecording && (
            <>
              <button
                onClick={handleCancel}
                disabled={stopping}
                aria-label="Descartar"
                title="Descartar"
                style={{
                  padding: "4px 10px",
                  fontSize: 11,
                  background: "transparent",
                  border: "1px solid var(--border)",
                  borderRadius: 7,
                  color: "var(--muted)",
                  cursor: stopping ? "default" : "pointer",
                  letterSpacing: "-0.005em",
                  fontWeight: 500,
                  fontFamily: "var(--sans)",
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
                className="inline-flex items-center gap-1.5"
                style={{
                  padding: "4px 11px 4px 9px",
                  fontSize: 11,
                  background: "var(--ink)",
                  color: "var(--canvas)",
                  border: "1px solid var(--ink)",
                  borderRadius: 7,
                  cursor: stopping ? "default" : "pointer",
                  letterSpacing: "-0.005em",
                  fontWeight: 500,
                  fontFamily: "var(--sans)",
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
                        width: 9,
                        height: 9,
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
                        width: 7,
                        height: 7,
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
            onClick={() => setDrawerOpen((v) => !v)}
            aria-label="Configurações"
            title="Configurações"
            className="grid place-items-center rounded-md transition-colors"
            style={{
              width: 24,
              height: 24,
              background: drawerOpen ? "var(--chip)" : "transparent",
              border: "none",
              color: drawerOpen ? "var(--ink)" : "var(--muted)",
              cursor: "pointer",
              marginLeft: 2,
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.background = "rgba(0,0,0,0.05)";
              e.currentTarget.style.color = "var(--ink)";
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.background = drawerOpen
                ? "var(--chip)"
                : "transparent";
              e.currentTarget.style.color = drawerOpen
                ? "var(--ink)"
                : "var(--muted)";
            }}
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h0a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h0a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
            </svg>
          </button>
          <button
            onClick={handleCloseRequest}
            aria-label="Fechar"
            title={isRecording ? "Fechar (confirmação)" : "Fechar"}
            className="grid place-items-center rounded-md transition-colors"
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
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </div>
      </div>

      {/* Drawer */}
      {drawerOpen && (
        <ConfigDrawer
          currentMic={micDevice}
          currentSysAudio={systemAudioDevice}
          detectedSpeakers={detectedSpeakers}
          overrides={overrides}
          onRename={renameSpeaker}
          dockVisible={dockVisible}
          onToggleDock={toggleDock}
        />
      )}

      {/* Save error banner */}
      {saveError && (
        <div
          className="shrink-0 flex items-start gap-3"
          style={{
            padding: "10px 14px",
            background: "rgba(201, 119, 102, 0.10)",
            borderBottom: "1px solid rgba(201, 119, 102, 0.30)",
          }}
        >
          <svg
            className="shrink-0 mt-0.5"
            width="15" height="15" viewBox="0 0 24 24" fill="none"
            stroke="var(--danger-ink)" strokeWidth="1.7"
            strokeLinecap="round" strokeLinejoin="round"
          >
            <circle cx="12" cy="12" r="10" />
            <line x1="12" y1="8" x2="12" y2="12" />
            <line x1="12" y1="16" x2="12.01" y2="16" />
          </svg>
          <div className="flex-1 min-w-0">
            <div
              style={{
                fontSize: 12,
                fontWeight: 500,
                color: "var(--danger-ink)",
                letterSpacing: "-0.005em",
                marginBottom: 2,
              }}
            >
              Falha ao salvar a reunião
            </div>
            <div style={{ fontSize: 11, color: "var(--ink)", lineHeight: 1.5 }}>
              {saveError} · Salvamos uma cópia local — vamos retentar a cada 30s.
            </div>
          </div>
          <div className="flex items-center gap-1.5 shrink-0">
            <button
              onClick={() => {
                setStopping(true);
                setSaveError(null);
                emit("nora://retry-save").catch(() => {
                  setStopping(false);
                });
              }}
              disabled={stopping}
              style={{
                padding: "4px 10px",
                fontSize: 11,
                background: "var(--ink)",
                color: "var(--canvas)",
                border: "1px solid var(--ink)",
                borderRadius: 7,
                cursor: stopping ? "default" : "pointer",
                letterSpacing: "-0.005em",
                fontWeight: 500,
                fontFamily: "var(--sans)",
                opacity: stopping ? 0.55 : 1,
              }}
            >
              {stopping ? "Tentando…" : "Tentar de novo"}
            </button>
            <button
              onClick={() => setSaveError(null)}
              style={{
                padding: "4px 10px",
                fontSize: 11,
                background: "transparent",
                border: "1px solid var(--border)",
                borderRadius: 7,
                color: "var(--muted)",
                cursor: "pointer",
                letterSpacing: "-0.005em",
                fontWeight: 500,
                fontFamily: "var(--sans)",
              }}
            >
              Esconder
            </button>
          </div>
        </div>
      )}

      {/* Body: chat (left) + highlights (right, collapsible) */}
      <div className="flex-1 min-h-0 flex relative">
        <div
          ref={scrollRef}
          className="flex-1 min-w-0 overflow-y-auto"
          style={{ padding: "14px 16px 16px" }}
        >
          {empty ? (
            <div className="h-full flex flex-col items-center justify-center text-center gap-2">
              <ShaderOrb
                size={42}
                speed={isRecording ? 1.4 : 0.6}
                intensity={isRecording ? 0.95 : 0.45}
              />
              <div
                style={{
                  fontSize: 12.5,
                  color: "var(--ink)",
                  fontWeight: 500,
                  letterSpacing: "-0.012em",
                }}
              >
                {isRecording ? "Aguardando fala…" : "Inicie uma gravação"}
              </div>
              <div
                style={{
                  fontSize: 11,
                  color: "var(--muted)",
                  lineHeight: 1.5,
                  maxWidth: 280,
                }}
              >
                {isRecording
                  ? "Suas falas à direita com a bolinha azul, as dos outros à esquerda."
                  : "A overlay abre automaticamente ao iniciar uma reunião."}
              </div>
            </div>
          ) : (
            <div className="flex flex-col gap-2.5">
              {groups.map((g) => (
                <ChatBubble key={g.id} group={g} startedAt={startedAt} />
              ))}
              {partial && <PartialBubble text={partial} isMe={partialIsMe} />}
            </div>
          )}
        </div>

        {highlightsVisible && (
          <HighlightsColumn onCollapse={() => toggleHighlights(false)} />
        )}

        {!highlightsVisible && (
          <button
            onClick={() => toggleHighlights(true)}
            aria-label="Mostrar painel de detecções"
            title="Mostrar detecções"
            className="absolute grid place-items-center transition-colors"
            style={{
              top: "50%",
              right: 8,
              transform: "translateY(-50%)",
              width: 22,
              height: 56,
              borderRadius: 8,
              background: "var(--canvas)",
              border: "1px solid var(--border)",
              color: "var(--muted)",
              cursor: "pointer",
            }}
            onMouseEnter={(e) => {
              e.currentTarget.style.background = "var(--sidebar)";
              e.currentTarget.style.color = "var(--ink)";
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.background = "var(--canvas)";
              e.currentTarget.style.color = "var(--muted)";
            }}
          >
            <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="11 17 6 12 11 7" />
              <polyline points="18 17 13 12 18 7" />
            </svg>
          </button>
        )}
      </div>

      {/* Close confirmation dialog */}
      {closeConfirm && (
        <CloseConfirmDialog
          onContinue={() => setCloseConfirm(false)}
          onStopAndSave={() => {
            setCloseConfirm(false);
            handleStop();
          }}
          onDiscard={() => {
            setCloseConfirm(false);
            handleCancel();
          }}
        />
      )}
    </div>
  );
}

// ── Close confirmation dialog ─────────────────────────────────────────
function CloseConfirmDialog({
  onContinue,
  onStopAndSave,
  onDiscard,
}: {
  onContinue: () => void;
  onStopAndSave: () => void;
  onDiscard: () => void;
}) {
  // Esc cancels (= continue recording, safest default)
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onContinue();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [onContinue]);

  return (
    <div
      onClick={onContinue}
      role="dialog"
      aria-modal="true"
      style={{
        position: "absolute",
        inset: 0,
        background: "rgba(20, 22, 26, 0.42)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 18,
        zIndex: 20,
        animation: "paletteFadeIn 160ms ease",
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: "min(380px, 100%)",
          background: "var(--canvas)",
          border: "1px solid var(--border)",
          borderRadius: 12,
          padding: "18px 18px 16px",
          display: "flex",
          flexDirection: "column",
          gap: 14,
          animation: "paletteSlideIn 200ms cubic-bezier(.2,.8,.2,1)",
        }}
      >
        <div className="flex items-start gap-3">
          <div
            className="grid place-items-center shrink-0"
            style={{
              width: 32,
              height: 32,
              borderRadius: 10,
              background: "rgba(201, 119, 102, 0.16)",
              color: "var(--danger-ink)",
            }}
          >
            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <path d="M10.29 3.86L1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" />
              <line x1="12" y1="9" x2="12" y2="13" />
              <line x1="12" y1="17" x2="12.01" y2="17" />
            </svg>
          </div>
          <div className="flex-1">
            <h3
              style={{
                fontFamily: "var(--display)",
                fontSize: 15,
                fontWeight: 500,
                letterSpacing: "-0.012em",
                color: "var(--ink)",
                margin: 0,
                lineHeight: 1.3,
              }}
            >
              Encerrar gravação?
            </h3>
            <p
              style={{
                fontSize: 12.5,
                color: "var(--muted)",
                margin: "4px 0 0",
                lineHeight: 1.5,
              }}
            >
              A captura ainda está ativa. Se descartar agora, a transcrição em
              andamento e os destaques detectados serão perdidos.
            </p>
          </div>
        </div>
        <div className="flex flex-col gap-2">
          <button
            type="button"
            onClick={onStopAndSave}
            className="inline-flex items-center justify-center gap-2"
            style={{
              padding: "9px 14px",
              background: "var(--ink)",
              color: "var(--canvas)",
              border: "1px solid var(--ink)",
              borderRadius: 9,
              fontSize: 13,
              fontWeight: 500,
              letterSpacing: "-0.005em",
              cursor: "pointer",
              fontFamily: "var(--sans)",
            }}
          >
            <span
              style={{
                width: 7,
                height: 7,
                background: "var(--canvas)",
                borderRadius: 2,
              }}
            />
            Parar e salvar
          </button>
          <div className="flex gap-2">
            <button
              type="button"
              onClick={onContinue}
              style={{
                flex: 1,
                padding: "8px 12px",
                background: "transparent",
                border: "1px solid var(--border)",
                borderRadius: 9,
                fontSize: 12.5,
                color: "var(--ink)",
                cursor: "pointer",
                fontFamily: "var(--sans)",
                fontWeight: 500,
                letterSpacing: "-0.005em",
              }}
            >
              Continuar gravando
            </button>
            <button
              type="button"
              onClick={onDiscard}
              style={{
                flex: 1,
                padding: "8px 12px",
                background: "transparent",
                border: "1px solid rgba(201, 119, 102, 0.4)",
                borderRadius: 9,
                fontSize: 12.5,
                color: "var(--danger-ink)",
                cursor: "pointer",
                fontFamily: "var(--sans)",
                fontWeight: 500,
                letterSpacing: "-0.005em",
              }}
            >
              Descartar
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}
