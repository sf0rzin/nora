"use client";

import { useRouter } from "next/navigation";
import { useState } from "react";
import MeetingGoalForm from "@/components/meeting-goal-form";
import ProductivityScoreCard from "@/components/productivity-score-card";
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
      <section className="space-y-3" aria-labelledby="productivity-heading">
        <h2
          id="productivity-heading"
          className="text-sm font-semibold uppercase tracking-wide text-slate-500"
        >
          Produtividade
        </h2>
        {editing ? (
          <MeetingGoalForm
            meetingId={meetingId}
            initialGoal={null}
            onSaved={handleSaved}
            onCancel={handleCancel}
          />
        ) : (
          <div className="space-y-3 rounded-lg border border-slate-200 bg-white p-6">
            <p className="text-sm text-slate-700">
              Quer entender o quão produtiva essa reunião foi? Declare o objetivo
              e os outcomes esperados e a NORA gera um indicador comparando o que
              aconteceu com o que era pra acontecer.
            </p>
            <p className="text-xs text-slate-500">
              Sem outcomes declarados, a NORA não tenta inventar um score.
            </p>
            <button
              type="button"
              onClick={() => setEditing(true)}
              className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800"
            >
              Avaliar produtividade desta reunião
            </button>
          </div>
        )}
      </section>
    );
  }

  // Caso B: goal definido, mas sem productivity → aguardando reprocessamento
  if (goal && !productivity) {
    return (
      <section className="space-y-3" aria-labelledby="productivity-heading">
        <h2
          id="productivity-heading"
          className="text-sm font-semibold uppercase tracking-wide text-slate-500"
        >
          Produtividade
        </h2>

        {editing ? (
          <MeetingGoalForm
            meetingId={meetingId}
            initialGoal={goal}
            onSaved={handleSaved}
            onCancel={handleCancel}
          />
        ) : (
          <div className="space-y-4 rounded-lg border border-slate-200 bg-white p-6">
            <MeetingGoalSummary goal={goal} />
            <p className="rounded-md border border-slate-200 bg-slate-50 px-3 py-2 text-sm text-slate-700">
              Aguardando análise…
            </p>
            <button
              type="button"
              onClick={() => setEditing(true)}
              className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50"
            >
              Editar objetivo
            </button>
          </div>
        )}
      </section>
    );
  }

  // Caso C: productivity disponível
  return (
    <section className="space-y-3" aria-labelledby="productivity-heading">
      <h2
        id="productivity-heading"
        className="text-sm font-semibold uppercase tracking-wide text-slate-500"
      >
        Produtividade
      </h2>

      {editing ? (
        <MeetingGoalForm
          meetingId={meetingId}
          initialGoal={goal}
          onSaved={handleSaved}
          onCancel={handleCancel}
        />
      ) : (
        <div className="space-y-4">
          {productivity && <ProductivityScoreCard assessment={productivity} />}
          {goal && (
            <details className="rounded-lg border border-slate-200 bg-white p-4">
              <summary className="cursor-pointer text-sm font-medium text-slate-700">
                Objetivo declarado
              </summary>
              <div className="mt-3">
                <MeetingGoalSummary goal={goal} />
              </div>
            </details>
          )}
          <div>
            <button
              type="button"
              onClick={() => setEditing(true)}
              className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-xs text-slate-700 hover:bg-slate-50"
            >
              Editar objetivo
            </button>
          </div>
        </div>
      )}
    </section>
  );
}

function MeetingGoalSummary({ goal }: { goal: MeetingGoal }) {
  return (
    <div className="space-y-3 text-sm">
      <div>
        <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
          Propósito
        </p>
        <p className="mt-1 text-slate-800">{goal.purpose}</p>
      </div>
      <div>
        <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
          Outcomes esperados ({goal.expectedOutcomes.length})
        </p>
        <ul className="mt-1 list-disc space-y-1 pl-5 text-slate-800">
          {goal.expectedOutcomes.map((outcome, idx) => (
            <li key={`${outcome}-${idx}`}>{outcome}</li>
          ))}
        </ul>
      </div>
      {goal.projectStateSnapshot && (
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
            Snapshot do projeto
          </p>
          <p className="mt-1 whitespace-pre-line text-slate-700">
            {goal.projectStateSnapshot}
          </p>
        </div>
      )}
    </div>
  );
}
