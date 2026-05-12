"use client";

import { useEffect, useState } from "react";
import { NoraLogo } from "@/components/brand/nora-logo";

/**
 * Sticky top nav. Detecta scroll > 32px e aplica blur+border via
 * data-scrolled. Replica Nav() de nora-app.jsx (linhas 86-156).
 *
 * Mantemos NoraLogo (soundwave) — Anthony aprovou.
 */
export function LandingNav() {
  const [scrolled, setScrolled] = useState(false);

  useEffect(() => {
    const onScroll = (): void => setScrolled(window.scrollY > 32);
    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => window.removeEventListener("scroll", onScroll);
  }, []);

  return (
    <nav className="nav" data-scrolled={scrolled}>
      <div className="nav-inner">
        <div className="nav-left">
          <a href="#top" aria-label="NORA — ir para o topo" style={{ display: "inline-flex" }}>
            <NoraLogo size={28} animate />
          </a>
        </div>
        <div className="nav-center">
          <a href="#problema">Problema</a>
          <a href="#superficies">Plataforma</a>
          <a href="#contexto">Contexto</a>
          <a href="#health">Health Score</a>
          <a href="#iam">Segurança</a>
        </div>
        <div className="nav-right">
          <a
            href="/auth/login"
            className="btn btn-ghost magnetic"
            style={{ padding: "8px 14px", fontSize: 13, marginRight: 8 }}
          >
            Entrar
          </a>
          <a
            href="#cta"
            className="btn btn-ghost magnetic"
            style={{ padding: "8px 14px", fontSize: 13 }}
          >
            Falar com vendas
          </a>
        </div>
      </div>
      <div className="nav-bg" aria-hidden="true" />
      <style jsx>{`
        .nav {
          position: fixed;
          top: 0;
          left: 0;
          right: 0;
          z-index: 40;
          height: 64px;
        }
        .nav-bg {
          position: absolute;
          inset: 0;
          background: color-mix(in oklab, var(--bg) 80%, transparent);
          backdrop-filter: blur(14px);
          -webkit-backdrop-filter: blur(14px);
          border-bottom: 1px solid transparent;
          opacity: 0;
          transition:
            opacity 240ms var(--ease),
            border-color 240ms var(--ease);
        }
        .nav[data-scrolled="true"] .nav-bg {
          opacity: 1;
          border-bottom-color: var(--border);
        }
        .nav-inner {
          position: relative;
          z-index: 1;
          height: 100%;
          max-width: 1240px;
          margin: 0 auto;
          padding: 0 var(--container-x);
          display: grid;
          grid-template-columns: 1fr auto 1fr;
          align-items: center;
          gap: 24px;
        }
        .nav-center {
          display: flex;
          gap: 28px;
          font-size: 13.5px;
          color: var(--fg-muted);
        }
        .nav-center :global(a):hover {
          color: var(--fg);
        }
        .nav-right {
          display: flex;
          justify-content: end;
        }
        @media (max-width: 760px) {
          .nav-center {
            display: none;
          }
        }
      `}</style>
    </nav>
  );
}
