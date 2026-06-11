"use client";

import Link from "next/link";
import { useSearchParams, useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { confirmPasswordReset, ApiRequestError } from "@/lib/api/client";

export default function PasswordResetConfirmPage() {
  return (
    <Suspense fallback={null}>
      <ConfirmForm />
    </Suspense>
  );
}

/** Pontuação de força de senha (0–4), igual ao protótipo v3. */
function strength(p: string): number {
  let s = 0;
  if (p.length >= 8) s++;
  if (p.length >= 12) s++;
  if (/[A-Z]/.test(p) && /[a-z]/.test(p)) s++;
  if (/\d/.test(p) && /[^A-Za-z0-9]/.test(p)) s++;
  return s;
}

// Índice 0 = vazio; 1–4 = cores por nível.
const STRENGTH_COLORS = [
  "var(--chip)",
  "var(--danger)",
  "var(--warn)",
  "var(--accent)",
  "var(--success)",
];

function ConfirmForm() {
  const params = useSearchParams();
  const router = useRouter();
  const token = params.get("token") ?? "";
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const score = strength(password);
  const mismatch = confirmPassword.length > 0 && confirmPassword !== password;
  const canSubmit = password.length >= 8 && password === confirmPassword;

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!token) {
      setError("Token ausente.");
      return;
    }
    if (password !== confirmPassword) {
      setError("As senhas não coincidem.");
      return;
    }
    setLoading(true);
    try {
      await confirmPasswordReset(token, password);
      router.replace("/auth/login?reset=ok");
    } catch (err) {
      setError(err instanceof ApiRequestError ? err.message : "Token invalido ou expirado.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <div>
      <h2 style={cardTitle}>Escolha a nova senha.</h2>
      <p style={cardSub}>Mínimo 8 caracteres. Recomendamos número + símbolo.</p>

      <form
        onSubmit={onSubmit}
        style={{ marginTop: 18, display: "flex", flexDirection: "column", gap: 14 }}
      >
        <div className="field">
          <label className="field-label" htmlFor="rc-pw">
            Nova senha
          </label>
          <input
            className="input"
            id="rc-pw"
            type="password"
            required
            minLength={8}
            placeholder="••••••••"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <div style={{ display: "flex", gap: 4, marginTop: 8 }}>
            {[0, 1, 2, 3].map((i) => (
              <span
                key={i}
                style={{
                  height: 3,
                  flex: 1,
                  borderRadius: 2,
                  background: i < score ? STRENGTH_COLORS[score] : "var(--chip)",
                  transition: "background 160ms ease",
                }}
              />
            ))}
          </div>
        </div>

        <div className="field">
          <label className="field-label" htmlFor="rc-conf">
            Confirme a senha
          </label>
          <input
            className="input"
            id="rc-conf"
            type="password"
            required
            minLength={8}
            placeholder="••••••••"
            value={confirmPassword}
            onChange={(e) => setConfirmPassword(e.target.value)}
          />
          {mismatch && <div className="field-help is-err">As senhas não coincidem.</div>}
        </div>

        {error && !mismatch && <p className="field-help is-err">{error}</p>}

        <button type="submit" className="btn btn-primary" disabled={!canSubmit || loading}>
          {loading ? "Salvando…" : "Redefinir senha"}
        </button>
      </form>

      <p style={footLink}>
        <Link href="/auth/login" style={{ textDecoration: "underline", textUnderlineOffset: 2 }}>
          Voltar para o login
        </Link>
      </p>
    </div>
  );
}

// ── Estilos compartilhados (cards de auth) ──────────────────────────
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
