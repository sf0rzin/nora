"use client";

import { useState, type CSSProperties } from "react";

/* =========================================================
   Types
   ========================================================= */

type Tier = "core" | "enterprise";
type SurfaceKey = "web" | "desktop" | "api";

type Surface = {
  key: SurfaceKey;
  name: string;
  tag: string;
  desc: string;
  tech: string[];
};

type Output = { label: string; glyph: string };

/* =========================================================
   Data
   ========================================================= */

const SURFACES: Surface[] = [
  {
    key: "web",
    name: "NORA Web",
    tag: "PÓS-REUNIÃO",
    desc: "Upload de texto ou áudio, dashboards, admin de IAM e do catálogo de produto.",
    tech: ["Next.js 14", "shadcn/ui", "Azure SWA"],
  },
  {
    key: "desktop",
    name: "NORA Desktop",
    tag: "TEMPO REAL",
    desc: "Captura WASAPI, transcrição streaming e coaching ao vivo durante a reunião.",
    tech: ["Tauri 2", "Rust", "WASAPI"],
  },
  {
    key: "api",
    name: "NORA API · MCP",
    tag: "INTEGRAÇÃO",
    desc: "Servers MCP nativos para Calendar, Linear, Jira, GitHub e Salesforce/HubSpot.",
    tech: ["Node 22", "Azure Functions", "MCP 1.0"],
  },
];

const OUTPUTS: Record<Tier, Output[]> = {
  core: [
    { label: "Resumo estruturado da reunião", glyph: "§" },
    { label: "Action items detectados e categorizados", glyph: "→" },
    { label: "Rastreamento de projetos no tempo", glyph: "◷" },
    { label: "Push automático via MCPs", glyph: "↗" },
  ],
  enterprise: [
    { label: "Oportunidades de venda · produto · probabilidade", glyph: "△" },
    { label: "Sinais de churn com contexto", glyph: "▽" },
    { label: "Competitive Radar — menções a concorrentes", glyph: "◇" },
    { label: "Customer Confidence Score por reunião", glyph: "◐" },
    { label: "Account Health Score temporal", glyph: "◉" },
    { label: "Next Best Action em 48–72h", glyph: "▶" },
  ],
};

/* =========================================================
   Surface Visual (placeholder bottom de cada card)
   ========================================================= */

type SurfaceVisualProps = { kind: SurfaceKey; active: boolean };

function SurfaceVisual({ kind, active }: SurfaceVisualProps) {
  return (
    <div className="surface-visual" data-kind={kind} data-active={active}>
      {kind === "web" && (
        <div className="sv-web">
          <div className="sv-web-bar">
            <span />
            <span />
            <span />
          </div>
          <div className="sv-web-row sv-web-row--lg" />
          <div className="sv-web-row" />
          <div className="sv-web-row sv-web-row--md" />
        </div>
      )}
      {kind === "desktop" && (
        <div className="sv-desktop">
          <div className="sv-wave">
            {Array.from({ length: 28 }).map((_, i) => {
              const style: CSSProperties & Record<"--i", string> = { "--i": String(i) };
              return <span key={i} style={style} />;
            })}
          </div>
          <div className="sv-rec">● REC</div>
        </div>
      )}
      {kind === "api" && (
        <div className="sv-api">
          <div className="sv-api-line">{`POST /v1/analyze`}</div>
          <div className="sv-api-line sv-api-line--dim">{`mcp://calendar`}</div>
          <div className="sv-api-line sv-api-line--dim">{`mcp://linear`}</div>
        </div>
      )}
      <style jsx>{`
        .surface-visual {
          margin-top: auto;
          height: 100px;
          border: 1px solid var(--border);
          border-radius: var(--radius-sm);
          background: var(--bg-deep);
          padding: 12px;
          display: flex;
          flex-direction: column;
          gap: 8px;
          overflow: hidden;
          position: relative;
        }
        .sv-web-bar {
          display: flex;
          gap: 4px;
          margin-bottom: 4px;
        }
        .sv-web-bar :global(span) {
          width: 8px;
          height: 8px;
          border-radius: 50%;
          background: var(--border-strong);
        }
        .sv-web-row {
          height: 8px;
          border-radius: 4px;
          background: linear-gradient(90deg, var(--surface-2), var(--border));
        }
        .sv-web-row--lg {
          width: 70%;
          background: var(--accent-dim);
        }
        .sv-web-row--md {
          width: 45%;
        }
        .sv-desktop {
          display: flex;
          align-items: center;
          justify-content: space-between;
          height: 100%;
        }
        .sv-wave {
          display: flex;
          align-items: center;
          gap: 3px;
          height: 100%;
          flex: 1;
        }
        .sv-wave :global(span) {
          width: 3px;
          border-radius: 2px;
          background: var(--accent);
          height: calc(20% + sin(calc(var(--i) * 0.7rad)) * 30% + 30%);
          animation: waveAnim 1.4s ease-in-out infinite;
          animation-delay: calc(var(--i) * 60ms);
          opacity: 0.7;
        }
        .surface-visual[data-active="false"] :global(.sv-wave span) {
          animation-play-state: paused;
          opacity: 0.4;
        }
        @keyframes waveAnim {
          0%,
          100% {
            transform: scaleY(0.4);
          }
          50% {
            transform: scaleY(1);
          }
        }
        .sv-rec {
          font-family: var(--font-mono);
          font-size: 10px;
          color: var(--danger);
          letter-spacing: 0.1em;
          align-self: start;
        }
        .sv-api {
          font-family: var(--font-mono);
          font-size: 11px;
          display: flex;
          flex-direction: column;
          gap: 6px;
        }
        .sv-api-line {
          padding: 6px 10px;
          background: var(--surface);
          border-left: 2px solid var(--accent);
          color: var(--fg);
          border-radius: 2px;
        }
        .sv-api-line--dim {
          color: var(--fg-dim);
          border-left-color: var(--border-strong);
        }
      `}</style>
    </div>
  );
}

