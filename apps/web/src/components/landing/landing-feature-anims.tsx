"use client";

import { useEffect, useMemo, useState } from "react";

// ── 1 · PII Shield ──
function PIIShieldAnim() {
  return (
    <div className="fa fa-pii">
      <div className="fa-pii-text">
        <span>{'"O '}</span>
        <span className="pii-span" data-kind="person">
          <span className="pii-orig">DPO da Camila</span>
          <span className="pii-mask">[[PERSON_1]]</span>
        </span>
        <span> exige redação do CPF </span>
        <span className="pii-span" data-kind="cpf">
          <span className="pii-orig">123.456.789-00</span>
          <span className="pii-mask">[[CPF_1]]</span>
        </span>
        <span> nos campos livres. Mandei mensagem pro </span>
        <span className="pii-span" data-kind="email">
          <span className="pii-orig">bruno@totvs.com.br</span>
          <span className="pii-mask">[[EMAIL_1]]</span>
        </span>
        <span>{'"'}</span>
      </div>
      <div className="fa-pii-meta">
        <span className="fa-pii-badge">
          <span className="fa-pii-shield">
            <svg
              width="11"
              height="11"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" />
            </svg>
          </span>
          PII Shield ativo
        </span>
        <span className="fa-pii-count">3 detectados</span>
      </div>
    </div>
  );
}

// ── 2 · Productivity Score ──
function ProductivityAnim() {
  const [score, setScore] = useState(0);

  useEffect(() => {
    let raf = 0;
    let start: number | null = null;
    function loop(ts: number) {
      if (start === null) start = ts;
      const elapsed = (ts - start) / 1000;
      const t = elapsed % 8;
      let v: number;
      if (t < 2.2) v = (t / 2.2) * 82;
      else if (t < 6.5) v = 82;
      else v = 82 * (1 - (t - 6.5) / 1.5);
      setScore(Math.max(0, Math.round(v)));
      raf = requestAnimationFrame(loop);
    }
    raf = requestAnimationFrame(loop);
    return () => cancelAnimationFrame(raf);
  }, []);

  const r = 32;
  const c = 2 * Math.PI * r;
  const dash = c * (score / 100);

  return (
    <div className="fa fa-prod">
      <div className="fa-prod-gauge">
        <svg width="92" height="92" viewBox="0 0 92 92">
          <circle cx="46" cy="46" r={r} fill="none" stroke="var(--chip)" strokeWidth="5" />
          <circle
            cx="46"
            cy="46"
            r={r}
            fill="none"
            stroke={score < 40 ? "#c97766" : score < 70 ? "#d4a04c" : "#62b585"}
            strokeWidth="5"
            strokeLinecap="round"
            strokeDasharray={`${dash} ${c}`}
            transform="rotate(-90 46 46)"
            style={{ transition: "stroke 240ms ease" }}
          />
        </svg>
        <div className="fa-prod-num">{score}</div>
      </div>
      <div className="fa-prod-outcomes">
        <div className="fa-prod-row" style={{ animationDelay: "2.4s" }}>
          <span className="fa-prod-check fa-addressed">✓</span>
          <span>Alinhar escopo</span>
        </div>
        <div className="fa-prod-row" style={{ animationDelay: "3.0s" }}>
          <span className="fa-prod-check fa-addressed">✓</span>
          <span>Confirmar regras de PII</span>
        </div>
        <div className="fa-prod-row" style={{ animationDelay: "3.6s" }}>
          <span className="fa-prod-check fa-partial">◐</span>
          <span>Definir métricas</span>
        </div>
        <div className="fa-prod-row" style={{ animationDelay: "4.2s" }}>
          <span className="fa-prod-check fa-missed">○</span>
          <span>Travar go-live</span>
        </div>
      </div>
    </div>
  );
}

// ── 3 · Action items ──
function ActionsAnim() {
  return (
    <div className="fa fa-actions">
      <div className="fa-quote">
        <span className="fa-quote-speaker">Camila · 14:31</span>
        <span className="fa-quote-text">
          {'"Mando a proposta do piloto pra TOTVS na segunda, Rafael fica responsável."'}
        </span>
      </div>
      <div className="fa-arrow">
        <svg
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="1.8"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <line x1="12" y1="5" x2="12" y2="19" />
          <polyline points="5 12 12 19 19 12" />
        </svg>
      </div>
      <div className="fa-task">
        <span className="fa-task-check" />
        <div className="fa-task-body">
          <div className="fa-task-text">Enviar proposta do piloto pra TOTVS</div>
          <div className="fa-task-meta">
            <span>Rafael</span>
            <span>Segunda</span>
            <span className="fa-task-pri">HIGH</span>
          </div>
        </div>
      </div>
    </div>
  );
}

