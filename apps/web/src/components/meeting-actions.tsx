"use client";

import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { ApiRequestError, deleteMeeting, reprocessMeeting } from "@/lib/api/client";

/**
 * Botao para re-disparar a analise de uma reuniao (POST /meetings/{id}/reprocess).
 * Usado tanto no bloco de erro (FAILED) quanto na zona de acoes do detalhe.
 * Espelha o que o Desktop ja faz.
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

  const base: React.CSSProperties = {
    fontSize: 13,
    fontWeight: 500,
    padding: "8px 16px",
    borderRadius: 8,
    cursor: busy ? "default" : "pointer",
    opacity: busy ? 0.6 : 1,
    transition: "opacity 0.15s",
  };
  const skin: React.CSSProperties =
    variant === "solid"
      ? { background: "var(--accent-ink)", color: "var(--bg)", border: "1px solid var(--accent-ink)" }
      : { background: "transparent", color: "var(--ink)", border: "1px solid var(--border)" };

  return (
    <div style={{ display: "inline-flex", flexDirection: "column", gap: 6 }}>
      <button type="button" onClick={onClick} disabled={busy} style={{ ...base, ...skin }}>
        {busy ? "Reprocessando…" : label}
      </button>
      {error && <span style={{ fontSize: 12, color: "var(--danger)" }}>{error}</span>}
    </div>
  );
}

/**
 * Zona de acoes destrutivas do detalhe da reuniao: reanalisar + apagar
 * permanentemente (LGPD, direito ao esquecimento — DELETE /privacy/meetings/{id}).
 * O apagar exige confirmacao por digitacao do titulo (typed-confirm).
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
      // 404 = ja nao existe no tenant: tratamos como sucesso idempotente.
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
          <button
            type="button"
            onClick={() => setOpen(true)}
            style={{
              fontSize: 13,
              fontWeight: 500,
              padding: "8px 16px",
              borderRadius: 8,
              cursor: "pointer",
              background: "transparent",
              color: "var(--danger)",
              border: "1px solid var(--border)",
            }}
          >
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
            Isto apaga <strong>definitivamente</strong> a reunião e todo o conteúdo
            associado (transcrição, participantes, análise). A ação é irreversível
            (LGPD, direito ao esquecimento). Para confirmar, digite o título da reunião:
          </div>
          <code style={{ fontSize: 12.5, color: "var(--muted)", fontFamily: "var(--mono)" }}>{title}</code>
          <input
            value={confirmText}
            onChange={(e) => setConfirmText(e.target.value)}
            placeholder="Digite o título exato"
            style={{
              fontSize: 14,
              padding: "8px 12px",
              borderRadius: 8,
              border: "1px solid var(--border)",
              background: "var(--bg)",
              color: "var(--ink)",
            }}
          />
          {error && <span style={{ fontSize: 12, color: "var(--danger)" }}>{error}</span>}
          <div style={{ display: "flex", gap: 10 }}>
            <button
              type="button"
              onClick={onDelete}
              disabled={!canDelete}
              style={{
                fontSize: 13,
                fontWeight: 500,
                padding: "8px 16px",
                borderRadius: 8,
                cursor: canDelete ? "pointer" : "not-allowed",
                background: canDelete ? "var(--danger)" : "var(--chip)",
                color: canDelete ? "#fff" : "var(--muted)",
                border: "1px solid var(--border)",
              }}
            >
              {busy ? "Apagando…" : "Apagar para sempre"}
            </button>
            <button
              type="button"
              onClick={() => {
                setOpen(false);
                setConfirmText("");
                setError(null);
              }}
              disabled={busy}
              style={{
                fontSize: 13,
                padding: "8px 16px",
                borderRadius: 8,
                cursor: "pointer",
                background: "transparent",
                color: "var(--muted)",
                border: "1px solid var(--border)",
              }}
            >
              Cancelar
            </button>
          </div>
        </div>
      )}
    </div>
  );
}
