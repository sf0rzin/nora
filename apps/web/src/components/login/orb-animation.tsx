/**
 * OrbAnimation — 3 orbs concentricos flutuando suavemente em ciclos longos.
 *
 * Composicao visual do art panel: tres circulos de tamanhos distintos
 * (520 / 380 / 260 px), com bordas de brand-soft em opacidades decrescentes,
 * que se deslocam em loop usando `orbFloatN` keyframes definidas no CSS module.
 *
 * Aceita `children` para sobrepor conteudo centralizado (ex.: SoundwaveBars)
 * usando o mesmo flex container, como no design original. Respeita
 * `prefers-reduced-motion` via regra global em tokens.css.
 */

import type { ReactNode } from "react";
import styles from "@/app/auth/login/login.module.css";

type Props = {
  children?: ReactNode;
};

export function OrbAnimation({ children }: Props) {
  return (
    <div className={styles.orbStage} aria-hidden={children ? undefined : "true"}>
      <span className={`${styles.orb} ${styles.orb3}`} aria-hidden="true" />
      <span className={`${styles.orb} ${styles.orb1}`} aria-hidden="true" />
      <span className={`${styles.orb} ${styles.orb2}`} aria-hidden="true" />
      {children}
    </div>
  );
}
