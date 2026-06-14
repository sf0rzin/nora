"use client";

import type { Route } from "next";
import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { useState, type CSSProperties, type FormEvent } from "react";

import { NoraLogo } from "@/components/brand/nora-logo";
import { ApiRequestError, login, resendVerificationEmail, signup } from "@/lib/api/client";
import { setSession } from "@/lib/auth";
import { PASSWORD_MIN } from "@/lib/password-policy";

import "./auth.css";

type Mode = "login" | "signup";

/** Aceita só paths internos; rejeita `//host` e absolutas (open-redirect). */
function safeNextPath(raw: string | null): Route {
  if (!raw || !raw.startsWith("/") || raw.startsWith("//") || raw.startsWith("/\\")) {
    return "/dashboard" as Route;
  }
  return raw as Route;
}

// ── Live transcription (painel esquerdo · variante ambient) ──
type TLine = {
  t: number;
  speaker: string;
  time: string;
  text: string;
  tag: { kind: "decision" | "action" | "pii"; label: string };
};

const TRANSCRIPT_LINES: TLine[] = [
  {
    t: 0,
    speaker: "Camila",
    time: "14:30",
    text: "O DPO levantou três regras adicionais de PII pra fechar com a TOTVS.",
    tag: { kind: "decision", label: "Decisão" },
  },
  {
    t: 3.5,
    speaker: "Rafael",
    time: "14:31",
    text: "Mando a proposta do piloto na segunda.",
    tag: { kind: "action", label: "Action item" },
  },
  {
    t: 7,
    speaker: "Bruno",
    time: "14:33",
    text: "Conector Protheus pode ser read-only no MVP.",
    tag: { kind: "decision", label: "Decisão" },
  },
  {
    t: 10.5,
    speaker: "Camila",
    time: "14:34",
    text: "Camila Rodrigues pediu retenção máxima de 30 dias.",
    tag: { kind: "pii", label: "PII · nome" },
  },
  {
    t: 14,
    speaker: "Bruno",
    time: "14:36",
    text: "Levo a data de go-live pro board.",
    tag: { kind: "action", label: "Action item" },
  },
  {
    t: 17.5,
    speaker: "Lucas",
    time: "14:37",
    text: "Combinado. Documentar tudo no ADR 0012.",
    tag: { kind: "decision", label: "Decisão" },
  },
];
const TRANSCRIPT_DURATION = 23;

function TranscriptionAnimation() {
  return (
    <div className="transcript-anim" aria-hidden="true">
      <div className="transcript-bg" />
      <div className="transcript-fade-top" />
      <div className="transcript-fade-bottom" />
      <div className="transcript-stream">
        <div className="transcript-stream-inner">
          {TRANSCRIPT_LINES.map((l, i) => (
            <div
              key={i}
              className="t-line"
              style={
                {
                  "--line-delay": `${l.t}s`,
                  "--total-duration": `${TRANSCRIPT_DURATION}s`,
                } as CSSProperties
              }
            >
              <div className="t-meta">
                <span className="t-speaker">{l.speaker}</span>
                <span className="t-time">{l.time}</span>
              </div>
              <div className="t-text">
                <span>{l.text}</span>
                <span
                  className={`t-tag t-tag-${l.tag.kind}`}
                  style={{ "--tag-delay": `${l.t + 2.2}s` } as CSSProperties}
                >
                  <span className="t-tag-dot" />
                  {l.tag.label}
                </span>
              </div>
            </div>
          ))}
        </div>
      </div>
      <div className="transcript-badge">
        <span className="transcript-dot" />
        <span>NORA analisando…</span>
      </div>
    </div>
  );
}

function LeftPanel() {
  return (
    <div className="left">
      <TranscriptionAnimation />
      <div className="left-head">
        <NoraLogo size={22} />
        <Link href="/" className="back-link">
          <svg
            width="12"
            height="12"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <polyline points="15 18 9 12 15 6" />
          </svg>
          Voltar pro site
        </Link>
      </div>
      <div className="left-foot">
        <span>© 2026 NORA</span>
        <div className="links">
          <a href="#">Privacidade</a>
          <a href="#">Termos</a>
          <a href="#">Status</a>
        </div>
      </div>
    </div>
  );
}

