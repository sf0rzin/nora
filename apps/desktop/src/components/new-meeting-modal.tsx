import { useEffect, useRef, useState } from "react";
import { useActiveRecording } from "@/hooks/use-active-recording";
import { ShaderOrb } from "@/components/brand/shader-orb";
import { Button, IconButton, Input, Textarea, Field } from "./ui";
import { Spinner } from "@/components/spinner";

interface Props {
  open: boolean;
  onClose: () => void;
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
      // Defaults: default mic + system audio on.
      // The user adjusts devices later in the overlay drawer.
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
          borderRadius: "var(--radius-lg)",
          boxShadow: "var(--shadow-xl)",
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
          <IconButton onClick={onClose} disabled={submitting} aria-label="Fechar">
            <svg
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
            >
              <path d="M18 6L6 18M6 6l12 12" />
            </svg>
          </IconButton>
        </header>

        <div className="flex flex-col gap-3">
          <Field label="Nome" htmlFor="new-meeting-title">
            <Input
              id="new-meeting-title"
              ref={titleRef}
              type="text"
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Ex.: Discovery — TOTVS Protheus"
              required
              maxLength={140}
            />
          </Field>

          <Field label="Descrição (opcional)" htmlFor="new-meeting-description">
            <Textarea
              id="new-meeting-description"
              value={description}
              onChange={(e) => setDescription(e.target.value)}
              placeholder="O que você espera discutir?"
              rows={2}
              maxLength={600}
              style={{ resize: "vertical", minHeight: 60 }}
            />
          </Field>
        </div>

        {err && (
          <div
            style={{
              padding: "10px 12px",
              background: "var(--danger-soft-bg)",
              border: "1px solid var(--danger-soft-border)",
              borderRadius: "var(--radius)",
              fontSize: 12.5,
              color: "var(--danger-ink)",
              lineHeight: 1.5,
            }}
          >
            {err}
          </div>
        )}

        <footer className="flex items-center justify-end gap-2">
          <Button variant="secondary" onClick={onClose} disabled={submitting}>
            Cancelar
          </Button>
          <Button
            type="submit"
            variant="primary"
            disabled={!canStart}
            className="inline-flex items-center gap-2"
          >
            {submitting ? (
              <>
                <Spinner color="rgba(253,253,252,0.35)" topColor="var(--canvas)" />
                Iniciando…
              </>
            ) : (
              <>
                <span
                  style={{
                    width: 9,
                    height: 9,
                    borderRadius: "var(--radius-pill)",
                    background: "var(--danger)",
                    boxShadow: "0 0 0 3px rgba(201, 119, 102, 0.30)",
                  }}
                />
                Iniciar gravação
              </>
            )}
          </Button>
        </footer>
      </form>
    </div>
  );
}
