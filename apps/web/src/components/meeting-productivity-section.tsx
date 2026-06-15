"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import MeetingGoalForm from "@/components/meeting-goal-form";
import ProductivityScoreCard from "@/components/productivity-score-card";
import { ApiRequestError, deleteMeetingGoal } from "@/lib/api/client";
import type {
  MeetingGoal,
  ProductivityAssessment,
} from "@/lib/api/types";

export interface MeetingProductivitySectionProps {
  meetingId: string;
  goal: MeetingGoal | null;
  productivity: ProductivityAssessment | null;
}

export default function MeetingProductivitySection({
  meetingId,
  goal: initialGoal,
  productivity,
}: MeetingProductivitySectionProps) {
  const router = useRouter();
  const [goal, setGoal] = useState<MeetingGoal | null>(initialGoal);
  const [editing, setEditing] = useState<boolean>(false);

  function handleSaved(saved: MeetingGoal) {
    setGoal(saved);
    setEditing(false);
    // Refetch para puxar reprocessamento + productivity novo, se for o caso.
    router.refresh();
  }

  function handleCancel() {
    setEditing(false);
  }

  function onGoalRemoved() {
    setGoal(null);
    router.refresh();
  }

  // Caso A: sem goal e sem productivity → CTA pra avaliar produtividade
  if (!goal && !productivity) {
    return (
      <Frame>
        {editing ? (
          <MeetingGoalForm meetingId={meetingId} initialGoal={null} onSaved={handleSaved} onCancel={handleCancel} />
        ) : (
          <div className="card card--pad" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <p style={{ fontSize: 13.5, color: "var(--ink)", lineHeight: 1.6, margin: 0 }}>
              Quer entender o quão produtiva essa reunião foi? Declare o objetivo e os outcomes esperados e a Nora gera um
              indicador comparando o que aconteceu com o que era pra acontecer.
            </p>
            <p style={{ fontSize: 11.5, color: "var(--muted)", margin: 0 }}>
              Sem outcomes declarados, a Nora não tenta inventar um score.
            </p>
            <div>
              <button type="button" className="btn btn-primary btn-sm" onClick={() => setEditing(true)}>
                Avaliar produtividade desta reunião
              </button>
            </div>
          </div>
        )}
      </Frame>
    );
  }

  // Caso B: goal definido, mas sem productivity → aguardando reprocessamento
  if (goal && !productivity) {
    return (
      <Frame>
        {editing ? (
          <MeetingGoalForm meetingId={meetingId} initialGoal={goal} onSaved={handleSaved} onCancel={handleCancel} />
        ) : (
          <div className="card card--pad" style={{ display: "flex", flexDirection: "column", gap: 16 }}>
            <MeetingGoalSummary goal={goal} />
            <div className="notice" style={{ display: "flex", alignItems: "center", gap: 10 }}>
              <span className="status-dot status-dot--processing" />
              Aguardando análise…
            </div>
            <GoalControls onEdit={() => setEditing(true)} meetingId={meetingId} onRemoved={onGoalRemoved} />
          </div>
        )}
      </Frame>
    );
  }

  // Caso C: productivity disponível
  return (
    <Frame>
      {editing ? (
        <MeetingGoalForm meetingId={meetingId} initialGoal={goal} onSaved={handleSaved} onCancel={handleCancel} />
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          {productivity && <ProductivityScoreCard assessment={productivity} />}
          {goal && (
            <details className="card" style={{ padding: "14px 18px" }}>
              <summary style={{ cursor: "pointer", fontSize: 13, fontWeight: 500, color: "var(--ink)" }}>
                Objetivo declarado
              </summary>
              <div style={{ marginTop: 12 }}>
                <MeetingGoalSummary goal={goal} />
              </div>
            </details>
          )}
          <GoalControls onEdit={() => setEditing(true)} meetingId={meetingId} onRemoved={onGoalRemoved} />
        </div>
      )}
    </Frame>
  );
}

