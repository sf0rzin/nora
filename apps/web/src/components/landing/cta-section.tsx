"use client";

/**
 * CTA + glow gradient SVG no fundo. Portado de nora-sections-2.jsx
 * (linhas 765-826).
 */
export function CTASection() {
  return (
    <section className="section" id="cta" data-screen-label="08 CTA">
      <div className="container">
        <div className="cta">
          <div className="cta-bg" aria-hidden="true">
            <svg viewBox="0 0 800 400" preserveAspectRatio="none">
              <defs>
                <radialGradient id="ctaGlow" cx="50%" cy="100%" r="60%">
                  <stop offset="0%" stopColor="oklch(0.70 0.17 248)" stopOpacity="0.4" />
                  <stop offset="100%" stopColor="oklch(0.70 0.17 248)" stopOpacity="0" />
                </radialGradient>
              </defs>
              <rect width="800" height="400" fill="url(#ctaGlow)" />
            </svg>
          </div>
          <div className="cta-inner">
            <div className="kicker">Pronto pra começar</div>
            <h2 className="h-display" style={{ marginTop: 16 }}>
              Cada reunião é<br />
              capital intelectual.
              <br />
              <span style={{ color: "var(--accent-bright)" }}>Pare de perdê-lo.</span>
            </h2>
            <div className="cta-actions">
              <a className="btn btn-accent magnetic" href="#superficies">
                Falar com vendas
                <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
                  <path
                    d="M3 7h8M7 3l4 4-4 4"
                    stroke="currentColor"
                    strokeWidth="1.5"
                    strokeLinecap="round"
                    strokeLinejoin="round"
                  />
                </svg>
              </a>
              <a className="btn btn-ghost magnetic" href="#contexto">
                Como funciona o RAG
              </a>
            </div>
          </div>
        </div>
      </div>

      <style jsx>{`
        .cta {
          position: relative;
          padding: 80px 56px;
          background: var(--bg-deep);
          border: 1px solid var(--accent-dim);
          border-radius: var(--radius-lg);
          overflow: hidden;
        }
        .cta-bg {
          position: absolute;
          inset: 0;
          opacity: 0.6;
        }
        .cta-bg :global(svg) {
          width: 100%;
          height: 100%;
        }
        .cta-inner {
          position: relative;
        }
        .cta-actions {
          display: flex;
          gap: 12px;
          margin-top: 32px;
          flex-wrap: wrap;
        }
        @media (max-width: 720px) {
          .cta {
            padding: 48px 28px;
          }
        }
      `}</style>
    </section>
  );
}
