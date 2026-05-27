"use client";

import { useState } from "react";
import {
  ApiRequestError,
  setMeetingGoal,
} from "@/lib/api/client";
import type { MeetingGoal } from "@/lib/api/types";
import { Banner, Button, Field, Input, Textarea } from "@/components/core/ui";

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
      className="nora-card"
      aria-label="Formulário do objetivo da reunião"
    >
      <Field
        label="Propósito da reunião"
        required
        htmlFor="meeting-goal-purpose"
        hint={`${form.purpose.trim().length}/${PURPOSE_MAX} caracteres`}
      >
        <Textarea
          id="meeting-goal-purpose"
          required
          rows={3}
          value={form.purpose}
          maxLength={PURPOSE_MAX}
          onChange={(e) => updatePurpose(e.target.value)}
          placeholder="Refinement do épico X, discovery com lead novo, etc."
        />
      </Field>

      <fieldset style={{ border: "none", margin: 0, padding: 0, marginBottom: 16 }}>
        <div
          style={{
            display: "flex",
            alignItems: "center",
            justifyContent: "space-between",
            gap: 8,
            marginBottom: 6,
          }}
        >
          <legend className="nora-label" style={{ marginBottom: 0 }}>
            Outcomes esperados <span style={{ color: "var(--danger)" }}>*</span>
          </legend>
          <Button
            size="sm"
            onClick={addOutcome}
            disabled={form.expectedOutcomes.length >= OUTCOMES_MAX}
          >
            + outro outcome
          </Button>
        </div>
        <p style={{ fontSize: 11.5, color: "var(--muted)", marginBottom: 10 }}>
          Liste os pontos concretos que precisavam ser tratados ou decididos. Sem
          outcomes, a NORA não tenta gerar um score.
        </p>
        <ul
          style={{
            display: "flex",
            flexDirection: "column",
            gap: 8,
            margin: 0,
            padding: 0,
            listStyle: "none",
          }}
        >
          {form.expectedOutcomes.map((outcome, idx) => (
            <li key={idx} style={{ display: "flex", alignItems: "flex-start", gap: 8 }}>
              <Input
                aria-label={`Outcome esperado ${idx + 1}`}
                value={outcome}
                maxLength={OUTCOME_MAX}
                onChange={(e) => updateOutcome(idx, e.target.value)}
                placeholder="Ex: Definir critérios de aceite da feature X"
                style={{ flex: 1 }}
              />
              <Button
                variant="danger"
                size="sm"
                onClick={() => removeOutcome(idx)}
                disabled={form.expectedOutcomes.length <= 1 && outcome === ""}
                aria-label={`Remover outcome ${idx + 1}`}
              >
                Remover
              </Button>
            </li>
          ))}
        </ul>
      </fieldset>

      <Field
        label="Snapshot do projeto (opcional)"
        htmlFor="meeting-goal-snapshot"
        hint={`${form.projectStateSnapshot.trim().length}/${SNAPSHOT_MAX} caracteres`}
      >
        <Textarea
          id="meeting-goal-snapshot"
          rows={4}
          value={form.projectStateSnapshot}
          maxLength={SNAPSHOT_MAX}
          onChange={(e) => updateSnapshot(e.target.value)}
          placeholder="Opcional: o que está feito do projeto, blockers atuais, etc."
        />
      </Field>

      {error && (
        <div style={{ marginBottom: 16 }}>
          <Banner tone="error">{error}</Banner>
        </div>
      )}

      <div style={{ display: "flex", gap: 8 }}>
        <Button type="submit" variant="primary" disabled={saving}>
          {saving ? "Salvando…" : "Salvar objetivo"}
        </Button>
        <Button type="button" onClick={onCancel} disabled={saving}>
          Cancelar
        </Button>
      </div>
    </form>
  );
}
