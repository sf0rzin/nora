"use client";

import Link from "next/link";
import { useEffect, useMemo, useRef, useState } from "react";

function HeroSoundwave() {
  const bars = useMemo(() => {
    const N = 32;
    const arr: { h: number; delay: number }[] = [];
    for (let i = 0; i < N; i++) {
      const x = (i - 14) / 9;
      const env = Math.exp(-x * x) * 0.85 + 0.15;
      const wobble = Math.sin(i * 1.3) * 0.05 + Math.sin(i * 0.6 + 0.5) * 0.04;
      const h = Math.max(0.18, Math.min(1, env + wobble));
      arr.push({ h, delay: (i * 53) % 1800 });
    }
    return arr;
  }, []);

  return (
    <div className="hero-soundwave" aria-hidden="true">
      <div className="hero-soundwave-fade" />
      <div className="hero-soundwave-bars">
        {bars.map((b, i) => (
          <span
            key={i}
            className="sw-bar"
            style={{ height: `${b.h * 100}%`, animationDelay: `-${b.delay}ms` }}
          />
        ))}
      </div>
    </div>
  );
}

const SUGGESTIONS = [
  {
    label: "Resumir reunião",
    icon: (
      <svg
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
        <polyline points="14 2 14 8 20 8" />
        <line x1="8" y1="13" x2="16" y2="13" />
        <line x1="8" y1="17" x2="16" y2="17" />
      </svg>
    ),
  },
  {
    label: "Action items",
    icon: (
      <svg
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <polyline points="9 11 12 14 22 4" />
        <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
      </svg>
    ),
  },
  {
    label: "Analisar transcrição",
    icon: (
      <svg
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <circle cx="11" cy="11" r="7" />
        <path d="m20 20-3.5-3.5" />
      </svg>
    ),
  },
];

/** Hero: headline + composer + soundwave + suggestions + logos. */
export function LandingHero() {
  const [prompt, setPrompt] = useState("");
  const taRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = `${Math.min(ta.scrollHeight, 160)}px`;
  }, [prompt]);

  const trimmed = prompt.trim();

  return (
    <section className="hero-main">
      <HeroSoundwave />

      <div className="container hero-main-inner">
        <div className="hero-content">
          <h1>
            Saia de qualquer reunião
            <br />
            sabendo <em>o que decidir</em>.
          </h1>

          <div className="hero-composer">
            <textarea
              ref={taRef}
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              placeholder="Pergunte qualquer coisa sobre suas reuniões…"
              rows={1}
            />
            <Link
              href={{ pathname: "/auth/signup", query: trimmed ? { q: prompt } : {} }}
              className={`hero-composer-send ${trimmed ? "is-active" : ""}`}
              aria-label="Perguntar Nora"
            >
              Perguntar Nora
              <svg
                width="13"
                height="13"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.2"
                strokeLinecap="round"
                strokeLinejoin="round"
              >
                <path d="M12 19V5M5 12l7-7 7 7" />
              </svg>
            </Link>
          </div>

          <div className="hero-suggestions">
            {SUGGESTIONS.map((s) => (
              <Link key={s.label} href="/auth/signup" className="hero-chip">
                {s.icon}
                <span>{s.label}</span>
              </Link>
            ))}
          </div>
        </div>
      </div>

      <div className="container">
        <div className="logos-strip">
          <span className="lead">Usado por times em:</span>
          <div className="row">
            <span className="fake-logo">veridian</span>
            <span className="fake-logo">Lattice·BR</span>
            <span className="fake-logo">CAMPO×</span>
            <span className="fake-logo">norte sul</span>
            <span className="fake-logo">Praxe</span>
            <span className="fake-logo">FIAP</span>
          </div>
        </div>
      </div>

      <a href="#como-funciona" className="hero-scroll-hint" aria-label="Rolar para baixo">
        <span>Role pra explorar</span>
        <span className="hero-scroll-hint-arrow" />
      </a>
    </section>
  );
}
