"use client";

import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { ApiRequestError, deleteMeeting, reprocessMeeting } from "@/lib/api/client";

/**
 * Button to re-trigger a meeting's analysis (POST /meetings/{id}/reprocess).
 * Used both in the error block (FAILED) and in the detail's actions zone.
 * Mirrors what the Desktop already does.
 */
export function ReprocessButton({
  meetingId,
  label = "Reprocessar",
  variant = "solid",
}: {
  meetingId: string;
  label?: string;
  variant?: "solid" | "outline";
}) {
  const router = useRouter();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function onClick() {
    setBusy(true);
    setError(null);
    try {
      await reprocessMeeting(meetingId);
      router.refresh();
    } catch (err) {
      setError(err instanceof Error ? err.message : "Falha ao reprocessar.");
    } finally {
      setBusy(false);
    }
  }

  return (
    <div style={{ display: "inline-flex", flexDirection: "column", gap: 6 }}>
      <button
        type="button"
        className={`btn btn-sm ${variant === "solid" ? "btn-primary" : "btn-ghost"}`}
        onClick={onClick}
        disabled={busy}
      >
        {busy ? "Reprocessando…" : label}
      </button>
      {error && <span style={{ fontSize: 12, color: "var(--danger)" }}>{error}</span>}
    </div>
  );
}

/**
 * Destructive actions zone of the meeting detail: re-analyze + permanently
 * delete (LGPD, right to be forgotten — DELETE /privacy/meetings/{id}).
 * Deleting requires confirmation by typing the title (typed-confirm).
 */
export function MeetingDangerZone({
  meetingId,
  title,
  canReprocess,
}: {
  meetingId: string;
  title: string;
  canReprocess: boolean;
}) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const [confirmText, setConfirmText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canDelete = confirmText.trim() === title.trim() && !busy;

  async function onDelete() {
    if (!canDelete) return;
    setBusy(true);
    setError(null);
    try {
      await deleteMeeting(meetingId);
      router.push("/dashboard" as Route);
      router.refresh();
    } catch (err) {
      // 404 = no longer exists in the tenant: we treat it as idempotent success.
      if (err instanceof ApiRequestError && err.status === 404) {
        router.push("/dashboard" as Route);
        return;
      }
      setError(err instanceof Error ? err.message : "Falha ao apagar a reunião.");
      setBusy(false);
    }
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
        {canReprocess && <ReprocessButton meetingId={meetingId} label="Reanalisar reunião" variant="outline" />}
        {!open && (
          <button type="button" className="btn btn-ghost btn-sm" style={{ color: "var(--danger)" }} onClick={() => setOpen(true)}>
            Apagar permanentemente
          </button>
        )}
      </div>

      {open && (
        <div
          style={{
            padding: "16px 18px",
            borderRadius: 12,
            border: "1px solid var(--danger)",
            background: "var(--chip)",
            display: "flex",
            flexDirection: "column",
            gap: 12,
          }}
        >
          <div style={{ fontSize: 13.5, color: "var(--ink)", lineHeight: 1.5 }}>
            Isto apaga <strong>definitivamente</strong> a reunião e todo o conteúdo associado (transcrição, participantes,
            análise). A ação é irreversível (LGPD, direito ao esquecimento). Para confirmar, digite o título da reunião:
          </div>
          <code
            style={{
              display: "block",
              fontSize: 12.5,
              color: "var(--muted)",
              fontFamily: "var(--sans)",
              background: "var(--canvas)",
              border: "1px solid var(--border)",
              borderRadius: 6,
              padding: "6px 10px",
            }}
          >
            {title}
          </code>
          <input
            className="input"
            value={confirmText}
            onChange={(e) => setConfirmText(e.target.value)}
            placeholder="Digite o título exato"
          />
          {error && <span style={{ fontSize: 12, color: "var(--danger)" }}>{error}</span>}
          <div style={{ display: "flex", gap: 10 }}>
            <button type="button" className="btn btn-danger btn-sm" onClick={onDelete} disabled={!canDelete}>
              {busy ? "Apagando…" : "Apagar para sempre"}
            </button>
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => {
                setOpen(false);
                setConfirmText("");
                setError(null);
              }}
              disabled={busy}
            >
              Cancelar
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
