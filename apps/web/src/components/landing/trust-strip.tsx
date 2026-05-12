import styles from "./landing.module.css";

/**
 * Trust / Secao 05 - Confianca.
 *
 * 4 colunas (2x2 em mobile) com selos enterprise: PII Shield, RBAC+ABAC,
 * Multi-tenant isolado, Auditoria completa. Cada um tem icone SVG inline.
 */
export function TrustStrip() {
  return (
    <section id="empresa" className={styles.trust}>
      <div className={styles.shell}>
        <div className={styles.sectionNum}>05 / Confiança</div>
        <h2 className={styles.sectionTitle}>
          Enterprise-grade, <em>LGPD-first.</em>
        </h2>

        <div className={styles.trustGrid}>
          <div className={styles.trustItem}>
            <div className={styles.trustIc}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path
                  d="M8 1L13 4V8C13 11 11 13.5 8 15C5 13.5 3 11 3 8V4L8 1Z"
                  stroke="currentColor"
                  strokeWidth="1.4"
                />
              </svg>
            </div>
            <h5 className={styles.trustH5}>PII Shield</h5>
            <p className={styles.trustP}>
              Redação automática de CPF, e-mail, telefone e dados sensíveis antes de qualquer
              chamada de LLM.
            </p>
          </div>
          <div className={styles.trustItem}>
            <div className={styles.trustIc}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <rect
                  x="3"
                  y="6"
                  width="10"
                  height="8"
                  rx="1.5"
                  stroke="currentColor"
                  strokeWidth="1.4"
                />
                <path
                  d="M5.5 6V4.5a2.5 2.5 0 015 0V6"
                  stroke="currentColor"
                  strokeWidth="1.4"
                />
              </svg>
            </div>
            <h5 className={styles.trustH5}>RBAC + ABAC</h5>
            <p className={styles.trustP}>
              Controle granular: por papel, por conta, por reunião. Quem vê o quê, sempre
              rastreável.
            </p>
          </div>
          <div className={styles.trustItem}>
            <div className={styles.trustIc}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M2 8h12M8 2v12" stroke="currentColor" strokeWidth="1.4" />
                <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="1.4" />
              </svg>
            </div>
            <h5 className={styles.trustH5}>Multi-tenant isolado</h5>
            <p className={styles.trustP}>
              Cada cliente em silo lógico. Embeddings, prompts, dados — nunca cruzam fronteiras.
            </p>
          </div>
          <div className={styles.trustItem}>
            <div className={styles.trustIc}>
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M2 4l6 4 6-4" stroke="currentColor" strokeWidth="1.4" />
                <rect
                  x="2"
                  y="3"
                  width="12"
                  height="10"
                  rx="1.5"
                  stroke="currentColor"
                  strokeWidth="1.4"
                />
              </svg>
            </div>
            <h5 className={styles.trustH5}>Auditoria completa</h5>
            <p className={styles.trustP}>
              Cada extração com hash de prompt, modelo, schema e versão de contexto. Provável e
              revisável.
            </p>
          </div>
        </div>
      </div>
    </section>
  );
}
