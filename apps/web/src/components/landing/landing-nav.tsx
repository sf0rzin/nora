import Link from "next/link";
import { NoraLogo } from "@/components/brand/nora-logo";
import styles from "./landing.module.css";

/**
 * Sticky top nav da landing.
 *
 * Logo a esquerda (NoraLogo animado on-mount), links de seccao no centro,
 * e CTAs de login a direita.
 */
export function LandingNav() {
  return (
    <nav className={styles.nav}>
      <div className={`${styles.shell} ${styles.navInner}`}>
        <Link href="/" className={styles.navBrand} aria-label="NORA">
          <NoraLogo size={28} animate />
        </Link>

        <div className={styles.navLinks}>
          <a href="#produto">Produto</a>
          <a href="#contexto">Contexto</a>
          <a href="#sinais">Sinais</a>
          <a href="#empresa">Empresa</a>
          <a href="#precos">Preços</a>
        </div>

        <div className={styles.navCta}>
          <Link href="/auth/login" className={styles.btnLink}>
            Entrar
          </Link>
          <Link href="/auth/login" className={`${styles.btn} ${styles.btnPrimary}`}>
            Começar agora
          </Link>
        </div>
      </div>
    </nav>
  );
}
