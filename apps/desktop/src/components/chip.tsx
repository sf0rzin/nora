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
 * Shared pill/chip (borderRadius 999) — they were inline spans repeated in
 * meeting-detail and meetings. Preserves the exact fontSize/padding per call site.
 * Desktop audit #66.
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
