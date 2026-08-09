/**
 * NORA Core — Generated avatar (organic blurred gradient, deterministic).
 *
 * "Out-of-focus macro photography" style: 3 radial-gradients layered over a
 * base color + heavy blur, all clipped to the circle. No external asset and no
 * randomness at runtime: an FNV-1a hash of the seed (user e-mail/id) picks a
 * curated palette and a variation of the gradient positions — the same user
 * always sees the same avatar, on any surface of the app.
 *
 * Server-safe (no hooks/effects): can be used in Server and Client
 * Components.
 */

import type { CSSProperties } from "react";

/** Palette: [base, blob 1, blob 2, blob 3] — spirit of the PO's references
 *  (coral/pink, blues, greens, lavender, amber, teal, rosé, sky). */
const PALETAS: ReadonlyArray<readonly [string, string, string, string]> = [
  // coral/pink with breathers of blue and yellow (ex1)
  ["#ef9fb4", "#f8c39c", "#85b8dc", "#f6df9d"],
  // deep blues with cold light (ex2)
  ["#4a8fd8", "#7fc3ee", "#2b6ac0", "#aedcf2"],
  // fresh green touching on blue (ex3)
  ["#8cc678", "#c3e09b", "#67a8d8", "#5f9e54"],
  // lavanda
  ["#b3a3dd", "#d9c9f0", "#88a6e8", "#ead9ea"],
  // amber
  ["#e6b569", "#f3d59c", "#d88c4c", "#f8e8c2"],
  // teal
  ["#56b5ad", "#8cdad2", "#3a8a9c", "#c2e9e1"],
  // rosé
  ["#e6a59c", "#f3c9c1", "#c47b92", "#f8e1d8"],
  // sky
  ["#86b6e6", "#bcdaf3", "#6890c8", "#dceaf8"],
];

/** Composition variations: centers of the 3 blobs over the base. */
const COMPOSICOES: ReadonlyArray<readonly [string, string, string]> = [
  ["22% 24%", "80% 64%", "58% 96%"],
  ["74% 22%", "20% 70%", "92% 88%"],
  ["30% 78%", "78% 30%", "10% 28%"],
  ["62% 14%", "16% 44%", "82% 92%"],
];

/** FNV-1a 32-bit hash — stable between server and client. */
function hashSeed(seed: string): number {
  let h = 0x811c9dc5;
  for (let i = 0; i < seed.length; i++) {
    h ^= seed.charCodeAt(i);
    h = Math.imul(h, 0x01000193);
  }
  return h >>> 0;
}

export function Avatar({
  seed,
  size = 28,
  className,
  style,
}: {
  /** Stable user identity (e-mail/id). Empty → neutral blue palette. */
  seed?: string | null;
  /** Diameter in px. */
  size?: number;
  className?: string;
  style?: CSSProperties;
}) {
  const h = hashSeed((seed ?? "").trim().toLowerCase());
  const paleta = seed ? PALETAS[h % PALETAS.length] : PALETAS[1];
  const [base, m1, m2, m3] = paleta;
  const [p1, p2, p3] = COMPOSICOES[(h >>> 3) % COMPOSICOES.length];

  // Blur proportional to the diameter; inner layer larger than the circle so
  // the blur edge never shows up inside the clip.
  const blur = Math.max(5, Math.round(size * 0.2));

  return (
    <div
      aria-hidden
      className={className}
      style={{
        width: size,
        height: size,
        borderRadius: "50%",
        flexShrink: 0,
        position: "relative",
        overflow: "hidden",
        boxShadow: "0 1px 2px rgba(0, 0, 0, 0.06)",
        ...style,
      }}
    >
      <div
        style={{
          position: "absolute",
          inset: "-35%",
          background: [
            `radial-gradient(circle at ${p1}, ${m1} 0%, transparent 58%)`,
            `radial-gradient(circle at ${p2}, ${m2} 0%, transparent 62%)`,
            `radial-gradient(circle at ${p3}, ${m3} 0%, transparent 52%)`,
            base,
          ].join(", "),
          filter: `blur(${blur}px) saturate(1.12)`,
        }}
      />
      {/* subtle sheen on top of the blur (finish from the old .user-orb) */}
      <div
        style={{
          position: "absolute",
          inset: 0,
          borderRadius: "50%",
          boxShadow: "inset 0 0 6px rgba(255, 255, 255, 0.35)",
        }}
      />
    </div>
  );
}
