/**
 * `src/lib/pii/redact.ts` — the BFF's redaction, and per ADR 0040 the ONLY redaction on the
 * chat path. Text that leaves this function goes to an external LLM provider.
 *
 * The module's own header promises it mirrors the STRUCTURED patterns of the worker's PII
 * Shield 1:1, and lines 20-25 of that file record that the promise had already been broken
 * once, silently, on the one pattern reachable from a nearly unauthenticated path. A mirror
 * nothing checks is a comment. So this suite has two halves:
 *
 *   1. BEHAVIOUR — each type with at least one positive and one negative, the check digits and
 *      Luhn doing the work they exist for, the ordering between overlapping types, and a large
 *      hyphen-dense input for the quadratic regression the header describes.
 *   2. THE MIRROR — the pattern text of this file compared, character for character, with the
 *      pattern text of `services/nlp-worker/src/nora_nlp/services/pii_shield.py`.
 *
 * The second half is the one that would have caught the historical defect, and it is the only
 * thing standing between "the two halves agree" and "the two halves agreed once".
 */
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { redactPii } from '@/lib/pii/redact';

describe('redactPii — e-mail', () => {
  it('redacts an address and leaves the surrounding text intact', () => {
    expect(redactPii('contato: ana.silva@empresa.com.br')).toBe('contato: [[EMAIL_1]]');
  });

  it('numbers each occurrence per type, in order of appearance', () => {
    expect(redactPii('ana@x.com e bruno@y.com')).toBe('[[EMAIL_1]] e [[EMAIL_2]]');
  });

  it('does not redact an address with no dotted TLD, nor a bare @', () => {
    expect(redactPii('usuario@localhost')).toBe('usuario@localhost');
    expect(redactPii('arroba @ solta')).toBe('arroba @ solta');
  });
});

describe('redactPii — BR phone', () => {
  it.each([
    ['telefone é 11 98765-4321', 'telefone é [[PHONE_1]]'],
    ['ligue +55 (11) 9 8765-4321', 'ligue [[PHONE_1]]'],
    ['ligue ( 11 ) 98765-4321', 'ligue [[PHONE_1]]'],
    ['(011) 3456-7890', '[[PHONE_1]]'],
    ['11  98765-4321', '[[PHONE_1]]'],
    ['11/98765/4321', '[[PHONE_1]]'],
  ])('redacts %j', (input, expected) => {
    expect(redactPii(input)).toBe(expected);
  });

  it('does not eat the whitespace in front of a bare-DDD number', () => {
    // Regression: an `\s*` that was not gated on the parenthesis made the bare form swallow
    // the space before it, producing "telefone é[[PHONE_1]]". The redacted text is what the
    // model reads, so a swallowed separator is a corrupted prompt as well as a wrong span.
    expect(redactPii('telefone é 11 98765-4321').startsWith('telefone é [[')).toBe(true);
  });

  it('leaves a number without a DDD alone — deliberately deferred, no check digit to lean on', () => {
    expect(redactPii('ramal 98765-4321 aqui')).toBe('ramal 98765-4321 aqui');
  });
});

describe('redactPii — CPF', () => {
  it('redacts the masked form', () => {
    expect(redactPii('CPF 111.444.777-35')).toBe('CPF [[CPF_1]]');
  });

  it.each([
    ['CPF 111 444 777 35'],
    ['CPF 111/444/777-35'],
    ['CPF 111-444-777-35'],
    ['CPF 11144477735'],
  ])('redacts the separator-tolerant and raw forms: %j', (input) => {
    expect(redactPii(input)).toBe('CPF [[CPF_1]]');
  });

  it('redacts a MASKED CPF even when the check digit is wrong', () => {
    // Deliberate, and mirrored from the worker: the `999.999.999-99` shape is unambiguous
    // enough on its own, so `CPF_RE` carries no validator. Only the ambiguous forms (raw
    // digits, arbitrary separators) have to earn redaction with a check digit.
    expect(redactPii('CPF 111.444.777-36')).toBe('CPF [[CPF_1]]');
  });

  it('rejects the trivial repeated sequence that passes the check digit arithmetic', () => {
    // `000.000.000-00` and its eleven siblings satisfy both check digits, which is why the
    // validator special-cases them. Without that guard, a run of zeros in a document number
    // or an order id would come back as a redacted CPF.
    expect(redactPii('sequencia 00000000000 aqui')).not.toContain('CPF');
    expect(redactPii('sequencia 99999999999 aqui')).not.toContain('CPF');
  });

  it('falls through to PHONE when eleven raw digits fail the check digit', () => {
    // This is the behaviour, and it is the safe direction: a number that is not a CPF is
    // still an eleven-digit BR mobile, and the shield redacts it as one rather than passing
    // it through. Asserted so that a future change to the ordering has to notice.
    expect(redactPii('CPF 11144477736')).toBe('CPF [[PHONE_1]]');
  });
});