function Frame({ children }: { children: React.ReactNode }) {
  return (
    <section aria-labelledby="productivity-heading" style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <h2 id="productivity-heading" className="sec-label">
        Produtividade
      </h2>
      {children}
    </section>
  );
}

/**
 * Editar objetivo + remover objetivo com confirmação por digitação ("REMOVER"),
 * no padrão da danger zone — sem window.confirm. Erro de API é exibido inline.
 */
function GoalControls({
  onEdit,
  meetingId,
  onRemoved,
}: {
  onEdit: () => void;
  meetingId: string;
  onRemoved: () => void;
}) {
  const [confirming, setConfirming] = useState(false);
  const [text, setText] = useState("");
  const [removing, setRemoving] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const canRemove = text.trim().toUpperCase() === "REMOVER" && !removing;

  function cancel() {
    setConfirming(false);
    setText("");
    setError(null);
  }

  async function onRemove() {
    if (!canRemove) return;
    setRemoving(true);
    setError(null);
    try {
      await deleteMeetingGoal(meetingId);
      onRemoved();
    } catch (err) {
      setError(
        err instanceof ApiRequestError ? err.message : "Falha ao remover o objetivo da reunião.",
      );
      setRemoving(false);
    }
  }

  return (
    <div>
      <div style={{ display: "flex", gap: 8, alignItems: "center", flexWrap: "wrap" }}>
        <button type="button" className="btn btn-ghost btn-sm" onClick={onEdit}>
          Editar objetivo
        </button>
        {!confirming && (
          <button type="button" className="btn btn-ghost btn-sm" style={{ color: "var(--danger)" }} onClick={() => setConfirming(true)}>
            Remover objetivo
          </button>
        )}
      </div>

      {confirming && (
        <div
          style={{
            marginTop: 12,
            padding: "14px 16px",
            border: "1px solid var(--danger)",
            borderRadius: 12,
            background: "var(--chip)",
          }}
        >
          <div style={{ fontSize: 13, lineHeight: 1.5, marginBottom: 10, color: "var(--ink)" }}>
            Remover o objetivo descarta o Productivity Score vinculado. Pra confirmar, digite <strong>REMOVER</strong>:
          </div>
          {error && <div style={{ fontSize: 12, color: "var(--danger)", marginBottom: 10 }}>{error}</div>}
          <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
            <input
              className="input"
              style={{ width: 140 }}
              placeholder="REMOVER"
              value={text}
              onChange={(e) => setText(e.target.value)}
            />
            <button type="button" className="btn btn-danger btn-sm" disabled={!canRemove} onClick={onRemove}>
              {removing ? "Removendo…" : "Remover"}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={cancel} disabled={removing}>
              Cancelar
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

function MeetingGoalSummary({ goal }: { goal: MeetingGoal }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
      <div>
        <div className="sec-label" style={{ marginBottom: 4 }}>
          Propósito
        </div>
        <p style={{ fontSize: 14, color: "var(--ink)", margin: 0, lineHeight: 1.5 }}>{goal.purpose}</p>
      </div>
      <div>
        <div className="sec-label" style={{ marginBottom: 4 }}>
          Outcomes esperados ({goal.expectedOutcomes.length})
        </div>
        <ul style={{ margin: 0, paddingLeft: 18, fontSize: 14, color: "var(--ink)", lineHeight: 1.6 }}>
          {goal.expectedOutcomes.map((outcome, idx) => (
            <li key={`${outcome}-${idx}`}>{outcome}</li>
          ))}
        </ul>
      </div>
      {goal.projectStateSnapshot && (
        <div>
          <div className="sec-label" style={{ marginBottom: 4 }}>
            Snapshot do projeto
          </div>
          <p style={{ fontSize: 13.5, color: "var(--muted)", margin: 0, lineHeight: 1.55, whiteSpace: "pre-line" }}>
            {goal.projectStateSnapshot}
          </p>
        </div>
      )}
    </div>
  );
}
