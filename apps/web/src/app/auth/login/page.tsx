"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import type { Route } from "next";
import { Suspense, useState } from "react";
import { login, ApiRequestError } from "@/lib/api/client";
import { setSession } from "@/lib/auth";

export default function LoginPage() {
  return (
    <Suspense fallback={null}>
      <LoginForm />
    </Suspense>
  );
}

function LoginForm() {
  const router = useRouter();
  const params = useSearchParams();
  const next = (params.get("next") ?? "/dashboard") as Route;
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const r = await login(email, password);
      setSession(
        r.accessToken,
        { userId: r.userId, tenantId: r.tenantId, email: r.email, displayName: r.displayName },
        r.expiresInSeconds,
      );
      router.replace(next);
      router.refresh();
    } catch (err) {
      const msg = err instanceof ApiRequestError ? err.message : "Falha ao entrar";
      setError(msg);
    } finally {
      setLoading(false);
    }
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <h2 className="text-lg font-medium text-slate-800">Entrar</h2>

      <div className="space-y-1.5">
        <label className="text-sm font-medium text-slate-700">E-mail</label>
        <input
          type="email"
          required
          value={email}
          onChange={(e) => setEmail(e.target.value)}
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
          placeholder="voce@empresa.com"
        />
      </div>

      <div className="space-y-1.5">
        <label className="text-sm font-medium text-slate-700">Senha</label>
        <input
          type="password"
          required
          value={password}
          onChange={(e) => setPassword(e.target.value)}
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
          placeholder="••••••••"
        />
      </div>

      {error && <p className="text-sm text-red-600">{error}</p>}

      <button
        type="submit"
        disabled={loading}
        className="w-full rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-slate-800 disabled:opacity-50"
      >
        {loading ? "Entrando…" : "Entrar"}
      </button>

      <div className="flex items-center justify-between text-xs text-slate-600">
        <Link href="/auth/signup" className="underline">
          Criar conta
        </Link>
        <Link href="/auth/password/reset/request" className="underline">
          Esqueci a senha
        </Link>
      </div>
    </form>
  );
}
