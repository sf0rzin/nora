import { describe, expect, it } from 'vitest';

import {
  CONDITION_OPERATORS,
  emptyForm,
  formIssues,
  formToJson,
  parsePolicyDocument,
  serializePolicyDocument,
} from './policy-document';
import type { ParseFailure, PolicyForm } from './policy-document';

const READ_ONLY = {
  version: '2026-05-07',
  statements: [
    {
      effect: 'Allow',
      action: ['meeting:read', 'task:read'],
      resource: ['nora:tenant/t1:meeting/*', 'nora:tenant/t1:task/*'],
    },
  ],
};

const WITH_CONDITIONS = {
  version: '2026-05-07',
  statements: [
    {
      effect: 'Allow',
      action: ['meeting:read'],
      resource: ['nora:tenant/t1:meeting/*'],
      condition: {
        StringEquals: { department: 'Vendas' },
        StringIn: { region: ['BR-SP', 'BR-RJ'] },
      },
    },
    {
      effect: 'Deny',
      action: ['meeting:read'],
      resource: ['nora:tenant/t1:meeting/secreta'],
    },
  ],
};

function parsedForm(document: unknown): PolicyForm {
  const result = parsePolicyDocument(document);
  if (!result.ok) throw new Error(`expected a parseable document, got ${result.failure}`);
  return result.form;
}

function failureOf(document: unknown): ParseFailure | 'PARSED' {
  const result = parsePolicyDocument(document);
  return result.ok ? 'PARSED' : result.failure;
}

describe('the round trip', () => {
  it('gives back the same document it was given', () => {
    expect(serializePolicyDocument(parsedForm(READ_ONLY))).toEqual(READ_ONLY);
  });

  it('keeps every condition, its operator, its key and its values', () => {
    expect(serializePolicyDocument(parsedForm(WITH_CONDITIONS))).toEqual(WITH_CONDITIONS);
  });

  it('is stable when applied twice', () => {
    const once = serializePolicyDocument(parsedForm(WITH_CONDITIONS));
    const twice = serializePolicyDocument(parsedForm(once));
    expect(twice).toEqual(once);
  });

  it('preserves the order of actions and of resources', () => {
    const form = parsedForm(READ_ONLY);
    expect(form.statements[0].actions).toEqual(['meeting:read', 'task:read']);
    expect(serializePolicyDocument(form).statements).toEqual(READ_ONLY.statements);
  });

  it('omits condition entirely when a statement has none', () => {
    const [statement] = serializePolicyDocument(parsedForm(READ_ONLY)).statements as Record<
      string,
      unknown
    >[];
    expect(Object.keys(statement)).toEqual(['effect', 'action', 'resource']);
  });

  it('produces JSON the parser reads back into the same form', () => {
    const form = parsedForm(WITH_CONDITIONS);
    expect(parsedForm(JSON.parse(formToJson(form)))).toEqual(form);
  });
});

