"use client";

import Link from "next/link";
import { useSearchParams } from "next/navigation";
import { Suspense, useEffect, useState } from "react";
import { verifyEmail, ApiRequestError } from "@/lib/api/client";

export default function VerifyEmailPage() {
  return (
    <Suspense fallback={null}>
      <VerifyEmailContent />
    </Suspense>
  );
}

function VerifyEmailContent() {
  const params = useSearchParams();
  const token = params.get("token");
  const [state, setState] = useState<"idle" | "loading" | "ok" | "error">("idle");
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    if (!token) {
      setState("error");
      setError("Token ausente.");
      return;
    }
    setState("loading");
    verifyEmail(token)
      .then(() => setState("ok"))
      .catch((err) => {
        setState("error");
        setError(err instanceof ApiRequestError ? err.message : "Token invalido.");
      });
  }, [token]);

  if (state === "ok") {
    return (
      <div>
        <StateIcon kind="ok" />
        <h2 style={cardTitle}>E-mail verificado.</h2>
        <p style={cardSub}>Sua conta está ativa. Bem-vindo à Nora.</p>
        <Link
          href="/auth/login"
          className="btn btn-primary"
          style={{ width: "100%", marginTop: 18 }}
        >
          Entrar
        </Link>
      </div>
    );
  }

  if (state === "error") {
    return (
      <div>
        <StateIcon kind="warn" />
        <h2 style={cardTitle}>Não foi possível verificar.</h2>
        <p style={cardSub}>{error}</p>
        <p style={footLink}>
          <Link href="/auth/login" style={{ textDecoration: "underline", textUnderlineOffset: 2 }}>
            Voltar para o login
          </Link>
        </p>
      </div>
    );
  }

  // idle / loading
  return (
    <div>
      <StateIcon kind="mail" />
      <h2 style={cardTitle}>Verificando seu e-mail.</h2>
      <p style={cardSub}>Só um instante enquanto confirmamos o link.</p>
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

function StateIcon({ kind }: { kind: "ok" | "warn" | "mail" }) {
  const base: React.CSSProperties = {
    width: 42,
    height: 42,
    borderRadius: "50%",
    display: "grid",
    placeItems: "center",
    marginBottom: 16,
  };

  if (kind === "ok") {
    return (
      <div
        style={{
          ...base,
          background: "color-mix(in oklab, var(--success) 14%, transparent)",
          color: "var(--success)",
        }}
      >
        <svg
          width="20"
          height="20"
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
    );
  }

  if (kind === "warn") {
    return (
      <div
        style={{
          ...base,
          background: "color-mix(in oklab, var(--warn) 16%, transparent)",
          color: "var(--warn)",
        }}
      >
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <circle cx="12" cy="12" r="9" />
          <path d="M12 7v5l3 3" />
        </svg>
      </div>
    );
  }

  return (
    <div style={{ ...base, background: "var(--accent-soft)", color: "var(--accent-ink)" }}>
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
