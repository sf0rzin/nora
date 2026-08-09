"use client";

import Link from "next/link";
import { useState } from "react";
import { requestPasswordReset, ApiRequestError } from "@/lib/api/client";

export default function PasswordResetRequestPage() {
  const [email, setEmail] = useState("");
  const [done, setDone] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      await requestPasswordReset(email);
      setDone(true);
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : "Falha ao solicitar.");
    } finally {
      setLoading(false);
    }
  }

  if (done) {
    return (
      <div>
        <MailIcon />
        <h2 style={cardTitle}>Verifique seu e-mail.</h2>
        <p style={cardSub}>
          Se{" "}
          <strong style={{ color: "var(--ink)", fontWeight: 500 }}>{email}</strong> estiver
          cadastrado, enviamos um link para redefinir a senha.
        </p>
        <p style={footLink}>
          <Link href="/auth/login" style={{ textDecoration: "underline", textUnderlineOffset: 2 }}>
            Voltar para o login
          </Link>
        </p>
      </div>
    );
  }

  return (
    <div>
      <h2 style={cardTitle}>Esqueci minha senha.</h2>
      <p style={cardSub}>Digite o e-mail da conta — mandamos um link para redefinir.</p>

      <form
        onSubmit={onSubmit}
        style={{ marginTop: 18, display: "flex", flexDirection: "column", gap: 14 }}
      >
        <div className="field">
          <label className="field-label" htmlFor="rr-email">
            E-mail
          </label>
          <input
            className="input"
            id="rr-email"
            type="email"
            required
            placeholder="você@empresa.com"
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </div>

        {error && <p className="field-help is-err">{error}</p>}

        <button type="submit" className="btn btn-primary" disabled={loading}>
          {loading ? "Enviando…" : "Enviar link"}
        </button>
      </form>

      <p style={footLink}>
        <Link href="/auth/login" style={{ textDecoration: "underline", textUnderlineOffset: 2 }}>
          Voltar
        </Link>
      </p>
    </div>
  );
}

// ── Shared styles (auth cards) ──────────────────────────────────────
const cardTitle: React.CSSProperties = {
  fontSize: 19,
  fontWeight: 500,
  letterSpacing: "-0.018em",
  margin: "0 0 6px",
  color: "var(--ink)",
};
const cardSub: React.CSSProperties = {
  fontSize: 13.5,
  color: "var(--muted)",
  lineHeight: 1.55,
  margin: 0,
};
const footLink: React.CSSProperties = {
  fontSize: 12,
  color: "var(--muted)",
  textAlign: "center",
  margin: "14px 0 0",
};

function MailIcon() {
  return (
    <div
      style={{
        width: 42,
        height: 42,
        borderRadius: "50%",
        display: "grid",
        placeItems: "center",
        marginBottom: 16,
        background: "var(--accent-soft)",
        color: "var(--accent-ink)",
      }}
    >
      <svg
        width="19"
        height="19"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <rect x="3" y="5" width="18" height="14" rx="2" />
        <path d="m3 7 9 6 9-6" />
      </svg>
    </div>
  );
}
