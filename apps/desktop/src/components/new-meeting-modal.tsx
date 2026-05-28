import { useEffect, useRef, useState } from "react";
import { useActiveRecording } from "@/hooks/use-active-recording";
import { ShaderOrb } from "@/components/brand/shader-orb";

interface Props {
  open: boolean;
  onClose: () => void;
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
  fontFamily: "var(--sans)",
};

function focusOn(e: React.FocusEvent<HTMLElement>) {
  e.currentTarget.style.borderColor = "var(--accent)";
  e.currentTarget.style.boxShadow = "0 0 0 3px var(--accent-soft)";
}
function focusOff(e: React.FocusEvent<HTMLElement>) {
  e.currentTarget.style.borderColor = "var(--border)";
  e.currentTarget.style.boxShadow = "none";
}

export function NewMeetingModal({ open, onClose }: Props) {
  const recording = useActiveRecording();
  const titleRef = useRef<HTMLInputElement | null>(null);

  const [title, setTitle] = useState("");
  const [description, setDescription] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!open) return;
    setTitle("");
    setDescription("");
    setErr(null);
    setSubmitting(false);
    setTimeout(() => titleRef.current?.focus(), 60);
  }, [open]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape" && !submitting) onClose();
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, submitting, onClose]);

  if (!open) return null;

  const canStart = title.trim().length >= 1 && !submitting;

  const submit = async (e?: React.FormEvent) => {
    e?.preventDefault();
    if (!canStart) return;
    setSubmitting(true);
    setErr(null);
    try {
      // Defaults: default mic + áudio do sistema ligado.
      // O usuário ajusta dispositivos depois no drawer da overlay.
      await recording.start({
        title: title.trim(),
        description: description.trim(),
        deviceName: null,
        captureSystemAudio: true,
        systemAudioDevice: null,
      });
      onClose();
    } catch (e2) {
      const msg = e2 instanceof Error ? e2.message : String(e2);
      setErr(msg || "Falha ao iniciar a gravação.");
      setSubmitting(false);
    }
  };

  return (
    <div
      onClick={() => !submitting && onClose()}
      role="dialog"
      aria-modal="true"
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 60,
        background: "rgba(20, 22, 26, 0.32)",
        backdropFilter: "blur(3px)",
        WebkitBackdropFilter: "blur(3px)",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        padding: 24,
        animation: "paletteFadeIn 180ms ease",
      }}
    >
      <form
        onClick={(e) => e.stopPropagation()}
        onSubmit={submit}
        style={{
          width: "min(440px, 100%)",
          background: "var(--canvas)",
          border: "1px solid var(--border)",
          borderRadius: 16,
          boxShadow:
            "0 32px 80px -24px rgba(15, 23, 42, 0.32), 0 8px 22px rgba(15, 23, 42, 0.06)",
          padding: "26px 26px 22px",
          display: "flex",
          flexDirection: "column",
          gap: 18,
          animation: "paletteSlideIn 220ms cubic-bezier(.2,.8,.2,1)",
        }}
      >
        <header className="flex items-start gap-4">
          <div
            className="relative shrink-0 grid place-items-center"
            style={{ width: 48, height: 48 }}
          >
            <div
              className="absolute inset-0 rounded-full"
              style={{
                background:
                  "radial-gradient(circle at 50% 50%, var(--accent-soft) 0%, transparent 60%)",
                opacity: 0.85,
                filter: "blur(6px)",
              }}
            />
            <ShaderOrb
              size={42}
              speed={0.9}
              intensity={0.85}
              style={{ position: "relative", zIndex: 1 }}
            />
          </div>
          <div className="flex-1 min-w-0">
            <h2
              style={{
                fontFamily: "var(--display)",
                fontSize: 20,
                fontWeight: 500,
                letterSpacing: "-0.022em",
                color: "var(--ink)",
                margin: 0,
                lineHeight: 1.2,
              }}
            >
              Nova reunião
            </h2>
            <p
              style={{
                fontSize: 13,
                color: "var(--muted)",
                margin: "4px 0 0",
                lineHeight: 1.5,
              }}
            >
              Dê um nome e um contexto rápido. O resto ajusta na overlay.
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            aria-label="Fechar"
            className="grid place-items-center rounded-md transition-colors"
            style={{
              width: 28,
              height: 28,
              background: "transparent",
              border: "none",
              color: "var(--muted)",
              cursor: submitting ? "default" : "pointer",
            }}
            onMouseEnter={(e) => {
              if (!submitting) {
                e.currentTarget.style.background = "var(--chip)";
                e.currentTarget.style.color = "var(--ink)";
              }
            }}
            onMouseLeave={(e) => {
              e.currentTarget.style.background = "transparent";
              e.currentTarget.style.color = "var(--muted)";
            }}
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </button>
        </header>

        <div className="flex flex-col gap-3">
          <label className="flex flex-col gap-1.5">
            <span
              style={{
                fontSize: 11.5,
                fontWeight: 500,
                color: "var(--muted)",
                letterSpacing: "0.04em",
                textTransform: "uppercase",
              }}
            >
              Nome
            </span>
            <input
              ref={titleRef}
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Ex.: Discovery — TOTVS Protheus"
              required
              maxLength={140}
              style={inputCss}
              onFocus={focusOn}
              onBlur={focusOff}
            />
          </label>

          <label className="flex flex-col gap-1.5">
            <span
              style={{
                fontSize: 11.5,
                fontWeight: 500,
                color: "var(--muted)",
                letterSpacing: "0.04em",
                textTransform: "uppercase",
              }}
            >
              Descrição{" "}
              <span style={{ textTransform: "none", letterSpacing: 0, fontWeight: 400, opacity: 0.7 }}>
                (opcional)
              </span>
            </span>
            <textarea
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="O que você espera discutir?"
              rows={2}
              maxLength={600}
              style={{
                ...inputCss,
                resize: "vertical",
                minHeight: 60,
                lineHeight: 1.5,
              }}
              onFocus={focusOn}
              onBlur={focusOff}
            />
          </label>
        </div>

        {err && (
          <div
            style={{
              padding: "10px 12px",
              background: "rgba(201, 119, 102, 0.10)",
              border: "1px solid rgba(201, 119, 102, 0.25)",
              borderRadius: 9,
              fontSize: 12.5,
              color: "var(--danger-ink)",
              lineHeight: 1.5,
            }}
          >
            {err}
          </div>
        )}

        <footer className="flex items-center justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            disabled={submitting}
            style={{
              padding: "9px 14px",
              background: "transparent",
              border: "1px solid var(--border)",
              borderRadius: 9,
              fontSize: 13,
              color: "var(--ink)",
              cursor: submitting ? "default" : "pointer",
              fontFamily: "var(--sans)",
              fontWeight: 500,
              opacity: submitting ? 0.5 : 1,
            }}
          >
            Cancelar
          </button>
          <button
            type="submit"
            disabled={!canStart}
            className="inline-flex items-center gap-2"
            style={{
              padding: "9px 18px",
              background: "var(--ink)",
              color: "var(--canvas)",
              border: "1px solid var(--ink)",
              borderRadius: 9,
              fontSize: 13,
              fontWeight: 500,
              cursor: canStart ? "pointer" : "default",
              opacity: canStart ? 1 : 0.5,
              fontFamily: "var(--sans)",
            }}
          >
            {submitting ? (
              <>
                <span
                  style={{
                    width: 12,
                    height: 12,
                    border: "2px solid rgba(253,253,252,0.35)",
                    borderTopColor: "var(--canvas)",
                    borderRadius: "50%",
                    animation: "nora-spin 0.9s linear infinite",
                  }}
                />
                Iniciando…
              </>
            ) : (
              <>
                <span
                  style={{
                    width: 9,
                    height: 9,
                    borderRadius: "50%",
                    background: "var(--danger)",
                    boxShadow: "0 0 0 3px rgba(201,119,102,0.30)",
                  }}
                />
                Iniciar gravação
              </>
            )}
          </button>
        </footer>
      </form>
      <style>{`
        @keyframes paletteFadeIn { from { opacity: 0; } to { opacity: 1; } }
        @keyframes paletteSlideIn {
          from { opacity: 0; transform: translateY(-8px) scale(0.985); }
          to   { opacity: 1; transform: translateY(0)    scale(1); }
        }
      `}</style>
    </div>
  );
}
