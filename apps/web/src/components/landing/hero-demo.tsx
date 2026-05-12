"use client";

import { useEffect, useRef, useState } from "react";
import styles from "./landing.module.css";

type InsightType = "opportunity" | "risk" | "action" | "decision";

interface Insight {
  type: InsightType;
  label: string;
  text: string;
  meta: string;
}

interface Turn {
  who: string;
  role: string;
  speakerClass: "s1" | "s2" | "s3";
  /** HTML literal contendo possivelmente <mark class="tag opportunity|risk|action|decision">…</mark>. */
  text: string;
  insight: Insight | null;
}

interface InsightCard extends Insight {
  /** Identificador estavel da insight (uso em React keys). */
  id: number;
  /** Controla a animacao de entrada (opacity/translateY). */
  show: boolean;
}

const TURNS: Turn[] = [
  {
    who: "Camila",
    role: "Cliente",
    speakerClass: "s1",
    text: 'A gente ainda usa <mark class="tag opportunity">Protheus pro financeiro</mark>, mas a equipe tá reclamando muito.',
    insight: null,
  },
  {
    who: "André",
    role: "Vendas",
    speakerClass: "s2",
    text: "Entendi. Em quais módulos especificamente o time tem fricção hoje?",
    insight: null,
  },
  {
    who: "Camila",
    role: "Cliente",
    speakerClass: "s1",
    text: 'Folha e fechamento mensal. <mark class="tag risk">A gente tá olhando o SAP</mark> também.',
    insight: {
      type: "risk",
      label: "Risco competitivo",
      text: "SAP citado como alternativa em avaliação ativa.",
      meta: "Confiança 0.91 · 23:34",
    },
  },
  {
    who: "Camila",
    role: "Cliente",
    speakerClass: "s1",
    text: 'Mas o que pesa pra gente é integração com <mark class="tag opportunity">RM e Fluig que já temos</mark>.',
    insight: {
      type: "opportunity",
      label: "Oportunidade",
      text: "Cross-sell de RM + Fluig já validado pelo cliente.",
      meta: "Confiança 0.96 · 23:41",
    },
  },
  {
    who: "André",
    role: "Vendas",
    speakerClass: "s2",
    text: "Faz total sentido. Posso preparar uma demo focada nessa integração pra terça?",
    insight: null,
  },
  {
    who: "Camila",
    role: "Cliente",
    speakerClass: "s1",
    text: '<mark class="tag decision">Sim, terça às 14h funciona</mark>. Vou trazer o Bruno do RH.',
    insight: {
      type: "decision",
      label: "Decisão",
      text: "Demo agendada para terça 14h. Bruno (RH) confirmado.",
      meta: "Confiança 0.99 · 23:48",
    },
  },
  {
    who: "André",
    role: "Vendas",
    speakerClass: "s2",
    text: 'Combinado. <mark class="tag action">Mando o calendário com a pauta hoje</mark>.',
    insight: {
      type: "action",
      label: "Ação",
      text: "André: enviar calendário com pauta — hoje 18:00.",
      meta: "Confiança 0.98 · 23:51",
    },
  },
];

const INITIAL_SECONDS = 23 * 60 + 14; // 00:23:14
const MAX_VISIBLE_INSIGHTS = 4;
const STEP_INTERVAL_MS = 2400;
const FIRST_STEP_DELAY_MS = 800;
const RESET_DELAY_MS = 4500;

function formatClock(secs: number): string {
  const h = String(Math.floor(secs / 3600)).padStart(2, "0");
  const m = String(Math.floor((secs % 3600) / 60)).padStart(2, "0");
  const s = String(secs % 60).padStart(2, "0");
  return `${h}:${m}:${s}`;
}

function speakerClassName(cls: Turn["speakerClass"]): string {
  switch (cls) {
    case "s1":
      return styles.speakerS1;
    case "s2":
      return styles.speakerS2;
    case "s3":
      return styles.speakerS3;
  }
}