// ── 4 · MCP integrations ──
function MCPIcon({ kind }: { kind: string }) {
  if (kind === "linear")
    return (
      <svg
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <polyline points="3 12 9 6 15 12 21 6" />
      </svg>
    );
  if (kind === "calendar")
    return (
      <svg
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.8"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <rect x="3" y="4" width="18" height="18" rx="2" />
        <line x1="16" y1="2" x2="16" y2="6" />
        <line x1="8" y1="2" x2="8" y2="6" />
        <line x1="3" y1="10" x2="21" y2="10" />
      </svg>
    );
  if (kind === "jira")
    return (
      <svg
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <polygon points="12 2 22 22 2 22" />
      </svg>
    );
  return (
    <svg
      width="14"
      height="14"
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
      strokeLinecap="round"
      strokeLinejoin="round"
    >
      <path d="M9 19c-5 1.5-5-2.5-7-3m14 6v-3.87a3.37 3.37 0 0 0-.94-2.61c3.14-.35 6.44-1.54 6.44-7A5.44 5.44 0 0 0 20 4.77 5.07 5.07 0 0 0 19.91 1S18.73.65 16 2.48a13.38 13.38 0 0 0-7 0C6.27.65 5.09 1 5.09 1A5.07 5.07 0 0 0 5 4.77a5.44 5.44 0 0 0-1.5 3.78c0 5.42 3.3 6.61 6.44 7A3.37 3.37 0 0 0 9 18.13V22" />
    </svg>
  );
}

function MCPAnim() {
  const lines = [
    { x1: 160, y1: 110, x2: 50, y2: 50, d: 0 },
    { x1: 160, y1: 110, x2: 270, y2: 50, d: 0.4 },
    { x1: 160, y1: 110, x2: 50, y2: 170, d: 0.8 },
    { x1: 160, y1: 110, x2: 270, y2: 170, d: 1.2 },
  ];
  const apps = [
    { id: "linear", label: "Linear", x: "15%", y: "23%" },
    { id: "calendar", label: "Calendar", x: "85%", y: "23%" },
    { id: "jira", label: "Jira", x: "15%", y: "77%" },
    { id: "github", label: "GitHub", x: "85%", y: "77%" },
  ];
  return (
    <div className="fa fa-mcp">
      <svg className="fa-mcp-svg" viewBox="0 0 320 220">
        {lines.map((l, i) => (
          <g key={i}>
            <line
              x1={l.x1}
              y1={l.y1}
              x2={l.x2}
              y2={l.y2}
              stroke="var(--border)"
              strokeWidth="1"
              strokeDasharray="3 3"
            />
            <circle r="3" fill="var(--accent)">
              <animateMotion
                dur="3s"
                repeatCount="indefinite"
                begin={`${l.d}s`}
                path={`M${l.x1},${l.y1} L${l.x2},${l.y2}`}
              />
            </circle>
          </g>
        ))}
      </svg>
      <div className="fa-mcp-center">
        <span className="logo-bars" aria-hidden="true">
          <span style={{ width: 3, height: "40%", borderRadius: 3 }} />
          <span style={{ width: 3, height: "75%", borderRadius: 3 }} />
          <span style={{ width: 3, height: "100%", borderRadius: 3 }} />
          <span style={{ width: 3, height: "75%", borderRadius: 3 }} />
          <span style={{ width: 3, height: "40%", borderRadius: 3 }} />
        </span>
      </div>
      {apps.map((app) => (
        <div key={app.id} className="fa-mcp-app" style={{ left: app.x, top: app.y }}>
          <MCPIcon kind={app.id} />
          <span>{app.label}</span>
        </div>
      ))}
    </div>
  );
}

