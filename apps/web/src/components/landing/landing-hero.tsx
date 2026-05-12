import Link from "next/link";
import styles from "./landing.module.css";

/**
 * Hero da landing: eyebrow + headline editorial gigante + sub + CTAs.
 *
 * Tipografia: Instrument Serif para a headline, com .accent destacando
 * o trecho "nao capturado" em italico azul brand.
 */
export function LandingHero() {
  return (
    <section className={styles.hero}>
      <div className={styles.shell}>
        <div className={styles.heroEyebrow}>Conversational intelligence · Beta privado</div>
        <h1 className={styles.heroTitle}>
          Toda conversa
          <br />
          tem um valor
          <br />
          <span className={styles.accent}>não capturado.</span>
        </h1>
        <p className={styles.heroSub}>
          <strong>NORA escuta suas reuniões</strong>, entende o contexto da sua empresa e devolve
          decisões, riscos, oportunidades e a próxima ação — antes que a memória do time as
          perca.
        </p>
        <div className={styles.heroActions}>
          <Link href="/auth/login" className={`${styles.btn} ${styles.btnPrimary}`}>
            Começar agora <span aria-hidden="true">→</span>
          </Link>
          <a href="#demo" className={`${styles.btn} ${styles.btnGhost}`}>
            Ver demonstração
          </a>
        </div>
      </div>
    </section>
  );
}
