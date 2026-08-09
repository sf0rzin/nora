import type { FocusEvent } from "react";

/**
 * Shared focus border + shadow for inputs/textarea. They were duplicated
 * inline in new-meeting-modal, meetings, login and overlay. Desktop audit #70.
 * (Does NOT unify `inputCss` — paddings/fontSizes diverge per screen.)
 */
export function focusOn(e: FocusEvent<HTMLElement>) {
  e.currentTarget.style.borderColor = "var(--accent)";
  e.currentTarget.style.boxShadow = "0 0 0 3px var(--accent-soft)";
}

export function focusOff(e: FocusEvent<HTMLElement>) {
  e.currentTarget.style.borderColor = "var(--border)";
  e.currentTarget.style.boxShadow = "none";
}
