"use client";

import { useReveal } from "./use-reveal";
import { useMagnetic } from "./use-magnetic";
import { LandingNav } from "./landing-nav";
import { HeroLiveDemo } from "./hero-live-demo";
import { ProblemSection } from "./problem-section";
import { SurfacesSection } from "./surfaces-section";
import { ProductContextSection } from "./product-context-section";
import { LandingFooter } from "./landing-footer";
import styles from "./landing.module.css";

/**
 * Composição final da landing v2 — espelha App() de nora-app.jsx
 * (linhas 967-983). Wrapper client porque executa useReveal/useMagnetic
 * (DOM queries via IntersectionObserver e mousemove listeners).
 *
 * Seções restantes (HealthScore, IAM, CTA) são adicionadas em commits
 * subsequentes.
 */
export function LandingPage() {
  useReveal();
  useMagnetic();

  return (
    <div className={styles.page}>
      <LandingNav />
      <main>
        <HeroLiveDemo />
        <hr className="hr container" />
        <div className="reveal">
          <ProblemSection />
        </div>
        <hr className="hr container" />
        <div className="reveal">
          <SurfacesSection />
        </div>
        <hr className="hr container" />
        <div className="reveal">
          <ProductContextSection />
        </div>
      </main>
      <LandingFooter />
    </div>
  );
}
