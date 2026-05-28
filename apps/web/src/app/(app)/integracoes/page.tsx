"use client";

/**
 * NORA Core — Integrações (MCP).
 *
 * Superfície de conexões via Model Context Protocol (ADR/vision §7). O catálogo
 * reflete o roadmap: os conectores saem do NORA pras ferramentas que o usuário já
 * usa (Calendar, task managers, CRM…). Status honesto: tudo "Em breve" no MVP —
 * nenhum servidor MCP está plugado ainda. A UI já existe pra dar o shape do produto.
 */
import { useState } from "react";

type Connector = {
  id: string;
  name: string;
  category: string;
  desc: string;
  initials: string;
  tint: string;
  status: "available" | "soon";
};

const CONNECTORS: Connector[] = [
  { id: "gcal", name: "Google Calendar", category: "Agenda", desc: "Registra o resumo no evento da reunião.", initials: "GC", tint: "oklch(0.72 0.12 250)", status: "soon" },
  { id: "outlook", name: "Microsoft Outlook", category: "Agenda", desc: "Agenda + resumo no convite, via Microsoft 365.", initials: "OL", tint: "oklch(0.7 0.12 235)", status: "soon" },
  { id: "linear", name: "Linear", category: "Tarefas", desc: "Cria issues a partir dos action items detectados.", initials: "LN", tint: "oklch(0.66 0.13 285)", status: "soon" },
  { id: "jira", name: "Jira", category: "Tarefas", desc: "Abre tarefas no board com contexto da reunião.", initials: "JR", tint: "oklch(0.68 0.13 250)", status: "soon" },
  { id: "github", name: "GitHub", category: "Código", desc: "Linka a discussão técnica ao PR/issue citado.", initials: "GH", tint: "oklch(0.5 0.02 280)", status: "soon" },
  { id: "salesforce", name: "Salesforce", category: "CRM", desc: "Empurra a oportunidade com contexto estruturado.", initials: "SF", tint: "oklch(0.68 0.13 230)", status: "soon" },
  { id: "totvs", name: "TOTVS CRM", category: "CRM", desc: "Conector read-only pro ecossistema TOTVS (Protheus).", initials: "TV", tint: "oklch(0.62 0.16 150)", status: "soon" },
  { id: "slack", name: "Slack", category: "Comunicação", desc: "Manda o resumo no canal do time.", initials: "SL", tint: "oklch(0.7 0.13 320)", status: "soon" },
];

export default function IntegracoesPage() {
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <div style={{ maxWidth: 920, margin: "0 auto", padding: "56px 40px 80px" }}>
      <header style={{ marginBottom: 14 }}>
        <h1 style={{ fontFamily: "var(--display)", fontSize: 30, fontWeight: 500, letterSpacing: "-0.025em", color: "var(--ink)", margin: "0 0 8px" }}>
          Integrações
        </h1>
        <p style={{ fontSize: 14, color: "var(--muted)", margin: 0, lineHeight: 1.6, maxWidth: 620 }}>
          A NORA se conecta às ferramentas que você já usa via <strong style={{ color: "var(--ink)", fontWeight: 600 }}>MCP (Model Context Protocol)</strong>.
          Os dados <em>saem</em> do NORA pro seu fluxo — sem dupla entrada. Você transcreve uma vez; a NORA distribui.
        </p>
      </header>

      {notice && (
        <div style={{ margin: "0 0 20px", padding: "10px 14px", background: "var(--accent-soft)", color: "var(--accent-ink)", borderRadius: 9, fontSize: 13 }}>
          {notice}
        </div>
      )}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 12, marginTop: 8 }}>
        {CONNECTORS.map((c) => (
          <div
            key={c.id}
            style={{
              border: "1px solid var(--border)",
              borderRadius: 12,
              padding: 16,
              background: "var(--canvas)",
              display: "flex",
              flexDirection: "column",
              gap: 12,
            }}
          >
            <div style={{ display: "flex", alignItems: "center", gap: 11 }}>
              <div style={{ width: 36, height: 36, borderRadius: 9, background: c.tint, color: "white", display: "grid", placeItems: "center", fontSize: 13, fontWeight: 600, letterSpacing: "0.01em", flexShrink: 0 }}>
                {c.initials}
              </div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 14, fontWeight: 500, color: "var(--ink)", letterSpacing: "-0.01em" }}>{c.name}</div>
                <div style={{ fontSize: 11.5, color: "var(--muted)" }}>{c.category}</div>
              </div>
            </div>
            <p style={{ fontSize: 12.5, color: "var(--muted)", lineHeight: 1.5, margin: 0, flex: 1 }}>{c.desc}</p>
            <button
              type="button"
              onClick={() => setNotice(`A integração com ${c.name} via MCP está no roadmap — chega após o piloto.`)}
              style={{
                alignSelf: "flex-start",
                display: "inline-flex",
                alignItems: "center",
                gap: 7,
                fontSize: 12.5,
                color: "var(--muted)",
                background: "var(--sidebar)",
                border: "1px solid var(--border)",
                borderRadius: 8,
                padding: "6px 12px",
                cursor: "pointer",
              }}
            >
              <span style={{ width: 6, height: 6, borderRadius: "50%", background: "var(--warn)" }} />
              Em breve
            </button>
          </div>
        ))}
      </div>

      <div style={{ marginTop: 40, paddingTop: 18, borderTop: "1px solid var(--border)", fontSize: 12, color: "var(--muted)", lineHeight: 1.6 }}>
        O NORA também expõe um <strong style={{ color: "var(--ink)", fontWeight: 600 }}>servidor MCP próprio</strong> — pra que assistentes (Claude, etc.) leiam
        suas reuniões e action items com segurança e isolamento por tenant. Em desenvolvimento.
      </div>
    </div>
  );
}
