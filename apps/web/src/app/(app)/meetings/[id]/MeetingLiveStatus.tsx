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
import { Button } from "@/components/core/ui";

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
      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 8,
          borderRadius: "var(--radius-sm)",
          border: "1px solid var(--accent-soft)",
          background: "var(--accent-soft)",
          color: "var(--accent-ink)",
          padding: "10px 14px",
          fontSize: 13,
          marginBottom: 28,
        }}
      >
        <span
          aria-hidden="true"
          style={{
            width: 8,
            height: 8,
            borderRadius: "50%",
            background: "var(--accent)",
            animation: "noraOrbPulse 1.6s ease-in-out infinite",
          }}
        />
        Analisando reunião… esta página atualiza sozinha quando terminar.
      </div>
    );
  }

  return (
    <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 12, marginBottom: 28 }}>
      <Button size="sm" onClick={onReprocess} disabled={busy}>
        {busy ? "Reprocessando…" : "Reprocessar análise"}
      </Button>
      {err && <span style={{ fontSize: 13, color: "var(--danger)" }}>{err}</span>}
    </div>
  );
}