function insightClassName(type: InsightType): string {
  switch (type) {
    case "opportunity":
      return styles.insightOpportunity;
    case "risk":
      return styles.insightRisk;
    case "action":
      return styles.insightAction;
    case "decision":
      return styles.insightDecision;
  }
}

/**
 * Demo ao vivo do Hero: simula uma reuniao sendo transcrita em tempo real
 * com NORA extraindo sinais (risco / oportunidade / decisao / acao) em paralelo.
 *
 * Mecanica adaptada de docs/design/landing.html (linhas ~1281-1403):
 *  - Cycle entre turnos, scroll-snap visual via translateY do stream.
 *  - Quando turn.insight existir, push insight no stack com fade-in.
 *  - Stack max 4 cards (FIFO).
 *  - Ao acabar todos os turnos, reset apos pausa.
 *  - Relogio incrementando a cada segundo a partir de 00:23:14.
 *
 * Limpezas explicitas em useEffect cleanup (timer + interval) evitam
 * leaks ao desmontar (StrictMode roda mount->unmount->mount em dev).
 */
export function HeroDemo() {
  const [currentIdx, setCurrentIdx] = useState(-1);
  const [insights, setInsights] = useState<InsightCard[]>([]);
  const [clockSecs, setClockSecs] = useState(INITIAL_SECONDS);
  const [streamOffset, setStreamOffset] = useState(0);

  // Refs pros nodes de cada turno — usados pra calcular offsetTop dinamicamente.
  const streamRef = useRef<HTMLDivElement>(null);
  const turnRefs = useRef<Array<HTMLDivElement | null>>([]);
  // Contador estavel pra gerar IDs unicos das insights mesmo apos reset.
  const insightIdRef = useRef(0);

  // Anima fade-in: depois que pintamos a card sem .show, agendamos rAF
  // pra adicionar .show no proximo frame, disparando a transicao CSS.
  const pendingShowRef = useRef<Set<number>>(new Set());

  // ── Cycle dos turnos + insights ─────────────────────────────────
  useEffect(() => {
    let stepInterval: ReturnType<typeof setInterval> | null = null;
    let resetTimeout: ReturnType<typeof setTimeout> | null = null;
    let firstStep: ReturnType<typeof setTimeout> | null = null;
    let cancelled = false;

    function advance() {
      if (cancelled) return;
      setCurrentIdx((prev) => {
        const next = prev + 1;
        if (next >= TURNS.length) {
          // Agenda reset; nao incrementa idx alem do limite enquanto isso.
          if (!resetTimeout) {
            resetTimeout = setTimeout(() => {
              if (cancelled) return;
              setCurrentIdx(-1);
              setInsights([]);
              setStreamOffset(0);
              resetTimeout = null;
              // Reinicia o ciclo logo depois.
              firstStep = setTimeout(advance, FIRST_STEP_DELAY_MS);
            }, RESET_DELAY_MS);
          }
          return prev; // mantem prev pra evitar idx invalido renderizando
        }

        const turn = TURNS[next];
        if (turn?.insight) {
          const id = ++insightIdRef.current;
          const newCard: InsightCard = { ...turn.insight, id, show: false };
          pendingShowRef.current.add(id);
          setInsights((cur) => {
            const merged = [...cur, newCard];
            // Mantem so as ultimas MAX_VISIBLE_INSIGHTS.
            return merged.length > MAX_VISIBLE_INSIGHTS
              ? merged.slice(merged.length - MAX_VISIBLE_INSIGHTS)
              : merged;
          });
        }
        return next;
      });
    }

    firstStep = setTimeout(() => {
      advance();
      stepInterval = setInterval(advance, STEP_INTERVAL_MS);
    }, FIRST_STEP_DELAY_MS);

    return () => {
      cancelled = true;
      if (firstStep) clearTimeout(firstStep);
      if (stepInterval) clearInterval(stepInterval);
      if (resetTimeout) clearTimeout(resetTimeout);
    };
  }, []);

  // ── Disparo da animacao .show das cards recem inseridas ─────────
  useEffect(() => {
    const pendingIds = Array.from(pendingShowRef.current);
    if (pendingIds.length === 0) return;
    const raf = requestAnimationFrame(() => {
      setInsights((cur) =>
        cur.map((c) => (pendingShowRef.current.has(c.id) ? { ...c, show: true } : c)),
      );
      pendingShowRef.current.clear();
    });
    return () => cancelAnimationFrame(raf);
  }, [insights.length]);

  // ── Scroll do stream: shifta pra colocar o turn atual perto do topo ──
  useEffect(() => {
    if (currentIdx < 0) {
      setStreamOffset(0);
      return;
    }
    const node = turnRefs.current[currentIdx];
    if (!node) return;
    // 40px pra dar respiro visual no topo do scroll area.
    setStreamOffset(-(node.offsetTop - 40));
  }, [currentIdx]);

  // ── Relogio incrementando ───────────────────────────────────────
  useEffect(() => {
    const clockInterval = setInterval(() => {
      setClockSecs((s) => s + 1);
    }, 1000);
    return () => clearInterval(clockInterval);
  }, []);

  const insightsCount = String(insights.length).padStart(2, "0");

  return (
    <section id="demo" className={styles.demoWrap}>
      <div className={styles.shell}>
        <div className={styles.demo}>
          <div className={styles.demoHeader}>
            <div className={styles.demoTitle}>Reunião · Discovery — Mercato &amp; NORA</div>
            <div className={styles.demoMeta}>
              <span className={styles.demoRec}>Ao vivo</span>
              <span>{formatClock(clockSecs)}</span>
            </div>
          </div>

          <div className={styles.demoGrid}>
            {/* Transcript */}
            <div className={styles.transcript}>
              <div
                ref={streamRef}
                className={styles.transcriptStream}
                style={{ transform: `translateY(${streamOffset}px)` }}
              >
                {TURNS.map((turn, i) => {
                  let stateClass = styles.turnFuture;
                  if (currentIdx >= 0) {
                    if (i < currentIdx) stateClass = styles.turnPast;
                    else if (i === currentIdx) stateClass = styles.turnCurrent;
                  }
                  return (
                    <div
                      key={i}
                      ref={(el) => {
                        turnRefs.current[i] = el;
                      }}
                      className={`${styles.turn} ${stateClass}`}
                    >
                      <div
                        className={`${styles.speaker} ${speakerClassName(turn.speakerClass)}`}
                      >
                        {turn.who.charAt(0)}
                      </div>
                      <div className={styles.turnBody}>
                        <div className={styles.turnName}>
                          {turn.who} · {turn.role}
                        </div>
                        <div
                          className={styles.turnText}
                          // Conteudo controlado vem do dataset estatico definido
                          // neste arquivo (sem origem em runtime/usuario), portanto
                          // o HTML embutido (<mark class="tag …">) e seguro.
                          dangerouslySetInnerHTML={{ __html: turn.text }}
                        />
                      </div>
                    </div>
                  );
                })}
              </div>
            </div>

            {/* Insights */}
            <div className={styles.insights}>
              <div className={styles.insightsH}>
                <span>Sinais extraídos</span>
                <span className={styles.insightsHCount}>{insightsCount}</span>
              </div>
              <div className={styles.insightStack}>
                {insights.map((card) => {
                  const [conf, when] = card.meta.split(" · ");
                  return (
                    <div
                      key={card.id}
                      className={`${styles.insight} ${insightClassName(card.type)} ${
                        card.show ? styles.insightShow : ""
                      }`}
                    >
                      <div className={styles.insightHead}>
                        <span className={styles.insightDot} />
                        <span className={styles.insightType}>{card.label}</span>
                        <span className={styles.insightSpacer} />
                        <span className={styles.insightConf}>{conf}</span>
                      </div>
                      <div className={styles.insightText}>{card.text}</div>
                      <div className={styles.insightMeta}>{when ?? ""}</div>
                    </div>
                  );
                })}
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
