"use client";

/**
 * NORA Core — Projetos.
 *
 * Projetos agrupam reuniões e action items ao longo do tempo, sem preenchimento
 * manual (vision: "Core — Projetos"). O agrupamento automático por contexto está
 * em construção; por ora a tela apresenta o conceito e leva o usuário pro fluxo.
 */
import Link from "next/link";
import type { Route } from "next";

import { ShaderOrb } from "@/components/brand/shader-orb";

export default function ProjetosPage() {
  return (
    <div style={{ maxWidth: 920, margin: "0 auto", padding: "56px 40px 80px" }}>
      <header style={{ marginBottom: 28 }}>
        <h1 style={{ fontFamily: "var(--display)", fontSize: 30, fontWeight: 500, letterSpacing: "-0.025em", color: "var(--ink)", margin: "0 0 8px" }}>
          Projetos
        </h1>
        <p style={{ fontSize: 14, color: "var(--muted)", margin: 0, lineHeight: 1.6, maxWidth: 600 }}>
          A NORA agrupa suas reuniões e action items por projeto automaticamente — sem você preencher nada.
          Assim você acompanha cada frente ao longo do tempo, num só lugar.
        </p>
      </header>

      <div
        style={{
          border: "1px dashed var(--border-strong)",
          borderRadius: 16,
          padding: "56px 24px",
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          gap: 20,
          textAlign: "center",
          background: "var(--canvas)",
        }}
      >
        <ShaderOrb size={92} speed={0.9} intensity={0.8} />
        <div style={{ maxWidth: 420 }}>
          <h2 style={{ fontFamily: "var(--display)", fontSize: 18, fontWeight: 500, letterSpacing: "-0.018em", color: "var(--ink)", margin: "0 0 6px" }}>
            Seus projetos aparecem aqui conforme você usa a NORA.
          </h2>
          <p style={{ fontSize: 13.5, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
            Envie reuniões e converse com a NORA — ela conecta os pontos e organiza tudo por projeto.
          </p>
        </div>
        <div style={{ display: "flex", gap: 10, flexWrap: "wrap", justifyContent: "center" }}>
          <Link
            href={"/meetings/upload" as Route}
            style={{ display: "inline-flex", alignItems: "center", gap: 7, padding: "9px 16px", background: "var(--ink)", color: "var(--canvas)", borderRadius: 9, fontSize: 13, fontWeight: 500 }}
          >
            Enviar reunião
          </Link>
          <Link
            href={"/chat" as Route}
            style={{ display: "inline-flex", alignItems: "center", gap: 7, padding: "9px 16px", background: "var(--sidebar)", color: "var(--ink)", border: "1px solid var(--border)", borderRadius: 9, fontSize: 13, fontWeight: 500 }}
          >
            Conversar com a NORA
          </Link>
        </div>
      </div>
    </div>
  );
}
