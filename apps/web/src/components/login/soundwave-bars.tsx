/**
 * SoundwaveBars — 13 barras animadas em cascata, evocando uma waveform viva.
 *
 * Usado no art panel da tela de login, sobreposto aos orbs flutuantes.
 * Cada barra cresce/encolhe em `waveBeat` com delays simétricos (palindromo)
 * pra produzir uma onda que se propaga do centro para as bordas.
 *
 * Cor padrao herda do parent via `currentColor`/CSS vars no module — o componente
 * apenas estrutura os elementos. Animacao respeita `prefers-reduced-motion`
 * via regra global em tokens.css.
 */

import styles from "@/app/auth/login/login.module.css";

// Palindromo de delays (s) — produz onda simetrica que parte do centro.
const BAR_DELAYS = [
  "0.00s",
  "0.08s",
  "0.16s",
  "0.24s",
  "0.32s",
  "0.40s",
  "0.48s",
  "0.40s",
  "0.32s",
  "0.24s",
  "0.16s",
  "0.08s",
  "0.00s",
] as const;

export function SoundwaveBars() {
  return (
    <div className={styles.wave} aria-hidden="true">
      {BAR_DELAYS.map((delay, i) => (
        <span key={i} style={{ animationDelay: delay }} />
      ))}
    </div>
  );
}
