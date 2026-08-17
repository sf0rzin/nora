"use client";

/**
 * PolicyFormEditor (US42)
 * -------------------------------------------------------------------------
 * Form-based editor for an IAM policy document — the half of US42 that was
 * missing. It sits BESIDE `policy-editor.tsx` (Monaco + JSON schema), never in
 * front of it: the JSON editor stays the way to write anything this form
 * cannot represent, and this component refuses to open such a document rather
 * than rendering an approximation of it.
 *
 * Contract, identical to PolicyEditor so the page can swap one for the other:
 *
 *   <PolicyFormEditor value={jsonString} onChange={(json, isValid) => ...} />
 *
 * - `value`: raw JSON string. Parsed into the form model on the way in,
 *   re-serialised on every edit, so the two editors share one piece of state
 *   and switching tabs never loses a keystroke.
 * - `onChange(value, isValid)`: `isValid` is false while the form holds
 *   something the API would refuse (a statement with no action, a condition
 *   with no key). The caller uses it to disable Save.
 *
 * The condition operator is a <select> over the FIVE the evaluator implements
 * and nothing else. That is the safety property of this component: an
 * unsupported operator makes a statement not match, so a form that offered one
 * would produce an Allow that silently never allows.
 */

import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import {
  CONDITION_OPERATORS,
  MULTI_VALUE_OPERATOR,
  emptyStatement,
  formIssues,
  formToJson,
  parsePolicyDocument,
} from "@/lib/iam/policy-document";
import type {
  ConditionOperator,
  ConditionRow,
  FormIssue,
  ParseFailure,
  PolicyForm,
  StatementForm,
} from "@/lib/iam/policy-document";

/** A local reason on top of the parser's, for text that is not JSON at all. */
type Refusal = ParseFailure | "INVALID_JSON";

const REFUSAL_COPY: Record<Refusal, string> = {
  INVALID_JSON: "O texto atual não é um JSON válido.",
  NOT_AN_OBJECT: "O documento precisa ser um objeto com version e statements.",
  NO_STATEMENTS: "O documento não tem nenhum statement.",
  UNKNOWN_DOCUMENT_FIELD: "O documento tem um campo que o formulário não representa.",
  UNKNOWN_STATEMENT_FIELD: "Um statement tem um campo que o formulário não representa.",
  AMBIGUOUS_STATEMENT: "Um statement declara a mesma lista nas duas grafias (action e actions).",
  UNKNOWN_EFFECT: "O effect precisa ser Allow ou Deny.",
  EMPTY_ACTION: "Um statement está sem action.",
  EMPTY_RESOURCE: "Um statement está sem resource.",
  MALFORMED_CONDITION: "A condition precisa ser operador → { chave: valor }.",
  UNSUPPORTED_OPERATOR:
    "A condition usa um operador que o avaliador não implementa — ele nega por padrão.",
  NON_STRING_VALUE: "Um valor de condition não é texto.",
  LIST_ON_SINGLE_VALUE_OPERATOR: "Apenas StringIn aceita uma lista de valores.",
};

const ISSUE_COPY: Record<FormIssue, string> = {
  EMPTY_ACTION: "Todo statement precisa de pelo menos uma action.",
  EMPTY_RESOURCE: "Todo statement precisa de pelo menos um resource.",
  EMPTY_CONDITION_KEY: "Toda condição precisa de uma chave.",
  EMPTY_CONDITION_VALUE: "Toda condição precisa de um valor.",
  DUPLICATE_CONDITION_KEY:
    "Duas condições com o mesmo operador e a mesma chave: ao salvar, uma sobrescreve a outra.",
};

/**
 * Suggestions for the action field, offered through a <datalist> — the field
 * stays free text, so an action missing from this list is still typable and a
 * drift costs a suggestion, never a grant. Source: the action catalogue in
 * `docs/engineering/architecture.md` §4, extracted from the controllers.
 */
const ACTION_SUGGESTIONS = [
  "*",
  "meeting:upload",
  "meeting:read",
  "meeting:update",
  "meeting:reprocess",
  "meeting:analyze:live",
  "task:read",
  "task:write",
  "tenant:read",
  "tenant:name:write",
  "tenant:domain:read",
  "tenant:domain:write",
  "tenant:context:read",
  "tenant:context:write",
  "workflow:read",
  "workflow:write",
  "workflow:test",
  "integration:read",
  "integration:write",
  "iam:group:read",
  "iam:group:create",
  "iam:group:delete",
  "iam:group:add-member",
  "iam:group:remove-member",
  "iam:policy:read",
  "iam:policy:create",
  "iam:policy:update",
  "iam:policy:delete",
  "iam:policy:simulate",
  "iam:attachment:create",
  "iam:attachment:delete",
  "iam:audit:read",
  "iam:user:invite",
  "iam:invite:read",
  "iam:invite:revoke",
];

