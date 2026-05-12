import styles from "./landing.module.css";

/**
 * Context / Secao 03 - O diferencial.
 *
 * 3 colunas (Admin configura -> Embeddings + RAG -> Saida calibrada),
 * setas SVG dashed entre elas, e 3 quotes de exemplo embaixo
 * (ERP / Fintech / Distribuidora) que ilustram a configuracao
 * por-tenant — sem hardcode em produto.
 */
function ArrowSvg() {
  return (
    <div className={styles.contextArrow} aria-hidden="true">
      <svg viewBox="0 0 56 24" fill="none">
        <path d="M0 12 H48" stroke="var(--mute)" strokeWidth="1" strokeDasharray="2 3" />
        <path d="M44 6 L52 12 L44 18" stroke="var(--mute)" strokeWidth="1" fill="none" />
      </svg>
    </div>
  );
}

export function ContextDiagram() {
  return (
    <section id="contexto" className={styles.context}>
      <div className={styles.shell}>
        <div className={styles.sectionNum}>03 / O diferencial</div>
        <h2 className={styles.sectionTitle}>
          Inteligência que <em>fala a língua</em> da{" "}
          <span className={styles.accent}>sua empresa.</span>
        </h2>
        <p className={styles.sectionLede}>
          NORA não tem conhecimento hardcoded de nenhuma indústria. Cada tenant configura o
          próprio catálogo, concorrência e glossário — e a extração se cala ou se acende com
          base nele.
        </p>

        <div className={styles.contextDiagram}>
          <div className={styles.contextCol}>
            <span className={styles.ctxH}>Etapa 01</span>
            <h4 className={styles.ctxTitle}>Admin configura o contexto.</h4>
            <div className={styles.ctxPills}>
              <span className={styles.ctxPill}>Catálogo de produtos</span>
              <span className={styles.ctxPill}>Concorrentes</span>
              <span className={styles.ctxPill}>Glossário interno</span>
              <span className={styles.ctxPill}>ICP</span>
              <span className={styles.ctxPill}>Playbook</span>
            </div>
            <div className={styles.ctxFoot}>Texto livre. Sem código. Versionado.</div>
          </div>

          <ArrowSvg />

          <div className={styles.contextCol}>
            <span className={styles.ctxH}>Etapa 02</span>
            <h4 className={styles.ctxTitle}>Embeddings + RAG.</h4>
            <div className={styles.ctxPills}>
              <span className={`${styles.ctxPill} ${styles.ctxPillBrand}`}>
                OpenAI Embeddings
              </span>
              <span className={`${styles.ctxPill} ${styles.ctxPillBrand}`}>
                Azure AI Search
              </span>
              <span className={styles.ctxPill}>PII Shield</span>
              <span className={styles.ctxPill}>JSON Schema</span>
            </div>
            <div className={styles.ctxFoot}>
              Indexação isolada por tenant. Zero vazamento entre clientes.
            </div>
          </div>

          <ArrowSvg />

          <div className={styles.contextCol}>
            <span className={styles.ctxH}>Etapa 03</span>
            <h4 className={styles.ctxTitle}>Saída calibrada para o seu negócio.</h4>
            <div className={styles.ctxPills}>
              <span className={styles.ctxPill}>Sumário</span>
              <span className={styles.ctxPill}>Decisões</span>
              <span className={styles.ctxPill}>Tasks</span>
              <span className={styles.ctxPill}>Riscos</span>
              <span className={styles.ctxPill}>Oportunidades</span>
              <span className={styles.ctxPill}>Health Score</span>
            </div>
            <div className={styles.ctxFoot}>
              Validada por JSON Schema. Sem alucinação livre cruzando fronteiras.
            </div>
          </div>
        </div>

        <div className={styles.contextFoot}>
          <div className={styles.ctxEx}>
            <div className={styles.ctxExLbl}>ERP · Tenant A</div>
            <p className={styles.ctxExQuote}>
              “Configuramos <em>Protheus, RM e Fluig.</em> NORA agora reconhece menções por
              sigla, módulo e processo.”
            </p>
            <div className={styles.ctxExWho}>— Diretor de Vendas</div>
          </div>
          <div className={styles.ctxEx}>
            <div className={styles.ctxExLbl}>Fintech · Tenant B</div>
            <p className={styles.ctxExQuote}>
              “<em>PIX recorrente</em>, <em>antecipação</em>, <em>onboarding KYC</em>. Mesmo
              motor, vocabulário totalmente diferente.”
            </p>
            <div className={styles.ctxExWho}>— Head de Produto</div>
          </div>
          <div className={styles.ctxEx}>
            <div className={styles.ctxExLbl}>Distribuidora · Tenant C</div>
            <p className={styles.ctxExQuote}>
              “Nosso <em>SKU master</em> e nossas <em>rotas de expedição</em>. NORA aprendeu em
              uma tarde.”
            </p>
            <div className={styles.ctxExWho}>— Diretor Comercial</div>
          </div>
        </div>
      </div>
    </section>
  );
}
