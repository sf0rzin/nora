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
      <div className="space-y-3 text-sm text-slate-700">
        <h2 className="text-lg font-medium text-slate-800">Verifique seu e-mail</h2>
        <p>Se {email} estiver cadastrado, enviamos um link para redefinir a senha.</p>
        <Link href="/auth/login" className="text-slate-900 underline">
          Voltar para o login
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <h2 className="text-lg font-medium text-slate-800">Esqueci a senha</h2>
      <div className="space-y-1.5">
        <label className="text-sm font-medium text-slate-700">E-mail</label>
        <input
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
        />
      </div>
      {error && <p className="text-sm text-red-600">{error}</p>}
      <button
        type="submit"
        disabled={loading}
        className="w-full rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-slate-800 disabled:opacity-50"
      >
        {loading ? "Enviando…" : "Enviar link"}
      </button>
      <p className="text-center text-xs text-slate-600">
        <Link href="/auth/login" className="underline">
          Voltar
        </Link>
      </p>
    </form>
  );
}
