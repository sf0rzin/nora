interface SpinnerProps {
  size?: number;
  thickness?: number;
  /** Cor do anel (track). */
  color?: string;
  /** Cor do arco ativo (borderTop). */
  topColor?: string;
}

/**
 * Spinner circular (animação nora-spin) — substitui vários spans inline
 * idênticos. Defaults batem com o uso mais comum; cada call site preserva
 * seu size/cor/espessura exatos. Auditoria desktop #72.
 */
export function Spinner({
  size = 12,
  thickness = 2,
  color = "var(--chip)",
  topColor = "var(--accent)",
}: SpinnerProps) {
  return (
    <span
      style={{
        width: size,
        height: size,
        border: `${thickness}px solid ${color}`,
        borderTopColor: topColor,
        borderRadius: "50%",
        animation: "nora-spin 0.9s linear infinite",
      }}
    />
  );
}
