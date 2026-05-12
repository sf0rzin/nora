import styles from "./landing.module.css";

/**
 * Manifesto / Secao 01 - O problema.
 *
 * Layout 2 colunas: lead editorial a esquerda, lista numerada de
 * dores a direita (4 itens). Em mobile vira 1 coluna.
 */
export function Manifesto() {
  return (
    <section className={styles.manifesto}>
      <div className={styles.shell}>
        <div className={styles.sectionNum}>01 / O problema</div>
        <div className={styles.manifestoGrid}>
          <p className={styles.manifestoLead}>
            O conhecimento gerado em conversas <em>morre na memória</em> dos participantes — ou
            em anotações que ninguém relê.
            <br />
            <br />
            <span className={styles.accent}>NORA muda isso.</span>
          </p>
          <aside className={styles.manifestoAside}>
            <div className={styles.manifestoRow}>
              <div className={styles.manifestoNum}>[01]</div>
              <div className={styles.manifestoBody}>
                <strong>Oportunidades não capturadas.</strong> Times comerciais perdem sinais de
                compra porque o CRM depende de quem teve tempo de preencher.
              </div>
            </div>
            <div className={styles.manifestoRow}>
              <div className={styles.manifestoNum}>[02]</div>
              <div className={styles.manifestoBody}>
                <strong>Churn detectado tarde.</strong> O cliente já estava insatisfeito há três
                reuniões; ninguém somou os sinais.
              </div>
            </div>
            <div className={styles.manifestoRow}>
              <div className={styles.manifestoNum}>[03]</div>
              <div className={styles.manifestoBody}>
                <strong>Tasks que evaporam.</strong> Decisões de produto vistas como “todo mundo
                combinou” e nunca executadas.
              </div>
            </div>
            <div className={styles.manifestoRow}>
              <div className={styles.manifestoNum}>[04]</div>
              <div className={styles.manifestoBody}>
                <strong>Ferramentas genéricas.</strong> Transcrição não é inteligência. Sales
                intel não fala português, nem entende a sua empresa.
              </div>
            </div>
          </aside>
        </div>
      </div>
    </section>
  );
}