describe('what the form refuses to open', () => {
  // Each of these is a document the form could render only by changing what it says. Refusing
  // sends the user to the JSON editor, which is still there; guessing would save a policy that
  // no longer matches what the author wrote.

  it('refuses an operator the evaluator does not implement', () => {
    const document = {
      version: '2026-05-07',
      statements: [
        {
          effect: 'Allow',
          action: ['meeting:read'],
          resource: ['nora:tenant/t1:meeting/*'],
          condition: { StringNotEquals: { department: 'Suporte' } },
        },
      ],
    };
    expect(failureOf(document)).toBe('UNSUPPORTED_OPERATOR');
  });

  it('refuses a field it would drop on save', () => {
    const document = {
      version: '2026-05-07',
      statements: [
        {
          sid: 'AllowReads',
          effect: 'Allow',
          action: ['meeting:read'],
          resource: ['nora:tenant/t1:meeting/*'],
        },
      ],
    };
    expect(failureOf(document)).toBe('UNKNOWN_STATEMENT_FIELD');
  });

  it('refuses a list on an operator that compares a single value', () => {
    const document = {
      version: '2026-05-07',
      statements: [
        {
          effect: 'Allow',
          action: ['meeting:read'],
          resource: ['nora:tenant/t1:meeting/*'],
          condition: { StringEquals: { department: ['Vendas', 'Suporte'] } },
        },
      ],
    };
    expect(failureOf(document)).toBe('LIST_ON_SINGLE_VALUE_OPERATOR');
  });

  it('refuses a non-string condition value instead of stringifying it', () => {
    const document = {
      version: '2026-05-07',
      statements: [
        {
          effect: 'Allow',
          action: ['meeting:read'],
          resource: ['nora:tenant/t1:meeting/*'],
          condition: { StringEquals: { seats: 5 } },
        },
      ],
    };
    expect(failureOf(document)).toBe('NON_STRING_VALUE');
  });

  it('refuses a statement that spells the same list both ways', () => {
    const document = {
      version: '2026-05-07',
      statements: [
        {
          effect: 'Allow',
          action: ['meeting:read'],
          actions: ['meeting:update'],
          resource: ['nora:tenant/t1:meeting/*'],
        },
      ],
    };
    expect(failureOf(document)).toBe('AMBIGUOUS_STATEMENT');
  });

  it('refuses a document with no statements, and one that is not an object', () => {
    expect(failureOf({ version: '2026-05-07', statements: [] })).toBe('NO_STATEMENTS');
    expect(failureOf('{}')).toBe('NOT_AN_OBJECT');
  });

  it('refuses an effect that is neither Allow nor Deny', () => {
    const document = {
      statements: [{ effect: 'Maybe', action: ['a:b'], resource: ['r'] }],
    };
    expect(failureOf(document)).toBe('UNKNOWN_EFFECT');
  });
});

describe('what the form accepts and normalises', () => {
  // Normalising is not dropping: every case here means the same thing to the evaluator before and
  // after, which is the line between a rewrite the user did not ask for and a shape change.

  it('reads the plural spelling an older API answered with', () => {
    const legacy = {
      version: '2026-05-07',
      statements: [
        { effect: 'ALLOW', actions: ['meeting:read'], resources: ['nora:tenant/t1:meeting/*'] },
      ],
    };
    expect(serializePolicyDocument(parsedForm(legacy))).toEqual({
      version: '2026-05-07',
      statements: [
        { effect: 'Allow', action: ['meeting:read'], resource: ['nora:tenant/t1:meeting/*'] },
      ],
    });
  });

  it('accepts a bare string where the API accepts one', () => {
    const document = {
      statements: [{ effect: 'Deny', action: 'meeting:*', resource: 'nora:tenant/t1:meeting/*' }],
    };
    const form = parsedForm(document);
    expect(form.statements[0].actions).toEqual(['meeting:*']);
    expect(form.statements[0].resources).toEqual(['nora:tenant/t1:meeting/*']);
  });

  it('defaults the version when the document does not carry one', () => {
    const document = { statements: [{ effect: 'Allow', action: ['a:b'], resource: ['r'] }] };
    expect(parsedForm(document).version).toBe('2026-05-07');
  });
});

describe('the operator list', () => {
  it('is exactly the five the evaluator implements', () => {
    // Mirrors SUPPORTED_CONDITION_OPERATORS in PolicyEvaluator.java. A sixth entry here would be
    // a form producing Allows that never allow — fail-closed denies what it cannot evaluate.
    expect([...CONDITION_OPERATORS]).toEqual([
      'StringEquals',
      'StringIn',
      'StringLike',
      'DateGreaterThan',
      'DateLessThan',
    ]);
  });
});

describe('formIssues', () => {
  it('reports nothing for a filled-in statement', () => {
    expect(formIssues(parsedForm(WITH_CONDITIONS))).toEqual([]);
  });

  it('reports the empty lists a fresh form starts with', () => {
    expect(formIssues(emptyForm())).toEqual(['EMPTY_ACTION', 'EMPTY_RESOURCE']);
  });

  it('reports two condition rows that would collapse into one on save', () => {
    const form: PolicyForm = {
      version: '2026-05-07',
      statements: [
        {
          effect: 'Allow',
          actions: ['meeting:read'],
          resources: ['nora:tenant/t1:meeting/*'],
          conditions: [
            { operator: 'StringEquals', key: 'department', values: ['Vendas'] },
            { operator: 'StringEquals', key: 'department', values: ['Suporte'] },
          ],
        },
      ],
    };
    expect(formIssues(form)).toEqual(['DUPLICATE_CONDITION_KEY']);
  });
});
