/**
 * Política de senha do client web — espelha
 * `br.com.nora.api.domain.identity.PasswordPolicy` (fonte da verdade no backend).
 * Fonte única pra signup, reset de senha e aceite de convite não divergirem.
 */

export const PASSWORD_MIN = 10;
export const PASSWORD_MAX = 128;

/** Retorna mensagem de erro (pt-BR) ou `null` se a senha satisfaz a policy. */
export function validatePassword(raw: string): string | null {
  if (raw.length < PASSWORD_MIN || raw.length > PASSWORD_MAX) {
    return `A senha deve ter entre ${PASSWORD_MIN} e ${PASSWORD_MAX} caracteres.`;
  }
  let hasLetter = false;
  let hasDigit = false;
  for (const c of raw) {
    if (/[A-Za-z]/.test(c)) hasLetter = true;
    else if (/[0-9]/.test(c)) hasDigit = true;
    if (hasLetter && hasDigit) break;
  }
  if (!hasLetter || !hasDigit) return "A senha deve conter letras e números.";
  return null;
}

/** Conveniência booleana pra gating de UI (botões "continuar"). */
export function isPasswordValid(raw: string): boolean {
  return validatePassword(raw) === null;
}
