"use client";

import { useReveal } from "./use-reveal";
import { useMagnetic } from "./use-magnetic";
import { LandingNav } from "./landing-nav";
import { HeroLiveDemo } from "./hero-live-demo";
import { ProblemSection } from "./problem-section";
import { SurfacesSection } from "./surfaces-section";
import { ProductContextSection } from "./product-context-section";
import { HealthScoreSection } from "./health-score-section";
import { IAMSection } from "./iam-section";
import { CTASection } from "./cta-section";
import { LandingFooter } from "./landing-footer";
import styles from "./landing.module.css";

/**
 * Composição final da landing v2 — espelha App() de nora-app.jsx
 * (linhas 967-983). Wrapper client porque executa useReveal/useMagnetic
 * (DOM queries via IntersectionObserver e mousemove listeners).
 *
 * Ordem: Nav · Hero · hr · Problem · hr · Surfaces · hr · ProductContext
 *      · hr · HealthScore · hr · IAM · CTA · Footer.
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
        <hr className="hr container" />
        <div className="reveal">
          <HealthScoreSection />
        </div>
        <hr className="hr container" />
        <div className="reveal">
          <IAMSection />
        </div>
        <div className="reveal">
          <CTASection />
        </div>
      </main>
      <LandingFooter />
    </div>
  );
}
