"use client";

import { useEffect, useMemo, useRef, useState, type CSSProperties, type JSX } from "react";

/* =========================================================
   Types
   ========================================================= */

type MarkKind = "buying" | "context" | "concern" | "competitor" | "objection";

type Mark = {
  match: string;
  kind: MarkKind;
  label: string;
};

type TranscriptTurn = {
  speaker: string;
  initials: string;
  avatarColor: string;
  role: string;
  time: string;
  color: string;
  text: string;
  marks: Mark[];
};

type Detected = {
  id: string;
  kind: MarkKind;
  match: string;
  label: string;
  line: number;
};

type PlaybackState = {
  lineIdx: number;
  charIdx: number;
  detected: Detected[];
  confidence: number;
};

type KindStyle = { color: string; bg: string };

/* =========================================================
   Data
   ========================================================= */

const TRANSCRIPT: TranscriptTurn[] = [
  {
    speaker: "João Silva",
    initials: "JS",
    avatarColor: "oklch(0.65 0.14 30)",
    role: "Acme Corp",
    time: "14:22",
    color: "var(--fg-muted)",
    text: "Mariana, estamos animados com a proposta. Mas eu queria entender melhor como funciona a integração com o nosso Protheus.",
    marks: [
      { match: "animados com a proposta", kind: "buying", label: "Sinal de compra" },
      { match: "Protheus", kind: "context", label: "Catálogo · TOTVS" },
      { match: "integração", kind: "concern", label: "Preocupação técnica" },
    ],
  },
  {
    speaker: "Mariana Alves",
    initials: "MA",
    avatarColor: "oklch(0.62 0.16 248)",
    role: "Você · AE",
    time: "14:23",
    color: "var(--fg)",
    text: "O módulo de integração é nativo, João. Conector pronto pra Protheus em cloud, com SSO via Entra ID — sem reescrita.",
    marks: [],
  },
  {
    speaker: "João Silva",
    initials: "JS",
    avatarColor: "oklch(0.65 0.14 30)",
    role: "Acme Corp",
    time: "14:24",
    color: "var(--fg-muted)",
    text: "O concorrente X disse que entrega isso em duas semanas. Vocês conseguem prazo similar?",
    marks: [
      { match: "concorrente X", kind: "competitor", label: "Concorrente · avaliando" },
      { match: "duas semanas", kind: "objection", label: "Objeção · prazo" },
    ],
  },
];

const KIND_STYLE: Record<MarkKind, KindStyle> = {
  buying: { color: "oklch(0.78 0.16 155)", bg: "oklch(0.78 0.16 155 / 0.14)" },
  context: { color: "var(--accent-bright)", bg: "var(--accent-glow)" },
  concern: { color: "oklch(0.82 0.16 70)", bg: "oklch(0.82 0.16 70 / 0.14)" },
  competitor: { color: "oklch(0.74 0.20 25)", bg: "oklch(0.74 0.20 25 / 0.14)" },
  objection: { color: "oklch(0.74 0.20 25)", bg: "oklch(0.74 0.20 25 / 0.14)" },
};

const KIND_LABEL_SHORT: Record<MarkKind, string> = {
  buying: "Buying signal",
  context: "Product context",
  concern: "Technical concern",
  competitor: "Competitor",
  objection: "Objection",
};

const KIND_DETAIL: Record<MarkKind, [string, string]> = {
  buying: ["Sinaliza intenção de compra", "Avançar para proposta comercial"],
  context: ["Referência ao catálogo do tenant", "NORA carregou docs do produto"],
  concern: ["Necessita resposta técnica", "Sugerir agendar demo do módulo"],
  competitor: ["Cliente em avaliação de alternativas", "Reforce diferenciais e prazos"],
  objection: ["Risco de perder o deal", "Endereçar imediatamente na conversa"],
};

const KIND_ICON: Record<MarkKind, JSX.Element> = {
  buying: (
    <svg viewBox="0 0 16 16" fill="none">
      <path
        d="M2 11l4-4 3 3 5-6"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  ),
  context: (
    <svg viewBox="0 0 16 16" fill="none">
      <circle cx="8" cy="8" r="5" stroke="currentColor" strokeWidth="1.5" />
      <circle cx="8" cy="8" r="2" fill="currentColor" />
    </svg>
  ),
  concern: (
    <svg viewBox="0 0 16 16" fill="none">
      <path d="M8 4v4M8 11v.5" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
      <circle cx="8" cy="8" r="6" stroke="currentColor" strokeWidth="1.4" />
    </svg>
  ),
  competitor: (
    <svg viewBox="0 0 16 16" fill="none">
      <path d="M3 13l5-9 5 9H3z" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
    </svg>
  ),
  objection: (
    <svg viewBox="0 0 16 16" fill="none">
      <path
        d="M5 5l6 6M11 5l-6 6"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
      />
    </svg>
  ),
};

