import { useEffect, useRef, useState } from "react";
import { useRecording } from "@/hooks/use-recording";
import { NoraLogo } from "@/components/brand/nora-logo";
import { formatDuration } from "@/lib/format";
import { Button, Card, Field, Input } from "./ui";

// UI fina da janela nativa "recorder". NÃO reescreve orquestração: tudo vem do
// hook useRecording (start/acumula transcript via eventos/parar/saveMeeting →
// upload). Esta tela só desenha os três estados — IDLE, RECORDING e SALVO —
// sobre os tokens editoriais do NORA (DM Sans, --ink, etc.), igual ao web.

/** Título padrão quando o usuário não digita nada: "Reunião <data local>". */
function defaultTitle(): string {
  return "Reunião " + new Date().toLocaleString("pt-BR");
}

// <select> nativo estilizado com os tokens do NORA (mesmo padrão da overlay).
const selectStyle: React.CSSProperties = {
  width: "100%",
  padding: "9px 30px 9px 11px",
  fontSize: 13,
  background: "var(--canvas)",
  border: "1px solid var(--border)",
  borderRadius: "var(--radius-md)",
  color: "var(--ink)",
  outline: "none",
  letterSpacing: "-0.005em",
  appearance: "none",
  backgroundImage:
    'url("data:image/svg+xml;utf8,<svg xmlns=\'http://www.w3.org/2000/svg\' width=\'10\' height=\'10\' viewBox=\'0 0 24 24\' fill=\'none\' stroke=\'%236E7178\' stroke-width=\'2\' stroke-linecap=\'round\'><polyline points=\'6 9 12 15 18 9\'/></svg>")',
  backgroundRepeat: "no-repeat",
  backgroundPosition: "right 10px center",
  fontFamily: "var(--sans)",
};

function Header() {
  return (
    <div
      className="flex items-center justify-between shrink-0"
      style={{
        padding: "14px 22px",
        borderBottom: "1px solid var(--border)",
        background: "var(--sidebar)",
      }}
    >
      <NoraLogo size={20} />
      <span
        style={{
          fontSize: 11,
          fontWeight: 500,
          letterSpacing: "0.08em",
          textTransform: "uppercase",
          color: "var(--muted)",
        }}
      >
        Gravação
      </span>
    </div>
  );
}

// Banner de erro reaproveitado pelos estados (start/save).
function ErrorBanner({ message }: { message: string }) {
  return (
    <div
      style={{
        padding: "10px 13px",
        background: "var(--danger-soft-bg)",
        border: "1px solid rgba(201, 119, 102, 0.30)",
        borderRadius: "var(--radius-md)",
        fontSize: 12.5,
        color: "var(--danger-ink)",
        lineHeight: 1.5,
      }}
    >
      {message}
    </div>
  );
}

