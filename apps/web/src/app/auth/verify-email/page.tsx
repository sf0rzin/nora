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

  return (
    <div className="space-y-3 text-sm text-slate-700">
      <h2 className="text-lg font-medium text-slate-800">Verificar e-mail</h2>
      {state === "loading" && <p>Verificando…</p>}
      {state === "ok" && (
        <p>
          E-mail verificado com sucesso.{" "}
          <Link href="/auth/login" className="text-slate-900 underline">
            Entrar
          </Link>
        </p>
      )}
      {state === "error" && (
        <>
          <p className="text-red-600">{error}</p>
          <Link href="/auth/login" className="text-slate-900 underline">
            Voltar para o login
          </Link>
        </>
      )}
    </div>
  );
}
