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
 * Public landing v3 — port of the Claude Design bundle.
 *
 * Section order mirrors the bundle's `landing/app.jsx`: Nav · Hero · HowItWorks
 * · Features · Demo · Privacy · Pricing · FAQ · CTA · Footer. Styles in
 * `landing-v2.css` (scoped under `.nora-landing`); tokens in styles/tokens.css.
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
