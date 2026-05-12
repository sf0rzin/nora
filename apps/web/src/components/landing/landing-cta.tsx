import Link from "next/link";
import styles from "./landing.module.css";

/**
 * CTA final antes do footer — repete a tese principal com acento brand e
 * dois botoes (Comecar agora + Falar com vendas).
 */
export function LandingCta() {
  return (
    <section className={styles.cta}>
      <div className={styles.shell}>
        <div className={styles.ctaInner}>
          <div className={styles.sectionNum}>→ Próximo passo</div>
          <h2 className={styles.ctaTitle}>
            Suas reuniões já estão
            <br />
            gerando inteligência.
            <br />
            <em>Está na hora de</em>
            <br />
            <span className={styles.accent}>capturá-la.</span>
          </h2>
          <div className={styles.heroActions}>
            <Link href="/auth/login" className={`${styles.btn} ${styles.btnPrimary}`}>
              Começar agora <span aria-hidden="true">→</span>
            </Link>
            <Link href="/auth/signup" className={`${styles.btn} ${styles.btnGhost}`}>
              Falar com vendas
            </Link>
          </div>
        </div>
      </div>
    </section>
  );
}
