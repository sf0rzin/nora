"use client";

/**
 * Estado vivo da reunião no detalhe: enquanto PENDING/PROCESSING, faz polling e
 * dá router.refresh() quando o backend termina (a página é um Server Component,
 * então um refresh re-busca a análise). Quando já terminou, oferece reprocessar.
 *
 * Isso fecha a lacuna de abrir um link direto de uma reunião ainda em análise
 * (antes ficava num estado estático "ainda não analisada" até refresh manual).
 */

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import { ApiRequestError, getMeeting, reprocessMeeting } from "@/lib/api/client";
import type { ProcessingStatus } from "@/lib/api/types";

const POLL_MS = 2500;

export default function MeetingLiveStatus({
  meetingId,
  status,
}: {
  meetingId: string;
  status: ProcessingStatus;
}) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [err, setErr] = useState<string | null>(null);
  const inFlight = useRef(false);

  const active = status === "PENDING" || status === "PROCESSING";

  useEffect(() => {
    if (!active) return;
    let cancelled = false;
    const id = setInterval(async () => {
      if (cancelled || inFlight.current) return;
      inFlight.current = true;
      try {
        const m = await getMeeting(meetingId);
        if (!cancelled && (m.processingStatus === "COMPLETED" || m.processingStatus === "FAILED")) {
          router.refresh();
        }
      } catch {
        /* erro transiente: tenta de novo no próximo tick */
      } finally {
        inFlight.current = false;
      }
    }, POLL_MS);
    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [active, meetingId, router]);

  async function onReprocess() {
    if (busy) return;
    setBusy(true);
    setErr(null);
    try {
      await reprocessMeeting(meetingId);
      router.refresh();
    } catch (e) {
      setErr(e instanceof ApiRequestError ? e.message : "Falha ao reprocessar.");
      setBusy(false);
    }
  }

  if (active) {
    return (
      <div className="flex items-center gap-2 rounded-md border border-blue-200 bg-blue-50 px-3 py-2 text-sm text-blue-800">
        <span className="h-2 w-2 animate-pulse rounded-full bg-blue-500" aria-hidden="true" />
        Analisando reunião… esta página atualiza sozinha quando terminar.
      </div>
    );
  }

  return (
    <div className="flex flex-wrap items-center gap-3">
      <button
        type="button"
        onClick={onReprocess}
        disabled={busy}
        className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 transition hover:bg-slate-50 disabled:opacity-50"
      >
        {busy ? "Reprocessando…" : "Reprocessar análise"}
      </button>
      {err && <span className="text-sm text-red-600">{err}</span>}
    </div>
  );
}
