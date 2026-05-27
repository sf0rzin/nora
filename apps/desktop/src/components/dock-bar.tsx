import { useEffect, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";

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

function DockBars({ active }: { active: boolean }) {
  return (
    <span className="inline-flex items-end gap-[2px]" style={{ height: 14 }}>
      {[0.45, 0.7, 1.0, 0.65, 0.5].map((h, i) => (
        <span
          key={i}
          style={{
            display: "block",
            width: 2.5,
            height: `${h * 100}%`,
            background: active ? "#ff7a6c" : "rgba(255,255,255,0.85)",
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
  active,
  children,
}: {
  onClick: () => void;
  title: string;
  active?: boolean;
  children: React.ReactNode;
}) {
  return (
    <button
      onClick={onClick}
      title={title}
      aria-label={title}
      className="grid place-items-center transition-colors"
      style={{
        width: 30,
        height: 30,
        borderRadius: 8,
        background: active ? "rgba(255,255,255,0.16)" : "transparent",
        border: "none",
        color: active ? "#FDFDFC" : "rgba(253,253,252,0.78)",
        cursor: "pointer",
        padding: 0,
      }}
      onMouseEnter={(e) => {
        if (!active) {
          e.currentTarget.style.background = "rgba(255,255,255,0.10)";
          e.currentTarget.style.color = "#FDFDFC";
        }
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.background = active
          ? "rgba(255,255,255,0.16)"
          : "transparent";
        e.currentTarget.style.color = active
          ? "#FDFDFC"
          : "rgba(253,253,252,0.78)";
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

  // Tick to update the timer label
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

  return (
    <div
      className="flex items-center justify-center h-full"
      style={{
        padding: 4,
      }}
    >
      <div
        data-tauri-drag-region
        className="flex items-center gap-1"
        style={{
          padding: "6px 10px 6px 8px",
          background: "rgba(15, 17, 22, 0.92)",
          color: "#FDFDFC",
          borderRadius: 999,
          border: "1px solid rgba(255,255,255,0.10)",
          boxShadow:
            "0 12px 28px -10px rgba(0,0,0,0.55), 0 2px 6px rgba(0,0,0,0.18), inset 0 1px 0 rgba(255,255,255,0.06)",
          backdropFilter: "saturate(140%) blur(18px)",
          WebkitBackdropFilter: "saturate(140%) blur(18px)",
          fontFamily: "var(--sans)",
          cursor: "move",
        }}
      >
        {/* drag handle */}
        <span
          aria-hidden
          className="grid place-items-center"
          style={{
            width: 18,
            height: 26,
            opacity: 0.45,
          }}
        >
          <svg width="10" height="14" viewBox="0 0 10 14" fill="currentColor">
            <circle cx="2" cy="3" r="1" />
            <circle cx="2" cy="7" r="1" />
            <circle cx="2" cy="11" r="1" />
            <circle cx="8" cy="3" r="1" />
            <circle cx="8" cy="7" r="1" />
            <circle cx="8" cy="11" r="1" />
          </svg>
        </span>

        {/* status pill */}
        <span
          className="inline-flex items-center gap-2"
          style={{
            padding: "5px 12px 5px 10px",
            background: isRecording
              ? "rgba(255, 122, 108, 0.16)"
              : "rgba(255,255,255,0.06)",
            border: `1px solid ${
              isRecording ? "rgba(255, 122, 108, 0.4)" : "rgba(255,255,255,0.10)"
            }`,
            borderRadius: 999,
            fontSize: 11.5,
            color: isRecording ? "#ffb3a8" : "rgba(253,253,252,0.85)",
            fontWeight: 500,
            letterSpacing: "-0.005em",
            fontVariantNumeric: "tabular-nums",
            cursor: "default",
          }}
        >
          <DockBars active={isRecording} />
          {isRecording ? `REC ${formatDuration(duration)}` : "NORA"}
        </span>

        <span
          aria-hidden
          style={{
            width: 1,
            height: 18,
            background: "rgba(255,255,255,0.12)",
            margin: "0 2px",
          }}
        />

        {/* actions */}
        <DockButton onClick={handleOpenMain} title="Abrir NORA Desktop">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M3 12l9-9 9 9" />
            <path d="M5 10v10a1 1 0 0 0 1 1h4v-6h4v6h4a1 1 0 0 0 1-1V10" />
          </svg>
        </DockButton>
        <DockButton onClick={handleOpenOverlay} title="Mostrar overlay">
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
            <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
          </svg>
        </DockButton>

        <span
          aria-hidden
          style={{
            width: 1,
            height: 18,
            background: "rgba(255,255,255,0.12)",
            margin: "0 2px",
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
