import { useEffect, useMemo, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { emit } from "@tauri-apps/api/event";
import { getCurrentWebviewWindow } from "@tauri-apps/api/webviewWindow";
import { useLiveTranscript, type LiveTranscriptLine } from "@/hooks/use-live-transcript";
import { useLiveHighlights, type LiveHighlights } from "@/hooks/use-live-highlights";
import { useTauriListener } from "@/hooks/use-tauri-listener";
import { ShaderOrb } from "@/components/brand/shader-orb";
import { Avatar } from "@/components/brand/avatar";
import { NoraBars } from "@/components/brand/nora-bars";
import {
  NotificationStack,
  useNotifications,
} from "@/components/overlay-notifications";
import { formatDuration, relTime } from "@/lib/format";
import { DecisionIcon, NextStepIcon, ObservationIcon, TaskIcon } from "@/components/brand/feed-icons";
import { getDockVisible, setDockVisible as persistDockPref } from "@/lib/dock-prefs";
import { EVENTS, type DockVisibilityPayload } from "@/lib/desktop-events";
import { Button, IconButton, Input } from "./ui";

type SpeakerMap = Record<string, string>;

const SPEAKER_OVERRIDES_KEY = "nora.overlay.speaker-overrides";
const HIGHLIGHTS_STORAGE_KEY = "nora.overlay.highlights-visible";

function loadHighlightsPref(): boolean {
  // Default: minimizado. Highlights aparecem como toasts e o usuário expande
  // o painel se quiser ver o histórico.
  try {
    const v = localStorage.getItem(HIGHLIGHTS_STORAGE_KEY);
    return v == null ? false : v === "1";
  } catch {
    return false;
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
                borderRadius: "var(--radius-md)",
                borderTopRightRadius: isMe ? 5 : 12,
                borderTopLeftRadius: isMe ? 12 : 5,
                fontSize: 12.5,
                lineHeight: 1.45,
                letterSpacing: "-0.005em",
                textAlign: "left",
                whiteSpace: "pre-wrap",
                wordBreak: "break-word",
                boxShadow: isMe
                  ? "var(--shadow-md)"
                  : "var(--shadow-sm)",
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
          borderRadius: "var(--radius-md)",
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

// ── Highlights feed (right panel) ──────────────────────────────────────
// Um único feed cronológico, baixo em cor. Sem seções rígidas, sem blocos
// coloridos por categoria: cada detecção é uma linha com um glifo monocromático
// e um eyebrow do tipo. O azul de acento fica reservado só pro item mais
// recente (cue de "acabou de detectar"), que esmaece ao chegar o próximo.
type FeedKind = "decision" | "nextStep" | "observation" | "task";

const KIND_META: Record<FeedKind, { label: string; icon: React.ReactElement }> = {
  decision: { label: "Decisão", icon: <DecisionIcon /> },
  nextStep: { label: "Próximo passo", icon: <NextStepIcon /> },
  observation: { label: "Observação", icon: <ObservationIcon /> },
  task: { label: "Tarefa", icon: <TaskIcon /> },
};

interface FeedRow {
  id: string;
  kind: FeedKind;
  text: string;
  receivedAt: number;
  priority?: string;
  assignee?: string | null;
}

function buildFeed(h: LiveHighlights): FeedRow[] {
  const rows: FeedRow[] = [];
  const norm = (s: string) => s.toLowerCase().trim();
  for (const d of h.decisions)
    rows.push({ id: `decision-${norm(d.text)}`, kind: "decision", text: d.text, receivedAt: d.receivedAt ?? 0 });
  for (const d of h.nextSteps)
    rows.push({ id: `nextStep-${norm(d.text)}`, kind: "nextStep", text: d.text, receivedAt: d.receivedAt ?? 0 });
  for (const d of h.observations)
    rows.push({ id: `observation-${norm(d.text)}`, kind: "observation", text: d.text, receivedAt: d.receivedAt ?? 0 });
  for (const t of h.tasks)
    rows.push({
      id: `task-${norm(t.title)}`,
      kind: "task",
      text: t.title,
      receivedAt: t.receivedAt ?? 0,
      priority: t.priority,
      assignee: t.assignee,
    });
  // Mais recente no topo. Sort estável mantém ordem de inserção em empates
  // (itens da mesma rodada de análise compartilham ~o mesmo receivedAt).
  return rows.sort((a, b) => b.receivedAt - a.receivedAt);
}

function DetectionRow({ row, fresh }: { row: FeedRow; fresh: boolean }) {
  const meta = KIND_META[row.kind];
  const isHighTask = row.kind === "task" && row.priority === "HIGH";
  return (
    <div
      className="flex items-start gap-2.5"
      style={{
        padding: "8px 9px",
        borderRadius: 9,
        background: fresh ? "var(--accent-soft)" : "transparent",
        animation: "revealUp 220ms cubic-bezier(.2,.8,.2,1)",
        transition: "background 700ms ease",
      }}
    >
      <span
        className="grid place-items-center shrink-0"
        style={{
          width: 20,
          height: 20,
          borderRadius: 6,
          background: fresh ? "var(--canvas)" : "var(--chip)",
          color: fresh ? "var(--accent-ink)" : "var(--muted)",
          marginTop: 1,
          transition: "color 700ms ease, background 700ms ease",
        }}
      >
        {meta.icon}
      </span>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1.5" style={{ marginBottom: 2 }}>
          <span
            style={{
              fontSize: 9,
              fontWeight: 500,
              letterSpacing: "0.08em",
              textTransform: "uppercase",
              color: fresh ? "var(--accent-ink)" : "var(--muted)",
              transition: "color 700ms ease",
            }}
          >
            {meta.label}
          </span>
          {row.kind === "task" && row.priority && (
            <span
              className="inline-flex items-center gap-1"
              style={{
                fontSize: 9,
                letterSpacing: "0.04em",
                color: isHighTask ? "var(--danger-ink)" : "var(--muted)",
              }}
            >
              <span
                style={{
                  width: 3,
                  height: 3,
                  borderRadius: "50%",
                  background: isHighTask ? "var(--danger-ink)" : "var(--muted)",
                }}
              />
              {row.priority}
            </span>
          )}
        </div>
        <div
          style={{
            fontSize: 12,
            color: "var(--ink)",
            lineHeight: 1.4,
            letterSpacing: "-0.005em",
          }}
        >
          {row.text}
        </div>
        {row.kind === "task" && row.assignee && (
          <div style={{ fontSize: 10.5, color: "var(--muted)", marginTop: 2 }}>
            {row.assignee}
          </div>
        )}
      </div>
    </div>
  );
}

function HighlightsColumn({ onCollapse }: { onCollapse: () => void }) {
  const { highlights, isAnalyzing, lastUpdatedAt, lastLatencyMs } = useLiveHighlights();
  const feed = useMemo(() => buildFeed(highlights), [highlights]);
  const total = feed.length;
  const newestAt = total > 0 ? feed[0].receivedAt : 0;

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
          Nora detectou
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
          <IconButton
            size="sm"
            onClick={onCollapse}
            aria-label="Esconder painel de detecções"
            title="Esconder painel"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <polyline points="13 17 18 12 13 7" />
              <polyline points="6 17 11 12 6 7" />
            </svg>
          </IconButton>
        </div>
      </div>
      <div className="flex-1 min-h-0 overflow-y-auto" style={{ padding: "6px 8px" }}>
        {total === 0 ? (
          <div
            className="h-full flex flex-col items-center justify-center text-center"
            style={{ gap: 10, padding: "0 18px" }}
          >
            <NoraBars size={18} active={isAnalyzing} animate={isAnalyzing} />
            <div style={{ fontSize: 11.5, color: "var(--muted)", lineHeight: 1.5 }}>
              {isAnalyzing
                ? "Analisando a conversa…"
                : "Decisões, tarefas e observações aparecem aqui conforme a Nora detecta."}
            </div>
          </div>
        ) : (
          <div className="flex flex-col gap-px">
            {feed.map((row) => (
              <DetectionRow
                key={row.id}
                row={row}
                fresh={row.receivedAt > 0 && row.receivedAt === newestAt}
              />
            ))}
          </div>
        )}
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
          <Button
            variant="primary"
            size="sm"
            onClick={apply}
            disabled={applying}
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
          </Button>
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
              Barra flutuante com atalhos pra alternar entre Nora Desktop e a overlay.
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
                <Input
                  type="text"
                  value={overrides[s.speakerId] ?? ""}
                  onChange={(e) => onRename(s.speakerId, e.target.value)}
                  placeholder={s.fallback}
                  className="flex-1"
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
    partials,
    isRecording,
    startedAt,
    duration,
    micDevice,
    systemAudioDevice,
  } = useLiveTranscript();

  const [overrides, setOverrides] = useState<SpeakerMap>(loadOverrides);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [dockVisible, setDockVisible] = useState<boolean>(getDockVisible);
  const [highlightsVisible, setHighlightsVisible] = useState<boolean>(
    loadHighlightsPref,
  );
  const [stopping, setStopping] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [closeConfirm, setCloseConfirm] = useState(false);

  const { items: notifications, push: pushNotification, dismiss: dismissNotification } =
    useNotifications();

  // Arraste da overlay via startDragging() — funciona no WebKitGTK (Linux), onde
  // data-tauri-drag-region / -webkit-app-region NÃO funcionam. Mesmo padrão do
  // titlebar e do dock. Só botão esquerdo; os controles ficam fora da região.
  const overlayWin = useMemo(() => getCurrentWebviewWindow(), []);
  const onHeaderDragMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0) return;
    overlayWin
      .startDragging()
      .catch((err) => console.warn("[overlay] startDragging failed:", err));
  };

  // Listen to highlights and surface new items as toasts
  const { highlights } = useLiveHighlights();
  const prevHighlightCountsRef = useRef({
    decisions: 0,
    nextSteps: 0,
    observations: 0,
    tasks: 0,
  });
  const highlightsInitializedRef = useRef(false);

  useEffect(() => {
    const prev = prevHighlightCountsRef.current;
    const current = {
      decisions: highlights.decisions.length,
      nextSteps: highlights.nextSteps.length,
      observations: highlights.observations.length,
      tasks: highlights.tasks.length,
    };

    // First mount or a session reset (counts went down) — capture baseline,
    // don't notify retroactively.
    if (
      !highlightsInitializedRef.current ||
      current.decisions < prev.decisions ||
      current.nextSteps < prev.nextSteps ||
      current.observations < prev.observations ||
      current.tasks < prev.tasks
    ) {
      prevHighlightCountsRef.current = current;
      highlightsInitializedRef.current = true;
      return;
    }

    const newDecisions = highlights.decisions.slice(prev.decisions);
    const newNextSteps = highlights.nextSteps.slice(prev.nextSteps);
    const newObservations = highlights.observations.slice(prev.observations);
    const newTasks = highlights.tasks.slice(prev.tasks);

    newDecisions.forEach((d) =>
      pushNotification({ variant: "decision", title: d.text }),
    );
    newNextSteps.forEach((n) =>
      pushNotification({ variant: "action", title: n.text }),
    );
    newObservations.forEach((o) =>
      pushNotification({ variant: "observation", title: o.text }),
    );
    newTasks.forEach((t) =>
      pushNotification({
        variant: "task",
        title: t.title,
        body: t.assignee ? `Atribuído a ${t.assignee}` : undefined,
      }),
    );

    prevHighlightCountsRef.current = current;
  }, [highlights, pushNotification]);

  const toggleHighlights = (next: boolean) => {
    setHighlightsVisible(next);
    saveHighlightsPref(next);
  };

  // Reset de estado quando uma nova sessão de gravação começa. Unifica dois
  // useEffect que tinham a MESMA guarda (auditoria #44) e zera `stopping` pra
  // não ficar travado entre sessões depois de um Descartar (#32).
  useEffect(() => {
    if (isRecording && lines.length === 0) {
      setOverrides({});
      saveOverrides({});
      setSaveError(null);
      setStopping(false);
    }
  }, [isRecording, lines.length]);

  // Listen for save flow result from main window
  useTauriListener<{ ok: boolean; error?: string; meetingId?: string }>(
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

  // Sync dock visibility when changed by another window (dock's own X button)
  useTauriListener<DockVisibilityPayload>(
    EVENTS.DOCK_VISIBILITY_CHANGED,
    (e) => {
      if (typeof e.payload?.visible !== "boolean") return;
      const visible = e.payload.visible;
      setDockVisible(visible);
      persistDockPref(visible);
      if (!visible) {
        pushNotification({
          variant: "info",
          title: "Dock escondido",
          body: "Você pode trazer de volta no toggle do drawer.",
          duration: 5000,
        });
      }
    },
    [pushNotification],
  );

  // Erros do sidecar de STT (Rust emite "stt-error" com o payload cru do
  // sidecar). Sem este listener o erro de transcrição sumia sem feedback.
  useTauriListener<{ message?: string; error?: string }>(
    "stt-error",
    (e) => {
      const detail =
        typeof e.payload?.message === "string"
          ? e.payload.message
          : typeof e.payload?.error === "string"
            ? e.payload.error
            : undefined;
      pushNotification({
        variant: "warn",
        title: "Transcrição interrompida",
        body: detail ?? "O serviço de transcrição reportou um erro.",
        duration: 8000,
      });
    },
    [pushNotification],
  );

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
    persistDockPref(next);
    invoke("toggle_dock", { show: next }).catch(() => {});
    // Espelha pelo event bus pra qualquer outra janela aberta (e pra ativar
    // o toast de "Dock escondido" via o mesmo listener que cobre o X do dock).
    emit(EVENTS.DOCK_VISIBILITY_CHANGED, { visible: next }).catch(() => {});
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

  // Um "digitando" por track (mic = eu/direita, system = convidado/esquerda).
  // Ordena convidado antes de mim, então minha bolha fica por último (embaixo),
  // como num chat. Cada parcial usa o SEU track — não o do último final.
  const partialBubbles = useMemo(() => {
    return Object.entries(partials)
      .filter(([, text]) => text && text.trim().length > 0)
      .sort(([a], [b]) => (a === "mic" ? 1 : 0) - (b === "mic" ? 1 : 0))
      .map(([track, text]) => ({ track, text, isMe: track === "mic" }));
  }, [partials]);
  const partialKey = partialBubbles.map((p) => `${p.track}:${p.text.length}`).join("|");

  const scrollRef = useRef<HTMLDivElement | null>(null);
  // "Grudado no fim": só auto-scrolla quando o usuário já está no rodapé. Se ele
  // rolou pra cima (pra reler o histórico enquanto fala), respeitamos a posição
  // e não puxamos de volta a cada parcial. Quando ele volta pro fim, regruda.
  const pinnedToBottomRef = useRef(true);
  const [showJumpToLatest, setShowJumpToLatest] = useState(false);

  const onChatScroll = () => {
    const el = scrollRef.current;
    if (!el) return;
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    const atBottom = distanceFromBottom < 60;
    pinnedToBottomRef.current = atBottom;
    setShowJumpToLatest((prev) => (prev === !atBottom ? prev : !atBottom));
  };

  const jumpToLatest = () => {
    const el = scrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
    pinnedToBottomRef.current = true;
    setShowJumpToLatest(false);
  };

  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    if (pinnedToBottomRef.current) el.scrollTop = el.scrollHeight;
  }, [groups.length, partialKey]);

  // Nova sessão (linhas zeradas) — regruda no fim e some o botão.
  useEffect(() => {
    if (lines.length === 0) {
      pinnedToBottomRef.current = true;
      setShowJumpToLatest(false);
    }
  }, [lines.length]);

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
    setStopping(true);
    try {
      await emit("nora://cancel-recording");
    } catch (e) {
      console.error("[overlay] failed to emit cancel:", e);
      setStopping(false);
    }
  };
  const handleMinimize = () => {
    // Esconde a overlay sem parar a gravação (toggle_overlay só dá window.hide).
    // Garante o dock visível pra ter caminho de volta — e ele mostra o timer,
    // deixando claro que a captura continua ativa. Restaura pelo botão
    // "Mostrar overlay" do dock.
    if (!dockVisible) toggleDock(true);
    invoke("toggle_overlay", { show: false }).catch(() => {});
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

  const empty = groups.length === 0 && partialBubbles.length === 0;

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
        boxShadow: "var(--shadow-lg)",
      }}
    >
      {/* Header */}
      <div
        className="flex items-center justify-between shrink-0"
        style={{
          padding: "9px 14px",
          borderBottom: "1px solid var(--border)",
          background: "var(--sidebar)",
        }}
      >
        <div
          className="flex flex-1 items-center gap-2.5 min-w-0"
          onMouseDown={onHeaderDragMouseDown}
          style={{ cursor: "move" }}
        >
          <NoraBars size={16} active={isRecording} animate />
          <span
            className="truncate"
            style={{
              fontSize: 11.5,
              fontWeight: 500,
              letterSpacing: "-0.005em",
              color: isRecording ? "var(--danger-ink)" : "var(--muted)",
            }}
          >
            {isRecording ? "Nora · gravando" : "Nora Live"}
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
              <Button
                variant="ghost"
                size="sm"
                onClick={handleCancel}
                disabled={stopping}
                aria-label="Descartar"
                title="Descartar"
              >
                Descartar
              </Button>
              <Button
                variant="primary"
                size="sm"
                onClick={handleStop}
                disabled={stopping}
                aria-label="Parar e salvar"
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
              </Button>
            </>
          )}
          <IconButton
            size="sm"
            onClick={() => setDrawerOpen((v) => !v)}
            aria-label="Configurações"
            title="Configurações"
            style={
              drawerOpen
                ? { background: "var(--chip)", color: "var(--ink)" }
                : undefined
            }
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="3" />
              <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h0a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h0a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
            </svg>
          </IconButton>
          <IconButton
            size="sm"
            onClick={handleMinimize}
            aria-label="Minimizar"
            title="Minimizar (esconder — gravação continua)"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <line x1="5" y1="12" x2="19" y2="12" />
            </svg>
          </IconButton>
          <IconButton
            size="sm"
            onClick={handleCloseRequest}
            aria-label="Fechar"
            title={isRecording ? "Fechar (confirmação)" : "Fechar"}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </IconButton>
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
            background: "var(--danger-soft-bg)",
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
            <Button
              variant="primary"
              size="sm"
              onClick={() => {
                setStopping(true);
                setSaveError(null);
                emit("nora://retry-save").catch(() => {
                  setStopping(false);
                });
              }}
              disabled={stopping}
            >
              {stopping ? "Tentando…" : "Tentar de novo"}
            </Button>
            <Button
              variant="ghost"
              size="sm"
              onClick={() => setSaveError(null)}
            >
              Esconder
            </Button>
          </div>
        </div>
      )}

      {/* Body: chat (left) + highlights (right, collapsible) */}
      <div className="flex-1 min-h-0 flex relative">
        <div className="flex-1 min-w-0 relative flex flex-col">
        <div
          ref={scrollRef}
          className="flex-1 min-h-0 overflow-y-auto"
          style={{ padding: "14px 16px 16px" }}
          onScroll={onChatScroll}
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
              {partialBubbles.map((p) => (
                <PartialBubble key={p.track} text={p.text} isMe={p.isMe} />
              ))}
            </div>
          )}
        </div>
          {showJumpToLatest && (
            <button
              onClick={jumpToLatest}
              className="absolute inline-flex items-center gap-1.5 transition-colors"
              style={{
                bottom: 12,
                left: "50%",
                transform: "translateX(-50%)",
                padding: "5px 12px",
                background: "var(--ink)",
                color: "var(--canvas)",
                border: "none",
                borderRadius: 999,
                fontSize: 11,
                fontWeight: 500,
                letterSpacing: "-0.005em",
                cursor: "pointer",
                boxShadow: "0 6px 18px -8px rgba(15, 23, 42, 0.45)",
                fontFamily: "var(--sans)",
                animation: "revealUp 200ms cubic-bezier(.2,.8,.2,1)",
              }}
            >
              <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                <line x1="12" y1="5" x2="12" y2="19" />
                <polyline points="19 12 12 19 5 12" />
              </svg>
              Novas mensagens
            </button>
          )}
        </div>

        {highlightsVisible && (
          <HighlightsColumn onCollapse={() => toggleHighlights(false)} />
        )}

        <NotificationStack
          items={notifications}
          onDismiss={dismissNotification}
        />

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
          <Button variant="primary" block onClick={onStopAndSave}>
            <span
              style={{
                width: 7,
                height: 7,
                background: "var(--canvas)",
                borderRadius: 2,
              }}
            />
            Parar e salvar
          </Button>
          <div className="flex gap-2">
            <Button
              variant="secondary"
              onClick={onContinue}
              style={{ flex: 1 }}
            >
              Continuar gravando
            </Button>
            <Button
              variant="danger"
              onClick={onDiscard}
              style={{ flex: 1 }}
            >
              Descartar
            </Button>
          </div>
        </div>
      </div>
    </div>
  );
}
