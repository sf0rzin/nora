/**
 * Deterministic PII (BR) redaction for the BFF — last gate before sending
 * any user/context text to an external LLM provider (ADR 0012).
 *
 * Ports the STRUCTURED patterns of the worker's PII Shield
 * (`services/nlp-worker/src/nora_nlp/services/pii_shield.py`): e-mail, phone,
 * CPF, CNPJ and card — with check-digit validation (CPF/CNPJ) and Luhn
 * (card) to minimize false positives. Each occurrence becomes `[[TIPO_N]]`.
 *
 * Scope: covers the structured PII with the highest LGPD risk with ~zero false positives.
 * The heuristic PERSON_NAME redaction (name list + negative list) stays
 * in the worker's analysis pipeline — it is deliberately not duplicated here, so as not
 * to over-redact legitimate Title Case terms in the chat (products, projects, etc.).
 */

type PiiType = "EMAIL" | "PHONE" | "CPF" | "CNPJ" | "CREDIT_CARD";

// Patterns mirrored 1:1 from the worker. Flag `g` required for matchAll (which clones
// the regex internally — safe to reuse the module instance across calls).
const EMAIL_RE = /(?<![\w@])[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}\b/g;
// BR phone (audit 2026-06-16): DDD required (a phone has no check digit).
// Tolerates the +55 prefix, parentheses with an inner space "( 11 )", DDD with a zero "(011)",
// the mobile's 9th digit dictated LOOSE "(11) 9 8765-4321" and the "/" separator.
// DEFERRED: phone WITHOUT DDD and non-BR international (+1) — high FP risk without a check digit.
//
// The parenthesised and bare forms are ALTERNATIVES, and the DDD/number separator allows up to
// two characters. Written as `\(?\s*0?\d{2}\s*\)?[\s.\-/]?` the inner `\s*` was not gated on the
// parenthesis, so a bare number ate the space in front of it ("telefone e[[PHONE_1]]"), and one
// separator was too few for "11  98765-4321" — which this file then forwarded raw to the
// embeddings and chat providers while the worker's shield redacted the identical string. Keep
// this in step with `_PHONE_RE` in the worker; the whole point of the file is that it is a
// mirror, and it silently stopped being one.
const PHONE_RE =
  /(?<!\d)(?:\+?55[\s.\-]?)?(?:\(\s*0?\d{2}\s*\)|0?\d{2}\)?)[\s.\-/]{0,3}(?:9[\s.\-/]?)?\d{4,5}[\s.\-/]?\d{4}(?!\d)/g;
const CPF_RE = /(?<!\d)\d{3}\.\d{3}\.\d{3}-\d{2}(?!\d)/g;
const CPF_PARTIAL_RE = /(?<!\d)\d{8}-\d{2}(?!\d)/g;
const CPF_SPACED_RE = /(?<!\d)\d{3}\s\d{3}\s\d{3}\s\d{2}(?!\d)/g;
// CPF tolerant of an arbitrary separator (3-3-3-2 with [.\-/\s] between each group):
// "111.444.777 35", "111/444/777-35", "111-444-777-35". The check digit is the gate.
const CPF_SEP_RE = /(?<!\d)\d{3}[.\-/\s]\d{3}[.\-/\s]\d{3}[.\-/\s]\d{2}(?!\d)/g;
const CNPJ_RE = /(?<!\d)\d{2}\.\d{3}\.\d{3}\/\d{4}-\d{2}(?!\d)/g;
const CNPJ_SPACED_RE = /(?<!\d)\d{2}\s\d{3}\s\d{3}\s\d{4}\s\d{2}(?!\d)/g;
// CNPJ with a dot between all groups (2.3.3.4.2): "11.222.333.0001.81". The check digit is the gate.
const CNPJ_DOTS_RE = /(?<!\d)\d{2}\.\d{3}\.\d{3}\.\d{4}\.\d{2}(?!\d)/g;
const CNPJ_RAW_RE = /(?<!\d)\d{14}(?!\d)/g;
const CPF_RAW_RE = /(?<!\d)\d{11}(?!\d)/g;
const CARD_AMEX_RE = /(?<!\d)3[47]\d{2}[\s.\-]?\d{6}[\s.\-]?\d{5}(?!\d)/g;
const CARD_RE = /(?<!\d)(?:\d{4}[\s.\-]?){3}\d{4}(?!\d)/g;
// Diners Club (and some UnionPay): 14 digits in 4-4-4-2 groups
// "3056 9309 0259 04". Luhn (validateCard, now accepting len 14) is the gate.
const CARD_DINERS_RE = /(?<!\d)\d{4}[\s.\-]?\d{4}[\s.\-]?\d{4}[\s.\-]?\d{2}(?!\d)/g;

function stripSeparators(value: string): string {
  return value.replace(/\D/g, "");
}