export interface PolicyFormEditorProps {
  /** Raw JSON string — the same value the JSON editor holds. */
  value: string;
  onChange: (value: string, isValid: boolean) => void;
  readOnly?: boolean;
}

const INPUT = "rounded-md border border-slate-300 px-2 py-1 text-xs";
const GHOST_BUTTON = "text-xs text-slate-600 hover:underline disabled:opacity-50";

export default function PolicyFormEditor({
  value,
  onChange,
  readOnly = false,
}: PolicyFormEditorProps) {
  const [form, setForm] = useState<PolicyForm | null>(null);
  const [refusal, setRefusal] = useState<{ reason: Refusal; where: string } | null>(null);
  const actionListId = "policy-form-actions";

  const onChangeRef = useRef(onChange);
  useEffect(() => {
    onChangeRef.current = onChange;
  }, [onChange]);

  // What this component last emitted. Re-parsing our own output would rebuild the
  // form on every keystroke and move the caret to the end of the field.
  const emitted = useRef<string | null>(null);

  useEffect(() => {
    if (value === emitted.current) return;
    let raw: unknown;
    try {
      raw = JSON.parse(value);
    } catch {
      setForm(null);
      setRefusal({ reason: "INVALID_JSON", where: "" });
      return;
    }
    const parsed = parsePolicyDocument(raw);
    if (parsed.ok) {
      setForm(parsed.form);
      setRefusal(null);
    } else {
      setForm(null);
      setRefusal({ reason: parsed.failure, where: parsed.where });
    }
  }, [value]);

  const issues = useMemo(() => (form ? formIssues(form) : []), [form]);

  const update = useCallback((next: PolicyForm) => {
    setForm(next);
    const json = formToJson(next);
    emitted.current = json;
    onChangeRef.current(json, formIssues(next).length === 0);
  }, []);

  const patchStatement = useCallback(
    (index: number, patch: Partial<StatementForm>) => {
      if (!form) return;
      update({
        ...form,
        statements: form.statements.map((s, i) => (i === index ? { ...s, ...patch } : s)),
      });
    },
    [form, update],
  );

  if (refusal) {
    return (
      <div
        className="space-y-1 rounded-md border border-amber-300 bg-amber-50 px-3 py-2 text-xs text-amber-900"
        role="status"
      >
        <p className="font-medium">Este documento não pode ser editado no formulário.</p>
        <p>
          {REFUSAL_COPY[refusal.reason]}
          {refusal.where && <span className="text-amber-700"> ({refusal.where})</span>}
        </p>
        <p>
          O formulário se recusa a abrir o que não consegue representar exatamente — abrir mesmo
          assim salvaria uma policy diferente da escrita. Use a aba JSON.
        </p>
      </div>
    );
  }

  if (!form) {
    return <p className="text-xs text-slate-500">Carregando formulário…</p>;
  }

  return (
    <div className="space-y-3">
      <datalist id={actionListId}>
        {ACTION_SUGGESTIONS.map((action) => (
          <option key={action} value={action} />
        ))}
      </datalist>

      {form.statements.map((statement, index) => (
        <fieldset
          key={index}
          className="space-y-3 rounded-md border border-slate-300 p-3 text-xs"
          disabled={readOnly}
        >
          <legend className="px-1 text-xs font-medium text-slate-600">
            Statement {index + 1}
          </legend>

          <label className="flex items-center gap-2">
            <span className="w-20 text-slate-600">Efeito</span>
            <select
              value={statement.effect}
              onChange={(e) =>
                patchStatement(index, { effect: e.target.value === "Deny" ? "Deny" : "Allow" })
              }
              className={INPUT}
            >
              <option value="Allow">Allow</option>
              <option value="Deny">Deny</option>
            </select>
            <span className="text-slate-400">Deny vence qualquer Allow.</span>
          </label>

          <StringList
            label="Ações"
            placeholder="meeting:read"
            listId={actionListId}
            values={statement.actions}
            onChange={(actions) => patchStatement(index, { actions })}
          />

          <StringList
            label="Recursos"
            placeholder="nora:tenant/…:meeting/*"
            values={statement.resources}
            onChange={(resources) => patchStatement(index, { resources })}
          />

          <Conditions
            rows={statement.conditions}
            onChange={(conditions) => patchStatement(index, { conditions })}
          />

          <button
            type="button"
            className="text-xs text-red-600 hover:underline disabled:opacity-50"
            disabled={form.statements.length === 1}
            onClick={() =>
              update({ ...form, statements: form.statements.filter((_, i) => i !== index) })
            }
          >
            remover statement
          </button>
        </fieldset>
      ))}

      <button
        type="button"
        className={GHOST_BUTTON}
        disabled={readOnly}
        onClick={() => update({ ...form, statements: [...form.statements, emptyStatement()] })}
      >
        adicionar statement
      </button>

      {issues.length > 0 && (
        <ul className="space-y-1 text-xs text-red-600" role="alert">
          {issues.map((issue) => (
            <li key={issue}>{ISSUE_COPY[issue]}</li>
          ))}
        </ul>
      )}
    </div>
  );
}