// ── Icons ──
function ArrowRightIcon() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <line x1="5" y1="12" x2="19" y2="12" />
      <polyline points="12 5 19 12 12 19" />
    </svg>
  );
}
function ArrowLeftIcon() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="2"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <line x1="19" y1="12" x2="5" y2="12" />
      <polyline points="12 19 5 12 12 5" />
    </svg>
  );
}
function MicrosoftIcon() {
  return (
    <svg viewBox="0 0 23 23" width="16" height="16" aria-hidden="true">
      <rect x="1" y="1" width="10" height="10" fill="#F25022" />
      <rect x="12" y="1" width="10" height="10" fill="#7FBA00" />
      <rect x="1" y="12" width="10" height="10" fill="#00A4EF" />
      <rect x="12" y="12" width="10" height="10" fill="#FFB900" />
    </svg>
  );
}
function EyeIcon({ shown }: { shown: boolean }) {
  return shown ? (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M3 3l18 18" />
      <path d="M10.6 6.2A11 11 0 0 1 12 6c6.5 0 10 6 10 6a16 16 0 0 1-3 3.4M6.1 6.1A16 16 0 0 0 2 12s3.5 6 10 6a11 11 0 0 0 4.6-1" />
      <path d="M14.1 14.1A3 3 0 0 1 9.9 9.9" />
    </svg>
  ) : (
    <svg
      width="15"
      height="15"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7S2 12 2 12z" />
      <circle cx="12" cy="12" r="3" />
    </svg>
  );
}

// SSO ainda não conectado (Entra ID) — botão ativo por design; back-end é issue à parte.
const SSO_NOTICE = "Login com Microsoft ainda não está disponível — em integração.";

// ── Login ──
function LoginForm({ onSwitchToSignup, next }: { onSwitchToSignup: () => void; next: Route }) {
  const router = useRouter();
  const [email, setEmail] = useState("");
  const [pw, setPw] = useState("");
  const [showPw, setShowPw] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  // E-mail ainda não verificado (401 EMAIL_NOT_VERIFIED): oferece reenvio.
  const [needsVerify, setNeedsVerify] = useState(false);
  const [resending, setResending] = useState(false);
  const [resentNote, setResentNote] = useState<string | null>(null);

  async function submit(e: FormEvent) {
    e.preventDefault();
    setErr(null);
    setNeedsVerify(false);
    setResentNote(null);
    setBusy(true);
    try {
      const r = await login(email, pw);
      setSession(
        { userId: r.userId, tenantId: r.tenantId, email: r.email, displayName: r.displayName },
        r.expiresInSeconds,
      );
      router.replace(next);
      router.refresh();
    } catch (e2) {
      if (e2 instanceof ApiRequestError) {
        setErr(e2.message);
        if (e2.status === 401 && e2.payload?.code === "EMAIL_NOT_VERIFIED") {
          setNeedsVerify(true);
        }
      } else {
        setErr("Falha ao entrar.");
      }
      setBusy(false);
    }
  }

  async function resend() {
    if (!email) return;
    setResending(true);
    try {
      await resendVerificationEmail(email);
      // 202 sempre (anti-enumeração) — a mensagem é a mesma em qualquer caso.
      setErr(null);
      setNeedsVerify(false);
      setResentNote(
        "Se este e-mail estiver cadastrado e ainda não verificado, enviamos um novo link. Confira sua caixa de entrada.",
      );
    } catch {
      setResentNote("Não foi possível reenviar agora. Tente de novo em instantes.");
    } finally {
      setResending(false);
    }
  }

  return (
    <div className="right-inner">
      <div className="head">
        <h2>Bem-vindo de volta.</h2>
        <p>Entre na sua conta NORA pra continuar.</p>
      </div>

      <div className="sso-row">
        <button type="button" className="sso-btn" onClick={() => setErr(SSO_NOTICE)}>
          <MicrosoftIcon />
          Continuar com Microsoft
        </button>
      </div>

      <div className="divider">ou</div>

      {err && (
        <div className="error-banner">
          {err}
          {needsVerify && (
            <button
              type="button"
              onClick={resend}
              disabled={resending}
              style={{
                display: "block",
                marginTop: 8,
                background: "none",
                border: "none",
                padding: 0,
                font: "inherit",
                fontWeight: 500,
                color: "var(--ink)",
                cursor: resending ? "default" : "pointer",
                textDecoration: "underline",
              }}
            >
              {resending ? "Reenviando…" : "Reenviar e-mail de verificação"}
            </button>
          )}
        </div>
      )}
      {resentNote && (
        <div
          className="error-banner"
          style={{
            color: "var(--accent-ink)",
            background: "var(--accent-soft)",
            borderColor: "transparent",
          }}
        >
          {resentNote}
        </div>
      )}

      <form onSubmit={submit}>
        <div className="field">
          <label className="field-label" htmlFor="login-email">
            E-mail
          </label>
          <div className="field-input-wrap">
            <input
              id="login-email"
              type="email"
              className="field-input"
              autoComplete="email"
              placeholder="você@empresa.com"
              value={email}
              onChange={(e) => setEmail(e.target.value)}
              required
            />
          </div>
        </div>

        <div className="field">
          <label className="field-label" htmlFor="login-pw">
            <span>Senha</span>
            <Link href="/auth/password/reset/request">Esqueci minha senha</Link>
          </label>
          <div className="field-input-wrap">
            <input
              id="login-pw"
              type={showPw ? "text" : "password"}
              className="field-input with-icon"
              autoComplete="current-password"
              placeholder="••••••••"
              value={pw}
              onChange={(e) => setPw(e.target.value)}
              required
            />
            <button
              type="button"
              className="field-toggle"
              onClick={() => setShowPw((s) => !s)}
              aria-label={showPw ? "Ocultar senha" : "Mostrar senha"}
            >
              <EyeIcon shown={showPw} />
            </button>
          </div>
        </div>

        <button type="submit" className="btn btn-primary btn-full" disabled={busy}>
          {busy ? "Entrando…" : "Entrar"}
          {!busy && <ArrowRightIcon />}
        </button>

        <div className="foot">
          <span>
            Não tem conta?{" "}
            <button type="button" onClick={onSwitchToSignup}>
              Criar agora
            </button>
          </span>
          <span style={{ fontSize: 11, letterSpacing: "0.04em" }}>SSO · SAML</span>
        </div>
      </form>
    </div>
  );
}