// ── 5 · Desktop real-time ──
function DesktopAnim() {
  const [timer, setTimer] = useState("00:24");
  useEffect(() => {
    let s = 24;
    const id = setInterval(() => {
      s = (s + 1) % 100;
      const m = Math.floor(s / 60);
      const sec = s % 60;
      setTimer(`${String(m).padStart(2, "0")}:${String(sec).padStart(2, "0")}`);
    }, 900);
    return () => clearInterval(id);
  }, []);

  const bars = useMemo(
    () => Array.from({ length: 22 }, (_, i) => ({ delay: (i * 73) % 1400 })),
    [],
  );

  return (
    <div className="fa fa-desktop">
      <div className="fa-window">
        <div className="fa-window-chrome">
          <span className="fa-tl tl-r" />
          <span className="fa-tl tl-y" />
          <span className="fa-tl tl-g" />
        </div>
        <div className="fa-window-body">
          <div className="fa-rec-bar">
            <span className="fa-rec-dot" />
            <span>Capturando áudio</span>
            <span className="fa-rec-timer">{timer}</span>
          </div>
          <div className="fa-rec-wave">
            {bars.map((b, i) => (
              <span key={i} style={{ animationDelay: `-${b.delay}ms` }} />
            ))}
          </div>
          <div className="fa-rec-text">
            <span>{'"…então a proposta da'}</span>
            <span className="fa-rec-typing">Veridian fecha em </span>
            <span className="fa-rec-cursor">|</span>
          </div>
        </div>
      </div>
    </div>
  );
}

// ── 6 · PT-BR slangs ──
function PTBRAnim() {
  return (
    <div className="fa fa-ptbr">
      <div className="fa-ptbr-line">
        <span>{'"O '}</span>
        <span className="slang slang-1">
          AE<span className="slang-tip">Account Executive</span>
        </span>
        <span> abriu um </span>
        <span className="slang slang-2">
          PR<span className="slang-tip">Pull Request</span>
        </span>
        <span> pra ajustar a integração</span>
      </div>
      <div className="fa-ptbr-line line-2">
        <span>do </span>
        <span className="slang slang-3">
          ADR 0012<span className="slang-tip">Architecture Decision Record</span>
        </span>
        <span>. </span>
        <span className="slang slang-4">
          Beleza<span className="slang-tip">{'"Ok, combinado"'}</span>
        </span>
        <span>, mando até quinta.&quot;</span>
      </div>
      <div className="fa-ptbr-tag">
        <svg
          width="11"
          height="11"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <circle cx="12" cy="12" r="9" />
          <path d="M3 12h18M12 3a14 14 0 0 1 0 18M12 3a14 14 0 0 0 0 18" />
        </svg>
        <span>PT-BR · gírias técnicas reconhecidas</span>
      </div>
    </div>
  );
}

const FEATURES = [
  {
    anim: <PIIShieldAnim />,
    title: "PII Shield",
    body: "Redação automática de CPF, CNPJ, e-mail, telefone, cartão e nomes brasileiros antes do LLM externo. ADR 0012.",
  },
  {
    anim: <ProductivityAnim />,
    title: "Productivity Score",
    body: "Declare o objetivo da reunião; NORA mede cobertura real (Addressed / Partial / Missed) e devolve score 0–100.",
  },
  {
    anim: <ActionsAnim />,
    title: "Action items inteligentes",
    body: "Detecção com confiança calibrada, citação textual da fonte, prioridade e vencimento estimado.",
  },
  {
    anim: <MCPAnim />,
    title: "Integração via MCP",
    body: "Empurre tasks pro Linear/Jira, registre resumo no Calendar, sincronize com Salesforce — tudo via Model Context Protocol.",
  },
  {
    anim: <DesktopAnim />,
    title: "Desktop real-time",
    body: "App Tauri captura áudio do sistema enquanto a reunião acontece — Windows, macOS, Linux.",
  },
  {
    anim: <PTBRAnim />,
    title: "Em português, na sua língua",
    body: "Treinado em PT-BR. Reconhece gírias, regionalismos, siglas técnicas brasileiras.",
  },
];

/** Grid de features com 6 mini-animações. */
export function LandingFeatures() {
  return (
    <section id="produto">
      <div className="container">
        <div style={{ maxWidth: 720 }}>
          <div className="section-label">Produto</div>
          <h2 className="section-title">Construído pra como você realmente trabalha.</h2>
          <p className="section-subtitle">
            Cada feature responde a uma dor concreta — não a uma checklist genérica de IA.
          </p>
        </div>
        <div className="features-grid-v2">
          {FEATURES.map((f) => (
            <div className="feature-v2" key={f.title}>
              <div className="feature-v2-stage">{f.anim}</div>
              <div className="feature-v2-meta">
                <h3>{f.title}</h3>
                <p>{f.body}</p>
              </div>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
