import styles from "./landing.module.css";

/**
 * Signals / Secao 04 - Os sinais.
 *
 * Grid 2 colunas: Health Score (tall, ocupa 2 rows na coluna esquerda)
 * + Competitive Radar e Next Best Action empilhados na direita.
 * Em mobile vira 1 coluna.
 */
export function SignalsGrid() {
  return (
    <section id="sinais" className={styles.signals}>
      <div className={styles.shell}>
        <div className={styles.sectionNum}>04 / Os sinais</div>
        <h2 className={styles.sectionTitle}>
          O que NORA <em>vê na conversa</em> que você está perdendo.
        </h2>
        <p className={styles.sectionLede}>
          Cinco produtos de inteligência, alimentados pelo mesmo motor. Cada um responde a uma
          pergunta de negócio — não a uma curiosidade.
        </p>

        <div className={styles.signalsGrid}>
          {/* Health Score (tall) */}
          <div className={`${styles.signal} ${styles.health}`}>
            <span className={styles.signalTag}>Account Health Score</span>
            <h3 className={styles.signalH}>A confiança do seu cliente, reunião por reunião.</h3>
            <p className={styles.signalP}>
              Composto de sinais de compra, objeções, sentimento e cadência. Detecta degradação{" "}
              <strong>antes</strong> do churn pedir reunião urgente.
            </p>

            <div className={styles.healthNum}>
              78
              <span className={styles.healthDelta}>↑ 6</span>
            </div>
            <div className={styles.healthBar}>
              <div className={styles.healthBarFill} />
            </div>
            <div className={styles.healthMeta}>
              <span>Mercato Indústria</span>
              <span>Q3 · v18</span>
            </div>

            <div className={styles.healthChart}>
              <svg viewBox="0 0 400 120" preserveAspectRatio="none" aria-hidden="true">
                <defs>
                  <linearGradient id="landing-health-gradient" x1="0" x2="0" y1="0" y2="1">
                    <stop offset="0%" stopColor="var(--brand)" stopOpacity="0.22" />
                    <stop offset="100%" stopColor="var(--brand)" stopOpacity="0" />
                  </linearGradient>
                </defs>
                <path
                  d="M0,80 C40,72 60,90 100,82 C140,74 160,50 200,46 C240,42 260,55 300,40 C340,30 360,28 400,22 L400,120 L0,120 Z"
                  fill="url(#landing-health-gradient)"
                />
                <path
                  d="M0,80 C40,72 60,90 100,82 C140,74 160,50 200,46 C240,42 260,55 300,40 C340,30 360,28 400,22"
                  fill="none"
                  stroke="var(--brand)"
                  strokeWidth="2"
                />
                <circle cx="400" cy="22" r="4" fill="var(--brand)" />
                <circle cx="400" cy="22" r="9" fill="var(--brand)" opacity="0.18" />
              </svg>
            </div>
          </div>

          {/* Competitive Radar */}
          <div className={styles.signal}>
            <span className={styles.signalTag}>Competitive Radar</span>
            <h3 className={styles.signalH}>
              Quem está sendo citado nas conversas dos seus prospects.
            </h3>
            <div className={styles.radarList}>
              <div className={styles.radarRow}>
                <span className={styles.radarName}>SAP</span>
                <span className={`${styles.radarStage} ${styles.radarStageHot}`}>Avaliando</span>
                <span className={styles.radarMentions}>14 menções</span>
              </div>
              <div className={styles.radarRow}>
                <span className={styles.radarName}>Oracle</span>
                <span className={`${styles.radarStage} ${styles.radarStageWarm}`}>
                  Comparando
                </span>
                <span className={styles.radarMentions}>9 menções</span>
              </div>
              <div className={styles.radarRow}>
                <span className={styles.radarName}>Senior</span>
                <span className={styles.radarStage}>Mencionado</span>
                <span className={styles.radarMentions}>4 menções</span>
              </div>
              <div className={styles.radarRow}>
                <span className={styles.radarName}>Sankhya</span>
                <span className={styles.radarStage}>Mencionado</span>
                <span className={styles.radarMentions}>2 menções</span>
              </div>
            </div>
          </div>

          {/* Next Best Action */}
          <div className={styles.signal}>
            <span className={styles.signalTag}>Next Best Action</span>
            <h3 className={styles.signalH}>A próxima jogada — não um relatório.</h3>
            <p className={styles.signalP}>
              Recomendação concreta para 48–72 horas. Com motivo, dono e canal.
            </p>
            <div className={styles.nbaCard}>
              <div className={styles.nbaH}>
                <span>Conta · Mercato</span>
                <span className={styles.nbaWhen}>Em 48h</span>
              </div>
              <div className={styles.nbaAction}>
                “Apresentar caso PRX-Indústria à Camila antes da reunião de aprovação.”
              </div>
              <div className={styles.nbaH} style={{ marginTop: 4 }}>
                <span>Probabilidade · Alta</span>
                <span>Origem · 3 reuniões</span>
              </div>
              <div className={styles.nbaFoot}>
                <button type="button" className={`${styles.nbaBtn} ${styles.nbaBtnPrimary}`}>
                  Aceitar
                </button>
                <button type="button" className={styles.nbaBtn}>
                  Adiar
                </button>
                <button type="button" className={styles.nbaBtn}>
                  Ver evidências
                </button>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
