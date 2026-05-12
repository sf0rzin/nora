import { LandingNav } from "./landing-nav";
import { LandingHero } from "./landing-hero";
import { HeroDemo } from "./hero-demo";
import { LandingFooter } from "./landing-footer";
import styles from "./landing.module.css";

/**
 * Composicao final da landing.
 *
 * Estrutura atual: nav + hero + demo ao vivo + footer. Demais secoes
 * (manifesto, surfaces, context, signals, trust, cta) entram nos commits
 * subsequentes pra manter cada commit verificavel.
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
      <LandingFooter />
    </div>
  );
}
