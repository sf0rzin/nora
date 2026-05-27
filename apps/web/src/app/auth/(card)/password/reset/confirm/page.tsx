"use client";

import Link from "next/link";
import { useSearchParams, useRouter } from "next/navigation";
import { Suspense, useState } from "react";
import { confirmPasswordReset, ApiRequestError } from "@/lib/api/client";
import { validatePassword, PASSWORD_MIN } from "@/lib/password-policy";

export default function PasswordResetConfirmPage() {
  return (
    <Suspense fallback={null}>
      <ConfirmForm />
    </Suspense>
  );
}

function ConfirmForm() {
  const params = useSearchParams();
  const router = useRouter();
  const token = params.get("token") ?? "";
  const [password, setPassword] = useState("");
  const [confirmPassword, setConfirmPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (!token) {
      setError("Token ausente.");
      return;
    }
    const pwError = validatePassword(password);
    if (pwError) {
      setError(pwError);
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
    <form onSubmit={onSubmit} className="space-y-4">
      <h2 className="text-lg font-medium text-slate-800">Definir nova senha</h2>
      <p className="text-sm text-slate-600">
        Escolha uma nova senha. Mínimo {PASSWORD_MIN} caracteres, com letras e números.
      </p>
      <div className="space-y-1.5">
        <label htmlFor="new-password" className="text-sm font-medium text-slate-700">
          Nova senha
        </label>
        <input
          id="new-password"
          type="password"
          required
          minLength={PASSWORD_MIN}
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          autoComplete="new-password"
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
        />
      </div>
      <div className="space-y-1.5">
        <label htmlFor="confirm-password" className="text-sm font-medium text-slate-700">
          Confirmar nova senha
        </label>
        <input
          id="confirm-password"
          type="password"
          required
          minLength={PASSWORD_MIN}
          value={confirmPassword}
          onChange={(e) => setConfirmPassword(e.target.value)}
          autoComplete="new-password"
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
        />
      </div>
      {error && <p className="text-sm text-red-600">{error}</p>}
      <button
        type="submit"
        disabled={loading}
        className="w-full rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-slate-800 disabled:opacity-50"
      >
        {loading ? "Salvando…" : "Salvar nova senha"}
      </button>
      <p className="text-center text-xs text-slate-600">
        <Link href="/auth/login" className="underline">
          Voltar para o login
        </Link>
      </p>
    </form>
  );
}