describe('redactPii — CNPJ', () => {
  it.each([
    ['CNPJ 11.222.333/0001-81'],
    ['CNPJ 11.222.333.0001.81'],
    ['CNPJ 11 222 333 0001 81'],
    ['CNPJ 11222333000181'],
  ])('redacts %j', (input) => {
    expect(redactPii(input)).toBe('CNPJ [[CNPJ_1]]');
  });

  it('rejects the trivial repeated sequence, same reasoning as the CPF validator', () => {
    expect(redactPii('sequencia 00000000000000 aqui')).not.toContain('CNPJ');
  });

  it('leaves fourteen digits alone when the check digit does not hold', () => {
    // 14 digits also fit the Diners card shape, so this doubles as proof that Luhn rejects it
    // too — otherwise it would come back as [[CREDIT_CARD_1]].
    expect(redactPii('CNPJ 11222333000182')).toBe('CNPJ 11222333000182');
  });
});

describe('redactPii — credit card', () => {
  it.each([
    ['cartao 4111 1111 1111 1111', '16 digits'],
    ['amex 3782 822463 10005', '15 digits, Amex grouping'],
    ['diners 3056 9309 0259 04', '14 digits, Diners grouping'],
  ])('redacts %j (%s)', (input) => {
    expect(redactPii(input)).toMatch(/\[\[CREDIT_CARD_1\]\]/);
  });

  it('leaves a sixteen-digit number that fails Luhn alone', () => {
    expect(redactPii('cartao 4111 1111 1111 1112')).toBe('cartao 4111 1111 1111 1112');
  });
});

describe('redactPii — overlap, idempotence and empty input', () => {
  it('emits one placeholder per span, never two overlapping ones', () => {
    // A raw CPF matches CPF_RAW and PHONE at the same offset. The declaration order in
    // PATTERNS plus the stable sort decides, and the cursor guard drops the loser.
    const out = redactPii('11144477735');
    expect(out).toBe('[[CPF_1]]');
    expect(out).not.toContain('PHONE');
  });

  it('is stable when run over its own output', () => {
    const once = redactPii('ana@x.com ligou de 11 98765-4321');
    expect(redactPii(once)).toBe(once);
  });

  it('returns the input untouched when there is nothing to redact', () => {
    expect(redactPii('sem pii nenhum aqui')).toBe('sem pii nenhum aqui');
    expect(redactPii('')).toBe('');
  });

  it('counts each type independently', () => {
    const out = redactPii('ana@x.com, bruno@y.com, 11 98765-4321, 21 98765-4321');
    expect(out).toBe('[[EMAIL_1]], [[EMAIL_2]], [[PHONE_1]], [[PHONE_2]]');
  });
});

describe('redactPii — large hyphen-dense input', () => {
  // The e-mail local part is bounded to 64 (RFC 5321) and the header of redact.ts records
  // that the bound is also what keeps the pattern linear: `-` is not a `\w`, so the left
  // anchor succeeds after every hyphen, and an unbounded `+` rescans to the end of the string
  // from each one. The worker measured the unbounded version at 4.8 s on 154 KB inside a
  // request thread. 154 KB of alternating `a-` is that same shape.
  //
  // The budget below is ~two orders of magnitude above what this measures (single-digit
  // milliseconds), so it fails on a quadratic regression and not on a busy CI runner.
  it('stays linear', () => {
    const input = `${'a-'.repeat(77_000)} fim`;
    expect(input.length).toBeGreaterThan(150_000);

    const startedAt = performance.now();
    const output = redactPii(input);
    const elapsedMs = performance.now() - startedAt;

    expect(output).toBe(input);
    expect(elapsedMs).toBeLessThan(2_000);
  });
});

