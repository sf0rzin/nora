/**
 * The policy document, as a shape a form can edit (US42).
 *
 * No copy lives here — the pt-BR strings stay in the component, which is where
 * `scripts/check-language.sh` expects UI text. What this module exports are the two conversions
 * the form is built on and the failure codes the component translates.
 *
 * THE RULE THIS MODULE EXISTS TO ENFORCE: a form that silently drops what it cannot render is
 * worse than no form, because the user saves a policy that no longer says what it said. So
 * `parsePolicyDocument` REFUSES anything it cannot represent exactly — an unknown field, an
 * operator the evaluator does not implement, a value shape the evaluator would stringify into
 * something else — and the caller falls back to the JSON editor, which is still there and still
 * the source of truth for anything unusual.
 */

/** `effect`, in the casing the API accepts and returns. */
export type PolicyEffect = 'Allow' | 'Deny';

/**
 * The five operators `PolicyEvaluator` implements
 * (`services/api/.../domain/iam/PolicyEvaluator.java`, `SUPPORTED_CONDITION_OPERATORS`).
 *
 * The form offers these and nothing else, and the reason is not tidiness: an unsupported operator
 * makes the statement not match, so an Allow carrying one grants nothing at all. A form that let
 * someone build `StringNotEquals` would be a form whose output silently never allows anything.
 */
export const CONDITION_OPERATORS = [
  'StringEquals',
  'StringIn',
  'StringLike',
  'DateGreaterThan',
  'DateLessThan',
] as const;

export type ConditionOperator = (typeof CONDITION_OPERATORS)[number];

/** `StringIn` is the only operator whose value is a list; the rest take a single string. */
export const MULTI_VALUE_OPERATOR: ConditionOperator = 'StringIn';

export interface ConditionRow {
  operator: ConditionOperator;
  /** Context attribute the operator reads, e.g. `department`. */
  key: string;
  /** One entry for every operator except `StringIn`, which may carry several. */
  values: string[];
}

export interface StatementForm {
  effect: PolicyEffect;
  actions: string[];
  resources: string[];
  conditions: ConditionRow[];
}

export interface PolicyForm {
  version: string;
  statements: StatementForm[];
}

/** Why a document cannot be opened in the form. The component turns each one into pt-BR copy. */
export type ParseFailure =
  | 'NOT_AN_OBJECT'
  | 'NO_STATEMENTS'
  | 'UNKNOWN_DOCUMENT_FIELD'
  | 'UNKNOWN_STATEMENT_FIELD'
  | 'AMBIGUOUS_STATEMENT'
  | 'UNKNOWN_EFFECT'
  | 'EMPTY_ACTION'
  | 'EMPTY_RESOURCE'
  | 'MALFORMED_CONDITION'
  | 'UNSUPPORTED_OPERATOR'
  | 'NON_STRING_VALUE'
  | 'LIST_ON_SINGLE_VALUE_OPERATOR';

export type ParseResult =
  | { ok: true; form: PolicyForm }
  | { ok: false; failure: ParseFailure; where: string };

/** Something the form can hold but the API would reject. Save stays disabled while any is open. */
export type FormIssue =
  | 'EMPTY_ACTION'
  | 'EMPTY_RESOURCE'
  | 'EMPTY_CONDITION_KEY'
  | 'EMPTY_CONDITION_VALUE'
  | 'DUPLICATE_CONDITION_KEY';

export const DOCUMENT_VERSION = '2026-05-07';

const DOCUMENT_FIELDS = new Set(['version', 'statements']);
const STATEMENT_FIELDS = new Set([
  'effect',
  'action',
  'actions',
  'resource',
  'resources',
  'condition',
]);

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

function fail(failure: ParseFailure, where: string): ParseResult {
  return { ok: false, failure, where };
}

/**
 * Reads one of the two list fields of a statement.
 *
 * Both spellings are accepted on purpose. `POST /iam/policies` has always parsed the singular
 * `action`/`resource`, and that is what the API now returns as well; the plural is the shape the
 * read endpoint emitted before it was aligned, and a browser can be holding a page older or newer
 * than the API that answers it. A statement carrying BOTH is refused rather than guessed at.
 */
function readList(
  statement: Record<string, unknown>,
  singular: string,
  plural: string,
): string[] | 'AMBIGUOUS' | 'INVALID' {
  const hasSingular = singular in statement;
  const hasPlural = plural in statement;
  if (hasSingular && hasPlural) return 'AMBIGUOUS';
  const raw = hasSingular ? statement[singular] : statement[plural];
  if (typeof raw === 'string') return raw ? [raw] : [];
  if (!Array.isArray(raw)) return 'INVALID';
  if (!raw.every((entry): entry is string => typeof entry === 'string')) return 'INVALID';
  return [...raw];
}

function parseConditions(raw: unknown, where: string): ConditionRow[] | ParseResult {
  if (raw === undefined || raw === null) return [];
  if (!isRecord(raw)) return fail('MALFORMED_CONDITION', where);
  const rows: ConditionRow[] = [];
  for (const [operator, requirements] of Object.entries(raw)) {
    if (!isConditionOperator(operator)) return fail('UNSUPPORTED_OPERATOR', `${where} ${operator}`);
    if (!isRecord(requirements)) return fail('MALFORMED_CONDITION', `${where} ${operator}`);
    for (const [key, value] of Object.entries(requirements)) {
      const at = `${where} ${operator}.${key}`;
      if (Array.isArray(value)) {
        if (operator !== MULTI_VALUE_OPERATOR) return fail('LIST_ON_SINGLE_VALUE_OPERATOR', at);
        if (!value.every((entry): entry is string => typeof entry === 'string')) {
          return fail('NON_STRING_VALUE', at);
        }
        rows.push({ operator, key, values: [...value] });
        continue;
      }
      if (typeof value !== 'string') return fail('NON_STRING_VALUE', at);
      rows.push({ operator, key, values: [value] });
    }
  }
  return rows;
}

