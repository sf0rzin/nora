import { useEffect, useRef, useState } from "react";
import { invoke } from "@tauri-apps/api/core";
import { useRecording } from "@/hooks/use-recording";
import { useLiveHighlights } from "@/hooks/use-live-highlights";
import { ShaderOrb } from "@/components/brand/shader-orb";
import { Avatar } from "@/components/brand/avatar";

interface AudioPrerequisites {
  platform: string;
  available: boolean;
  missingDriver: string | null;
  supportsScreenCaptureKit: boolean;
  message: string;
}

function formatDuration(seconds: number): string {
  const h = Math.floor(seconds / 3600);
  const m = Math.floor((seconds % 3600) / 60);
  const s = seconds % 60;
  const pad = (n: number) => String(n).padStart(2, "0");
  return h > 0 ? `${h}:${pad(m)}:${pad(s)}` : `${pad(m)}:${pad(s)}`;
}

function formatTime(ms: number): string {
  const total = Math.floor(ms / 1000);
  const m = Math.floor(total / 60);
  const s = total % 60;
  return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
}

// ── Section ─────────────────────────────────────────────────────────────
function SectionLabel({
  children,
  right,
}: {
  children: React.ReactNode;
  right?: React.ReactNode;
}) {
  return (
    <div
      className="flex items-baseline justify-between"
      style={{ marginBottom: 12, paddingBottom: 8, borderBottom: "1px solid var(--border)" }}
    >
      <h2
        style={{
          fontSize: 10.5,
          fontWeight: 500,
          letterSpacing: "0.08em",
          textTransform: "uppercase",
          color: "var(--muted)",
          margin: 0,
        }}
      >
        {children}
      </h2>
      {right}
    </div>
  );
}

// ── BlackHole wizard ────────────────────────────────────────────────────
function BlackHoleBanner({ onDismiss }: { onDismiss: () => void }) {
  return (
    <div
      className="mb-6"
      style={{
        padding: "14px 16px",
        background: "var(--accent-soft)",
        border: "1px solid var(--border)",
        borderRadius: 12,
      }}
    >
      <div className="flex items-start gap-3">
        <svg
          className="shrink-0 mt-0.5"
          width="18" height="18" viewBox="0 0 24 24" fill="none"
          stroke="var(--accent-ink)" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"
        >
          <circle cx="12" cy="12" r="10" />
          <line x1="12" y1="8" x2="12" y2="12" />
          <line x1="12" y1="16" x2="12.01" y2="16" />
        </svg>
        <div className="flex-1">
          <h3 style={{ fontSize: 13.5, fontWeight: 500, color: "var(--accent-ink)", marginBottom: 4 }}>
            Driver de áudio necessário no macOS
          </h3>
          <p style={{ fontSize: 12.5, color: "var(--ink)", lineHeight: 1.55, marginBottom: 10 }}>
            Pra capturar áudio do sistema no macOS, instale o BlackHole (driver virtual gratuito).
          </p>
          <div className="flex gap-2">
            <a
              href="https://existential.audio/blackhole/"
              target="_blank"
              rel="noopener noreferrer"
              style={{
                padding: "6px 12px",
                background: "var(--ink)",
                color: "var(--canvas)",
                borderRadius: 7,
                fontSize: 12.5,
                fontWeight: 500,
              }}
            >
              Baixar BlackHole
            </a>
            <button
              onClick={onDismiss}
              style={{
                padding: "6px 12px",
                background: "transparent",
                border: "1px solid var(--border)",
                borderRadius: 7,
                fontSize: 12.5,
                color: "var(--ink)",
                cursor: "pointer",
              }}
            >
              Ignorar
            </button>
          </div>
        </div>
      </div>
    </div>
  );
}

// ── Field components ───────────────────────────────────────────────────
function Field({
  label,
  children,
}: {
  label: string;
  children: React.ReactNode;
}) {
  return (
    <label className="flex flex-col gap-1.5">
      <span style={{ fontSize: 11.5, fontWeight: 500, color: "var(--muted)", letterSpacing: "0.04em", textTransform: "uppercase" }}>
        {label}
      </span>
      {children}
    </label>
  );
}

