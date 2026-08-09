"use client";

import { useEffect, useState } from "react";

type Variant = "brand" | "paper";

type Props = {
  /** Total logo height in pixels (default 28). */
  size?: number;
  /** Shows the "NORA" wordmark next to the bars (default true). */
  showWordmark?: boolean;
  /** "brand" = blue bars on a light background. "paper" = light bars on a dark background. */
  variant?: Variant;
  /** Animates on mount; when false, it comes in static. */
  animate?: boolean;
  className?: string;
};

/**
 * NoraLogo — soundwave logomark + wordmark.
 *
 * Concept: the product listens to conversations and crystallizes intelligence.
 * The 7 vertical bars at varying heights evoke a frozen waveform. The on-mount
 * animation grows each bar from 0 to its final height in a cascade; the "NORA"
 * wordmark appears at the end, sliding smoothly in from the left.
 *
 * Respects `prefers-reduced-motion` via global CSS in tokens.css.
 */
export function NoraLogo({
  size = 28,
  showWordmark = true,
  variant = "brand",
  animate = true,
  className = "",
}: Props) {
  const [mounted, setMounted] = useState(!animate);

  useEffect(() => {
    if (!animate) return;
    const t = setTimeout(() => setMounted(true), 60);
    return () => clearTimeout(t);
  }, [animate]);

  // Heights (% of size) — symmetric soundwave with UNIFORM steps up to the
  // central peak; every bar with the same thickness (pill). PO's reference.
  const heights = [50, 75, 100, 75, 50];

  // Rebrand v3: black bars (--ink). "paper" = light bars for dark background.
  const barColor = variant === "paper" ? "var(--canvas)" : "var(--ink)";
  const wordColor = variant === "paper" ? "var(--canvas)" : "var(--ink)";

  const barWidth = Math.max(2, Math.round(size * 0.12));
  const barGap = Math.max(2, Math.round(size * 0.1));
  const wordmarkGap = Math.round(size * 0.4);
  const wordmarkSize = Math.round(size * 0.95);
  const wordmarkDelayMs = heights.length * 60 + 80;

  return (
    <span
      className={`inline-flex items-center ${className}`}
      style={{ height: size, gap: wordmarkGap }}
      aria-label="Nora"
      role="img"
    >
      <span
        style={{
          display: "inline-flex",
          alignItems: "center",
          gap: barGap,
          height: size,
        }}
        aria-hidden="true"
      >
        {heights.map((h, i) => (
          <span
            key={i}
            style={{
              display: "block",
              width: barWidth,
              height: mounted ? `${h}%` : "0%",
              background: barColor,
              borderRadius: barWidth / 2,
              transition: `height 0.42s var(--ease-out-expo) ${i * 60}ms`,
              willChange: "height",
            }}
          />
        ))}
      </span>
      {showWordmark && (
        <span
          style={{
            fontFamily: "var(--font-sans), system-ui, sans-serif",
            fontWeight: 600,
            fontSize: wordmarkSize,
            letterSpacing: "-0.01em",
            color: wordColor,
            lineHeight: 1,
            opacity: mounted ? 1 : 0,
            transform: mounted ? "translateX(0)" : "translateX(-8px)",
            transition: `opacity 0.5s var(--ease-out-expo) ${wordmarkDelayMs}ms, transform 0.5s var(--ease-out-expo) ${wordmarkDelayMs}ms`,
          }}
        >
          Nora
        </span>
      )}
    </span>
  );
}
