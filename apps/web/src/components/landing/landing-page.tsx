import { LandingNav } from "./landing-nav";
import { LandingHero } from "./landing-hero";
import { HeroDemo } from "./hero-demo";
import { Manifesto } from "./manifesto";
import { SurfacesGrid } from "./surfaces-grid";
import { ContextDiagram } from "./context-diagram";
import { SignalsGrid } from "./signals-grid";
import { TrustStrip } from "./trust-strip";
import { LandingCta } from "./landing-cta";
import { LandingFooter } from "./landing-footer";
import styles from "./landing.module.css";

/**
 * Composicao final da landing.
 *
 * A maioria dos componentes e Server Component (sem 'use client'); apenas
 * <HeroDemo /> e <NoraLogo /> sao client por dependerem de timers/state.
 *
 * O div.page envolve tudo pra aplicar background NORA via CSS module
 * (sobrescreve o body cinza padrao do globals.css com background paper).
 */
export function LandingPage() {
  return (
    <div className={styles.page}>
      <LandingNav />
      <LandingHero />
      <HeroDemo />
      <Manifesto />
      <SurfacesGrid />
      <ContextDiagram />
      <SignalsGrid />
      <TrustStrip />
      <LandingCta />
      <LandingFooter />
    </div>
  );
}
