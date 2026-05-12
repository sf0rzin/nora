"use client";

import { NoraLogo } from "@/components/brand/nora-logo";

/**
 * Footer com grid 4 col + bottom strip. Portado de nora-sections-2.jsx
 * (linhas 829-923). Diferença vs design original: usamos <NoraLogo />
 * soundwave em vez de texto puro (Anthony amou a soundwave).
 */
export function LandingFooter() {
  return (
    <footer className="footer">
      <div className="container">
        <div className="footer-grid">
          <div>
            <div className="footer-mark">
              <NoraLogo size={36} animate={false} />
            </div>
            <div className="footer-tagline">
              Negotiation Observability
              <br />
              &amp; Revenue Assistant
            </div>
          </div>
          <div className="footer-col">
            <div className="kicker">Produto</div>
            <a href="#superficies">Plataforma</a>
            <a href="#contexto">Product Context</a>
            <a href="#health">Health Score</a>
            <a href="#iam">IAM</a>
          </div>
          <div className="footer-col">
            <div className="kicker">Recursos</div>
            <a href="#superficies">NORA Web</a>
            <a href="#superficies">NORA Desktop</a>
            <a href="#superficies">API &amp; MCP</a>
            <a href="#iam">LGPD</a>
          </div>
          <div className="footer-col">
            <div className="kicker">Empresa</div>
            <a href="#problema">Sobre</a>
            <a href="#cta">Contato</a>
            <a href="#iam">Segurança</a>
            <a href="#contexto">Privacidade</a>
          </div>
        </div>
        <div className="footer-bottom">
          <span style={{ fontSize: 11, color: "var(--fg-dim)" }}>
            © 2026 NORA. Todos os direitos reservados.
          </span>
          <span style={{ fontSize: 11, color: "var(--fg-dim)" }}>
            LGPD-first · OWASP Top 10 · TLS 1.3
          </span>
        </div>
      </div>
      <style jsx>{`
        .footer {
          padding-top: 80px;
          padding-bottom: 40px;
          border-top: 1px solid var(--border);
          background: var(--bg-deep);
        }
        .footer-grid {
          display: grid;
          grid-template-columns: 1.4fr 1fr 1fr 1fr;
          gap: 40px;
          padding-bottom: 56px;
          border-bottom: 1px solid var(--border);
        }
        .footer-mark {
          margin-bottom: 12px;
        }
        .footer-tagline {
          font-size: 13px;
          color: var(--fg-muted);
          line-height: 1.6;
        }
        .footer-col {
          display: flex;
          flex-direction: column;
          gap: 8px;
          font-size: 13px;
        }
        .footer-col :global(a),
        .footer-col :global(span) {
          color: var(--fg-muted);
          transition: color 180ms var(--ease);
        }
        .footer-col :global(a):hover {
          color: var(--fg);
        }
        .footer-col :global(.kicker) {
          margin-bottom: 6px;
        }
        .footer-bottom {
          margin-top: 32px;
          display: flex;
          justify-content: space-between;
          flex-wrap: wrap;
          gap: 12px;
        }
        @media (max-width: 760px) {
          .footer-grid {
            grid-template-columns: 1fr 1fr;
          }
        }
      `}</style>
    </footer>
  );
}
