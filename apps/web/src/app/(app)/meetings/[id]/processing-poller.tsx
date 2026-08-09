"use client";

/**
 * Polling of the meeting detail while the analysis has not finished.
 *
 * Mirrors the dashboard's ProcessingPoller (Filters.tsx), but at the detail
 * level: while the status is PENDING/PROCESSING, it revalidates the Server
 * Component via router.refresh() every 2.5s. Stops after ~5min without a
 * terminal state (COMPLETED/FAILED) and shows a discreet timeout notice — no
 * infinite automatic reload.
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
  // Wait start persists across refreshes (the client component does not remount
  // when the Server Component revalidates) — that is what measures the 5min cap.
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
