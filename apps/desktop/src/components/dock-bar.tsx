import { useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { emit } from "@tauri-apps/api/event";
import { getCurrentWebviewWindow } from "@tauri-apps/api/webviewWindow";
import { LogicalSize } from "@tauri-apps/api/dpi";
import { NoraLogo } from "@/components/brand/nora-logo";
import { useRecording } from "@/hooks/use-recording";
import { formatDuration } from "@/lib/format";
import { setDockVisible } from "@/lib/dock-prefs";
import { EVENTS } from "@/lib/desktop-events";

// ─────────────────────────────────────────────────────────────────────────────
// DOCK — floating recording control (Cluely style, in Nora's LIGHT theme).
//
// The "dock" window is transparent + decorations:false + alwaysOnTop + skipTaskbar.
// We render ONE floating "bar" element (we do not fill the whole window),
// in Nora's PAPER (light) theme — it mirrors the styles.css tokens to match
// the web exactly. The bar is the CONTROL of the native recording; the live
// transcription shows up in the OVERLAY window. All the orchestration (start → accumulates
// transcript via events → stop → saveMeeting → upload) comes from the
// useRecording hook — this UI only draws the states and fires its actions.
// ─────────────────────────────────────────────────────────────────────────────

// Window dimensions (logical px) — compact vs. expanded. The Rust side already
// grants core:window:allow-set-size.
const SIZE_COMPACT = { width: 460, height: 56 };
const SIZE_EXPANDED = { width: 460, height: 308 };

// Bar theme — Nora's light tokens (var(--…) from styles.css). We keep a local
// object only for the few translucent/glass values that do not exist as a
// named token; everything else references the CSS variables directly.
const THEME = {
  bg: "rgba(253,253,252,0.86)",
  border: "1px solid var(--border)",
  text: "var(--ink)",
  textDim: "var(--muted)",
  iconIdle: "var(--muted)",
  iconHover: "var(--ink)",
  hoverBg: "rgba(0,0,0,0.05)",
  divider: "var(--border)",
  panelBg: "var(--sidebar)",
  panelBorder: "1px solid var(--border)",
  danger: "var(--danger-ink)",
};

/** Default title when the field is not filled in: "Reunião <local date>". */
function defaultTitle(): string {
  return "Reunião " + new Date().toLocaleString("pt-BR");
}

// Icon button — ~28px, dim color → hover ink + light bg. `active` paints in the
// Nora accent (on state: stealth/config).
function DockIconButton({
  onClick,
  title,
  active,
  danger,
  children,
}: {
  onClick: () => void;
  title: string;
  active?: boolean;
  danger?: boolean;
  children: React.ReactNode;
}) {
  const [hover, setHover] = useState(false);
  const color = danger
    ? THEME.danger
    : active
      ? "var(--accent)"
      : hover
        ? THEME.iconHover
        : THEME.iconIdle;
  return (
    <button
      type="button"
      onClick={onClick}
      title={title}
      aria-label={title}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      className="grid place-items-center shrink-0"
      style={{
        width: 28,
        height: 28,
        borderRadius: 8,
        border: "none",
        background: hover || active ? THEME.hoverBg : "transparent",
        color,
        cursor: "pointer",
        padding: 0,
        transition: "background 120ms ease, color 120ms ease",
      }}
    >
      {children}
    </button>
  );
}

// Light <select> for the expanded panel (microphone selector).
const selectStyle: React.CSSProperties = {
  width: "100%",
  padding: "8px 28px 8px 10px",
  fontSize: 12.5,
  background: "var(--canvas)",
  border: "1px solid var(--border)",
  borderRadius: 8,
  color: THEME.text,
  outline: "none",
  letterSpacing: "-0.005em",
  appearance: "none",
  colorScheme: "light",
  backgroundImage:
    "url(\"data:image/svg+xml;utf8,<svg xmlns='http://www.w3.org/2000/svg' width='10' height='10' viewBox='0 0 24 24' fill='none' stroke='%236E7178' stroke-width='2' stroke-linecap='round'><polyline points='6 9 12 15 18 9'/></svg>\")",
  backgroundRepeat: "no-repeat",
  backgroundPosition: "right 9px center",
  fontFamily: "var(--sans)",
};

export function DockBar() {
  const [captureSystemAudio, setCaptureSystemAudio] = useState(true);
  const r = useRecording({ captureSystemAudio });

  const [expanded, setExpanded] = useState(false);
  const [stealth, setStealth] = useState(false);
  const [starting, setStarting] = useState(false);
  const [title, setTitle] = useState("");
  // Shows "Salvo" for a few seconds after a successful upload.
  const [showSaved, setShowSaved] = useState(false);

  const win = useRef(getCurrentWebviewWindow()).current;
  const savedTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  // Loads the audio devices + initial stealth state on mount.
  useEffect(() => {
    r.loadDevices().catch(() => {});
    invoke<boolean>("get_stealth_mode")
      .then((v) => setStealth(!!v))
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Resizes the dock window when expanding/collapsing the config panel.
  useEffect(() => {
    const { width, height } = expanded ? SIZE_EXPANDED : SIZE_COMPACT;
    win.setSize(new LogicalSize(width, height)).catch((e) =>
      console.warn("[dock] setSize failed:", e),
    );
  }, [expanded, win]);

  // Shows "Salvo" when saveMeeting resolves successfully (new meetingId).
  useEffect(() => {
    if (!r.savedMeetingId) return;
    setShowSaved(true);
    if (savedTimerRef.current) clearTimeout(savedTimerRef.current);
    savedTimerRef.current = setTimeout(() => setShowSaved(false), 4000);
    return () => {
      if (savedTimerRef.current) clearTimeout(savedTimerRef.current);
    };
  }, [r.savedMeetingId]);

  // Drag the window by the bar's background. CRITICAL: startDragging() captures the
  // mouse natively, so if it fired on ANY mousedown it would swallow the
  // click on the buttons (the click became a drag and no button worked —
  // bug reported in v0.2.x). That's why we ignore mousedown originating in an
  // interactive element; only the bar's empty space drags.
  const onDragMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0) return;
    const target = e.target as HTMLElement;
    if (target.closest("button, a, input, select, textarea, label, [data-no-drag]")) {
      return;
    }
    win
      .startDragging()
      .catch((err) => console.warn("[dock] startDragging failed:", err));
  };

  const handleToggleStealth = () => {
    const next = !stealth;
    setStealth(next);
    invoke("set_stealth_mode", { enabled: next }).catch((e) => {
      console.warn("[dock] set_stealth_mode failed:", e);
      // Reverts the visual state if the backend refused (e.g.: non-Windows).
      setStealth(!next);
    });
  };

  const handleOpenOverlay = () => {
    invoke("toggle_overlay", { show: true }).catch(() => {});
    invoke("focus_overlay_window").catch(() => {});
  };

  const handleHide = () => {
    setDockVisible(false);
    invoke("toggle_dock", { show: false }).catch(() => {});
    // localStorage does not fire `storage` across Tauri webviews — notifies
    // overlay/main to sync the "Mostrar dock" toggle.
    emit(EVENTS.DOCK_VISIBILITY_CHANGED, { visible: false }).catch(() => {});
  };

  const handleStart = async () => {
    setStarting(true);
    try {
      // The hook already calls toggle_overlay(show:true) on start.
      await r.startRecording({
        deviceName: r.selectedDevice,
        captureSystemAudio,
      });
    } finally {
      setStarting(false);
    }
  };

  const handleStopAndSave = async () => {
    await r.stopRecording();
    await r.saveMeeting(title.trim() || defaultTitle());
    setTitle("");
  };

  const statusLine = (() => {
    if (r.isSaving) return { text: "Salvando…", danger: false };
    if (r.saveError) return { text: r.saveError, danger: true };
    if (r.error) return { text: r.error, danger: true };
    if (showSaved) return { text: "Salvo", danger: false };
    return null;
  })();

  return (
    // Transparent wrapper that fills the window; centers the bar and leaves slack
    // so the shadow is not clipped.
    <div
      className="flex flex-col h-full w-full"
      style={{ padding: 7, fontFamily: "var(--sans)" }}
    >
      <div
        onMouseDown={onDragMouseDown}
        className="flex flex-col flex-1 min-h-0"
        style={{
          background: THEME.bg,
          backdropFilter: "blur(22px) saturate(160%)",
          WebkitBackdropFilter: "blur(22px) saturate(160%)",
          border: THEME.border,
          borderRadius: "var(--radius-lg)",
          boxShadow: "var(--shadow-lg)",
          color: THEME.text,
          cursor: "grab",
          overflow: "hidden",
        }}
      >
        {/* ── COMPACT ROW (always visible) ────────────────────────────── */}
        <div
          className="flex items-center gap-1.5 shrink-0"
          style={{ height: 42, padding: "0 8px 0 13px" }}
        >
          {/* Nora logo — ink bars (variant "brand") on the light paper. */}
          <span className="shrink-0" style={{ display: "inline-flex" }}>
            <NoraLogo size={16} showText={false} variant="brand" />
          </span>

          {/* REC button: idle = circle with a dot; recording = pulsing red
              square (stop). */}
          <RecButton
            recording={r.isRecording}
            starting={starting}
            onStart={handleStart}
            onStop={handleStopAndSave}
            saving={r.isSaving}
          />

          {/* Timer while recording. */}
          {r.isRecording && (
            <span
              style={{
                fontSize: 12.5,
                fontWeight: 500,
                color: THEME.text,
                fontVariantNumeric: "tabular-nums",
                letterSpacing: "-0.01em",
                minWidth: 42,
              }}
            >
              {formatDuration(r.duration)}
            </span>
          )}

          {/* Status / error — small line to the left of the actions. */}
          {statusLine && (
            <span
              className="truncate"
              style={{
                flex: 1,
                minWidth: 0,
                fontSize: 11,
                color: statusLine.danger ? THEME.danger : THEME.textDim,
                letterSpacing: "-0.005em",
              }}
              title={statusLine.text}
            >
              {statusLine.text}
            </span>
          )}

          {/* Pushes the actions to the right when no status is filling in. */}
          {!statusLine && <span style={{ flex: 1 }} />}

          {/* Compact actions: stealth · config · close. */}
          <DockIconButton
            onClick={handleToggleStealth}
            title={stealth ? "Stealth ligado (invisível na captura)" : "Stealth desligado"}
            active={stealth}
          >
            {stealth ? <EyeOffIcon /> : <EyeIcon />}
          </DockIconButton>

          <DockIconButton
            onClick={() => setExpanded((v) => !v)}
            title={expanded ? "Fechar configurações" : "Configurações"}
            active={expanded}
          >
            <GearIcon />
          </DockIconButton>

          <span
            aria-hidden
            style={{
              width: 1,
              height: 18,
              background: THEME.divider,
              margin: "0 2px",
              flexShrink: 0,
            }}
          />

          <DockIconButton onClick={handleHide} title="Ocultar dock">
            <CloseIcon />
          </DockIconButton>
        </div>

        {/* ── EXPANDED PANEL (gear on) ────────────────────────────────── */}
        {expanded && (
          // onMouseDown stopPropagation: interactions in the panel do not drag the window.
          <div
            onMouseDown={(e) => e.stopPropagation()}
            className="flex-1 min-h-0 overflow-y-auto"
            style={{
              cursor: "default",
              padding: "12px 14px 14px",
              borderTop: THEME.panelBorder,
              display: "flex",
              flexDirection: "column",
              gap: 12,
            }}
          >
            {/* Microphone */}
            <label style={{ display: "flex", flexDirection: "column", gap: 6 }}>
              <span style={{ fontSize: 11, fontWeight: 500, color: THEME.textDim, letterSpacing: "0.01em" }}>
                Microfone
              </span>
              <select
                value={r.selectedDevice ?? ""}
                onChange={(e) => r.setSelectedDevice(e.target.value || null)}
                style={selectStyle}
              >
                <option value="">Padrão do sistema</option>
                {r.devices.map((d) => (
                  <option key={d} value={d}>
                    {d}
                  </option>
                ))}
              </select>
            </label>

            {/* Optional meeting title */}
            <label style={{ display: "flex", flexDirection: "column", gap: 6 }}>
              <span style={{ fontSize: 11, fontWeight: 500, color: THEME.textDim, letterSpacing: "0.01em" }}>
                Título (opcional)
              </span>
              <input
                type="text"
                value={title}
                onChange={(e) => setTitle(e.target.value)}
                placeholder={defaultTitle()}
                style={{
                  width: "100%",
                  padding: "8px 10px",
                  fontSize: 12.5,
                  background: "var(--canvas)",
                  border: "1px solid var(--border)",
                  borderRadius: 8,
                  color: THEME.text,
                  outline: "none",
                  letterSpacing: "-0.005em",
                  fontFamily: "var(--sans)",
                }}
              />
            </label>

            {/* Toggles */}
            <ToggleRow
              label="Capturar áudio do sistema"
              checked={captureSystemAudio}
              onChange={setCaptureSystemAudio}
            />
            <ToggleRow
              label="Stealth (invisível na captura de tela)"
              checked={stealth}
              onChange={handleToggleStealth}
            />

            {/* Open overlay */}
            <button
              type="button"
              onClick={handleOpenOverlay}
              className="flex items-center justify-center gap-2"
              style={{
                width: "100%",
                padding: "8px 12px",
                fontSize: 12.5,
                fontWeight: 500,
                background: THEME.panelBg,
                border: THEME.panelBorder,
                borderRadius: 8,
                color: THEME.text,
                cursor: "pointer",
                fontFamily: "var(--sans)",
                letterSpacing: "-0.005em",
              }}
            >
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
              </svg>
              Abrir overlay de transcrição
            </button>
          </div>
        )}
      </div>
    </div>
  );
}

// Circular REC button. Idle: ring with a dot. Recording: pulsing red
// square (stop). Shows a discreet spinner during "Iniciando…"/"Salvando…".
function RecButton({
  recording,
  starting,
  saving,
  onStart,
  onStop,
}: {
  recording: boolean;
  starting: boolean;
  saving: boolean;
  onStart: () => void;
  onStop: () => void;
}) {
  const busy = starting || saving;
  const title = recording
    ? saving
      ? "Salvando…"
      : "Parar e salvar"
    : starting
      ? "Iniciando…"
      : "Iniciar gravação";

  return (
    <button
      type="button"
      onClick={recording ? onStop : onStart}
      disabled={busy && !recording}
      title={title}
      aria-label={title}
      className="grid place-items-center shrink-0"
      style={{
        width: 30,
        height: 30,
        borderRadius: "50%",
        border: recording
          ? "1px solid var(--danger-soft-border)"
          : "1.5px solid var(--border-strong)",
        background: recording ? "var(--danger-soft-bg)" : "transparent",
        cursor: busy && !recording ? "default" : "pointer",
        padding: 0,
        opacity: busy && !recording ? 0.7 : 1,
        transition: "background 140ms ease, border-color 140ms ease",
      }}
    >
      {recording ? (
        // Pulsing red square = stop.
        <span
          style={{
            width: 11,
            height: 11,
            borderRadius: 3,
            background: "var(--danger)",
            animation: "recPulse 1.6s ease-in-out infinite",
          }}
        />
      ) : starting ? (
        // Spinner while starting.
        <span
          style={{
            width: 12,
            height: 12,
            borderRadius: "50%",
            border: "2px solid rgba(0,0,0,0.15)",
            borderTopColor: "var(--ink)",
            animation: "nora-spin 0.7s linear infinite",
          }}
        />
      ) : (
        // Dot = record.
        <span
          style={{
            width: 10,
            height: 10,
            borderRadius: "50%",
            background: "var(--danger)",
          }}
        />
      )}
    </button>
  );
}

// Switch-style toggle in the light theme.
function ToggleRow({
  label,
  checked,
  onChange,
}: {
  label: string;
  checked: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <label
      className="flex items-center justify-between gap-3 cursor-pointer"
      style={{ fontSize: 12.5, color: THEME.text, letterSpacing: "-0.005em" }}
    >
      <span>{label}</span>
      <span
        onClick={(e) => {
          e.preventDefault();
          onChange(!checked);
        }}
        className="shrink-0"
        style={{
          width: 34,
          height: 19,
          borderRadius: 999,
          background: checked ? "var(--accent)" : "rgba(0,0,0,0.14)",
          position: "relative",
          transition: "background 140ms ease",
        }}
      >
        <span
          style={{
            position: "absolute",
            top: 2,
            left: checked ? 17 : 2,
            width: 15,
            height: 15,
            borderRadius: "50%",
            background: "#fff",
            boxShadow: "0 1px 2px rgba(0,0,0,0.2)",
            transition: "left 140ms ease",
          }}
        />
      </span>
    </label>
  );
}

// ── Icons (stroke currentColor) ─────────────────────────────────────────────
function GearIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 0 1-4 0v-.09A1.65 1.65 0 0 0 9 19.4a1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 0 1 0-4h.09A1.65 1.65 0 0 0 4.6 9a1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H9a1.65 1.65 0 0 0 1-1.51V3a2 2 0 0 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 0 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  );
}

function EyeIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M1 12s4-7 11-7 11 7 11 7-4 7-11 7-11-7-11-7z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

function EyeOffIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17.94 17.94A10.07 10.07 0 0 1 12 20c-7 0-11-8-11-8a18.45 18.45 0 0 1 5.06-5.94M9.9 4.24A9.12 9.12 0 0 1 12 4c7 0 11 8 11 8a18.5 18.5 0 0 1-2.16 3.19m-6.72-1.07a3 3 0 1 1-4.24-4.24" />
      <line x1="1" y1="1" x2="23" y2="23" />
    </svg>
  );
}

function CloseIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M18 6L6 18M6 6l12 12" />
    </svg>
  );
}
