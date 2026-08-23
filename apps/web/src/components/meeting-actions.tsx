"use client";

import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { ApiRequestError, eraseMeeting, removeMeeting, reprocessMeeting } from "@/lib/api/client";

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
 * Destructive actions zone of the meeting detail. Two removals, and the whole point is that they
 * are not the same button.
 *
 * "Remover" is `DELETE /meetings/{id}` (IAM `meeting:delete`): the soft delete of ADR 0021. The
 * meeting stops being listed and everything about it survives, so it is the one a user reaches
 * for after uploading the wrong file, and one confirmation is enough because nothing is destroyed.
 * There is no restore endpoint yet, and the copy on screen is careful not to imply one.
 *
 * "Apagar permanentemente" is `DELETE /privacy/meetings/{id}` (IAM `meeting:erase`): the LGPD
 * erasure, which physically destroys the transcript, the participants and the analyses of
 * everybody who was in the meeting. It keeps the typed-confirm, because nothing brings it back.
 *
 * Until the first of the two had an endpoint, this screen offered only the second — so the only
 * way to get rid of a mistaken upload was to erase other people's data. That is the finding.
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
  const [confirmRemove, setConfirmRemove] = useState(false);
  const [confirmText, setConfirmText] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [removeError, setRemoveError] = useState<string | null>(null);

  const canDelete = confirmText.trim() === title.trim() && !busy;

  async function onRemove() {
    setBusy(true);
    setRemoveError(null);
    try {
      await removeMeeting(meetingId);
      router.push("/dashboard" as Route);
      router.refresh();
    } catch (err) {
      // 404 = already gone from the listing: idempotent, same as the erase below.
      if (err instanceof ApiRequestError && err.status === 404) {
        router.push("/dashboard" as Route);
        return;
      }
      setRemoveError(err instanceof Error ? err.message : "Falha ao remover a reunião.");
      setBusy(false);
    }
  }

  async function onDelete() {
    if (!canDelete) return;
    setBusy(true);
    setError(null);
    try {
      await eraseMeeting(meetingId);
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
        {!confirmRemove ? (
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            data-testid="meeting-remove"
            onClick={() => setConfirmRemove(true)}
            disabled={busy}
          >
            Remover
          </button>
        ) : (
          <span style={{ display: "inline-flex", alignItems: "center", gap: 8, fontSize: 13 }}>
            <span style={{ color: "var(--muted)" }}>Tirar da lista de reuniões?</span>
            <button
              type="button"
              className="btn btn-primary btn-sm"
              data-testid="meeting-remove-confirm"
              onClick={() => void onRemove()}
              disabled={busy}
            >
              {busy ? "Removendo…" : "Remover"}
            </button>
            <button
              type="button"
              className="btn btn-ghost btn-sm"
              onClick={() => {
                setConfirmRemove(false);
                setRemoveError(null);
              }}
              disabled={busy}
            >
              Cancelar
            </button>
          </span>
        )}
        {!open && (
          <button
            type="button"
            className="btn btn-ghost btn-sm"
            style={{ color: "var(--danger)" }}
            data-testid="meeting-erase"
            onClick={() => setOpen(true)}
          >
            Apagar permanentemente
          </button>
        )}
      </div>

      {/* The copy stops exactly where the product does. The row survives — that is what the soft
          delete is — but there is no restore endpoint yet, so promising the user they can undo it
          themselves would be the same kind of claim this screen was audited for. */}
      {confirmRemove && (
        <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0, lineHeight: 1.5 }}>
          A reunião sai da lista e para de contar nos números, mas nada é destruído — transcrição,
          participantes e análise continuam guardados. Diferente de “apagar permanentemente”, isto
          não elimina dado nenhum.
        </p>
      )}
      {removeError && <span style={{ fontSize: 12, color: "var(--danger)" }}>{removeError}</span>}

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
            <button
              type="button"
              className="btn btn-danger btn-sm"
              data-testid="meeting-erase-confirm"
              onClick={onDelete}
              disabled={!canDelete}
            >
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