export function isConditionOperator(value: string): value is ConditionOperator {
  return (CONDITION_OPERATORS as readonly string[]).includes(value);
}

/**
 * Turns a policy document into the form model, or says why it cannot.
 *
 * `where` points at the statement (and, for a condition, at the operator and key) so the message
 * can name the spot instead of asking the user to hunt for it.
 */
export function parsePolicyDocument(raw: unknown): ParseResult {
  if (!isRecord(raw)) return fail('NOT_AN_OBJECT', 'document');
  for (const field of Object.keys(raw)) {
    if (!DOCUMENT_FIELDS.has(field)) return fail('UNKNOWN_DOCUMENT_FIELD', field);
  }
  const statements = raw.statements;
  if (!Array.isArray(statements) || statements.length === 0) {
    return fail('NO_STATEMENTS', 'document');
  }

  const parsed: StatementForm[] = [];
  for (let index = 0; index < statements.length; index += 1) {
    const where = `statement ${index + 1}`;
    const statement = statements[index];
    if (!isRecord(statement)) return fail('NOT_AN_OBJECT', where);
    for (const field of Object.keys(statement)) {
      if (!STATEMENT_FIELDS.has(field)) return fail('UNKNOWN_STATEMENT_FIELD', `${where} ${field}`);
    }

    const effect = typeof statement.effect === 'string' ? statement.effect.toLowerCase() : '';
    if (effect !== 'allow' && effect !== 'deny') return fail('UNKNOWN_EFFECT', where);

    const actions = readList(statement, 'action', 'actions');
    if (actions === 'AMBIGUOUS') return fail('AMBIGUOUS_STATEMENT', `${where} action`);
    if (actions === 'INVALID' || actions.length === 0) return fail('EMPTY_ACTION', where);

    const resources = readList(statement, 'resource', 'resources');
    if (resources === 'AMBIGUOUS') return fail('AMBIGUOUS_STATEMENT', `${where} resource`);
    if (resources === 'INVALID' || resources.length === 0) return fail('EMPTY_RESOURCE', where);

    const conditions = parseConditions(statement.condition, where);
    if (!Array.isArray(conditions)) return conditions;

    parsed.push({
      effect: effect === 'allow' ? 'Allow' : 'Deny',
      actions,
      resources,
      conditions,
    });
  }

  const version = typeof raw.version === 'string' && raw.version ? raw.version : DOCUMENT_VERSION;
  return { ok: true, form: { version, statements: parsed } };
}

/**
 * The form model as the document the API accepts: `action`/`resource` singular, `effect` in
 * `Allow`/`Deny` casing, `condition` omitted when the statement has none — the same shape `GET
 * /iam/policies` returns, so a document read, opened in the form and saved unchanged is the
 * document that was read.
 */
export function serializePolicyDocument(form: PolicyForm): Record<string, unknown> {
  return {
    version: form.version || DOCUMENT_VERSION,
    statements: form.statements.map((statement) => {
      const out: Record<string, unknown> = {
        effect: statement.effect,
        action: [...statement.actions],
        resource: [...statement.resources],
      };
      const condition = serializeConditions(statement.conditions);
      if (condition) out.condition = condition;
      return out;
    }),
  };
}

function serializeConditions(rows: ConditionRow[]): Record<string, unknown> | null {
  if (rows.length === 0) return null;
  const condition: Record<string, Record<string, string | string[]>> = {};
  for (const row of rows) {
    const block = condition[row.operator] ?? {};
    condition[row.operator] = block;
    block[row.key] = row.operator === MULTI_VALUE_OPERATOR ? [...row.values] : row.values[0] ?? '';
  }
  return condition;
}

/** Pretty JSON for the editor, produced from the form model. */
export function formToJson(form: PolicyForm): string {
  return JSON.stringify(serializePolicyDocument(form), null, 2);
}

/**
 * What the form is holding that the API would refuse. Reported rather than auto-corrected: an
 * empty action list silently dropped is a statement the user believes they wrote.
 *
 * `DUPLICATE_CONDITION_KEY` is the one issue that is not obvious on screen. The document nests
 * conditions as operator then key, so two rows with the same pair collapse into one on save and
 * the second would quietly win.
 */
export function formIssues(form: PolicyForm): FormIssue[] {
  const issues = new Set<FormIssue>();
  for (const statement of form.statements) {
    if (statement.actions.filter((action) => action.trim()).length === 0) {
      issues.add('EMPTY_ACTION');
    }
    if (statement.resources.filter((resource) => resource.trim()).length === 0) {
      issues.add('EMPTY_RESOURCE');
    }
    const seen = new Set<string>();
    for (const row of statement.conditions) {
      if (!row.key.trim()) issues.add('EMPTY_CONDITION_KEY');
      if (row.values.filter((value) => value.trim()).length === 0) {
        issues.add('EMPTY_CONDITION_VALUE');
      }
      const pair = `${row.operator}.${row.key.trim()}`;
      if (row.key.trim() && seen.has(pair)) issues.add('DUPLICATE_CONDITION_KEY');
      seen.add(pair);
    }
  }
  return [...issues];
}

/** A statement the form starts from: an Allow that grants nothing until it is filled in. */
export function emptyStatement(): StatementForm {
  return { effect: 'Allow', actions: [''], resources: [''], conditions: [] };
}

export function emptyForm(): PolicyForm {
  return { version: DOCUMENT_VERSION, statements: [emptyStatement()] };
}
