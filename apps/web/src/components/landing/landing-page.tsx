"use client";

import { useReveal } from "./use-reveal";
import { useMagnetic } from "./use-magnetic";
import { LandingNav } from "./landing-nav";
import { LandingFooter } from "./landing-footer";
import styles from "./landing.module.css";

/**
 * Composição final da landing v2 — espelha App() de nora-app.jsx
 * (linhas 967-983). Wrapper client porque executa useReveal/useMagnetic
 * (DOM queries via IntersectionObserver e mousemove listeners).
 *
 * As seções (Hero, Problem, Surfaces, ProductContext, HealthScore, IAM, CTA)
 * são adicionadas em commits subsequentes — este commit estabelece apenas a
 * estrutura base.
 */
export function LandingPage() {
  useReveal();
  useMagnetic();

  return (
    <div className={styles.page}>
      <LandingNav />
      <main />
      <LandingFooter />
    </div>
  );
}