const inputCss: React.CSSProperties = {
  width: "100%",
  padding: "10px 14px",
  fontSize: 13.5,
  background: "var(--canvas)",
  border: "1px solid var(--border)",
  borderRadius: 9,
  color: "var(--ink)",
  outline: "none",
  letterSpacing: "-0.005em",
  transition: "border-color 140ms ease, box-shadow 140ms ease",
};

const selectCss: React.CSSProperties = {
  ...inputCss,
  appearance: "none",
  paddingRight: 32,
  backgroundImage:
    'url("data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'12\' height=\'12\' viewBox=\'0 0 24 24\' fill=\'none\' stroke=\'%236E7178\' stroke-width=\'2\' stroke-linecap=\'round\'><polyline points=\'6 9 12 15 18 9\'/></svg>")',
  backgroundRepeat: "no-repeat",
  backgroundPosition: "right 10px center",
};

function focusOn(e: React.FocusEvent<HTMLElement>) {
  e.currentTarget.style.borderColor = "var(--accent)";
  e.currentTarget.style.boxShadow = "0 0 0 3px var(--accent-soft)";
}
function focusOff(e: React.FocusEvent<HTMLElement>) {
  e.currentTarget.style.borderColor = "var(--border)";
  e.currentTarget.style.boxShadow = "none";
}

// ── Speaker color ──────────────────────────────────────────────────────
function speakerColor(name: string | null) {
  if (!name) return "var(--muted)";
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return `oklch(0.50 0.13 ${h % 360})`;
}

// ── Live highlight chip strip ─────────────────────────────────────────
function LiveHighlightsStrip() {
  const { highlights, isAnalyzing } = useLiveHighlights();
  const counts = {
    decisions: highlights.decisions.length,
    nextSteps: highlights.nextSteps.length,
    observations: highlights.observations.length,
    tasks: highlights.tasks.length,
  };
  const total = counts.decisions + counts.nextSteps + counts.observations + counts.tasks;
  if (total === 0 && !isAnalyzing) return null;

  const items: { label: string; count: number; color: string }[] = [
    { label: "Decisões", count: counts.decisions, color: "var(--accent-ink)" },
    { label: "Próximos passos", count: counts.nextSteps, color: "#3f8a5e" },
    { label: "Observações", count: counts.observations, color: "var(--muted)" },
    { label: "Tarefas", count: counts.tasks, color: "#a37528" },
  ].filter((i) => i.count > 0);

  return (
    <div
      className="flex items-center gap-2.5 flex-wrap"
      style={{ marginTop: 14 }}
    >
      {isAnalyzing && (
        <span
          className="inline-flex items-center gap-1.5"
          style={{
            fontSize: 11.5,
            color: "var(--accent-ink)",
            padding: "3px 9px",
            borderRadius: 999,
            background: "var(--accent-soft)",
          }}
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
          NORA analisando
        </span>
      )}
      {items.map((i) => (
        <span
          key={i.label}
          className="inline-flex items-center gap-1.5"
          style={{
            fontSize: 11.5,
            color: "var(--ink)",
            padding: "3px 10px",
            borderRadius: 999,
            background: "var(--chip)",
          }}
        >
          <span style={{ width: 6, height: 6, borderRadius: "50%", background: i.color }} />
          {i.label}
          <span style={{ color: "var(--muted)", fontVariantNumeric: "tabular-nums" }}>
            {i.count}
          </span>
        </span>
      ))}
    </div>
  );
}

// ── Soundwave bars (active visual) ────────────────────────────────────
function ActiveBars({ count = 28 }: { count?: number }) {
  return (
    <div className="flex items-end justify-center gap-[3px]" style={{ height: 28 }}>
      {Array.from({ length: count }).map((_, i) => (
        <span
          key={i}
          style={{
            display: "block",
            width: 2,
            height: `${20 + Math.abs(Math.sin(i * 0.7)) * 80}%`,
            background:
              i % 4 === 0
                ? "var(--accent)"
                : i % 7 === 0
                  ? "var(--accent-ink)"
                  : "var(--ink)",
            borderRadius: 999,
            opacity: 0.85,
            animation: `recBar 1.4s ease-in-out ${i * 0.04}s infinite`,
          }}
        />
      ))}
      <style>{`
        @keyframes recBar {
          0%, 100% { transform: scaleY(0.5); opacity: 0.45; }
          50%      { transform: scaleY(1);   opacity: 1; }
        }
      `}</style>
    </div>
  );
}

