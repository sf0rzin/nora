import styles from "./landing.module.css";

/**
 * Surfaces / Secao 02 - O produto.
 *
 * Grid de 3 cards (Web / Desktop / API+MCP), cada um com visual ilustrativo
 * proprio na parte inferior. Em mobile vira 1 coluna.
 */
export function SurfacesGrid() {
  return (
    <section id="produto" className={styles.surfaces}>
      <div className={styles.shell}>
        <div className={styles.sectionNum}>02 / O produto</div>
        <h2 className={styles.sectionTitle}>
          Três superfícies, <em>um motor.</em>
        </h2>
        <p className={styles.sectionLede}>
          Web pra revisar e configurar. Desktop pra acompanhar e ser coachado em tempo real. API
          e MCPs pra empurrar inteligência pro resto do seu stack.
        </p>

        <div className={styles.surfacesGrid}>
          {/* Web */}
          <div className={styles.surface}>
            <span className={styles.surfaceTag}>A · Web</span>
            <h3 className={styles.surfaceH}>
              Pós-reunião,
              <br />
              com calma.
            </h3>
            <p className={styles.surfaceP}>
              Upload, revisão e follow-up. Dashboards de pipeline, contas, ações pendentes.
              Configuração de catálogo e contexto.
            </p>
            <div className={styles.surfaceVis}>
              <div className={styles.visWeb}>
                <div className={styles.visWebMini}>
                  <div className={styles.visWebLbl}>Health</div>
                  <div className={`${styles.visWebVal} ${styles.visWebValUp}`}>82</div>
                </div>
                <div className={styles.visWebMini}>
                  <div className={styles.visWebLbl}>Reuniões</div>
                  <div className={styles.visWebVal}>214</div>
                </div>
                <div className={`${styles.visWebMini} ${styles.visWebSpan2}`}>
                  <div className={styles.visWebLbl}>Sinais por semana</div>
                  <div className={styles.visWebBars}>
                    <span style={{ height: "40%" }} />
                    <span style={{ height: "62%" }} />
                    <span style={{ height: "48%" }} />
                    <span style={{ height: "78%" }} />
                    <span style={{ height: "90%" }} />
                    <span style={{ height: "72%" }} />
                    <span style={{ height: "100%" }} />
                  </div>
                </div>
              </div>
            </div>
          </div>

          {/* Desktop */}
          <div className={styles.surface}>
            <span className={styles.surfaceTag}>B · Desktop</span>
            <h3 className={styles.surfaceH}>
              Coaching ao vivo,
              <br />
              na sua mesa.
            </h3>
            <p className={styles.surfaceP}>
              Captura WASAPI nativa via Tauri. Transcreve, sumariza e sugere a próxima pergunta
              enquanto você conversa.
            </p>
            <div className={styles.surfaceVis}>
              <div className={styles.visDesktop}>
                <div className={styles.liveRow}>
                  <span className={styles.liveRowT}>23:08</span>
                  <span className={styles.liveRowWho}>Cliente</span>
                  <span className={styles.liveRowTxt}>…ainda usamos o Protheus pra…</span>
                </div>
                <div className={styles.liveRow}>
                  <span className={styles.liveRowT}>23:11</span>
                  <span className={styles.liveRowWho}>Você</span>
                  <span className={styles.liveRowTxt}>Em quais módulos especificamente?</span>
                </div>
                <div className={`${styles.liveRow} ${styles.liveRowCoach}`}>
                  <span className={styles.liveRowT}>↳</span>
                  <span className={styles.liveRowWho}>NORA</span>
                  <span className={styles.liveRowTxt}>
                    Pergunte sobre integração com a folha
                  </span>
                </div>
                <div className={styles.liveRow}>
                  <span className={styles.liveRowT}>23:15</span>
                  <span className={styles.liveRowWho}>Cliente</span>
                  <span className={styles.liveRowTxt}>RH e financeiro principalmente.</span>
                </div>
              </div>
            </div>
          </div>

          {/* API */}
          <div className={styles.surface}>
            <span className={styles.surfaceTag}>C · API · MCP</span>
            <h3 className={styles.surfaceH}>
              Inteligência
              <br />
              onde o trabalho mora.
            </h3>
            <p className={styles.surfaceP}>
              Servers MCP pra Calendar, Linear, Jira, GitHub, Salesforce e HubSpot. Tasks,
              oportunidades e decisões já chegam onde seu time já está.
            </p>
            <div className={styles.surfaceVis}>
              <div className={styles.visApi}>
                <svg
                  viewBox="0 0 360 200"
                  xmlns="http://www.w3.org/2000/svg"
                  preserveAspectRatio="xMidYMid meet"
                  aria-hidden="true"
                >
                  <defs>
                    <marker
                      id="landing-arr"
                      viewBox="0 0 10 10"
                      refX="8"
                      refY="5"
                      markerWidth="5"
                      markerHeight="5"
                      orient="auto"
                    >
                      <path d="M0,0 L10,5 L0,10 z" fill="var(--mute)" />
                    </marker>
                  </defs>
                  {/* center node */}
                  <g>
                    <circle cx="180" cy="100" r="36" fill="var(--brand)" />
                    <text
                      x="180"
                      y="104"
                      textAnchor="middle"
                      fill="var(--paper)"
                      fontFamily="var(--font-display)"
                      fontSize="22"
                      fontStyle="italic"
                    >
                      N
                    </text>
                  </g>
                  {/* outer nodes */}
                  <g fontFamily="var(--font-mono)" fontSize="9" fill="var(--ink-2)">
                    <g transform="translate(40,30)">
                      <rect
                        width="80"
                        height="28"
                        rx="14"
                        fill="var(--paper)"
                        stroke="var(--hair-strong)"
                      />
                      <text x="40" y="18" textAnchor="middle">
                        CALENDAR
                      </text>
                    </g>
                    <g transform="translate(40,90)">
                      <rect
                        width="80"
                        height="28"
                        rx="14"
                        fill="var(--paper)"
                        stroke="var(--hair-strong)"
                      />
                      <text x="40" y="18" textAnchor="middle">
                        LINEAR
                      </text>
                    </g>
                    <g transform="translate(40,150)">
                      <rect
                        width="80"
                        height="28"
                        rx="14"
                        fill="var(--paper)"
                        stroke="var(--hair-strong)"
                      />
                      <text x="40" y="18" textAnchor="middle">
                        GITHUB
                      </text>
                    </g>
                    <g transform="translate(240,30)">
                      <rect
                        width="92"
                        height="28"
                        rx="14"
                        fill="var(--paper)"
                        stroke="var(--hair-strong)"
                      />
                      <text x="46" y="18" textAnchor="middle">
                        SALESFORCE
                      </text>
                    </g>
                    <g transform="translate(240,90)">
                      <rect
                        width="92"
                        height="28"
                        rx="14"
                        fill="var(--paper)"
                        stroke="var(--hair-strong)"
                      />
                      <text x="46" y="18" textAnchor="middle">
                        HUBSPOT
                      </text>
                    </g>
                    <g transform="translate(240,150)">
                      <rect
                        width="92"
                        height="28"
                        rx="14"
                        fill="var(--paper)"
                        stroke="var(--hair-strong)"
                      />
                      <text x="46" y="18" textAnchor="middle">
                        JIRA
                      </text>
                    </g>
                  </g>
                  {/* lines */}
                  <g stroke="var(--mute)" strokeWidth="1" fill="none" strokeDasharray="2 3">
                    <path d="M120,44 Q150,72 148,90" />
                    <path d="M120,104 L148,100" />
                    <path d="M120,164 Q150,128 148,110" />
                    <path d="M240,44 Q210,72 212,90" />
                    <path d="M240,104 L212,100" />
                    <path d="M240,164 Q210,128 212,110" />
                  </g>
                </svg>
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