/* =========================================================
   Surfaces Section
   ========================================================= */

export function SurfacesSection() {
  const [tier, setTier] = useState<Tier>("enterprise");
  const [active, setActive] = useState<number>(0);

  return (
    <section className="section" id="superficies" data-screen-label="03 Superficies">
      <div className="container">
        <div className="surfaces-head">
          <div>
            <div className="eyebrow">A Solução</div>
            <h2 className="h-section">Três superfícies. Um motor.</h2>
            <p className="h-sub">
              Mesmo backend, mesmo motor de IA, mesmo banco. A inteligência segue você do upload
              pós-reunião até o coaching em tempo real.
            </p>
          </div>
          <div className="tier-toggle" role="tablist" aria-label="Plano">
            <button
              role="tab"
              aria-selected={tier === "core"}
              className={`tier-btn ${tier === "core" ? "is-active" : ""}`}
              onClick={() => setTier("core")}
              type="button"
            >
              Core
              <span className="tier-btn-sub">individual</span>
            </button>
            <button
              role="tab"
              aria-selected={tier === "enterprise"}
              className={`tier-btn ${tier === "enterprise" ? "is-active" : ""}`}
              onClick={() => setTier("enterprise")}
              type="button"
            >
              Enterprise
              <span className="tier-btn-sub">times comerciais</span>
            </button>
            <span className="tier-pill" data-pos={tier} aria-hidden="true" />
          </div>
        </div>

        <div className="surfaces-grid">
          {SURFACES.map((s, i) => (
            <button
              key={s.key}
              className={`surface-card magnetic ${active === i ? "is-active" : ""}`}
              onClick={() => setActive(i)}
              onMouseEnter={() => setActive(i)}
              aria-pressed={active === i}
              type="button"
            >
              <div className="surface-tag mono">{s.tag}</div>
              <div className="surface-name">{s.name}</div>
              <div className="surface-desc">{s.desc}</div>
              <div className="surface-tech">
                {s.tech.map((t) => (
                  <span key={t} className="surface-tech-chip">
                    {t}
                  </span>
                ))}
              </div>
              <SurfaceVisual kind={s.key} active={active === i} />
            </button>
          ))}
        </div>

        <div className="outputs">
          <div className="outputs-head">
            <span className="kicker">
              Saídas · {tier === "core" ? "NORA Core" : "NORA Enterprise"}
            </span>
          </div>
          <div className="outputs-list">
            {OUTPUTS[tier].map((o, idx) => {
              const rowStyle: CSSProperties & Record<"--i", string> = { "--i": String(idx) };
              return (
                <div key={o.label} className="output-row" style={rowStyle}>
                  <span className="output-glyph">{o.glyph}</span>
                  <span>{o.label}</span>
                </div>
              );
            })}
          </div>
        </div>
      </div>

      <style jsx>{`
        .surfaces-head {
          display: flex;
          justify-content: space-between;
          align-items: end;
          gap: 32px;
          margin-bottom: 56px;
        }
        .surfaces-head > div:first-child {
          flex: 1;
          max-width: 640px;
        }
        .tier-toggle {
          position: relative;
          display: inline-flex;
          padding: 4px;
          border: 1px solid var(--border);
          border-radius: 999px;
          background: var(--surface);
        }
        .tier-btn {
          position: relative;
          z-index: 1;
          padding: 10px 18px;
          background: transparent;
          border: 0;
          color: var(--fg-muted);
          font-size: 13px;
          font-weight: 500;
          cursor: pointer;
          border-radius: 999px;
          transition: color 200ms var(--ease);
          display: flex;
          flex-direction: column;
          align-items: center;
          line-height: 1.1;
        }
        .tier-btn-sub {
          font-family: var(--font-mono);
          font-size: 9px;
          letter-spacing: 0.1em;
          text-transform: uppercase;
          margin-top: 2px;
          opacity: 0.7;
        }
        .tier-btn.is-active {
          color: var(--bg);
        }
        .tier-pill {
          position: absolute;
          top: 4px;
          bottom: 4px;
          width: calc(50% - 4px);
          border-radius: 999px;
          background: var(--accent);
          transition: transform 380ms var(--ease-out-expo);
        }
        .tier-pill[data-pos="core"] {
          transform: translateX(0);
          left: 4px;
        }
        .tier-pill[data-pos="enterprise"] {
          transform: translateX(100%);
          left: 4px;
        }

        .surfaces-grid {
          display: grid;
          grid-template-columns: repeat(3, 1fr);
          gap: 16px;
          margin-bottom: 56px;
        }
        .surface-card {
          position: relative;
          text-align: left;
          padding: 24px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: var(--radius);
          color: inherit;
          cursor: pointer;
          transition: all 280ms var(--ease);
          display: flex;
          flex-direction: column;
          gap: 12px;
          min-height: 320px;
          overflow: hidden;
        }
        .surface-card.is-active {
          border-color: var(--accent-dim);
          background: linear-gradient(
            180deg,
            var(--surface),
            color-mix(in oklab, var(--accent) 6%, var(--surface))
          );
          transform: translateY(-2px);
        }
        .surface-tag {
          font-size: 10px;
          letter-spacing: 0.16em;
          color: var(--fg-dim);
        }
        .surface-name {
          font-size: 24px;
          font-weight: 500;
          letter-spacing: -0.02em;
        }
        .surface-desc {
          font-size: 14px;
          color: var(--fg-muted);
          line-height: 1.5;
        }
        .surface-tech {
          display: flex;
          gap: 6px;
          flex-wrap: wrap;
          margin-top: 4px;
        }
        .surface-tech-chip {
          font-family: var(--font-mono);
          font-size: 10px;
          padding: 3px 8px;
          background: var(--bg-deep);
          border: 1px solid var(--border);
          border-radius: 4px;
          color: var(--fg-muted);
        }

        .outputs {
          padding: 28px 32px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: var(--radius-lg);
        }
        .outputs-head {
          margin-bottom: 16px;
        }
        .outputs-list {
          display: grid;
          grid-template-columns: 1fr 1fr;
          gap: 4px 32px;
        }
        .output-row {
          display: flex;
          align-items: center;
          gap: 14px;
          padding: 10px 0;
          border-bottom: 1px solid var(--border);
          font-size: 15px;
          animation: outputIn 380ms var(--ease-out-expo) backwards;
          animation-delay: calc(var(--i) * 50ms);
        }
        .output-row:last-child,
        .output-row:nth-last-child(2) {
          border-bottom: 0;
        }
        .output-glyph {
          font-family: var(--font-mono);
          color: var(--accent);
          width: 18px;
          text-align: center;
        }
        @keyframes outputIn {
          from {
            opacity: 0;
            transform: translateY(8px);
          }
          to {
            opacity: 1;
            transform: none;
          }
        }

        @media (max-width: 980px) {
          .surfaces-head {
            flex-direction: column;
            align-items: start;
          }
          .surfaces-grid {
            grid-template-columns: 1fr;
          }
          .outputs-list {
            grid-template-columns: 1fr;
          }
        }
      `}</style>
    </section>
  );
}
