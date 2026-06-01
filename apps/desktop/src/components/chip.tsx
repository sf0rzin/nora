import type { ReactNode } from "react";

interface ChipProps {
  children: ReactNode;
  /** neutral = chip/ink · accent = accent-soft/accent-ink. */
  variant?: "neutral" | "accent";
  fontSize?: number;
  padding?: string;
  className?: string;
}

/**
 * Pill/chip (borderRadius 999) compartilhado — eram spans inline repetidos em
 * meeting-detail e meetings. Preserva fontSize/padding exatos por call site.
 * Auditoria desktop #66.
 */
export function Chip({
  children,
  variant = "neutral",
  fontSize = 12,
  padding = "3px 9px",
  className,
}: ChipProps) {
  const colors =
    variant === "accent"
      ? { background: "var(--accent-soft)", color: "var(--accent-ink)" }
      : { background: "var(--chip)", color: "var(--ink)" };
  return (
    <span className={className} style={{ fontSize, padding, borderRadius: 999, ...colors }}>
      {children}
    </span>
  );
}
