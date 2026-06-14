interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  /** Padding maior (20x22) para cards de seção. */
  pad?: boolean;
}

/**
 * Container primitivo: borda + raio (--radius-md) + fundo canvas. Substitui os
 * cards com border-radius/padding inventados por tela (8/10/12 ad-hoc).
 */
export function Card({ pad, className = "", ...rest }: CardProps) {
  const cls = ["ui-card", pad ? "ui-card--pad" : "", className].filter(Boolean).join(" ");
  return <div className={cls} {...rest} />;
}