/* =========================================================
   Hook: transcript playback (typing letra-a-letra com restart)
   ========================================================= */

function useTranscriptPlayback(): PlaybackState {
  const [state, setState] = useState<PlaybackState>({
    lineIdx: 0,
    charIdx: 0,
    detected: [],
    confidence: 0,
  });

  useEffect(() => {
    let cancelled = false;
    let timeout: ReturnType<typeof setTimeout> | undefined;

    const wait = (ms: number): Promise<void> =>
      new Promise((resolve) => {
        timeout = setTimeout(resolve, ms);
      });

    async function run(): Promise<void> {
      const detected: Detected[] = [];
      let confidence = 0;
      for (let li = 0; li < TRANSCRIPT.length; li++) {
        const line = TRANSCRIPT[li];
        if (!line) continue;
        const speed = line.color === "var(--fg)" ? 18 : 24;
        for (let ci = 0; ci <= line.text.length; ci++) {
          if (cancelled) return;
          const just = line.marks.find((m) => {
            const start = line.text.indexOf(m.match);
            return (
              start >= 0 &&
              start + m.match.length === ci &&
              !detected.find((d) => d.id === `${li}-${m.match}`)
            );
          });
          if (just) {
            detected.push({
              id: `${li}-${just.match}`,
              kind: just.kind,
              match: just.match,
              label: just.label,
              line: li,
            });
            const delta =
              just.kind === "buying"
                ? 0.18
                : just.kind === "objection" || just.kind === "competitor"
                  ? -0.1
                  : just.kind === "context"
                    ? 0.08
                    : -0.05;
            confidence = Math.min(0.92, confidence + delta);
            confidence = Math.max(0.3, confidence);
          }
          setState({
            lineIdx: li,
            charIdx: ci,
            detected: [...detected],
            confidence,
          });
          await wait(speed + (Math.random() * 12 - 6));
        }
        await wait(600);
      }
      await wait(4000);
      if (!cancelled) {
        setState({ lineIdx: 0, charIdx: 0, detected: [], confidence: 0 });
        void run();
      }
    }

    void run();
    return () => {
      cancelled = true;
      if (timeout) clearTimeout(timeout);
    };
  }, []);

  return state;
}

/* =========================================================
   Transcript line (typing + inline marks)
   ========================================================= */

type Segment =
  | { kind: "plain"; text: string }
  | { kind: "mark"; mark: Mark & { start: number; end: number }; text: string; complete: boolean };

type TranscriptLineProps = {
  line: TranscriptTurn;
  lineIdx: number;
  currentLine: number;
  currentChar: number;
};

function TranscriptLine({ line, lineIdx, currentLine, currentChar }: TranscriptLineProps) {
  const isCurrent = lineIdx === currentLine;
  const isPast = lineIdx < currentLine;
  const visible = isPast ? line.text.length : isCurrent ? currentChar : 0;
  const text = line.text.slice(0, visible);

  const segments = useMemo<Segment[]>(() => {
    if (!text) return [];
    const sorted = line.marks
      .map((m) => {
        const start = line.text.indexOf(m.match);
        return { ...m, start, end: start + m.match.length };
      })
      .filter((m) => m.start >= 0)
      .sort((a, b) => a.start - b.start);

    const segs: Segment[] = [];
    let cursor = 0;
    for (const m of sorted) {
      if (m.start >= visible) break;
      const visibleEnd = Math.min(m.end, visible);
      if (m.start > cursor) {
        segs.push({
          kind: "plain",
          text: text.slice(cursor, Math.min(m.start, visible)),
        });
      }
      segs.push({
        kind: "mark",
        mark: m,
        text: text.slice(m.start, visibleEnd),
        complete: visibleEnd >= m.end,
      });
      cursor = visibleEnd;
    }
    if (cursor < visible) segs.push({ kind: "plain", text: text.slice(cursor, visible) });
    return segs;
  }, [text, visible, line]);

  const isVisible = isPast || isCurrent;
  if (!isVisible) return null;

  const lineStyle: CSSProperties & Record<"--line-color", string> = {
    "--line-color": line.color,
  };

  return (
    <div className="ts-line" style={lineStyle}>
      <div className="ts-avatar" style={{ background: line.avatarColor }}>
        {line.initials}
      </div>
      <div className="ts-content">
        <div className="ts-meta">
          <span className="ts-speaker">{line.speaker}</span>
          <span className="ts-role">{line.role}</span>
          <span className="ts-time">{line.time}</span>
        </div>
        <div className="ts-text" style={{ color: line.color }}>
          {segments.map((s, i) => {
            if (s.kind === "plain") return <span key={i}>{s.text}</span>;
            const style = KIND_STYLE[s.mark.kind];
            return (
              <span
                key={i}
                className={`ts-mark ${s.complete ? "is-complete" : ""}`}
                style={{
                  background: style.bg,
                  color: style.color,
                  borderBottom: `1.5px solid ${style.color}`,
                }}
              >
                {s.text}
              </span>
            );
          })}
          {isCurrent && currentChar < line.text.length && <span className="ts-caret" />}
        </div>
      </div>
    </div>
  );
}