/** A list of free-text values with add/remove — used for actions and for resources. */
function StringList({
  label,
  placeholder,
  listId,
  values,
  onChange,
}: {
  label: string;
  placeholder: string;
  listId?: string;
  values: string[];
  onChange: (values: string[]) => void;
}) {
  return (
    <div className="space-y-1">
      <span className="text-slate-600">{label}</span>
      {values.map((entry, index) => (
        <div key={index} className="flex items-center gap-2">
          <input
            value={entry}
            list={listId}
            placeholder={placeholder}
            onChange={(e) => onChange(values.map((v, i) => (i === index ? e.target.value : v)))}
            className={`${INPUT} flex-1`}
            aria-label={`${label} ${index + 1}`}
          />
          <button
            type="button"
            className="text-xs text-red-600 hover:underline disabled:opacity-50"
            disabled={values.length === 1}
            onClick={() => onChange(values.filter((_, i) => i !== index))}
          >
            remover
          </button>
        </div>
      ))}
      <button type="button" className={GHOST_BUTTON} onClick={() => onChange([...values, ""])}>
        adicionar
      </button>
    </div>
  );
}

/**
 * The condition rows. The operator select carries exactly the five the
 * evaluator implements; switching to one that compares a single value drops
 * the extra values then and there, because leaving them in state would keep
 * them on screen while the saved document carried only the first.
 */
function Conditions({
  rows,
  onChange,
}: {
  rows: ConditionRow[];
  onChange: (rows: ConditionRow[]) => void;
}) {
  function patch(index: number, next: ConditionRow) {
    onChange(rows.map((row, i) => (i === index ? next : row)));
  }

  return (
    <div className="space-y-2">
      <span className="text-slate-600">Condições</span>
      <p className="text-slate-400">
        Lidas do contexto da requisição (atributos da reunião, do usuário). Uma condição não
        satisfeita nega — inclusive quando o atributo não existe.
      </p>
      {rows.map((row, index) => (
        <div key={index} className="flex flex-wrap items-start gap-2">
          <select
            value={row.operator}
            onChange={(e) => {
              const operator = e.target.value as ConditionOperator;
              const values =
                operator === MULTI_VALUE_OPERATOR ? row.values : row.values.slice(0, 1);
              patch(index, { ...row, operator, values });
            }}
            className={INPUT}
            aria-label={`Operador ${index + 1}`}
          >
            {CONDITION_OPERATORS.map((operator) => (
              <option key={operator} value={operator}>
                {operator}
              </option>
            ))}
          </select>
          <input
            value={row.key}
            placeholder="chave (ex: department)"
            onChange={(e) => patch(index, { ...row, key: e.target.value })}
            className={INPUT}
            aria-label={`Chave ${index + 1}`}
          />
          <div className="space-y-1">
            {row.values.map((entry, valueIndex) => (
              <input
                key={valueIndex}
                value={entry}
                placeholder="valor"
                onChange={(e) =>
                  patch(index, {
                    ...row,
                    values: row.values.map((v, i) => (i === valueIndex ? e.target.value : v)),
                  })
                }
                className={INPUT}
                aria-label={`Valor ${index + 1}.${valueIndex + 1}`}
              />
            ))}
            {row.operator === MULTI_VALUE_OPERATOR && (
              <button
                type="button"
                className={GHOST_BUTTON}
                onClick={() => patch(index, { ...row, values: [...row.values, ""] })}
              >
                adicionar valor
              </button>
            )}
          </div>
          <button
            type="button"
            className="text-xs text-red-600 hover:underline"
            onClick={() => onChange(rows.filter((_, i) => i !== index))}
          >
            remover
          </button>
        </div>
      ))}
      <button
        type="button"
        className={GHOST_BUTTON}
        onClick={() =>
          onChange([...rows, { operator: CONDITION_OPERATORS[0], key: "", values: [""] }])
        }
      >
        adicionar condição
      </button>
    </div>
  );
}
