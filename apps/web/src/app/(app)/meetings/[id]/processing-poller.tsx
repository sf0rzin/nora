"use client";

/**
 * Polling do detalhe da reunião enquanto a análise não termina.
 *
 * Espelha o ProcessingPoller do dashboard (Filters.tsx), mas no nível do
 * detalhe: enquanto o status for PENDING/PROCESSING, revalida o Server
 * Component via router.refresh() a cada 2,5s. Para após ~5min sem terminal
 * (COMPLETED/FAILED) e mostra um aviso discreto de timeout — sem reload
 * automático infinito.
 */
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";

import type { ProcessingStatus } from "@/lib/api/types";

const POLL_INTERVAL_MS = 2_500;
const MAX_POLL_MS = 5 * 60_000;

export default function MeetingProcessingPoller({ status }: { status: ProcessingStatus }) {
  const router = useRouter();
  const active = status === "PENDING" || status === "PROCESSING";
  const [timedOut, setTimedOut] = useState(false);
  // Início da espera persiste entre refreshes (o client component não remonta
  // quando o Server Component revalida) — é o que permite medir o teto de 5min.
  const startedAtRef = useRef<number | null>(null);

  useEffect(() => {
    if (!active) {
      startedAtRef.current = null;
      setTimedOut(false);
      return;
    }
    if (startedAtRef.current === null) startedAtRef.current = Date.now();

    const id = setInterval(() => {
      const startedAt = startedAtRef.current ?? Date.now();
      if (Date.now() - startedAt >= MAX_POLL_MS) {
        clearInterval(id);
        setTimedOut(true);
        return;
      }
      router.refresh();
    }, POLL_INTERVAL_MS);
    return () => clearInterval(id);
  }, [active, router]);

  if (!active || !timedOut) return null;

  return (
    <div className="notice" style={{ marginBottom: 20 }}>
      A análise está demorando mais que o esperado. Recarregue a página em instantes — se
      continuar assim, você pode reanalisar a reunião na seção Ações.
    </div>
  );
}