/**
 * ---------------------------------------------------------------------------------------
 * The mirror.
 *
 * Both files are read as TEXT and their pattern literals compared. Not `RegExp.source` on one
 * side and a hand-copied string on the other — a test that restates the pattern is a third
 * copy that can drift from the other two.
 *
 * The only normalisation is `\/` → `/`: a JavaScript regex literal must escape a forward slash
 * outside a character class, a Python raw string never does. Everything else has to be
 * identical, character for character.
 * ---------------------------------------------------------------------------------------
 */
const WORKER_SHIELD_PATH = fileURLToPath(
  new URL(
    '../../../../../services/nlp-worker/src/nora_nlp/services/pii_shield.py',
    import.meta.url,
  ),
);
const REDACT_PATH = fileURLToPath(new URL('./redact.ts', import.meta.url));

/**
 * Worker patterns that this file deliberately does NOT mirror. `redact.ts`'s header states the
 * reason: the heuristic PERSON_NAME machinery stays in the worker's analysis pipeline so the
 * chat path does not over-redact legitimate Title Case terms (products, projects). `VERB_TAIL`
 * is part of that machinery, not a structured-PII pattern.
 *
 * Anything that shows up in the worker and is not here and not in `redact.ts` fails the test
 * below. That is the point: DEC-16 puts an ADDRESS pattern on the worker's roadmap, and when
 * it lands, somebody has to decide in the open whether the chat path gets it too — rather than
 * the mirror quietly becoming a two-thirds mirror.
 */
const NOT_MIRRORED_BY_DESIGN = new Set(['VERB_TAIL']);

function readSource(path: string): string {
  try {
    return readFileSync(path, 'utf8');
  } catch (cause) {
    throw new Error(
      `Cannot read ${path}, which this test compares against. Both halves of the mirror are ` +
        'in this repository; if one moved, update the path — do not delete the assertion.',
      { cause },
    );
  }
}

/** `const NAME_RE = /pattern/g;`, with the literal allowed to sit on the following line. */
function typescriptPatterns(source: string): Map<string, string> {
  const found = new Map<string, string>();
  for (const m of source.matchAll(/^const (\w+)_RE\s*=\s*(?:\r?\n\s*)?\/(.+)\/g;\r?$/gm)) {
    found.set(m[1], m[2].replace(/\\\//g, '/'));
  }
  return found;
}

/** `_NAME_RE = re.compile(r"..." r"...")`, adjacent raw strings concatenated as Python does. */
function pythonPatterns(source: string): Map<string, string> {
  const found = new Map<string, string>();
  for (const m of source.matchAll(/^_(\w+)_RE = re\.compile\(\s*((?:r"[^"]*"\s*)+)\)/gm)) {
    const literals = [...m[2].matchAll(/r"([^"]*)"/g)].map((s) => s[1]);
    found.set(m[1], literals.join(''));
  }
  return found;
}

describe('the structured patterns mirror the worker PII Shield', () => {
  const ts = typescriptPatterns(readSource(REDACT_PATH));
  const py = pythonPatterns(readSource(WORKER_SHIELD_PATH));

  it('finds patterns on both sides — a mirror over zero patterns proves nothing', () => {
    // Guards the parsers themselves: if either regex above stops matching (someone reformats
    // a declaration), every per-pattern assertion below would vacuously pass.
    expect(ts.size).toBeGreaterThanOrEqual(14);
    expect(py.size).toBeGreaterThanOrEqual(14);
  });

  it('has no pattern the worker does not have', () => {
    expect([...ts.keys()].filter((name) => !py.has(name))).toEqual([]);
  });

  it('has every structured pattern the worker has, or declares why not', () => {
    const missing = [...py.keys()].filter(
      (name) => !ts.has(name) && !NOT_MIRRORED_BY_DESIGN.has(name),
    );
    expect(missing).toEqual([]);
  });

  it('matches the worker character for character on every shared pattern', () => {
    const drifted = [...ts.entries()]
      .filter(([name, pattern]) => py.get(name) !== pattern)
      .map(([name, pattern]) => `${name}\n  web:    ${pattern}\n  worker: ${py.get(name)}`);
    expect(drifted).toEqual([]);
  });
});
