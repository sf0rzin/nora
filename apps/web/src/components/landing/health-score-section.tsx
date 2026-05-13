"use client";

import { useMemo, useState } from "react";

/* =========================================================
   Types
   ========================================================= */

type HealthPoint = {
  x: number;
  y: number;
  label: string;
  events: string[];
  danger?: boolean;
  success?: boolean;
};

/* =========================================================
   Data
   ========================================================= */

const POINTS: HealthPoint[] = [
  { x: 0, y: 78, label: "Discovery", events: ["intro call"] },
  { x: 12, y: 82, label: "Proposta", events: ["envio proposta"] },
  { x: 24, y: 75, label: "Follow-up", events: ["dúvida técnica"] },
  { x: 36, y: 68, label: "Negociação", events: ["objeção preço"] },
  { x: 48, y: 58, label: "Churn risk", events: ["menção concorrente", "tom hesitante"], danger: true },
  { x: 60, y: 71, label: "Recuperação", events: ["NBA executada", "demo técnica"] },
  { x: 72, y: 84, label: "Closed-won", events: ["assinatura"], success: true },
];

const W = 800;
const H = 280;

/* =========================================================
   Section
   ========================================================= */

export function HealthScoreSection() {
  const [hover, setHover] = useState<number | null>(null);

  const path = useMemo<string>(() => {
    const px = (p: HealthPoint): number => (p.x / 80) * W;
    const py = (p: HealthPoint): number => H - (p.y / 100) * H + 30;
    const first = POINTS[0];
    if (!first) return "";
    let d = `M ${px(first)} ${py(first)}`;
    for (let i = 1; i < POINTS.length; i++) {
      const prev = POINTS[i - 1];
      const cur = POINTS[i];
      if (!prev || !cur) continue;
      const cx = (px(prev) + px(cur)) / 2;
      d += ` Q ${cx} ${py(prev)}, ${cx} ${(py(prev) + py(cur)) / 2}`;
      d += ` Q ${cx} ${py(cur)}, ${px(cur)} ${py(cur)}`;
    }
    return d;
  }, []);

  const fillPath = useMemo<string>(() => {
    const px = (p: HealthPoint): number => (p.x / 80) * W;
    const py = (p: HealthPoint): number => H - (p.y / 100) * H + 30;
    const first = POINTS[0];
    const last = POINTS[POINTS.length - 1];
    if (!first || !last) return "";
    let d = `M ${px(first)} ${H + 30}`;
    d += ` L ${px(first)} ${py(first)}`;
    for (let i = 1; i < POINTS.length; i++) {
      const prev = POINTS[i - 1];
      const cur = POINTS[i];
      if (!prev || !cur) continue;
      const cx = (px(prev) + px(cur)) / 2;
      d += ` Q ${cx} ${py(prev)}, ${cx} ${(py(prev) + py(cur)) / 2}`;
      d += ` Q ${cx} ${py(cur)}, ${px(cur)} ${py(cur)}`;
    }
    d += ` L ${px(last)} ${H + 30} Z`;
    return d;
  }, []);

  const hoveredPoint = hover !== null ? POINTS[hover] : null;

  return (
    <section className="section" id="health" data-screen-label="05 Account Health">
      <div className="container">
        <div className="hs-head">
          <div>
            <div className="eyebrow">Account Health Score</div>
            <h2 className="h-section">
              Detecte degradação de sentimento
              <br />
              <span style={{ color: "var(--fg-muted)" }}>antes do churn acontecer.</span>
            </h2>
            <p className="h-sub">
              Confidence Score por reunião alimenta o Health Score temporal da conta. A NORA
              correlaciona objeções, menções a concorrentes e mudanças de tom ao longo de múltiplas
              reuniões.
            </p>
          </div>
          <div className="hs-meta">
            <div className="hs-meta-item">
              <div className="kicker">Account</div>
              <div className="hs-meta-val">Acme Corp</div>
            </div>
            <div className="hs-meta-item">
              <div className="kicker">Owner</div>
              <div className="hs-meta-val">Mariana A.</div>
            </div>
            <div className="hs-meta-item">
              <div className="kicker">Stage</div>
              <div className="hs-meta-val">Closed-won</div>
            </div>
          </div>
        </div>

        <div className="hs-chart-wrap">
          <div className="hs-axis-y">
            <span>100</span>
            <span>75</span>
            <span>50</span>
            <span>25</span>
            <span>0</span>
          </div>
          <svg viewBox={`0 0 ${W} ${H + 40}`} className="hs-chart">
            <defs>
              <linearGradient id="hsFill" x1="0" x2="0" y1="0" y2="1">
                <stop offset="0%" stopColor="oklch(0.70 0.17 248)" stopOpacity="0.35" />
                <stop offset="100%" stopColor="oklch(0.70 0.17 248)" stopOpacity="0" />
              </linearGradient>
              <linearGradient id="hsLine" x1="0" x2="1">
                <stop offset="0%" stopColor="oklch(0.78 0.16 248)" />
                <stop offset="55%" stopColor="oklch(0.68 0.20 25)" />
                <stop offset="78%" stopColor="oklch(0.78 0.16 248)" />
                <stop offset="100%" stopColor="oklch(0.72 0.16 155)" />
              </linearGradient>
            </defs>
            {[80, 140, 200, 260].map((y) => (
              <line
                key={y}
                x1="0"
                x2={W}
                y1={y}
                y2={y}
                stroke="var(--border)"
                strokeDasharray="2 4"
              />
            ))}
            <path d={fillPath} fill="url(#hsFill)" />
            <path
              d={path}
              fill="none"
              stroke="url(#hsLine)"
              strokeWidth="2.5"
              strokeLinecap="round"
              className="hs-line"
            />
            {POINTS.map((p, i) => {
              const cx = (p.x / 80) * W;
              const cy = H - (p.y / 100) * H + 30;
              const isHover = hover === i;
              return (
                <g
                  key={i}
                  onMouseEnter={() => setHover(i)}
                  onMouseLeave={() => setHover(null)}
                  style={{ cursor: "pointer" }}
                >
                  <circle
                    cx={cx}
                    cy={cy}
                    r={isHover ? 24 : 14}
                    fill="var(--accent)"
                    opacity={isHover ? 0.18 : 0}
                  />
                  <circle
                    cx={cx}
                    cy={cy}
                    r={isHover ? 7 : 5}
                    fill={p.danger ? "var(--danger)" : p.success ? "var(--success)" : "var(--accent)"}
                    stroke="var(--bg)"
                    strokeWidth="2"
                  />
                  {isHover && (
                    <g>
                      <rect
                        x={cx - 90}
                        y={cy - 80}
                        width="180"
                        height="56"
                        rx="8"
                        fill="var(--surface)"
                        stroke="var(--border-strong)"
                      />
                      <text
                        x={cx}
                        y={cy - 58}
                        textAnchor="middle"
                        fill="var(--fg)"
                        fontSize="13"
                        fontWeight="500"
                      >
                        {p.label}
                      </text>
                      <text
                        x={cx}
                        y={cy - 40}
                        textAnchor="middle"
                        fill="var(--fg-muted)"
                        fontSize="11"
                        fontFamily="var(--font-mono)"
                      >
                        score · {p.y}
                      </text>
                    </g>
                  )}
                </g>
              );
            })}
          </svg>
          <div className="hs-axis-x">
            {POINTS.map((p) => (
              <div
                key={p.x}
                className="hs-axis-x-item"
                style={{ left: `${(p.x / 80) * 100}%` }}
              >
                <span className="mono">{p.label}</span>
              </div>
            ))}
          </div>
        </div>

        <div className="hs-events">
          {hoveredPoint &&
            hoveredPoint.events.map((e, i) => (
              <div key={i} className="hs-event">
                <span className="mono kicker">evento</span>
                <span>{e}</span>
              </div>
            ))}
          {!hoveredPoint && (
            <div className="hs-event hs-event--hint">
              <span className="mono kicker">dica</span>
              <span>
                Passe o mouse sobre os pontos para ver os eventos detectados em cada reunião.
              </span>
            </div>
          )}
        </div>
      </div>

      <style jsx>{`
        .hs-head {
          display: grid;
          grid-template-columns: 1.2fr 1fr;
          gap: 32px;
          align-items: end;
          margin-bottom: 56px;
        }
        .hs-meta {
          display: flex;
          gap: 32px;
          padding: 20px 24px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: var(--radius);
        }
        .hs-meta-val {
          font-size: 16px;
          font-weight: 500;
          margin-top: 4px;
        }
        .hs-chart-wrap {
          position: relative;
          padding: 32px 32px 56px 56px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: var(--radius-lg);
        }
        .hs-axis-y {
          position: absolute;
          left: 16px;
          top: 32px;
          bottom: 56px;
          display: flex;
          flex-direction: column;
          justify-content: space-between;
          font-family: var(--font-mono);
          font-size: 10px;
          color: var(--fg-dim);
        }
        .hs-chart {
          width: 100%;
          /* Aspect-ratio mantem proporcao viewBox 800x320 sem distorcer.
           * Antes: height: 320px fixo + preserveAspectRatio="none" no SVG
           * esticava horizontalmente em telas largas. */
          aspect-ratio: 800 / 320;
          height: auto;
          display: block;
          overflow: visible;
        }
        .hs-line {
          stroke-dasharray: 2000;
          stroke-dashoffset: 2000;
          animation: drawLine 2s var(--ease-out-expo) forwards;
        }
        @keyframes drawLine {
          to {
            stroke-dashoffset: 0;
          }
        }
        .hs-axis-x {
          position: relative;
          height: 24px;
          margin-top: 8px;
        }
        .hs-axis-x-item {
          position: absolute;
          transform: translateX(-50%);
          font-size: 11px;
          color: var(--fg-dim);
          letter-spacing: 0.04em;
        }
        .hs-events {
          margin-top: 24px;
          display: flex;
          gap: 12px;
          flex-wrap: wrap;
          min-height: 36px;
        }
        .hs-event {
          display: inline-flex;
          align-items: center;
          gap: 12px;
          padding: 8px 14px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: 999px;
          font-size: 13px;
        }
        .hs-event--hint {
          color: var(--fg-muted);
        }

        @media (max-width: 880px) {
          .hs-head {
            grid-template-columns: 1fr;
          }
          .hs-axis-x-item {
            font-size: 9px;
          }
        }
      `}</style>
    </section>
  );
}
