"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";
import { signup, ApiRequestError } from "@/lib/api/client";

export default function SignupPage() {
  const router = useRouter();
  const [form, setForm] = useState({
    email: "",
    password: "",
    displayName: "",
    companyName: "",
  });
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState(false);
  const [loading, setLoading] = useState(false);

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setLoading(true);
    try {
      const r = await signup(form);
      if (r.verificationRequired) {
        setDone(true);
      } else {
        router.replace("/auth/login");
      }
    } catch (err) {
      const msg = err instanceof ApiRequestError ? err.message : "Falha ao criar conta";
      setError(msg);
    } finally {
      setLoading(false);
    }
  }

  if (done) {
    return (
      <div className="space-y-3 text-sm text-slate-700">
        <h2 className="text-lg font-medium text-slate-800">Verifique seu e-mail</h2>
        <p>Enviamos um link de verificacao para {form.email}.</p>
        <Link href="/auth/login" className="text-slate-900 underline">
          Voltar para o login
        </Link>
      </div>
    );
  }

  return (
    <form onSubmit={onSubmit} className="space-y-4">
      <h2 className="text-lg font-medium text-slate-800">Criar conta</h2>

      {(["displayName", "companyName", "email", "password"] as const).map((k) => (
        <div key={k} className="space-y-1.5">
          <label className="text-sm font-medium text-slate-700">
            {k === "displayName"
              ? "Seu nome"
              : k === "companyName"
                ? "Nome da empresa"
                : k === "email"
                  ? "E-mail"
                  : "Senha"}
          </label>
          <input
            type={k === "password" ? "password" : k === "email" ? "email" : "text"}
            required
            value={form[k]}
            onChange={(e) => setForm({ ...form, [k]: e.target.value })}
            className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
          />
        </div>
      ))}

      {error && <p className="text-sm text-red-600">{error}</p>}

      <button
        type="submit"
        disabled={loading}
        className="w-full rounded-md bg-slate-900 px-3 py-2 text-sm font-medium text-white transition hover:bg-slate-800 disabled:opacity-50"
      >
        {loading ? "Criando…" : "Criar conta"}
      </button>

      <p className="text-center text-xs text-slate-600">
        Ja tem conta?{" "}
        <Link href="/auth/login" className="underline">
          Entrar
        </Link>
      </p>
    </form>
  );
}
