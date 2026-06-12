/**
 * NORA Core — Avatar gerado (gradiente orgânico desfocado, determinístico).
 *
 * Estilo "macro fotografia fora de foco": 3 radial-gradients sobrepostos numa
 * cor base + blur pesado, tudo clipado no círculo. Sem asset externo e sem
 * aleatoriedade em runtime: um hash FNV-1a do seed (e-mail/id do usuário)
 * escolhe uma paleta curada e uma variação de posição dos gradientes — o
 * mesmo usuário sempre vê o mesmo avatar, em qualquer superfície do app.
 *
 * Server-safe (sem hooks/efeitos): pode ser usado em Server e Client
 * Components.
 */

import type { CSSProperties } from "react";

/** Paleta: [base, mancha 1, mancha 2, mancha 3] — espírito das referências
 *  do PO (coral/rosa, azuis, verdes, lavanda, âmbar, teal, rosé, céu). */
const PALETAS: ReadonlyArray<readonly [string, string, string, string]> = [
  // coral/rosa com respiros de azul e amarelo (ex1)
  ["#ef9fb4", "#f8c39c", "#85b8dc", "#f6df9d"],
  // azuis profundos com luz fria (ex2)
  ["#4a8fd8", "#7fc3ee", "#2b6ac0", "#aedcf2"],
  // verde fresco encostando no azul (ex3)
  ["#8cc678", "#c3e09b", "#67a8d8", "#5f9e54"],
  // lavanda
  ["#b3a3dd", "#d9c9f0", "#88a6e8", "#ead9ea"],
  // âmbar
  ["#e6b569", "#f3d59c", "#d88c4c", "#f8e8c2"],
  // teal
  ["#56b5ad", "#8cdad2", "#3a8a9c", "#c2e9e1"],
  // rosé
  ["#e6a59c", "#f3c9c1", "#c47b92", "#f8e1d8"],
  // céu
  ["#86b6e6", "#bcdaf3", "#6890c8", "#dceaf8"],
];

/** Variações de composição: centros das 3 manchas sobre a base. */
const COMPOSICOES: ReadonlyArray<readonly [string, string, string]> = [
  ["22% 24%", "80% 64%", "58% 96%"],
  ["74% 22%", "20% 70%", "92% 88%"],
  ["30% 78%", "78% 30%", "10% 28%"],
  ["62% 14%", "16% 44%", "82% 92%"],
];

/** Hash FNV-1a 32-bit — estável entre server e client. */
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
  /** Identidade estável do usuário (e-mail/id). Vazio → paleta neutra azul. */
  seed?: string | null;
  /** Diâmetro em px. */
  size?: number;
  className?: string;
  style?: CSSProperties;
}) {
  const h = hashSeed((seed ?? "").trim().toLowerCase());
  const paleta = seed ? PALETAS[h % PALETAS.length] : PALETAS[1];
  const [base, m1, m2, m3] = paleta;
  const [p1, p2, p3] = COMPOSICOES[(h >>> 3) % COMPOSICOES.length];

  // Blur proporcional ao diâmetro; camada interna maior que o círculo pra
  // borda do desfoque nunca aparecer dentro do clip.
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
      {/* brilho sutil por cima do desfoque (acabamento do .user-orb antigo) */}
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
