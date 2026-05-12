import { LandingNav } from "./landing-nav";
import { LandingHero } from "./landing-hero";
import { LandingFooter } from "./landing-footer";
import styles from "./landing.module.css";

/**
 * Composicao final da landing.
 *
 * Estrutura inicial: nav + hero + footer. Demais secoes (demo, manifesto,
 * surfaces, context, signals, trust, cta) entram nos commits subsequentes
 * pra manter cada commit verificavel (lint + typecheck + build verdes).
 *
 * O div.page envolve tudo pra aplicar background NORA via CSS module
 * (sobrescreve o body cinza padrao do globals.css com background paper).
 */
export function LandingPage() {
  return (
    <div className={styles.page}>
      <LandingNav />
      <LandingHero />
      <LandingFooter />
    </div>
  );
}