/* =========================================================
   Hero section (composição final)
   ========================================================= */

export function HeroLiveDemo() {
  const { lineIdx, charIdx, detected, confidence } = useTranscriptPlayback();
  const transcriptRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (transcriptRef.current) {
      transcriptRef.current.scrollTop = transcriptRef.current.scrollHeight;
    }
  }, [lineIdx, charIdx]);

  const elapsed = useMemo(() => {
    const total = TRANSCRIPT.reduce((acc, l) => acc + l.text.length, 0);
    const so_far =
      TRANSCRIPT.slice(0, lineIdx).reduce((a, l) => a + l.text.length, 0) + charIdx;
    const seconds = Math.floor((so_far / total) * 38 * 60);
    const m = Math.floor(seconds / 60);
    const s = seconds % 60;
    return `${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  }, [lineIdx, charIdx]);

  return (
    <section className="hero" id="top" data-screen-label="01 Hero">
      <div className="hero-grid-bg" aria-hidden="true" />
      <div className="container hero-inner">
        <div className="hero-copy">
          <div className="eyebrow hero-eyebrow">
            <span>Inteligência conversacional para times comerciais</span>
          </div>
          <h1 className="hero-tagline">
            Cada reunião vira <em>decisões</em>, <em>ações</em> e <em>receita</em> — com contexto
            <br />
            do <span style={{ whiteSpace: "nowrap" }}>seu negócio</span>.
          </h1>
          <p className="hero-sub">
            A NORA escuta, transcreve e extrai oportunidades, riscos e próximos passos das suas
            reuniões — calibrada ao catálogo da sua empresa, não a um genérico.
          </p>
          <div className="hero-actions">
            <a href="#superficies" className="btn btn-accent magnetic">
              Como funciona
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
            <a href="#cta" className="btn btn-ghost magnetic">
              Falar com vendas
            </a>
          </div>
        </div>

        <div className="hero-demo">
          <div className="hd-window">
            <div className="hd-titlebar">
              <div className="hd-recording">
                <span className="hd-rec-dot" />
                <span className="hd-rec-label">AO VIVO</span>
                <span className="hd-rec-time">{elapsed}</span>
              </div>
              <div className="hd-meeting">
                <span className="hd-meeting-name">Demo técnica · Acme Corp</span>
                <span className="hd-meeting-meta">2 participantes</span>
              </div>
              <div className="hd-actions">
                <span className="hd-action-dot" />
              </div>
            </div>

            <div className="hd-body">
              <div className="hd-main">
                <div className="hd-transcript" ref={transcriptRef}>
                  {TRANSCRIPT.map((line, i) => (
                    <TranscriptLine
                      key={i}
                      line={line}
                      lineIdx={i}
                      currentLine={lineIdx}
                      currentChar={charIdx}
                    />
                  ))}
                  <div className="hd-cmd">
                    <span className="hd-cmd-mention">@NORA</span>
                    <span className="hd-cmd-text">
                      criar oportunidade · agendar follow-up em 48h · atribuir a mim
                    </span>
                  </div>
                </div>
                <div className="hd-toolbar">
                  <div className="hd-toolbar-left">
                    <button className="hd-tb-btn" aria-label="Mute">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7">
                        <path d="M12 2a3 3 0 0 0-3 3v7a3 3 0 0 0 6 0V5a3 3 0 0 0-3-3z" />
                        <path d="M19 10v2a7 7 0 0 1-14 0v-2M12 19v3" />
                      </svg>
                    </button>
                    <button className="hd-tb-btn" aria-label="Camera">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7">
                        <rect x="2" y="6" width="14" height="12" rx="2" />
                        <path d="M22 8l-6 4 6 4V8z" />
                      </svg>
                    </button>
                    <button className="hd-tb-btn" aria-label="Share">
                      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7">
                        <rect x="3" y="3" width="18" height="14" rx="2" />
                        <path d="M8 21h8M12 17v4" />
                      </svg>
                    </button>
                    <span className="hd-tb-divider" />
                    <button className="hd-tb-btn hd-tb-btn--text">
                      <span className="hd-tb-kbd">⌘K</span> Comandos
                    </button>
                  </div>
                  <div className="hd-toolbar-right">
                    <span className="hd-tb-status">
                      <span className="hd-tb-status-dot" />
                      NORA escutando
                    </span>
                  </div>
                </div>
              </div>

              <aside className="hd-side">
                <div className="hd-side-section">
                  <div className="kicker">Confidence Score</div>
                  <div className="hd-score">
                    <div className="hd-score-num">{Math.round(confidence * 100)}</div>
                    <div className="hd-score-bar">
                      <div className="hd-score-fill" style={{ width: `${confidence * 100}%` }} />
                    </div>
                  </div>
                </div>

                <div className="hd-side-section hd-side-section--feed">
                  <div className="kicker">Detectado em tempo real</div>
                  <div className="hd-feed">
                    {detected.length === 0 && (
                      <div className="hd-feed-empty">
                        <span className="hd-feed-pulse" />
                        Aguardando sinais…
                      </div>
                    )}
                    {detected
                      .slice()
                      .reverse()
                      .map((d) => {
                        const style = KIND_STYLE[d.kind];
                        const speaker = TRANSCRIPT[d.line]?.speaker ?? "NORA";
                        const feedStyle: CSSProperties &
                          Record<"--c" | "--bg", string> = {
                          "--c": style.color,
                          "--bg": style.bg,
                        };
                        return (
                          <div key={d.id} className="hd-feed-item" style={feedStyle}>
                            <div className="hd-feed-title">{d.label}</div>
                            <div className="hd-feed-meta">
                              <span className="hd-feed-status">
                                {KIND_ICON[d.kind]}
                                {KIND_LABEL_SHORT[d.kind]}
                              </span>
                              <span>· {speaker} · agora</span>
                            </div>
                            <ul className="hd-feed-bullets">
                              {KIND_DETAIL[d.kind].map((b, j) => (
                                <li key={j}>
                                  <span>{b}</span>
                                </li>
                              ))}
                            </ul>
                          </div>
                        );
                      })}
                  </div>
                </div>
              </aside>
            </div>
          </div>
        </div>
      </div>
      <style jsx>{`
        .hero {
          position: relative;
          min-height: 100vh;
          padding-top: 120px;
          padding-bottom: 80px;
          overflow: hidden;
        }
        .hero-grid-bg {
          position: absolute;
          inset: 0;
          background-image:
            linear-gradient(to right, var(--border) 1px, transparent 1px),
            linear-gradient(to bottom, var(--border) 1px, transparent 1px);
          background-size: 88px 88px;
          mask-image: radial-gradient(ellipse 70% 55% at 50% 30%, black 20%, transparent 75%);
          opacity: 0.3;
          pointer-events: none;
        }
        .hero-inner {
          position: relative;
          width: 100%;
          display: flex;
          flex-direction: column;
          align-items: stretch;
          gap: 56px;
        }
        .hero-copy {
          max-width: 880px;
          margin: 0 auto;
          text-align: center;
          display: flex;
          flex-direction: column;
          align-items: center;
          gap: 24px;
        }
        .hero-eyebrow {
          letter-spacing: 0.16em;
        }
        .hero-tagline {
          font-size: clamp(38px, 5.4vw, 72px);
          line-height: 1.05;
          letter-spacing: -0.035em;
          font-weight: 500;
          margin: 0;
          max-width: 22ch;
          text-wrap: balance;
          color: var(--fg);
        }
        .hero-tagline :global(em) {
          font-family: var(--font-serif);
          font-style: italic;
          font-weight: 400;
          color: var(--accent-bright);
        }
        .hero-sub {
          font-size: clamp(16px, 1.4vw, 19px);
          line-height: 1.55;
          color: var(--fg-muted);
          max-width: 56ch;
          margin: 0;
        }
        .hero-actions {
          display: flex;
          gap: 12px;
          flex-wrap: wrap;
          justify-content: center;
          margin-top: 8px;
        }

        /* ── Live demo window ── */
        .hero-demo {
          width: 100%;
          max-width: 1180px;
          margin: 32px auto 0;
        }
        .hd-window {
          background: var(--bg-deep);
          border: 1px solid var(--border-strong);
          border-radius: 16px;
          overflow: hidden;
          box-shadow:
            0 40px 100px -30px oklch(0 0 0 / 0.6),
            0 0 0 1px var(--border) inset,
            0 0 80px -20px var(--accent-glow);
        }
        .hd-titlebar {
          display: grid;
          grid-template-columns: auto 1fr auto;
          align-items: center;
          gap: 16px;
          padding: 12px 18px;
          background: var(--surface);
          border-bottom: 1px solid var(--border);
        }
        .hd-recording {
          display: inline-flex;
          align-items: center;
          gap: 8px;
          padding: 4px 10px;
          background: oklch(0.74 0.2 25 / 0.12);
          border: 1px solid oklch(0.74 0.2 25 / 0.4);
          border-radius: 999px;
          font-size: 11px;
          font-weight: 500;
          letter-spacing: 0.1em;
          color: oklch(0.78 0.16 25);
        }
        .hd-rec-dot {
          width: 6px;
          height: 6px;
          border-radius: 50%;
          background: oklch(0.74 0.2 25);
          animation: recPulse 1.6s ease-in-out infinite;
        }
        @keyframes recPulse {
          0%,
          100% {
            opacity: 1;
            transform: scale(1);
          }
          50% {
            opacity: 0.4;
            transform: scale(0.85);
          }
        }
        .hd-rec-time {
          font-variant-numeric: tabular-nums;
          color: var(--fg);
          font-weight: 500;
          letter-spacing: 0.05em;
          margin-left: 4px;
        }
        .hd-meeting {
          display: flex;
          flex-direction: column;
          align-items: center;
          gap: 1px;
          line-height: 1.2;
        }
        .hd-meeting-name {
          font-size: 13px;
          font-weight: 500;
        }
        .hd-meeting-meta {
          font-size: 11px;
          color: var(--fg-dim);
        }
        .hd-actions {
          display: flex;
          gap: 6px;
        }
        .hd-action-dot {
          width: 8px;
          height: 8px;
          border-radius: 50%;
          background: var(--border-strong);
        }

        .hd-body {
          display: grid;
          grid-template-columns: 1.55fr 1fr;
          min-height: 420px;
        }
        .hd-main {
          display: flex;
          flex-direction: column;
          min-width: 0;
        }
        .hd-transcript {
          padding: 24px 28px 16px;
          display: flex;
          flex-direction: column;
          gap: 18px;
          flex: 1;
          min-height: 0;
          max-height: 420px;
          overflow-y: auto;
          scroll-behavior: smooth;
        }
        .hd-cmd {
          display: flex;
          gap: 8px;
          padding: 12px 14px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: 8px;
          font-size: 13px;
          margin-top: 8px;
          animation: lineIn 400ms var(--ease-out-expo) 600ms backwards;
        }
        .hd-cmd-mention {
          color: var(--accent-bright);
          font-weight: 600;
          background: var(--accent-glow);
          padding: 1px 6px;
          border-radius: 4px;
        }
        .hd-cmd-text {
          color: var(--fg-muted);
        }
        .hd-toolbar {
          display: flex;
          align-items: center;
          justify-content: space-between;
          padding: 10px 16px;
          border-top: 1px solid var(--border);
          background: color-mix(in oklab, var(--surface) 50%, var(--bg-deep));
        }
        .hd-toolbar-left {
          display: flex;
          align-items: center;
          gap: 4px;
        }
        .hd-tb-btn {
          display: inline-flex;
          align-items: center;
          gap: 6px;
          padding: 6px 8px;
          background: transparent;
          border: 0;
          color: var(--fg-muted);
          border-radius: 6px;
          cursor: pointer;
          transition: all 160ms var(--ease);
        }
        .hd-tb-btn:hover {
          background: var(--bg-deep);
          color: var(--fg);
        }
        .hd-tb-btn--text {
          font-size: 12px;
          padding: 6px 10px;
        }
        .hd-tb-kbd {
          font-family: var(--font-mono);
          font-size: 10px;
          padding: 2px 5px;
          background: var(--bg-deep);
          border: 1px solid var(--border);
          border-radius: 4px;
          color: var(--fg-dim);
          margin-right: 4px;
        }
        .hd-tb-divider {
          width: 1px;
          height: 16px;
          background: var(--border);
          margin: 0 6px;
        }
        .hd-tb-status {
          display: inline-flex;
          align-items: center;
          gap: 6px;
          font-size: 11px;
          color: var(--fg-muted);
        }
        .hd-tb-status-dot {
          width: 6px;
          height: 6px;
          border-radius: 50%;
          background: var(--success);
          box-shadow: 0 0 8px var(--success);
          animation: pulseDot 1.6s ease-in-out infinite;
        }
        .hd-transcript::-webkit-scrollbar {
          width: 4px;
        }
        .hd-transcript::-webkit-scrollbar-thumb {
          background: var(--border);
          border-radius: 2px;
        }

        /* NOTA: regras .ts-* moveram para o bloco style jsx global
         * abaixo porque TranscriptLine e sub-componente. Sem global,
         * o hash jsx-XXX nao e propagado para o JSX retornado pelo
         * sub-componente e os seletores nunca casam (avatar vira barra,
         * meta gruda, etc.). Animations lineIn/markFlash/caret tambem
         * precisam ser globais porque sao referenciadas dentro do filho. */

        .hd-side {
          padding: 24px 24px;
          background: var(--bg-deep);
          border-left: 1px solid var(--border);
          display: flex;
          flex-direction: column;
          gap: 24px;
        }
        .hd-side-section {
          display: flex;
          flex-direction: column;
          gap: 10px;
        }
        .hd-side-section--feed {
          flex: 1;
          min-height: 0;
        }

        .hd-score {
          display: flex;
          flex-direction: column;
          gap: 8px;
        }
        .hd-score-num {
          font-size: 44px;
          font-weight: 500;
          letter-spacing: -0.04em;
          line-height: 1;
          font-variant-numeric: tabular-nums;
          transition: color 240ms var(--ease);
        }
        .hd-score-bar {
          height: 3px;
          background: var(--border);
          border-radius: 2px;
          overflow: hidden;
        }
        .hd-score-fill {
          height: 100%;
          background: linear-gradient(90deg, var(--accent), var(--accent-bright));
          transition: width 280ms var(--ease);
        }

        .hd-feed {
          display: flex;
          flex-direction: column;
          gap: 8px;
          flex: 1;
          overflow-y: auto;
          padding-right: 4px;
        }
        .hd-feed::-webkit-scrollbar {
          width: 4px;
        }
        .hd-feed::-webkit-scrollbar-thumb {
          background: var(--border);
          border-radius: 2px;
        }

        .hd-feed-empty {
          display: flex;
          align-items: center;
          gap: 8px;
          font-size: 12px;
          color: var(--fg-dim);
          padding: 12px 0;
        }
        .hd-feed-pulse {
          width: 6px;
          height: 6px;
          border-radius: 50%;
          background: var(--accent);
          animation: pulseDot 1.4s ease-in-out infinite;
        }
        @keyframes pulseDot {
          0%,
          100% {
            opacity: 0.3;
          }
          50% {
            opacity: 1;
            box-shadow: 0 0 8px var(--accent);
          }
        }

        .hd-feed-item {
          display: flex;
          flex-direction: column;
          gap: 6px;
          padding-bottom: 14px;
          border-bottom: 1px solid var(--border);
          animation: feedIn 380ms var(--ease-out-expo);
        }
        .hd-feed-item:last-child {
          border-bottom: 0;
          padding-bottom: 0;
        }
        @keyframes feedIn {
          from {
            opacity: 0;
            transform: translateX(8px);
          }
          to {
            opacity: 1;
            transform: none;
          }
        }
        .hd-feed-title {
          font-size: 13.5px;
          font-weight: 600;
          color: var(--fg);
          letter-spacing: -0.005em;
        }
        .hd-feed-meta {
          display: inline-flex;
          align-items: center;
          gap: 8px;
          font-size: 11.5px;
          color: var(--fg-dim);
        }
        .hd-feed-status {
          display: inline-flex;
          align-items: center;
          gap: 5px;
          color: var(--c);
          font-weight: 500;
        }
        .hd-feed-status :global(svg) {
          width: 12px;
          height: 12px;
        }
        .hd-feed-bullets {
          margin: 4px 0 0;
          padding: 0;
          list-style: none;
          display: flex;
          flex-direction: column;
          gap: 5px;
        }
        .hd-feed-bullets li {
          display: grid;
          grid-template-columns: 12px 1fr;
          gap: 8px;
          font-size: 12.5px;
          line-height: 1.5;
          color: var(--fg-muted);
        }
        .hd-feed-bullets li::before {
          content: "·";
          color: var(--fg-dim);
          font-weight: 700;
          line-height: 1.4;
          text-align: center;
        }

        @media (max-width: 920px) {
          .hd-body {
            grid-template-columns: 1fr;
          }
          .hd-side {
            border-left: 0;
            border-top: 1px solid var(--border);
          }
          .hd-titlebar {
            grid-template-columns: 1fr;
            gap: 8px;
            padding: 12px;
          }
          .hd-meeting {
            align-items: start;
          }
          .hd-actions {
            display: none;
          }
        }
      `}</style>
      {/* Bloco global: estilos para <TranscriptLine> (sub-componente).
       * styled-jsx so injeta a class hash jsx-XXX no JSX do componente que
       * possui o <style jsx>. O JSX retornado por sub-componentes (mesmo
       * declarados no mesmo arquivo) nao recebe a hash, entao seletores
       * scoped como `.ts-avatar` nao casam. Marcar `global` aqui faz com
       * que as regras se apliquem por nome puro de classe — exatamente o
       * que queremos para .ts-line/.ts-avatar/.ts-meta/etc. */}
      <style jsx global>{`
        .ts-line {
          display: grid;
          grid-template-columns: 32px 1fr;
          gap: 12px;
          animation: lineIn 400ms var(--ease-out-expo);
        }
        .ts-avatar {
          width: 32px;
          height: 32px;
          border-radius: 50%;
          display: flex;
          align-items: center;
          justify-content: center;
          font-size: 11px;
          font-weight: 600;
          letter-spacing: 0.02em;
          color: var(--bg);
          box-shadow: 0 0 0 1px color-mix(in oklab, var(--fg) 8%, transparent);
          flex-shrink: 0;
        }
        .ts-content {
          display: flex;
          flex-direction: column;
          gap: 4px;
          min-width: 0;
        }
        @keyframes lineIn {
          from {
            opacity: 0;
            transform: translateY(6px);
          }
          to {
            opacity: 1;
            transform: none;
          }
        }
        .ts-meta {
          display: flex;
          align-items: baseline;
          gap: 8px;
          font-size: 12px;
        }
        .ts-speaker {
          font-weight: 600;
          color: var(--fg);
          letter-spacing: -0.005em;
        }
        .ts-role {
          color: var(--fg-dim);
          font-size: 10.5px;
        }
        .ts-time {
          color: var(--fg-dim);
          font-size: 10.5px;
          font-variant-numeric: tabular-nums;
          margin-left: auto;
        }
        .ts-text {
          font-size: 15px;
          line-height: 1.6;
          letter-spacing: -0.005em;
        }
        .ts-mark {
          padding: 1px 4px;
          border-radius: 3px;
          margin: 0 -2px;
          transition: all 240ms var(--ease);
        }
        .ts-mark.is-complete {
          animation: markFlash 1200ms var(--ease) 1;
        }
        @keyframes markFlash {
          0% {
            box-shadow: 0 0 0 0 currentColor;
          }
          50% {
            box-shadow: 0 0 0 3px color-mix(in oklab, currentColor 28%, transparent);
          }
          100% {
            box-shadow: 0 0 0 0 transparent;
          }
        }
        .ts-caret {
          display: inline-block;
          width: 2px;
          height: 1em;
          background: var(--accent);
          margin-left: 2px;
          vertical-align: text-bottom;
          animation: caret 0.9s steps(2) infinite;
        }
        @keyframes caret {
          50% {
            opacity: 0;
          }
        }
      `}</style>
    </section>
  );
}
