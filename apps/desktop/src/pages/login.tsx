import { useState, useEffect, type FormEvent } from "react";
import { useAuth } from "@/hooks/use-auth";
import { login } from "@/lib/auth";
import { toUserMessage } from "@/lib/errors";
import { NoraLogo } from "@/components/brand/nora-logo";
import { Button, Input, Field, IconButton } from "@/components/ui";

interface TranscriptLine {
  delay: number;
  speaker: string;
  time: string;
  text: string;
  tag: { kind: "decision" | "action" | "pii"; label: string };
}

const TRANSCRIPT_LINES: TranscriptLine[] = [
  {
    delay: 0,
    speaker: "Camila",
    time: "14:30",
    text: "O DPO levantou três regras adicionais de PII pra fechar com a TOTVS.",
    tag: { kind: "decision", label: "Decisão" },
  },
  {
    delay: 3.5,
    speaker: "Rafael",
    time: "14:31",
    text: "Mando a proposta do piloto na segunda.",
    tag: { kind: "action", label: "Action item" },
  },
  {
    delay: 7,
    speaker: "Bruno",
    time: "14:33",
    text: "Conector Protheus pode ser read-only no MVP.",
    tag: { kind: "decision", label: "Decisão" },
  },
  {
    delay: 10.5,
    speaker: "Camila",
    time: "14:34",
    text: "Camila Rodrigues pediu retenção máxima de 30 dias.",
    tag: { kind: "pii", label: "PII · nome" },
  },
  {
    delay: 14,
    speaker: "Bruno",
    time: "14:36",
    text: "Levo a data de go-live pro board.",
    tag: { kind: "action", label: "Action item" },
  },
  {
    delay: 17.5,
    speaker: "Lucas",
    time: "14:37",
    text: "Combinado. Documentar tudo no ADR 0012.",
    tag: { kind: "decision", label: "Decisão" },
  },
];

const TRANSCRIPT_DURATION = 23;

function TagPill({ kind, label, delay }: { kind: TranscriptLine["tag"]["kind"]; label: string; delay: number }) {
  const palette: Record<typeof kind, { bg: string; fg: string; dot: string }> = {
    decision: { bg: "var(--accent-soft)", fg: "var(--accent-ink)", dot: "var(--accent-ink)" },
    action:   { bg: "rgba(98,181,133,0.16)", fg: "var(--success-ink)", dot: "var(--success-ink)" },
    pii:      { bg: "rgba(201,119,102,0.16)", fg: "var(--danger-ink)", dot: "var(--danger-ink)" },
  };
  const c = palette[kind];
  return (
    <span
      className="inline-flex items-center gap-1.5 px-2 py-0.5 rounded-full whitespace-nowrap"
      style={{
        fontSize: 10.5,
        fontWeight: 500,
        letterSpacing: "0.02em",
        background: c.bg,
        color: c.fg,
        opacity: 0,
        transform: "scale(0.85) translateY(2px)",
        animation: `tTagPop ${TRANSCRIPT_DURATION}s ease-out infinite`,
        animationDelay: `${delay}s`,
      }}
    >
      <span style={{ width: 5, height: 5, borderRadius: "50%", background: c.dot }} />
      {label}
    </span>
  );
}

