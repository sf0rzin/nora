"use client";

import { useState } from "react";
import {
  ApiRequestError,
  setMeetingGoal,
} from "@/lib/api/client";
import type { MeetingGoal } from "@/lib/api/types";

const PURPOSE_MIN = 3;
const PURPOSE_MAX = 500;
const OUTCOME_MIN = 3;
const OUTCOME_MAX = 240;
const OUTCOMES_MIN = 1;
const OUTCOMES_MAX = 10;
const SNAPSHOT_MAX = 2000;

interface FormState {
  purpose: string;
  expectedOutcomes: string[];
  projectStateSnapshot: string;
}

function toFormState(goal: MeetingGoal | null | undefined): FormState {
  if (!goal) {
    return { purpose: "", expectedOutcomes: [""], projectStateSnapshot: "" };
  }
  return {
    purpose: goal.purpose,
    expectedOutcomes:
      goal.expectedOutcomes.length > 0 ? [...goal.expectedOutcomes] : [""],
    projectStateSnapshot: goal.projectStateSnapshot ?? "",
  };
}

export interface MeetingGoalFormProps {
  meetingId: string;
  initialGoal?: MeetingGoal | null;
  onSaved: (goal: MeetingGoal) => void;
  onCancel: () => void;
}

export default function MeetingGoalForm({
  meetingId,
  initialGoal,
  onSaved,
  onCancel,
}: MeetingGoalFormProps) {
  const [form, setForm] = useState<FormState>(() => toFormState(initialGoal));
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);

  function updatePurpose(value: string) {
    setForm((s) => ({ ...s, purpose: value }));
  }

  function updateOutcome(idx: number, value: string) {
    setForm((s) => ({
      ...s,
      expectedOutcomes: s.expectedOutcomes.map((o, i) => (i === idx ? value : o)),
    }));
  }

  function addOutcome() {
    setForm((s) => {
      if (s.expectedOutcomes.length >= OUTCOMES_MAX) return s;
      return { ...s, expectedOutcomes: [...s.expectedOutcomes, ""] };
    });
  }

  function removeOutcome(idx: number) {
    setForm((s) => {
      if (s.expectedOutcomes.length <= 1) {
        return { ...s, expectedOutcomes: [""] };
      }
      return {
        ...s,
        expectedOutcomes: s.expectedOutcomes.filter((_, i) => i !== idx),
      };
    });
  }

  function updateSnapshot(value: string) {
    setForm((s) => ({ ...s, projectStateSnapshot: value }));
  }

  function validate(): { goal: MeetingGoal } | { errorMessage: string } {
    const purpose = form.purpose.trim();
    if (purpose.length < PURPOSE_MIN || purpose.length > PURPOSE_MAX) {
      return {
        errorMessage: `O propósito precisa ter entre ${PURPOSE_MIN} e ${PURPOSE_MAX} caracteres.`,
      };
    }
    const outcomes = form.expectedOutcomes
      .map((o) => o.trim())
      .filter((o) => o.length > 0);
    if (outcomes.length < OUTCOMES_MIN || outcomes.length > OUTCOMES_MAX) {
      return {
        errorMessage: `Informe entre ${OUTCOMES_MIN} e ${OUTCOMES_MAX} outcomes esperados.`,
      };
    }
    for (const outcome of outcomes) {
      if (outcome.length < OUTCOME_MIN || outcome.length > OUTCOME_MAX) {
        return {
          errorMessage: `Cada outcome precisa ter entre ${OUTCOME_MIN} e ${OUTCOME_MAX} caracteres.`,
        };
      }
    }
    const snapshot = form.projectStateSnapshot.trim();
    if (snapshot.length > SNAPSHOT_MAX) {
      return {
        errorMessage: `O snapshot do projeto não pode passar de ${SNAPSHOT_MAX} caracteres.`,
      };
    }
    return {
      goal: {
        purpose,
        expectedOutcomes: outcomes,
        projectStateSnapshot: snapshot.length > 0 ? snapshot : null,
      },
    };
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    const result = validate();
    if ("errorMessage" in result) {
      setError(result.errorMessage);
      return;
    }
    setSaving(true);
    try {
      const saved = await setMeetingGoal(meetingId, result.goal);
      onSaved(saved);
    } catch (err) {
      setError(
        err instanceof ApiRequestError
          ? err.message
          : "Falha ao salvar o objetivo da reunião.",
      );
    } finally {
      setSaving(false);
    }
  }

  return (
    <form
      onSubmit={onSubmit}
      className="space-y-5 rounded-lg border border-slate-200 bg-white p-6"
      aria-label="Formulário do objetivo da reunião"
    >
      <div className="space-y-1.5">
        <label
          htmlFor="meeting-goal-purpose"
          className="text-sm font-medium text-slate-700"
        >
          Propósito da reunião <span className="text-red-600">*</span>
        </label>
        <textarea
          id="meeting-goal-purpose"
          required
          rows={3}
          value={form.purpose}
          maxLength={PURPOSE_MAX}
          onChange={(e) => updatePurpose(e.target.value)}
          placeholder="Refinement do épico X, discovery com lead novo, etc."
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
        />
        <p className="text-xs text-slate-500">
          {form.purpose.trim().length}/{PURPOSE_MAX} caracteres
        </p>
      </div>

      <fieldset className="space-y-3">
        <div className="flex items-center justify-between">
          <legend className="text-sm font-medium text-slate-700">
            Outcomes esperados <span className="text-red-600">*</span>
          </legend>
          <button
            type="button"
            onClick={addOutcome}
            disabled={form.expectedOutcomes.length >= OUTCOMES_MAX}
            className="rounded-md border border-slate-300 bg-white px-2 py-1 text-xs hover:bg-slate-50 disabled:opacity-50"
          >
            + outro outcome
          </button>
        </div>
        <p className="text-xs text-slate-500">
          Liste os pontos concretos que precisavam ser tratados ou decididos. Sem
          outcomes, a NORA não tenta gerar um score.
        </p>
        <ul className="space-y-2">
          {form.expectedOutcomes.map((outcome, idx) => (
            <li key={idx} className="flex items-start gap-2">
              <input
                aria-label={`Outcome esperado ${idx + 1}`}
                value={outcome}
                maxLength={OUTCOME_MAX}
                onChange={(e) => updateOutcome(idx, e.target.value)}
                placeholder="Ex: Definir critérios de aceite da feature X"
                className="flex-1 rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
              />
              <button
                type="button"
                onClick={() => removeOutcome(idx)}
                disabled={form.expectedOutcomes.length <= 1 && outcome === ""}
                aria-label={`Remover outcome ${idx + 1}`}
                className="rounded-md border border-slate-300 bg-white px-2 py-2 text-xs text-red-600 hover:bg-red-50 disabled:opacity-50"
              >
                Remover
              </button>
            </li>
          ))}
        </ul>
      </fieldset>

      <div className="space-y-1.5">
        <label
          htmlFor="meeting-goal-snapshot"
          className="text-sm font-medium text-slate-700"
        >
          Snapshot do projeto (opcional)
        </label>
        <textarea
          id="meeting-goal-snapshot"
          rows={4}
          value={form.projectStateSnapshot}
          maxLength={SNAPSHOT_MAX}
          onChange={(e) => updateSnapshot(e.target.value)}
          placeholder="Opcional: o que está feito do projeto, blockers atuais, etc."
          className="w-full rounded-md border border-slate-300 px-3 py-2 text-sm focus:border-slate-500 focus:outline-none"
        />
        <p className="text-xs text-slate-500">
          {form.projectStateSnapshot.trim().length}/{SNAPSHOT_MAX} caracteres
        </p>
      </div>

      {error && (
        <p role="alert" className="text-sm text-red-600">
          {error}
        </p>
      )}

      <div className="flex gap-2">
        <button
          type="submit"
          disabled={saving}
          className="rounded-md bg-slate-900 px-4 py-2 text-sm font-medium text-white hover:bg-slate-800 disabled:opacity-50"
        >
          {saving ? "Salvando…" : "Salvar objetivo"}
        </button>
        <button
          type="button"
          onClick={onCancel}
          disabled={saving}
          className="rounded-md border border-slate-300 bg-white px-4 py-2 text-sm text-slate-700 hover:bg-slate-50 disabled:opacity-50"
        >
          Cancelar
        </button>
      </div>
    </form>
  );
}
