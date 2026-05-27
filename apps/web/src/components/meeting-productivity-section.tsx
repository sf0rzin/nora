"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import MeetingGoalForm from "@/components/meeting-goal-form";
import ProductivityScoreCard from "@/components/productivity-score-card";
import { Button, Card, Section } from "@/components/core/ui";
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

  // Caso A: sem goal e sem productivity → CTA pra avaliar produtividade
  if (!goal && !productivity) {
    return (
      <Section title="Produtividade">
        {editing ? (
          <MeetingGoalForm
            meetingId={meetingId}
            initialGoal={null}
            onSaved={handleSaved}
            onCancel={handleCancel}
          />
        ) : (
          <Card>
            <p style={{ fontSize: 13.5, lineHeight: 1.6, color: "var(--ink)" }}>
              Quer entender o quão produtiva essa reunião foi? Declare o objetivo
              e os outcomes esperados e a NORA gera um indicador comparando o que
              aconteceu com o que era pra acontecer.
            </p>
            <p style={{ fontSize: 12, color: "var(--muted)", marginTop: 8 }}>
              Sem outcomes declarados, a NORA não tenta inventar um score.
            </p>
            <div style={{ marginTop: 16 }}>
              <Button variant="primary" onClick={() => setEditing(true)}>
                Avaliar produtividade desta reunião
              </Button>
            </div>
          </Card>
        )}
      </Section>
    );
  }

  // Caso B: goal definido, mas sem productivity → aguardando reprocessamento
  if (goal && !productivity) {
    return (
      <Section title="Produtividade">
        {editing ? (
          <MeetingGoalForm
            meetingId={meetingId}
            initialGoal={goal}
            onSaved={handleSaved}
            onCancel={handleCancel}
          />
        ) : (
          <Card>
            <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
              <MeetingGoalSummary goal={goal} />
              <p
                style={{
                  border: "1px solid var(--border)",
                  background: "var(--chip)",
                  borderRadius: "var(--radius-sm)",
                  padding: "8px 12px",
                  fontSize: 13.5,
                  color: "var(--ink)",
                }}
              >
                Aguardando análise…
              </p>
              <div>
                <Button size="sm" onClick={() => setEditing(true)}>
                  Editar objetivo
                </Button>
              </div>
            </div>
          </Card>
        )}
      </Section>
    );
  }

  // Caso C: productivity disponível
  return (
    <Section title="Produtividade">
      {editing ? (
        <MeetingGoalForm
          meetingId={meetingId}
          initialGoal={goal}
          onSaved={handleSaved}
          onCancel={handleCancel}
        />
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 16 }}>
          {productivity && <ProductivityScoreCard assessment={productivity} />}
          {goal && (
            <details
              className="nora-card"
              style={{ padding: 16 }}
            >
              <summary
                style={{
                  cursor: "pointer",
                  fontSize: 13.5,
                  fontWeight: 500,
                  color: "var(--ink)",
                }}
              >
                Objetivo declarado
              </summary>
              <div style={{ marginTop: 12 }}>
                <MeetingGoalSummary goal={goal} />
              </div>
            </details>
          )}
          <div>
            <Button size="sm" onClick={() => setEditing(true)}>
              Editar objetivo
            </Button>
          </div>
        </div>
      )}
    </Section>
  );
}

function MeetingGoalSummary({ goal }: { goal: MeetingGoal }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 12 }}>
      <div>
        <p className="nora-section-title" style={{ marginBottom: 0 }}>
          Propósito
        </p>
        <p style={{ marginTop: 4, fontSize: 13.5, color: "var(--ink)" }}>
          {goal.purpose}
        </p>
      </div>
      <div>
        <p className="nora-section-title" style={{ marginBottom: 0 }}>
          Outcomes esperados ({goal.expectedOutcomes.length})
        </p>
        <ul
          style={{
            marginTop: 4,
            paddingLeft: 20,
            listStyle: "disc",
            display: "flex",
            flexDirection: "column",
            gap: 4,
            fontSize: 13.5,
            color: "var(--ink)",
          }}
        >
          {goal.expectedOutcomes.map((outcome, idx) => (
            <li key={`${outcome}-${idx}`}>{outcome}</li>
          ))}
        </ul>
      </div>
      {goal.projectStateSnapshot && (
        <div>
          <p className="nora-section-title" style={{ marginBottom: 0 }}>
            Snapshot do projeto
          </p>
          <p
            style={{
              marginTop: 4,
              whiteSpace: "pre-line",
              fontSize: 13.5,
              color: "var(--muted)",
            }}
          >
            {goal.projectStateSnapshot}
          </p>
        </div>
      )}
    </div>
  );
}
