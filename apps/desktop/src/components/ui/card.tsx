interface CardProps extends React.HTMLAttributes<HTMLDivElement> {
  /** Larger padding (20x22) for section cards. */
  pad?: boolean;
}

/**
 * Container primitive: border + radius (--radius-md) + canvas background. Replaces
 * the cards with border-radius/padding invented per screen (ad-hoc 8/10/12).
 */
export function Card({ pad, className = "", ...rest }: CardProps) {
  const cls = ["ui-card", pad ? "ui-card--pad" : "", className].filter(Boolean).join(" ");
  return <div className={cls} {...rest} />;
}
