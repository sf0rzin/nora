import type { FocusEvent } from "react";

/**
 * Borda + shadow de foco compartilhados para inputs/textarea. Eram duplicados
 * inline em new-meeting-modal, meetings, login e overlay. Auditoria desktop #70.
 * (NÃO unifica o `inputCss` — paddings/fontSizes divergem por tela.)
 */
export function focusOn(e: FocusEvent<HTMLElement>) {
  e.currentTarget.style.borderColor = "var(--accent)";
  e.currentTarget.style.boxShadow = "0 0 0 3px var(--accent-soft)";
}

export function focusOff(e: FocusEvent<HTMLElement>) {
  e.currentTarget.style.borderColor = "var(--border)";
  e.currentTarget.style.boxShadow = "none";
}