// ── Signup (multi-step) ──
function strengthOf(pw: string): number {
  if (!pw) return 0;
  let s = 0;
  if (pw.length >= 8) s++;
  if (pw.length >= 12) s++;
  if (/[A-Z]/.test(pw) && /[a-z]/.test(pw)) s++;
  if (/\d/.test(pw) && /[^A-Za-z0-9]/.test(pw)) s++;
  return s;
}
const STRENGTH_LABELS = ["—", "Fraca", "Razoável", "Boa", "Forte"];

const SIGNUP_STEPS = [
  { id: "email", label: "Conta" },
  { id: "password", label: "Segurança" },
  { id: "workspace", label: "Workspace" },
] as const;

type SignupData = {
  email: string;
  fullName: string;
  password: string;
  confirm: string;
  workspaceName: string;
  role: "individual" | "team" | "company";
  agreed: boolean;
};

function SignupForm({ onSwitchToLogin }: { onSwitchToLogin: () => void }) {
  const [stepIdx, setStepIdx] = useState(0);
  const [data, setData] = useState<SignupData>({
    email: "",
    fullName: "",
    password: "",
    confirm: "",
    workspaceName: "",
    role: "individual",
    agreed: false,
  });
  const [showPw, setShowPw] = useState(false);
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const [done, setDone] = useState(false);

  function update(patch: Partial<SignupData>) {
    setData((d) => ({ ...d, ...patch }));
  }

  const step = SIGNUP_STEPS[stepIdx];
  const canNext =
    step.id === "email"
      ? data.email.includes("@") && data.fullName.trim().length > 1
      : step.id === "password"
        ? data.password.length >= PASSWORD_MIN && data.password === data.confirm
        : data.workspaceName.trim().length > 1 && data.agreed;

  async function submit(e?: FormEvent) {
    if (e) e.preventDefault();
    setErr(null);
    if (!canNext) return;
    if (stepIdx < SIGNUP_STEPS.length - 1) {
      setStepIdx(stepIdx + 1);
      return;
    }
    setBusy(true);
    try {
      await signup({
        email: data.email,
        password: data.password,
        displayName: data.fullName,
        companyName: data.workspaceName,
        role: data.role,
      });
      setDone(true);
    } catch (e2) {
      setErr(e2 instanceof ApiRequestError ? e2.message : "Falha ao criar conta.");
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <div className="right-inner">
        <div className="done-state">
          <div className="done-check">
            <svg
              width="28"
              height="28"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.4"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <polyline points="20 6 9 17 4 12" />
            </svg>
          </div>
          <h3>Workspace criado.</h3>
          <p>
            Mandamos um e-mail pra{" "}
            <strong style={{ color: "var(--ink)", fontWeight: 500 }}>{data.email}</strong>. Confirma
            pra ativar a conta e entrar.
          </p>
          <button className="btn btn-primary btn-full" onClick={onSwitchToLogin}>
            Ir pro login
            <ArrowRightIcon />
          </button>
        </div>
      </div>
    );
  }

  const s = strengthOf(data.password);

  return (
    <div className="right-inner">
      <div className="stepper">
        {SIGNUP_STEPS.map((_, i) => (
          <span
            key={i}
            className={`stepper-pip ${i < stepIdx ? "is-done" : i === stepIdx ? "is-current" : ""}`}
          />
        ))}
      </div>
      <div className="stepper-meta">
        <span>
          Passo {stepIdx + 1} de {SIGNUP_STEPS.length}
        </span>
        <strong>{step.label}</strong>
      </div>

      <div className="head" style={{ marginTop: 20 }}>
        {step.id === "email" && (
          <>
            <h2>Crie sua conta NORA.</h2>
            <p>Grátis pra sempre. Sem cartão.</p>
          </>
        )}
        {step.id === "password" && (
          <>
            <h2>Escolha uma senha forte.</h2>
            <p>Mínimo 8 caracteres. Recomendamos número + símbolo.</p>
          </>
        )}
        {step.id === "workspace" && (
          <>
            <h2>Conte sobre o workspace.</h2>
            <p>Você pode trocar essas informações depois nas configurações.</p>
          </>
        )}
      </div>

      {err && <div className="error-banner">{err}</div>}

      <form onSubmit={submit}>
        {step.id === "email" && (
          <>
            <div className="sso-row">
              <button type="button" className="sso-btn" onClick={() => setErr(SSO_NOTICE)}>
                <MicrosoftIcon />
                Cadastrar com Microsoft
              </button>
            </div>
            <div className="divider">ou com e-mail</div>
            <div className="field">
              <label className="field-label" htmlFor="su-name">
                Nome completo
              </label>
              <input
                id="su-name"
                className="field-input"
                type="text"
                placeholder="Lucas Pereira"
                value={data.fullName}
                onChange={(e) => update({ fullName: e.target.value })}
                autoComplete="name"
                required
              />
            </div>
            <div className="field">
              <label className="field-label" htmlFor="su-email">
                E-mail corporativo
              </label>
              <input
                id="su-email"
                className="field-input"
                type="email"
                placeholder="você@empresa.com"
                value={data.email}
                onChange={(e) => update({ email: e.target.value })}
                autoComplete="email"
                required
              />
              <div className="field-helper">
                Recomendamos e-mail corporativo pra recursos colaborativos.
              </div>
            </div>
          </>
        )}

        {step.id === "password" && (
          <>
            <div className="field">
              <label className="field-label" htmlFor="su-pw">
                Senha
              </label>
              <div className="field-input-wrap">
                <input
                  id="su-pw"
                  type={showPw ? "text" : "password"}
                  className="field-input with-icon"
                  placeholder="••••••••"
                  value={data.password}
                  onChange={(e) => update({ password: e.target.value })}
                  autoComplete="new-password"
                  required
                />
                <button
                  type="button"
                  className="field-toggle"
                  onClick={() => setShowPw((p) => !p)}
                  aria-label={showPw ? "Ocultar" : "Mostrar"}
                >
                  <EyeIcon shown={showPw} />
                </button>
              </div>
              <div className="pw-strength">
                <div className={`pw-bars s${s}`}>
                  <span />
                  <span />
                  <span />
                  <span />
                </div>
                <div className="pw-meta">
                  <span>{STRENGTH_LABELS[s]}</span>
                  <span>{data.password.length} caracteres</span>
                </div>
              </div>
            </div>
            <div className="field">
              <label className="field-label" htmlFor="su-confirm">
                Confirme a senha
              </label>
              <input
                id="su-confirm"
                type={showPw ? "text" : "password"}
                className="field-input"
                placeholder="••••••••"
                value={data.confirm}
                onChange={(e) => update({ confirm: e.target.value })}
                autoComplete="new-password"
                required
              />
              {data.confirm && data.confirm !== data.password && (
                <div className="field-helper is-err">As senhas não coincidem.</div>
              )}
            </div>
          </>
        )}

        {step.id === "workspace" && (
          <>
            <div className="field">
              <label className="field-label" htmlFor="su-ws">
                Nome do workspace
              </label>
              <input
                id="su-ws"
                className="field-input"
                type="text"
                placeholder={
                  data.fullName
                    ? `${data.fullName.split(" ")[0]} · Pessoal`
                    : "Ex.: Acme · Engineering"
                }
                value={data.workspaceName}
                onChange={(e) => update({ workspaceName: e.target.value })}
                required
              />
              <div className="field-helper">Você pode renomear depois nas configurações.</div>
            </div>
            <div className="field">
              <label className="field-label">Como você vai usar?</label>
              <div className="role-options">
                {(
                  [
                    {
                      id: "individual",
                      title: "Pra mim mesmo",
                      sub: "Copiloto pessoal de reuniões. Plano Core.",
                    },
                    {
                      id: "team",
                      title: "Pra um time pequeno",
                      sub: "Convido colegas depois. Continua no Core até precisar de Enterprise.",
                    },
                    {
                      id: "company",
                      title: "Pra empresa toda",
                      sub: "Quero avaliar Enterprise. Falo com vendas.",
                    },
                  ] as const
                ).map((opt) => (
                  <button
                    key={opt.id}
                    type="button"
                    className={`role-opt ${data.role === opt.id ? "is-selected" : ""}`}
                    onClick={() => update({ role: opt.id })}
                  >
                    <span className="role-radio" />
                    <span className="role-content">
                      <span className="role-title">{opt.title}</span>
                      <span className="role-sub">{opt.sub}</span>
                    </span>
                  </button>
                ))}
              </div>
            </div>
            <label className="terms">
              <input
                type="checkbox"
                checked={data.agreed}
                onChange={(e) => update({ agreed: e.target.checked })}
              />
              <span>
                Li e aceito os <a href="#">Termos de Uso</a> e a{" "}
                <a href="#">Política de Privacidade</a>. Entendo que NORA é LGPD-compliant e que
                posso solicitar exclusão dos meus dados a qualquer momento.
              </span>
            </label>
          </>
        )}

        <div className="submit-row">
          {stepIdx > 0 && (
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => {
                setErr(null);
                setStepIdx(stepIdx - 1);
              }}
            >
              <ArrowLeftIcon /> Voltar
            </button>
          )}
          <button
            type="submit"
            className={`btn btn-primary ${stepIdx === 0 ? "btn-full" : ""}`}
            style={stepIdx > 0 ? { flex: 1 } : undefined}
            disabled={!canNext || busy}
          >
            {busy
              ? "Criando…"
              : stepIdx === SIGNUP_STEPS.length - 1
                ? "Criar workspace"
                : "Continuar"}
            {!busy && <ArrowRightIcon />}
          </button>
        </div>

        <div className="foot">
          <span>
            Já tem conta?{" "}
            <button type="button" onClick={onSwitchToLogin}>
              Entrar
            </button>
          </span>
        </div>
      </form>
    </div>
  );
}

// ── Shell ──
export function AuthScreen({ initialMode }: { initialMode: Mode }) {
  const params = useSearchParams();
  const next = safeNextPath(params.get("next"));
  const [mode, setMode] = useState<Mode>(initialMode);

  function switchTo(m: Mode) {
    setMode(m);
    if (typeof window !== "undefined") {
      const nextQs = params.get("next");
      const url =
        m === "signup"
          ? "/auth/signup"
          : `/auth/login${nextQs ? `?next=${encodeURIComponent(nextQs)}` : ""}`;
      window.history.replaceState(null, "", url);
    }
  }

  return (
    <div className="nora-auth">
      <div className="shell">
        <LeftPanel />
        <div className="right">
          {mode === "login" ? (
            <LoginForm next={next} onSwitchToSignup={() => switchTo("signup")} />
          ) : (
            <SignupForm onSwitchToLogin={() => switchTo("login")} />
          )}
        </div>
      </div>
    </div>
  );
}
