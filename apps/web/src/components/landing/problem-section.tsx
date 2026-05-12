"use client";

/**
 * Problem section — grid 2 colunas: heading + 3 cards de comparação
 * (Gong/Clari, Fireflies/Otter, NORA). Portado de nora-sections.jsx
 * (linhas 7-93). Sem state, mas client component pra usar styled-jsx.
 */
export function ProblemSection() {
  return (
    <section className="section" id="problema" data-screen-label="02 Problema">
      <div className="container">
        <div className="problem-grid">
          <div>
            <div className="eyebrow">O Problema</div>
            <h2 className="h-section">
              O conhecimento gerado em reuniões
              <br />
              <span style={{ color: "var(--fg-muted)" }}>
                morre na memória dos participantes.
              </span>
            </h2>
          </div>
          <div className="problem-body">
            <p className="h-sub">
              Decisões, oportunidades, riscos e compromissos somem em anotações desconexas. Para
              times comerciais, isso vira oportunidades não capturadas, churn não detectado e CRMs
              subpreenchidos.
            </p>
            <div className="problem-tools">
              <div className="problem-tool">
                <div className="kicker">Sales Intelligence</div>
                <div className="problem-tool-name">Gong, Clari</div>
                <div className="problem-tool-flaw">
                  Caras, em inglês, sem contexto da sua empresa.
                </div>
              </div>
              <div className="problem-tool">
                <div className="kicker">Transcrição</div>
                <div className="problem-tool-name">Fireflies, Otter</div>
                <div className="problem-tool-flaw">
                  Capturam texto, mas não extraem inteligência.
                </div>
              </div>
              <div className="problem-tool problem-tool--accent">
                <div className="kicker" style={{ color: "var(--accent-bright)" }}>
                  NORA
                </div>
                <div className="problem-tool-name">Inteligência contextual</div>
                <div className="problem-tool-flaw" style={{ color: "var(--fg)" }}>
                  Adapta-se ao contexto proprietário de cada tenant.
                </div>
              </div>
            </div>
          </div>
        </div>
      </div>
      <style jsx>{`
        .problem-grid {
          display: grid;
          grid-template-columns: 1fr 1.2fr;
          gap: 64px;
          align-items: start;
        }
        .problem-body {
          display: flex;
          flex-direction: column;
          gap: 40px;
        }
        .problem-tools {
          display: grid;
          grid-template-columns: 1fr 1fr 1fr;
          gap: 12px;
        }
        .problem-tool {
          padding: 20px;
          border: 1px solid var(--border);
          border-radius: var(--radius);
          background: var(--surface);
          display: flex;
          flex-direction: column;
          gap: 10px;
          min-height: 160px;
        }
        .problem-tool--accent {
          border-color: var(--accent-dim);
          background: linear-gradient(
            180deg,
            var(--surface),
            color-mix(in oklab, var(--accent) 8%, var(--surface))
          );
        }
        .problem-tool-name {
          font-size: 18px;
          font-weight: 500;
          letter-spacing: -0.01em;
          margin-top: 4px;
        }
        .problem-tool-flaw {
          font-size: 14px;
          color: var(--fg-muted);
          line-height: 1.5;
          margin-top: auto;
        }
        @media (max-width: 880px) {
          .problem-grid {
            grid-template-columns: 1fr;
            gap: 32px;
          }
          .problem-tools {
            grid-template-columns: 1fr;
          }
        }
      `}</style>
    </section>
  );
}
