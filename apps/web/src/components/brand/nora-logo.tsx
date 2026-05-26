"use client";

import { useEffect, useState } from "react";

type Variant = "brand" | "paper";

type Props = {
  /** Altura total do logo em pixels (default 28). */
  size?: number;
  /** Mostra wordmark "NORA" ao lado das barras (default true). */
  showWordmark?: boolean;
  /** "brand" = barras azuis sobre fundo claro. "paper" = barras claras sobre fundo escuro. */
  variant?: Variant;
  /** Anima ao montar; quando false, já entra estático. */
  animate?: boolean;
  className?: string;
};

/**
 * NoraLogo — logomark soundwave + wordmark.
 *
 * Conceito: o produto escuta conversas e cristaliza inteligência. As 7 barras
 * verticais em alturas variadas evocam uma waveform congelada. Animação on-mount
 * faz cada barra crescer do 0 à sua altura final em cascata; o wordmark "NORA"
 * aparece ao fim, sliding suavemente da esquerda.
 *
 * Respeita `prefers-reduced-motion` via CSS global em tokens.css.
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

  // Alturas (% do size) — soundwave simétrica com passos UNIFORMES até o pico
  // central; todas as barras com a mesma espessura (pill). Ref. do PO.
  const heights = [50, 75, 100, 75, 50];

  // Rebrand v3: barras pretas (--ink). "paper" = barras claras p/ fundo escuro.
  const barColor = variant === "paper" ? "var(--canvas)" : "var(--ink)";
  const wordColor = variant === "paper" ? "var(--canvas)" : "var(--ink)";

  const barWidth = Math.max(3, Math.round(size * 0.16));
  const barGap = Math.max(2, Math.round(size * 0.13));
  const wordmarkGap = Math.round(size * 0.4);
  const wordmarkSize = Math.round(size * 0.95);
  const wordmarkDelayMs = heights.length * 60 + 80;

  return (
    <span
      className={`inline-flex items-center ${className}`}
      style={{ height: size, gap: wordmarkGap }}
      aria-label="NORA"
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
          NORA
        </span>
      )}
    </span>
  );
}
