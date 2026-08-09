interface SpinnerProps {
  size?: number;
  thickness?: number;
  /** Ring (track) color. */
  color?: string;
  /** Active arc color (borderTop). */
  topColor?: string;
}

/**
 * Circular spinner (nora-spin animation) — replaces several identical inline
 * spans. Defaults match the most common usage; each call site keeps its
 * exact size/color/thickness. Desktop audit #72.
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
