"use client";

import { useState } from "react";

type Kind = "decision" | "action" | "person" | "competitor";

const SPOTLIGHT_TOKENS: { text: string; kind: Kind | null }[] = [
  { text: "Camila", kind: "person" },
  { text: " levantou que o ", kind: null },
  { text: "DPO exige redação de CPF mesmo em campos livres", kind: "decision" },
  { text: ". ", kind: null },
  { text: "Rafael", kind: "person" },
  { text: " vai ", kind: null },
  { text: "mandar a proposta do piloto na segunda", kind: "action" },
  { text: ". O cliente comentou que ", kind: null },
  { text: "Gong", kind: "competitor" },
  { text: " custa três vezes mais. Combinamos ", kind: null },
  { text: "data de go-live na última semana de maio", kind: "decision" },
  { text: ". Documentar tudo no ADR 0012.", kind: null },
];

const KIND_META: Record<Kind, { label: string; color: string; bg: string }> = {
  decision: { label: "Decisão", color: "var(--accent-ink)", bg: "var(--accent-soft)" },
  action: { label: "Action item", color: "#3f8a5e", bg: "rgba(98,181,133,0.16)" },
  person: { label: "PII · nome", color: "#a04c3e", bg: "rgba(201,119,102,0.16)" },
  competitor: { label: "Concorrente", color: "#a37528", bg: "rgba(212,160,76,0.18)" },
};

function WordSpotlight() {
  const [active, setActive] = useState<number | null>(null);
  return (
    <div className="demo-spot">
      <div className="demo-spot-text">
        {SPOTLIGHT_TOKENS.map((t, i) => {
          if (!t.kind) return <span key={i}>{t.text}</span>;
          const meta = KIND_META[t.kind];
          const isActive = active === i;
          return (
            <span
              key={i}
              className="spot-tok"
              data-kind={t.kind}
              onMouseEnter={() => setActive(i)}
              onMouseLeave={() => setActive(null)}
              style={{
                background: isActive ? meta.bg : "transparent",
                color: isActive ? meta.color : "var(--ink)",
                boxShadow: isActive
                  ? `inset 0 -1.5px 0 ${meta.color}`
                  : `inset 0 -1.5px 0 ${meta.bg}`,
              }}
            >
              {t.text}
              {isActive && (
                <span className="spot-tag" style={{ background: meta.color }}>
                  {meta.label}
                </span>
              )}
            </span>
          );
        })}
      </div>
      <div className="demo-spot-legend">
        {(Object.entries(KIND_META) as [Kind, (typeof KIND_META)[Kind]][]).map(([k, m]) => (
          <span key={k} className="legend-chip">
            <span className="legend-dot" style={{ background: m.color }} />
            {m.label}
          </span>
        ))}
        <span className="legend-hint">Passe o mouse sobre qualquer palavra destacada.</span>
      </div>
    </div>
  );
}

/** Interactive demo section (Word Spotlight variant). */
export function LandingDemo() {
  return (
    <section id="demo">
      <div className="container">
        <div style={{ maxWidth: 720 }}>
          <div className="section-label">Demonstração</div>
          <h2 className="section-title">Da conversa à inteligência, palavra por palavra.</h2>
          <p className="section-subtitle">
            A Nora classifica cada trecho — decisão, action item, PII, concorrente — e devolve tudo
            estruturado. Passe o mouse pra ver.
          </p>
        </div>
        <div className="demo-shell-v2">
          <WordSpotlight />
        </div>
      </div>
    </section>
  );
}