/** Validates an 11-digit CPF via its check digit. Rejects trivial sequences (000... etc.). */
function validateCpf(digits: string): boolean {
  if (digits.length !== 11 || !/^\d+$/.test(digits)) return false;
  if (digits === digits[0].repeat(11)) return false;
  for (const j of [9, 10]) {
    let sum = 0;
    for (let i = 0; i < j; i++) sum += parseInt(digits[i], 10) * (j + 1 - i);
    let d = (sum * 10) % 11;
    if (d === 10) d = 0;
    if (d !== parseInt(digits[j], 10)) return false;
  }
  return true;
}

/** Validates a 14-digit CNPJ via its check digit. */
function validateCnpj(digits: string): boolean {
  if (digits.length !== 14 || !/^\d+$/.test(digits)) return false;
  if (digits === digits[0].repeat(14)) return false;
  const weights1 = [5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
  const weights2 = [6, 5, 4, 3, 2, 9, 8, 7, 6, 5, 4, 3, 2];
  for (const [j, weights] of [
    [12, weights1],
    [13, weights2],
  ] as Array<[number, number[]]>) {
    let sum = 0;
    for (let i = 0; i < j; i++) sum += parseInt(digits[i], 10) * weights[i];
    let d = sum % 11;
    d = d < 2 ? 0 : 11 - d;
    if (d !== parseInt(digits[j], 10)) return false;
  }
  return true;
}

/** Luhn check digit (mod 10). */
function luhnOk(digits: string): boolean {
  let total = 0;
  const reversed = digits.split("").reverse();
  for (let i = 0; i < reversed.length; i++) {
    let n = parseInt(reversed[i], 10);
    if (i % 2 === 1) {
      n *= 2;
      if (n > 9) n -= 9;
    }
    total += n;
  }
  return total % 10 === 0;
}

/** Card candidate: 14 (Diners), 15 (Amex) or 16 digits + valid Luhn. */
function validateCard(value: string): boolean {
  const digits = stripSeparators(value);
  return (digits.length === 14 || digits.length === 15 || digits.length === 16) && luhnOk(digits);
}

// Order matters (same as the worker): masked/cards first, then raw with a check digit,
// phone (most ambiguous) last. On a position tie, the type declared earlier
// wins (stable sort) — e.g. CPF_RAW before PHONE on the same 11-digit range.
const PATTERNS: Array<{ type: PiiType; re: RegExp; validate?: (raw: string) => boolean }> = [
  { type: "EMAIL", re: EMAIL_RE },
  { type: "CPF", re: CPF_RE },
  { type: "CPF", re: CPF_PARTIAL_RE },
  { type: "CPF", re: CPF_SPACED_RE, validate: (v) => validateCpf(stripSeparators(v)) },
  { type: "CPF", re: CPF_SEP_RE, validate: (v) => validateCpf(stripSeparators(v)) },
  { type: "CNPJ", re: CNPJ_RE },
  { type: "CNPJ", re: CNPJ_SPACED_RE, validate: (v) => validateCnpj(stripSeparators(v)) },
  { type: "CNPJ", re: CNPJ_DOTS_RE, validate: (v) => validateCnpj(stripSeparators(v)) },
  { type: "CREDIT_CARD", re: CARD_AMEX_RE, validate: validateCard },
  { type: "CREDIT_CARD", re: CARD_RE, validate: validateCard },
  { type: "CREDIT_CARD", re: CARD_DINERS_RE, validate: validateCard },
  { type: "CNPJ", re: CNPJ_RAW_RE, validate: validateCnpj },
  { type: "CPF", re: CPF_RAW_RE, validate: validateCpf },
  { type: "PHONE", re: PHONE_RE },
];

interface Match {
  type: PiiType;
  start: number;
  end: number;
}

/**
 * Replaces structured PII with `[[TIPO_N]]` placeholders. Idempotent enough
 * for the chat path (placeholders do not match again). Returns the original text
 * when empty/without PII.
 */
export function redactPii(text: string): string {
  if (!text) return text;

  const matches: Match[] = [];
  for (const { type, re, validate } of PATTERNS) {
    for (const m of text.matchAll(re)) {
      const raw = m[0];
      if (validate && !validate(raw)) continue;
      const start = m.index ?? 0;
      matches.push({ type, start, end: start + raw.length });
    }
  }
  matches.sort((a, b) => a.start - b.start);

  const counters: Record<string, number> = {};
  const parts: string[] = [];
  let cursor = 0;
  for (const m of matches) {
    if (m.start < cursor) continue; // overlap between types — ignore the second
    counters[m.type] = (counters[m.type] ?? 0) + 1;
    parts.push(text.slice(cursor, m.start));
    parts.push(`[[${m.type}_${counters[m.type]}]]`);
    cursor = m.end;
  }
  parts.push(text.slice(cursor));
  return parts.join("");
}
