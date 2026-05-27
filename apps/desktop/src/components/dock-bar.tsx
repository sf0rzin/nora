import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import { getCurrentWebviewWindow } from "@tauri-apps/api/webviewWindow";

interface RecordingStatus {
  isRecording: boolean;
  micDevice?: string;
  sampleRate?: number;
}

function formatDuration(seconds: number): string {
  const m = Math.floor(seconds / 60);
  const s = seconds % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

// NORA soundwave (same DNA as sidebar/login logos)
function NoraBars({ active }: { active: boolean }) {
  return (
    <span
      className="inline-flex items-center justify-center"
      style={{
        gap: 2,
        height: 16,
        width: 22,
      }}
    >
      {[0.42, 0.78, 1.0, 0.66, 0.52].map((h, i) => (
        <span
          key={i}
          style={{
            display: "block",
            width: 2.5,
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

function DockButton({
  onClick,
  title,
  children,
}: {
  onClick: () => void;
  title: string;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      aria-label={title}
      className="grid place-items-center transition-colors"
      style={{
        width: 28,
        height: 28,
        borderRadius: 8,
        background: "transparent",
        border: "none",
        color: "var(--muted)",
        cursor: "pointer",
        padding: 0,
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
      {children}
    </button>
  );
}

export function DockBar() {
  const [isRecording, setIsRecording] = useState(false);
  const [startedAt, setStartedAt] = useState<number | null>(null);
  const [, forceTick] = useState(0);

  useEffect(() => {
    const unStatus = listen<RecordingStatus>("recording-status", (e) => {
      const s = e.payload;
      setIsRecording((wasRec) => {
        if (s.isRecording && !wasRec) {
          setStartedAt(Date.now());
        } else if (!s.isRecording && wasRec) {
          setStartedAt(null);
        }
        return !!s.isRecording;
      });
    });
    invoke<RecordingStatus>("get_recording_status")
      .then((s) => {
        if (s.isRecording) {
          setIsRecording(true);
          setStartedAt((prev) => prev ?? Date.now());
        }
      })
      .catch(() => {});
    return () => {
      unStatus.then((fn) => fn()).catch(() => {});
    };
  }, []);

  useEffect(() => {
    if (!isRecording) return;
    const id = setInterval(() => forceTick((t) => t + 1), 500);
    return () => clearInterval(id);
  }, [isRecording]);

  const duration =
    startedAt == null ? 0 : Math.floor((Date.now() - startedAt) / 1000);

  const handleOpenMain = () => {
    invoke("focus_main_window").catch(() => {});
  };
  const handleOpenOverlay = () => {
    invoke("toggle_overlay", { show: true }).catch(() => {});
    invoke("focus_overlay_window").catch(() => {});
  };
  const handleHide = () => {
    invoke("toggle_dock", { show: false }).catch(() => {});
  };

  // `-webkit-app-region: drag` não funciona no WebKitGTK Linux.
  // Usamos `startDragging()` que é cross-platform (cobre x11/wayland/macos/windows).
  const onDragMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0) return;
    e.preventDefault();
    getCurrentWebviewWindow()
      .startDragging()
      .catch((err) => console.warn("[dock] startDragging failed:", err));
  };

  return (
    <div
      className="flex items-center justify-center h-full w-full"
      style={{ padding: 4 }}
    >
      <div
        className="flex items-center gap-1"
        style={{
          padding: "6px 8px 6px 6px",
          // Sólido — backdrop-filter blur causa artefatos no WebKitGTK Linux
          // ("quadrados do nada" e flashes de conteúdo stale). Trocamos por
          // canvas opaco + box-shadow forte pra manter o look flutuante.
          background: "var(--canvas)",
          color: "var(--ink)",
          borderRadius: 999,
          border: "1px solid var(--border)",
          boxShadow:
            "0 14px 32px -12px rgba(15, 23, 42, 0.28), 0 4px 10px rgba(15, 23, 42, 0.06), inset 0 1px 0 rgba(255,255,255,0.5)",
          fontFamily: "var(--sans)",
        }}
      >
        {/* drag handle — usa startDragging() porque -webkit-app-region não
            funciona no WebKitGTK Linux */}
        <span
          onMouseDown={onDragMouseDown}
          aria-hidden
          className="grid place-items-center shrink-0"
          style={{
            width: 24,
            height: 30,
            cursor: "grab",
            color: "var(--muted)",
            opacity: 0.6,
            userSelect: "none",
            WebkitUserSelect: "none",
          }}
          title="Arrastar"
        >
          <svg
            width="10"
            height="16"
            viewBox="0 0 10 16"
            fill="currentColor"
            style={{ pointerEvents: "none" }}
          >
            <circle cx="2" cy="3" r="1.1" />
            <circle cx="2" cy="8" r="1.1" />
            <circle cx="2" cy="13" r="1.1" />
            <circle cx="8" cy="3" r="1.1" />
            <circle cx="8" cy="8" r="1.1" />
            <circle cx="8" cy="13" r="1.1" />
          </svg>
        </span>

        {/* Brand + status pill */}
        <span
          className="inline-flex items-center gap-2 shrink-0"
          style={{
            padding: "5px 12px 5px 9px",
            background: isRecording ? "rgba(201, 119, 102, 0.10)" : "var(--sidebar)",
            border: `1px solid ${
              isRecording ? "rgba(201, 119, 102, 0.30)" : "var(--border)"
            }`,
            borderRadius: 999,
            fontSize: 11.5,
            fontWeight: 500,
            letterSpacing: "-0.005em",
            color: isRecording ? "var(--danger-ink)" : "var(--ink)",
            fontVariantNumeric: "tabular-nums",
          }}
        >
          <NoraBars active={isRecording} />
          <span
            style={{
              fontFamily: "var(--display)",
              fontWeight: 500,
              letterSpacing: "-0.01em",
              fontSize: 12,
            }}
          >
            NORA
          </span>
          {isRecording && (
            <>
              <span
                style={{
                  width: 3,
                  height: 3,
                  borderRadius: "50%",
                  background: "var(--danger)",
                  opacity: 0.7,
                }}
              />
              <span style={{ fontSize: 11.5, fontVariantNumeric: "tabular-nums" }}>
                {formatDuration(duration)}
              </span>
            </>
          )}
        </span>

        <span
          aria-hidden
          style={{
            width: 1,
            height: 18,
            background: "var(--border)",
            margin: "0 2px",
            flexShrink: 0,
          }}
        />

        {/* Actions */}
        <DockButton onClick={handleOpenMain} title="Abrir NORA Desktop">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
            <path d="M3 12l9-9 9 9" />
            <path d="M5 10v10a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V10" />
          </svg>
        </DockButton>
        <DockButton onClick={handleOpenOverlay} title="Mostrar overlay">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
          </svg>
        </DockButton>

        <span
          aria-hidden
          style={{
            width: 1,
            height: 18,
            background: "var(--border)",
            margin: "0 2px",
            flexShrink: 0,
          }}
        />

        <DockButton onClick={handleHide} title="Esconder dock">
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </DockButton>
      </div>
    </div>
  );
}