// ── Main page ──────────────────────────────────────────────────────────
export function RecordingPage() {
  const transcriptScrollRef = useRef<HTMLDivElement | null>(null);
  const [meetingTitle, setMeetingTitle] = useState("");
  const [captureSystemAudio, setCaptureSystemAudio] = useState(true);
  const [systemAudioDevice, setSystemAudioDevice] = useState<string | null>(null);
  const [showBlackHoleWizard, setShowBlackHoleWizard] = useState(false);

  useEffect(() => {
    invoke<AudioPrerequisites>("check_system_audio_prerequisites")
      .then((result) => {
        if (result.platform === "macos" && !result.available) {
          setShowBlackHoleWizard(true);
        }
      })
      .catch((e) =>
        console.error("[recording] failed to check audio prerequisites:", e),
      );
  }, []);

  const {
    isRecording,
    transcriptLines,
    partialText,
    fullTranscript,
    devices,
    selectedDevice,
    setSelectedDevice,
    duration,
    error,
    deviceName,
    sampleRate,
    speakerMap,
    renameSpeaker,
    getSpeakerName,
    isSaving,
    savedMeetingId,
    saveError,
    startRecording,
    stopRecording,
    saveMeeting,
  } = useRecording({
    captureSystemAudio,
    systemAudioDevice,
    language: "pt-BR",
  });

  // Auto-scroll transcript
  useEffect(() => {
    const el = transcriptScrollRef.current;
    if (!el) return;
    el.scrollTop = el.scrollHeight;
  }, [transcriptLines.length, partialText]);

  const recordingStart = isRecording
    ? Date.now() - duration * 1000
    : 0;
  const speakerCount = Object.keys(speakerMap).length;

  return (
    <main className="flex-1 overflow-y-auto" style={{ background: "var(--canvas)" }}>
      <div style={{ maxWidth: 880, margin: "0 auto", padding: "44px 48px 80px" }}>
        {/* Hero */}
        <header
          className="flex items-start justify-between gap-8"
          style={{ marginBottom: 36 }}
        >
          <div className="flex-1 min-w-0">
            <div
              style={{
                fontSize: 12.5,
                color: "var(--muted)",
                letterSpacing: "0.04em",
                textTransform: "uppercase",
                marginBottom: 8,
              }}
            >
              {isRecording ? "Sessão em andamento" : "Captura ao vivo"}
            </div>
            <h1
              style={{
                fontFamily: "var(--display)",
                fontSize: 34,
                fontWeight: 500,
                letterSpacing: "-0.028em",
                lineHeight: 1.08,
                color: "var(--ink)",
                margin: 0,
                textWrap: "balance",
              }}
            >
              {isRecording ? "NORA está ouvindo." : "Pronta pra ouvir."}
            </h1>
            <p
              style={{
                fontSize: 14,
                color: "var(--muted)",
                lineHeight: 1.55,
                marginTop: 10,
                marginBottom: 0,
                maxWidth: 460,
              }}
            >
              {isRecording
                ? "Toda palavra passa pelo PII Shield antes de virar análise. Termina aqui mesmo: para, salva e abre o resumo."
                : "Áudio capturado roda em PT-BR com PII Shield e fica só na sua máquina até você salvar."}
            </p>

            {/* Status row when recording */}
            {isRecording && (
              <div
                className="flex items-center gap-3 flex-wrap"
                style={{ marginTop: 18, fontSize: 12.5, color: "var(--muted)" }}
              >
                <span
                  className="inline-flex items-center gap-2"
                  style={{
                    padding: "4px 12px",
                    background: "rgba(201,119,102,0.10)",
                    border: "1px solid rgba(201,119,102,0.25)",
                    borderRadius: 999,
                    color: "var(--danger-ink)",
                    fontVariantNumeric: "tabular-nums",
                    fontSize: 12,
                  }}
                >
                  <span
                    style={{
                      width: 8,
                      height: 8,
                      borderRadius: "50%",
                      background: "var(--danger)",
                      animation: "recPulse 1.4s ease-in-out infinite",
                    }}
                  />
                  REC {formatDuration(duration)}
                </span>
                {deviceName && (
                  <span className="inline-flex items-center gap-1.5">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                      <rect x="9" y="2" width="6" height="12" rx="3" />
                      <path d="M5 10v2a7 7 0 0 0 14 0v-2" />
                    </svg>
                    <span>{deviceName}</span>
                  </span>
                )}
                {captureSystemAudio && (
                  <span className="inline-flex items-center gap-1.5">
                    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                      <rect x="3" y="3" width="18" height="14" rx="2" />
                      <line x1="8" y1="21" x2="16" y2="21" />
                      <line x1="12" y1="17" x2="12" y2="21" />
                    </svg>
                    <span>+ Sistema</span>
                  </span>
                )}
                {sampleRate && (
                  <span style={{ fontVariantNumeric: "tabular-nums" }}>
                    {(sampleRate / 1000).toFixed(1)}kHz
                  </span>
                )}
                {speakerCount > 0 && (
                  <span style={{ fontVariantNumeric: "tabular-nums" }}>
                    {speakerCount} {speakerCount === 1 ? "falante" : "falantes"}
                  </span>
                )}
              </div>
            )}

            <LiveHighlightsStrip />
          </div>

          {/* Orb */}
          <div
            className="relative shrink-0 grid place-items-center"
            style={{ width: 132, height: 132 }}
          >
            <div
              className="absolute inset-0 rounded-full"
              style={{
                background:
                  "radial-gradient(circle at 50% 50%, var(--accent-soft) 0%, transparent 60%)",
                opacity: isRecording ? 0.85 : 0.45,
                filter: "blur(8px)",
                transition: "opacity 400ms ease",
              }}
            />
            <ShaderOrb
              size={108}
              speed={isRecording ? 1.6 : 0.6}
              intensity={isRecording ? 1 : 0.45}
              style={{ position: "relative", zIndex: 1 }}
            />
            {isRecording && (
              <div
                aria-hidden
                style={{
                  position: "absolute",
                  inset: -10,
                  borderRadius: "50%",
                  border: "1px dashed var(--border)",
                  animation: "orbRing 12s linear infinite",
                }}
              />
            )}
          </div>
        </header>

        {/* BlackHole banner */}
        {showBlackHoleWizard && (
          <BlackHoleBanner onDismiss={() => setShowBlackHoleWizard(false)} />
        )}

        {/* Error banner */}
        {error && (
          <div
            className="mb-6"
            style={{
              padding: "10px 14px",
              background: "rgba(201, 119, 102, 0.10)",
              border: "1px solid rgba(201, 119, 102, 0.25)",
              borderRadius: 10,
              fontSize: 13,
              color: "var(--danger-ink)",
              lineHeight: 1.5,
            }}
          >
            {error}
          </div>
        )}

        {/* Setup card — visible only when not recording */}
        {!isRecording && (
          <section
            style={{
              padding: 24,
              border: "1px solid var(--border)",
              borderRadius: 14,
              background: "var(--canvas)",
              marginBottom: 24,
              boxShadow: "0 8px 24px -18px rgba(15,23,42,0.10)",
            }}
          >
            <SectionLabel>Configuração da sessão</SectionLabel>

            <div className="grid gap-4" style={{ gridTemplateColumns: "1fr" }}>
              <Field label="Título da reunião">
                <input
                  type="text"
                  placeholder="Ex.: Discovery — TOTVS Protheus"
                  value={meetingTitle}
                  onChange={(e) => setMeetingTitle(e.target.value)}
                  style={inputCss}
                  onFocus={focusOn}
                  onBlur={focusOff}
                />
              </Field>
              <div className="grid grid-cols-2 gap-3">
                <Field label="Microfone">
                  <select
                    value={selectedDevice || ""}
                    onChange={(e) => setSelectedDevice(e.target.value || null)}
                    style={selectCss}
                  >
                    <option value="">Padrão do sistema</option>
                    {devices.map((d) => (
                      <option key={d} value={d}>
                        {d}
                      </option>
                    ))}
                  </select>
                </Field>
                <Field label="Áudio do sistema">
                  {captureSystemAudio ? (
                    <select
                      value={systemAudioDevice || ""}
                      onChange={(e) =>
                        setSystemAudioDevice(e.target.value || null)
                      }
                      style={selectCss}
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
                        ...inputCss,
                        color: "var(--muted)",
                        background: "var(--sidebar)",
                      }}
                    >
                      Desativado
                    </div>
                  )}
                </Field>
              </div>
            </div>

            <label
              className="flex items-center gap-2.5 cursor-pointer"
              style={{ marginTop: 16, fontSize: 12.5 }}
            >
              <input
                type="checkbox"
                checked={captureSystemAudio}
                onChange={(e) => setCaptureSystemAudio(e.target.checked)}
                style={{ accentColor: "var(--accent)" }}
              />
              <span style={{ color: "var(--ink)" }}>
                Capturar áudio do sistema
              </span>
              <span style={{ color: "var(--muted)" }}>
                · útil pra reuniões remotas com áudio do outro lado
              </span>
            </label>
          </section>
        )}

        {/* Primary CTA */}
        <div
          className="flex flex-col items-center"
          style={{ marginBottom: 28 }}
        >
          {!isRecording ? (
            <>
              <button
                onClick={startRecording}
                className="inline-flex items-center gap-3"
                style={{
                  padding: "13px 26px",
                  background: "var(--ink)",
                  color: "var(--canvas)",
                  borderRadius: 12,
                  fontSize: 14.5,
                  fontWeight: 500,
                  letterSpacing: "-0.005em",
                  cursor: "pointer",
                  boxShadow:
                    "0 12px 28px -12px rgba(15,23,42,0.40), inset 0 1px 0 rgba(255,255,255,0.10)",
                  border: "1px solid #000",
                }}
                onMouseEnter={(e) => {
                  e.currentTarget.style.background = "#000";
                  e.currentTarget.style.transform = "translateY(-1px)";
                }}
                onMouseLeave={(e) => {
                  e.currentTarget.style.background = "var(--ink)";
                  e.currentTarget.style.transform = "translateY(0)";
                }}
              >
                <span
                  style={{
                    width: 12,
                    height: 12,
                    borderRadius: "50%",
                    background: "var(--danger)",
                    boxShadow: "0 0 0 3px rgba(201,119,102,0.30)",
                  }}
                />
                Iniciar gravação
              </button>
              <div
                className="mt-3 flex items-center gap-2.5"
                style={{ fontSize: 11.5, color: "var(--muted)" }}
              >
                <span>PII Shield ativo</span>
                <span style={{ opacity: 0.5 }}>·</span>
                <span>Tudo em PT-BR</span>
                <span style={{ opacity: 0.5 }}>·</span>
                <span>Roda local até salvar</span>
              </div>
            </>
          ) : (
            <button
              onClick={stopRecording}
              className="inline-flex items-center gap-3"
              style={{
                padding: "12px 24px",
                background: "var(--canvas)",
                color: "var(--ink)",
                border: "1px solid var(--border)",
                borderRadius: 12,
                fontSize: 14,
                fontWeight: 500,
                cursor: "pointer",
                boxShadow: "0 6px 18px -12px rgba(15,23,42,0.18)",
              }}
              onMouseEnter={(e) =>
                (e.currentTarget.style.background = "var(--sidebar)")
              }
              onMouseLeave={(e) =>
                (e.currentTarget.style.background = "var(--canvas)")
              }
            >
              <span
                style={{
                  width: 11,
                  height: 11,
                  borderRadius: 2,
                  background: "var(--ink)",
                }}
              />
              Parar gravação
            </button>
          )}
        </div>

        {/* Transcript card */}
        {(isRecording || transcriptLines.length > 0 || partialText) && (
          <section
            style={{
              border: "1px solid var(--border)",
              borderRadius: 14,
              overflow: "hidden",
              background: "var(--canvas)",
              boxShadow: "0 8px 24px -18px rgba(15,23,42,0.10)",
            }}
          >
            <div
              className="flex items-center justify-between"
              style={{
                padding: "12px 18px",
                background: "var(--sidebar)",
                borderBottom: "1px solid var(--border)",
              }}
            >
              <div className="flex items-center gap-3">
                <span
                  style={{
                    fontSize: 10.5,
                    fontWeight: 500,
                    letterSpacing: "0.08em",
                    textTransform: "uppercase",
                    color: "var(--muted)",
                  }}
                >
                  Transcrição
                </span>
                {isRecording && <ActiveBars />}
              </div>
              {transcriptLines.length > 0 && (
                <span
                  style={{
                    fontSize: 11.5,
                    color: "var(--muted)",
                    fontVariantNumeric: "tabular-nums",
                  }}
                >
                  {transcriptLines.length} linhas · {speakerCount}{" "}
                  {speakerCount === 1 ? "falante" : "falantes"}
                </span>
              )}
            </div>

            <div
              ref={transcriptScrollRef}
              className="overflow-y-auto"
              style={{
                padding: "20px 22px",
                minHeight: 340,
                maxHeight: 520,
              }}
            >
              {transcriptLines.length === 0 && !partialText && (
                <div
                  className="flex flex-col items-center justify-center text-center"
                  style={{ padding: "44px 8px", gap: 14 }}
                >
                  <div
                    style={{
                      width: 36,
                      height: 36,
                      borderRadius: "50%",
                      background: "var(--chip)",
                      display: "grid",
                      placeItems: "center",
                      color: "var(--muted)",
                    }}
                  >
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M3 11a9 9 0 0 1 9-9 9 9 0 0 1 9 9" />
                      <rect x="9" y="6" width="6" height="12" rx="3" />
                      <path d="M5 11v1a7 7 0 0 0 14 0v-1" />
                    </svg>
                  </div>
                  <div style={{ fontSize: 13.5, color: "var(--muted)" }}>
                    Aguardando fala…
                  </div>
                </div>
              )}

              <div className="flex flex-col gap-4">
                {transcriptLines.map((line) => {
                  const speakerName = getSpeakerName(
                    line.speakerId,
                    line.speaker,
                    line.track,
                  );
                  const ts = formatTime(
                    isRecording
                      ? Math.max(0, line.timestamp - recordingStart)
                      : 0,
                  );
                  return (
                    <div key={line.id} className="flex items-start gap-3">
                      {speakerName ? (
                        <Avatar name={speakerName} size={26} />
                      ) : (
                        <span
                          style={{
                            width: 26,
                            height: 26,
                            borderRadius: "50%",
                            background: "var(--chip)",
                            display: "inline-block",
                          }}
                        />
                      )}
                      <div className="flex-1 min-w-0">
                        <div className="flex items-baseline gap-2 mb-0.5">
                          {speakerName && (
                            <span
                              style={{
                                fontSize: 12.5,
                                fontWeight: 500,
                                color: speakerColor(speakerName),
                                letterSpacing: "-0.005em",
                              }}
                            >
                              {speakerName}
                            </span>
                          )}
                          {isRecording && (
                            <span
                              style={{
                                fontSize: 10.5,
                                color: "var(--muted)",
                                fontVariantNumeric: "tabular-nums",
                                letterSpacing: "0.04em",
                              }}
                            >
                              {ts}
                            </span>
                          )}
                          {line.track === "mic" && (
                            <span
                              style={{
                                fontSize: 9.5,
                                color: "var(--muted)",
                                letterSpacing: "0.06em",
                                textTransform: "uppercase",
                              }}
                            >
                              · mic
                            </span>
                          )}
                        </div>
                        <div
                          style={{
                            fontSize: 14.5,
                            lineHeight: 1.55,
                            color: "var(--ink)",
                          }}
                        >
                          {line.text}
                        </div>
                      </div>
                    </div>
                  );
                })}
                {partialText && (
                  <div className="flex items-start gap-3" style={{ opacity: 0.55 }}>
                    <span
                      style={{
                        width: 26,
                        height: 26,
                        borderRadius: "50%",
                        background: "var(--chip)",
                      }}
                    />
                    <div
                      className="flex-1"
                      style={{ fontSize: 14.5, color: "var(--ink)", lineHeight: 1.55 }}
                    >
                      {partialText}…
                    </div>
                  </div>
                )}
              </div>
            </div>
          </section>
        )}

        {/* Save flow */}
        {saveError && (
          <div
            className="mt-5 flex items-center justify-between"
            style={{
              padding: "10px 14px",
              background: "rgba(201, 119, 102, 0.10)",
              border: "1px solid rgba(201, 119, 102, 0.25)",
              borderRadius: 10,
              fontSize: 13,
              color: "var(--danger-ink)",
            }}
          >
            <span>Falha ao salvar: {saveError}</span>
            <button
              onClick={() => saveMeeting(meetingTitle)}
              disabled={isSaving}
              style={{
                padding: "5px 11px",
                background: "var(--danger-ink)",
                color: "var(--canvas)",
                borderRadius: 6,
                fontSize: 11.5,
                fontWeight: 500,
                cursor: isSaving ? "default" : "pointer",
                border: "none",
                opacity: isSaving ? 0.5 : 1,
              }}
            >
              Tentar novamente
            </button>
          </div>
        )}

        {fullTranscript && (
          <div className="mt-5 flex justify-end gap-2.5">
            <button
              onClick={() => navigator.clipboard.writeText(fullTranscript)}
              style={{
                padding: "9px 14px",
                background: "var(--canvas)",
                border: "1px solid var(--border)",
                borderRadius: 9,
                fontSize: 13,
                color: "var(--ink)",
                cursor: "pointer",
              }}
              onMouseEnter={(e) =>
                (e.currentTarget.style.background = "var(--sidebar)")
              }
              onMouseLeave={(e) =>
                (e.currentTarget.style.background = "var(--canvas)")
              }
            >
              Copiar transcrição
            </button>
            {!isRecording &&
              transcriptLines.length > 0 &&
              !savedMeetingId &&
              !saveError && (
                <button
                  onClick={() => saveMeeting(meetingTitle)}
                  disabled={isSaving}
                  className="inline-flex items-center gap-2"
                  style={{
                    padding: "9px 18px",
                    background: "var(--ink)",
                    color: "var(--canvas)",
                    borderRadius: 9,
                    fontSize: 13,
                    fontWeight: 500,
                    cursor: isSaving ? "default" : "pointer",
                    opacity: isSaving ? 0.55 : 1,
                    border: "none",
                  }}
                >
                  {isSaving && (
                    <span
                      style={{
                        width: 12,
                        height: 12,
                        border: "2px solid rgba(253,253,252,0.3)",
                        borderTopColor: "var(--canvas)",
                        borderRadius: "50%",
                        animation: "nora-spin 0.9s linear infinite",
                      }}
                    />
                  )}
                  {isSaving ? "Salvando…" : "Salvar e processar"}
                </button>
              )}
            {savedMeetingId && (
              <a
                href={`#/meetings/${savedMeetingId}`}
                className="inline-flex items-center gap-2"
                style={{
                  padding: "9px 14px",
                  background: "rgba(98, 181, 133, 0.16)",
                  border: "1px solid rgba(98, 181, 133, 0.35)",
                  borderRadius: 9,
                  fontSize: 13,
                  color: "var(--success-ink)",
                }}
              >
                <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
                  <polyline points="20 6 9 17 4 12" />
                </svg>
                Reunião salva — abrir
              </a>
            )}
          </div>
        )}

        {/* Speaker management — only when there are unmapped speakers */}
        {Object.keys(speakerMap).length > 0 && (
          <section
            className="mt-7"
            style={{
              border: "1px solid var(--border)",
              borderRadius: 14,
              overflow: "hidden",
              background: "var(--canvas)",
            }}
          >
            <div
              style={{
                padding: "10px 18px",
                background: "var(--sidebar)",
                borderBottom: "1px solid var(--border)",
                fontSize: 10.5,
                fontWeight: 500,
                letterSpacing: "0.08em",
                textTransform: "uppercase",
                color: "var(--muted)",
              }}
            >
              Participantes
            </div>
            <div className="p-4 flex flex-col gap-2.5">
              {Object.entries(speakerMap).map(([speakerId, name]) => (
                <div key={speakerId} className="flex items-center gap-3">
                  <Avatar name={name} size={26} />
                  <input
                    type="text"
                    value={name}
                    onChange={(e) => renameSpeaker(speakerId, e.target.value)}
                    style={{ ...inputCss, padding: "8px 12px", fontSize: 13 }}
                    onFocus={focusOn}
                    onBlur={focusOff}
                  />
                  <span
                    style={{
                      fontSize: 11,
                      color: "var(--muted)",
                      fontVariantNumeric: "tabular-nums",
                      minWidth: 50,
                      textAlign: "right",
                    }}
                  >
                    {speakerId}
                  </span>
                </div>
              ))}
            </div>
          </section>
        )}
      </div>
      <style>{`
        @keyframes orbRing {
          from { transform: rotate(0deg); }
          to   { transform: rotate(360deg); }
        }
      `}</style>
    </main>
  );
}
