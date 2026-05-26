import "./landing-v2.css";

import { LandingDemo } from "./landing-demo";
import { LandingFeatures } from "./landing-feature-anims";
import { LandingNav } from "./landing-nav";
import { LandingHero } from "./landing-hero";
import {
  LandingFAQ,
  LandingFinalCTA,
  LandingFooter,
  LandingHowItWorks,
  LandingPricing,
  LandingPrivacy,
} from "./landing-content";

/**
 * Landing pública v3 — port do bundle do Claude Design.
 *
 * Ordem das seções espelha `landing/app.jsx` do bundle: Nav · Hero · HowItWorks
 * · Features · Demo · Privacidade · Planos · FAQ · CTA · Footer. Estilos em
 * `landing-v2.css` (escopados sob `.nora-landing`); tokens em styles/tokens.css.
 */
export function LandingPage() {
  return (
    <div className="nora-landing">
      <LandingNav />
      <main>
        <LandingHero />
        <LandingHowItWorks />
        <LandingFeatures />
        <LandingDemo />
        <LandingPrivacy />
        <LandingPricing />
        <LandingFAQ />
        <LandingFinalCTA />
      </main>
      <LandingFooter />
    </div>
  );
}
