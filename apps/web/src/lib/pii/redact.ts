/**
 * Redação determinística de PII (BR) para o BFF — último gate antes de mandar
 * qualquer texto do usuário/contexto para um provedor de LLM externo (ADR 0012).
 *
 * Porta os padrões ESTRUTURADOS do PII Shield do worker
 * (`services/nlp-worker/src/nora_nlp/services/pii_shield.py`): e-mail, telefone,
 * CPF, CNPJ e cartão — com validação de dígito verificador (CPF/CNPJ) e Luhn
 * (cartão) para minimizar falso-positivo. Cada ocorrência vira `[[TIPO_N]]`.
 *
 * Escopo: cobre a PII estruturada de maior risco LGPD com falso-positivo ~zero.
 * A redação heurística de PERSON_NAME (lista de nomes + negative list) continua
 * no pipeline de análise do worker — não é duplicada aqui de propósito, para não
 * over-redigir termos Title Case legítimos no chat (produtos, projetos, etc.).
 */

type PiiType = "EMAIL" | "PHONE" | "CPF" | "CNPJ" | "CREDIT_CARD";

// Padrões espelhados 1:1 do worker. Flag `g` obrigatória para matchAll (que clona
// o regex internamente — seguro reutilizar a instância de módulo entre chamadas).
const EMAIL_RE = /(?<![\w@])[a-zA-Z0-9._%+\-]+@[a-zA-Z0-9.\-]+\.[a-zA-Z]{2,}\b/g;
const PHONE_RE = /(?<!\d)(?:\+?55\s?)?\(?\d{2}\)?[\s.\-]?\d{4,5}[\s.\-]?\d{4}(?!\d)/g;
const CPF_RE = /(?<!\d)\d{3}\.\d{3}\.\d{3}-\d{2}(?!\d)/g;
const CPF_PARTIAL_RE = /(?<!\d)\d{8}-\d{2}(?!\d)/g;
const CPF_SPACED_RE = /(?<!\d)\d{3}\s\d{3}\s\d{3}\s\d{2}(?!\d)/g;
const CNPJ_RE = /(?<!\d)\d{2}\.\d{3}\.\d{3}\/\d{4}-\d{2}(?!\d)/g;
const CNPJ_SPACED_RE = /(?<!\d)\d{2}\s\d{3}\s\d{3}\s\d{4}\s\d{2}(?!\d)/g;
const CNPJ_RAW_RE = /(?<!\d)\d{14}(?!\d)/g;
const CPF_RAW_RE = /(?<!\d)\d{11}(?!\d)/g;
const CARD_AMEX_RE = /(?<!\d)3[47]\d{2}[\s.\-]?\d{6}[\s.\-]?\d{5}(?!\d)/g;
const CARD_RE = /(?<!\d)(?:\d{4}[\s.\-]?){3}\d{4}(?!\d)/g;

function stripSeparators(value: string): string {
  return value.replace(/\D/g, "");
}

/** Valida CPF de 11 dígitos via DV. Rejeita sequências triviais (000... etc.). */
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

/** Valida CNPJ de 14 dígitos via DV. */
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

/** Dígito verificador de Luhn (mod 10). */
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

/** Candidato a cartão: 15 (Amex) ou 16 dígitos + Luhn válido (após normalizar). */
function validateCard(value: string): boolean {
  const digits = stripSeparators(value);
  return (digits.length === 15 || digits.length === 16) && luhnOk(digits);
}

// Ordem importa (igual ao worker): mascarados/cartões primeiro, depois raw com DV,
// telefone (mais ambíguo) por último. Em empate de posição, o tipo declarado antes
// vence (sort estável) — ex.: CPF_RAW antes de PHONE no mesmo range de 11 dígitos.
const PATTERNS: Array<{ type: PiiType; re: RegExp; validate?: (raw: string) => boolean }> = [
  { type: "EMAIL", re: EMAIL_RE },
  { type: "CPF", re: CPF_RE },
  { type: "CPF", re: CPF_PARTIAL_RE },
  { type: "CPF", re: CPF_SPACED_RE, validate: (v) => validateCpf(stripSeparators(v)) },
  { type: "CNPJ", re: CNPJ_RE },
  { type: "CNPJ", re: CNPJ_SPACED_RE, validate: (v) => validateCnpj(stripSeparators(v)) },
  { type: "CREDIT_CARD", re: CARD_AMEX_RE, validate: validateCard },
  { type: "CREDIT_CARD", re: CARD_RE, validate: validateCard },
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
 * Substitui PII estruturada por placeholders `[[TIPO_N]]`. Idempotente o suficiente
 * para o caminho do chat (placeholders não voltam a casar). Retorna o texto original
 * quando vazio/sem PII.
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
    if (m.start < cursor) continue; // overlap entre tipos — ignora o segundo
    counters[m.type] = (counters[m.type] ?? 0) + 1;
    parts.push(text.slice(cursor, m.start));
    parts.push(`[[${m.type}_${counters[m.type]}]]`);
    cursor = m.end;
  }
  parts.push(text.slice(cursor));
  return parts.join("");
}