export function Recorder() {
  const r = useRecording({ captureSystemAudio: true });

  const [title, setTitle] = useState("");
  const [captureSystemAudio, setCaptureSystemAudio] = useState(true);

  // Carrega os dispositivos de áudio ao montar (o hook também carrega, mas o
  // contrato pede o load explícito desta tela).
  useEffect(() => {
    r.loadDevices().catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Auto-scroll: mantém a transcrição grudada no fim conforme novas linhas
  // chegam, como num chat ao vivo.
  const scrollRef = useRef<HTMLDivElement | null>(null);
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [r.transcriptLines.length, r.partialText]);

  const handleStart = () => {
    r.startRecording({
      deviceName: r.selectedDevice,
      captureSystemAudio,
    });
  };

  const handleStopAndSave = async () => {
    await r.stopRecording();
    await r.saveMeeting(title.trim() || defaultTitle());
  };

  const handleDiscard = async () => {
    await r.stopRecording();
  };

  const handleNew = () => {
    setTitle("");
  };

  // ── Estado SALVO ──────────────────────────────────────────────────────
  if (r.savedMeetingId) {
    return (
      <div className="h-screen w-screen flex flex-col" style={{ background: "var(--canvas)", color: "var(--ink)" }}>
        <Header />
        <div className="flex-1 min-h-0 flex items-center justify-center" style={{ padding: 24 }}>
          <Card pad style={{ width: "min(420px, 100%)", textAlign: "center" }}>
            <div
              className="grid place-items-center"
              style={{
                width: 44,
                height: 44,
                margin: "0 auto 14px",
                borderRadius: 999,
                background: "var(--accent-soft)",
                color: "var(--accent-ink)",
              }}
            >
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
            </div>
            <h2
              style={{
                fontFamily: "var(--display)",
                fontSize: 19,
                fontWeight: 500,
                letterSpacing: "-0.018em",
                margin: "0 0 6px",
              }}
            >
              Reunião salva
            </h2>
            <p style={{ fontSize: 13, color: "var(--muted)", lineHeight: 1.55, margin: "0 0 18px" }}>
              A transcrição foi enviada e está sendo processada pela NORA.
            </p>
            <Button variant="primary" block onClick={handleNew}>
              Nova gravação
            </Button>
          </Card>
        </div>
      </div>
    );
  }

  // ── Estado RECORDING ──────────────────────────────────────────────────
  if (r.isRecording) {
    return (
      <div className="h-screen w-screen flex flex-col" style={{ background: "var(--canvas)", color: "var(--ink)" }}>
        <Header />

        {/* Barra de status: ponto pulsante + timer + ações */}
        <div
          className="flex items-center justify-between shrink-0"
          style={{ padding: "12px 22px", borderBottom: "1px solid var(--border)" }}
        >
          <div className="flex items-center gap-2.5">
            <span
              style={{
                width: 9,
                height: 9,
                borderRadius: "50%",
                background: "var(--danger-ink)",
                animation: "dotPulse 1.0s ease-in-out infinite",
              }}
            />
            <span style={{ fontSize: 12.5, fontWeight: 500, color: "var(--danger-ink)", letterSpacing: "-0.005em" }}>
              Gravando
            </span>
            <span
              style={{
                fontSize: 13,
                color: "var(--ink)",
                fontVariantNumeric: "tabular-nums",
                padding: "2px 10px",
                border: "1px solid var(--border)",
                borderRadius: 999,
                fontWeight: 500,
              }}
            >
              {formatDuration(r.duration)}
            </span>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="ghost" onClick={handleDiscard}>
              Descartar
            </Button>
            <Button variant="primary" onClick={handleStopAndSave} disabled={r.isSaving}>
              {r.isSaving ? "Salvando…" : "Parar e salvar"}
            </Button>
          </div>
        </div>

        {(r.error || r.saveError) && (
          <div style={{ padding: "12px 22px 0" }}>
            <ErrorBanner message={r.saveError || r.error || ""} />
          </div>
        )}

        {/* Transcrição ao vivo */}
        <div ref={scrollRef} className="flex-1 min-h-0 overflow-y-auto" style={{ padding: "16px 22px" }}>
          {r.transcriptLines.length === 0 && !r.partialText ? (
            <div className="h-full flex items-center justify-center text-center">
              <span style={{ fontSize: 13, color: "var(--muted)", lineHeight: 1.55, maxWidth: 320 }}>
                Aguardando fala… a transcrição aparece aqui em tempo real.
              </span>
            </div>
          ) : (
            <div className="flex flex-col gap-2.5">
              {r.transcriptLines.map((line) => {
                const name = r.getSpeakerName(line.speakerId, line.speaker, line.track);
                return (
                  <div key={line.id} style={{ fontSize: 13.5, lineHeight: 1.5, letterSpacing: "-0.005em" }}>
                    {name && (
                      <span style={{ fontWeight: 500, color: "var(--ink)", marginRight: 6 }}>{name}</span>
                    )}
                    <span style={{ color: "var(--ink)" }}>{line.text}</span>
                  </div>
                );
              })}
              {r.partialText && (
                <div style={{ fontSize: 13.5, lineHeight: 1.5, fontStyle: "italic", color: "var(--muted)" }}>
                  {r.partialText}
                </div>
              )}
            </div>
          )}
        </div>
      </div>
    );
  }

  // ── Estado IDLE ───────────────────────────────────────────────────────
  return (
    <div className="h-screen w-screen flex flex-col" style={{ background: "var(--canvas)", color: "var(--ink)" }}>
      <Header />
      <div className="flex-1 min-h-0 overflow-y-auto" style={{ padding: 24 }}>
        <Card pad style={{ width: "min(520px, 100%)", margin: "0 auto", display: "flex", flexDirection: "column", gap: 18 }}>
          <div>
            <h2
              style={{
                fontFamily: "var(--display)",
                fontSize: 21,
                fontWeight: 500,
                letterSpacing: "-0.02em",
                margin: "0 0 4px",
              }}
            >
              Nova gravação
            </h2>
            <p style={{ fontSize: 13, color: "var(--muted)", lineHeight: 1.55, margin: 0 }}>
              Capture o áudio da reunião com transcrição ao vivo. Ao salvar, a NORA
              processa o resumo, decisões e tarefas.
            </p>
          </div>

          <Field label="Título da reunião" htmlFor="recorder-title">
            <Input
              id="recorder-title"
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder={defaultTitle()}
            />
          </Field>

          <Field label="Microfone" htmlFor="recorder-mic">
            <select
              id="recorder-mic"
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
          </Field>

          <label className="flex items-center gap-2.5 cursor-pointer" style={{ fontSize: 13 }}>
            <input
              type="checkbox"
              checked={captureSystemAudio}
              onChange={(e) => setCaptureSystemAudio(e.target.checked)}
              style={{ accentColor: "var(--accent)", width: 15, height: 15 }}
            />
            <span style={{ color: "var(--ink)" }}>Capturar áudio do sistema</span>
          </label>

          {r.error && <ErrorBanner message={r.error} />}

          <Button variant="primary" block onClick={handleStart}>
            Iniciar gravação
          </Button>
        </Card>
      </div>
    </div>
  );
}