function LiveTranscription() {
  return (
    <div className="absolute inset-0 overflow-hidden pointer-events-none" aria-hidden>
      <div
        className="absolute inset-0"
        style={{
          background:
            "radial-gradient(ellipse 50% 40% at 30% 30%, oklch(0.94 0.045 248) 0%, transparent 70%), " +
            "radial-gradient(ellipse 60% 50% at 70% 70%, oklch(0.95 0.035 218) 0%, transparent 70%), " +
            "linear-gradient(180deg, var(--canvas) 0%, oklch(0.985 0.012 248) 50%, var(--canvas) 100%)",
        }}
      />
      {/* fade top */}
      <div
        className="absolute top-0 left-0 right-0"
        style={{
          height: "22%",
          background:
            "linear-gradient(to bottom, var(--canvas) 0%, var(--canvas) 40%, transparent 100%)",
          zIndex: 4,
        }}
      />
      {/* fade bottom */}
      <div
        className="absolute bottom-0 left-0 right-0"
        style={{
          height: "18%",
          background:
            "linear-gradient(to top, var(--canvas) 0%, var(--canvas) 30%, transparent 100%)",
          zIndex: 4,
        }}
      />

      {/* badge */}
      <div
        className="absolute inline-flex items-center gap-2"
        style={{
          top: 78,
          left: 48,
          padding: "5px 10px 5px 8px",
          background: "var(--canvas)",
          border: "1px solid var(--border)",
          borderRadius: 999,
          fontSize: 11,
          color: "var(--muted)",
          letterSpacing: "-0.005em",
          zIndex: 5,
          boxShadow: "0 4px 14px -8px rgba(15,23,42,0.15)",
          opacity: 0,
          animation: "badgeFadeIn 1s ease 0.5s forwards",
        }}
      >
        <span
          style={{
            width: 7,
            height: 7,
            borderRadius: "50%",
            background: "var(--accent)",
            boxShadow: "0 0 0 3px var(--accent-soft)",
            animation: "dotPulse 1.4s ease-in-out infinite",
          }}
        />
        <span>Nora analisando…</span>
      </div>

      <div
        className="absolute flex items-center justify-center"
        style={{ top: 96, bottom: 76, left: 0, right: 0, zIndex: 2, padding: "0 48px" }}
      >
        <div className="w-full flex flex-col gap-[18px]" style={{ maxWidth: 420 }}>
          {TRANSCRIPT_LINES.map((l, i) => (
            <div
              key={i}
              style={{
                opacity: 0,
                transform: "translateY(8px)",
                animation: `tLineCycle ${TRANSCRIPT_DURATION}s ease-in-out infinite`,
                animationDelay: `${l.delay}s`,
              }}
            >
              <div
                className="flex items-center gap-2.5 mb-1.5"
                style={{
                  fontSize: 10.5,
                  color: "var(--muted)",
                  letterSpacing: "0.04em",
                  textTransform: "uppercase",
                }}
              >
                <span
                  style={{
                    fontWeight: 500,
                    color: "var(--ink)",
                    textTransform: "none",
                    letterSpacing: "-0.005em",
                    fontSize: 12,
                  }}
                >
                  {l.speaker}
                </span>
                <span style={{ fontVariantNumeric: "tabular-nums" }}>{l.time}</span>
              </div>
              <div
                className="flex items-start gap-2 flex-wrap"
                style={{ fontSize: 14.5, lineHeight: 1.5, color: "var(--ink)" }}
              >
                <span style={{ flex: 1, minWidth: 0 }}>{l.text}</span>
                <TagPill kind={l.tag.kind} label={l.tag.label} delay={l.delay + 2.2} />
              </div>
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}

function MicrosoftIcon() {
  return (
    <svg className="w-4 h-4 shrink-0" viewBox="0 0 23 23" xmlns="http://www.w3.org/2000/svg">
      <rect x="1" y="1" width="10" height="10" fill="#F25022" />
      <rect x="12" y="1" width="10" height="10" fill="#7FBA00" />
      <rect x="1" y="12" width="10" height="10" fill="#00A4EF" />
      <rect x="12" y="12" width="10" height="10" fill="#FFB900" />
    </svg>
  );
}

function ArrowRight() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <line x1="5" y1="12" x2="19" y2="12" />
      <polyline points="12 5 19 12 12 19" />
    </svg>
  );
}

function EyeIcon({ shown }: { shown: boolean }) {
  return shown ? (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 3l18 18" />
      <path d="M10.6 6.2A11 11 0 0 1 12 6c6.5 0 10 6 10 6a16 16 0 0 1-3 3.4M6.1 6.1A16 16 0 0 0 2 12s3.5 6 10 6a11 11 0 0 0 4.6-1" />
      <path d="M14.1 14.1A3 3 0 0 1 9.9 9.9" />
    </svg>
  ) : (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

export function LoginPage() {
  const { authenticated, login: setAuthUser } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(false);

  useEffect(() => {
    if (authenticated) window.location.hash = "#/meetings";
  }, [authenticated]);

  if (authenticated) return null;

  const handleSubmit = async (e: FormEvent) => {
    e.preventDefault();
    setError("");
    setLoading(true);
    try {
      const user = await login({ email, password });
      setAuthUser(user);
    } catch (err: unknown) {
      console.error("[login] falha:", err);
      setError(toUserMessage(err, "Erro ao fazer login."));
    } finally {
      setLoading(false);
    }
  };

  return (
    <div
      className="h-full w-full grid"
      style={{
        gridTemplateColumns: "1fr 1fr",
        background: "var(--canvas)",
      }}
    >
      {/* LEFT — live transcription panel */}
      <aside
        className="relative hidden lg:flex flex-col overflow-hidden"
        style={{
          background: "var(--canvas)",
          borderRight: "1px solid var(--border)",
          padding: "36px 0",
        }}
      >
        <LiveTranscription />

        <div
          className="flex items-center justify-between relative z-10"
          style={{ padding: "0 48px" }}
        >
          <NoraLogo animate />
        </div>

        <div className="flex-1" />

        <div
          className="flex items-center justify-between relative z-10"
          style={{
            padding: "0 48px",
            fontSize: 12,
            color: "var(--muted)",
          }}
        >
          <span>Inteligência conversacional</span>
          <span>v1 · LGPD-first</span>
        </div>
      </aside>

      {/* RIGHT — form */}
      <main className="flex items-center justify-center p-8 overflow-y-auto">
        <form
          onSubmit={handleSubmit}
          className="w-full flex flex-col gap-7"
          style={{ maxWidth: 360 }}
        >
          <div>
            <h1
              className="mb-1.5"
              style={{
                fontFamily: "var(--display)",
                fontSize: 28,
                fontWeight: 500,
                letterSpacing: "-0.025em",
                color: "var(--ink)",
                lineHeight: 1.12,
              }}
            >
              Bem-vindo de volta.
            </h1>
            <p
              style={{
                fontSize: 14,
                color: "var(--muted)",
                lineHeight: 1.55,
              }}
            >
              Entre na sua conta Nora pra continuar.
            </p>
          </div>

          <Button
            variant="secondary"
            block
            onClick={() =>
              setError("Login com Microsoft em breve — use e-mail e senha por enquanto.")
            }
          >
            <MicrosoftIcon />
            Continuar com Microsoft
          </Button>

          <div
            className="flex items-center gap-3"
            style={{
              fontSize: 11,
              color: "var(--muted)",
              letterSpacing: "0.04em",
              textTransform: "uppercase",
            }}
          >
            <div className="flex-1 h-px" style={{ background: "var(--border)" }} />
            <span>ou</span>
            <div className="flex-1 h-px" style={{ background: "var(--border)" }} />
          </div>

          <div className="flex flex-col gap-3.5">
            <Field label="E-mail" htmlFor="email">
              <Input
                id="email"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                placeholder="você@empresa.com"
                required
              />
            </Field>

            <Field label="Senha" htmlFor="password">
              <div className="relative flex items-center">
                <Input
                  id="password"
                  type={showPassword ? "text" : "password"}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  placeholder="••••••••"
                  required
                  minLength={8}
                  className="pr-9"
                />
                <IconButton
                  size="sm"
                  onClick={() => setShowPassword(!showPassword)}
                  aria-label={showPassword ? "Ocultar senha" : "Mostrar senha"}
                  className="absolute right-1.5"
                >
                  <EyeIcon shown={showPassword} />
                </IconButton>
              </div>
            </Field>
          </div>

          {error && (
            <p
              style={{
                fontSize: 12.5,
                color: "var(--danger-ink)",
                padding: "10px 12px",
                background: "var(--danger-soft-bg)",
                border: "1px solid var(--danger-soft-border)",
                borderRadius: 8,
                lineHeight: 1.45,
              }}
            >
              {error}
            </p>
          )}

          <Button type="submit" variant="primary" block disabled={loading}>
            {loading ? "Entrando…" : "Entrar"}
            {!loading && <ArrowRight />}
          </Button>

          <div
            className="flex justify-between items-center"
            style={{ fontSize: 12.5, color: "var(--muted)" }}
          >
            <span>
              Não tem conta?{" "}
              <span style={{ color: "var(--muted)" }}>Falar com vendas</span>
            </span>
            <span style={{ letterSpacing: "0.04em" }}>SSO · SAML</span>
          </div>
        </form>
      </main>
    </div>
  );
}
