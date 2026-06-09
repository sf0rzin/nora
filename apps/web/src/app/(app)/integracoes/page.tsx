"use client";

/**
 * NORA Core — Integrações (MCP).
 *
 * Superfície de conexões via Model Context Protocol (ADR/vision §7). O catálogo
 * reflete o roadmap: os conectores saem do NORA pras ferramentas que o usuário já
 * usa (Calendar, task managers, CRM…). Status honesto: tudo "Em breve" no MVP —
 * nenhum servidor MCP está plugado ainda. A UI já existe pra dar o shape do produto.
 *
 * Os badges usam logos reais das marcas (SVG inline, estilo simple-icons),
 * num container 36×36 radius 9.
 */
import { useState, type ReactNode } from "react";

type Connector = {
  id: string;
  name: string;
  category: string;
  desc: string;
  logo: ReactNode;
  status: "available" | "soon";
};

/* ── Logos das marcas (SVG inline à mão, estilo simple-icons) ──────────────
   Container 36×36, radius 9. Cada logo é renderizado num quadro com o tom da
   marca em fundo suave + glifo na cor da marca, pra leitura imediata. */

function GoogleCalendarLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path fill="#4285F4" d="M16.5 3h-9A4.5 4.5 0 0 0 3 7.5v9A4.5 4.5 0 0 0 7.5 21h9a4.5 4.5 0 0 0 4.5-4.5v-9A4.5 4.5 0 0 0 16.5 3Z" opacity="0.12" />
      <path fill="none" stroke="#4285F4" strokeWidth="1.6" strokeLinejoin="round" d="M5 7.5A2.5 2.5 0 0 1 7.5 5h9A2.5 2.5 0 0 1 19 7.5v9a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 16.5v-9Z" />
      <path fill="none" stroke="#4285F4" strokeWidth="1.4" strokeLinecap="round" d="M8 3.6v2.6M16 3.6v2.6M5.3 9.3h13.4" />
      <rect x="9.4" y="11.4" width="5.2" height="5.2" rx="1" fill="#4285F4" />
    </svg>
  );
}

function OutlookLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <rect x="3" y="6" width="11.5" height="12" rx="1.4" fill="none" stroke="#0078D4" strokeWidth="1.6" />
      <ellipse cx="8.75" cy="12" rx="2.5" ry="3" fill="none" stroke="#0078D4" strokeWidth="1.6" />
      <path fill="#0078D4" d="M15 8.4 21 6v12l-6-2.4z" opacity="0.9" />
      <path fill="none" stroke="#0078D4" strokeWidth="1.2" strokeLinejoin="round" d="M15 9.2 21 12l-6 2.8" opacity="0.55" />
    </svg>
  );
}

function LinearLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <g fill="#5E6AD2">
        <path d="M3.2 13.4a9 9 0 0 0 7.4 7.4l-7.4-7.4Z" />
        <path d="M3 11.1a9 9 0 0 0 9.9 9.9L3 11.1Z" opacity="0.85" />
        <path d="M3.3 8.4 15.6 20.7a9 9 0 0 0 2.3-1L4.3 6.1a9 9 0 0 0-1 2.3Z" opacity="0.7" />
        <path d="M5.5 4.9 19.1 18.5A9 9 0 0 0 5.5 4.9Z" opacity="0.55" />
      </g>
    </svg>
  );
}

function JiraLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path fill="#2684FF" d="M12 2.2 21.8 12 12 21.8 9.6 19.4 16.9 12 9.6 4.6 12 2.2Z" />
      <path fill="#2684FF" d="M7 7.1 11.9 12 7 16.9 4.6 14.5 7.1 12 4.6 9.5 7 7.1Z" opacity="0.6" />
    </svg>
  );
}

function GitHubLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path
        fill="#15171a"
        d="M12 2.2a9.8 9.8 0 0 0-3.1 19.1c.5.1.66-.21.66-.48v-1.7c-2.72.6-3.3-1.16-3.3-1.16-.45-1.13-1.1-1.43-1.1-1.43-.9-.61.07-.6.07-.6 1 .07 1.52 1.02 1.52 1.02.88 1.52 2.32 1.08 2.89.83.09-.64.35-1.08.63-1.33-2.17-.25-4.45-1.09-4.45-4.84 0-1.07.38-1.95 1.02-2.63-.1-.25-.45-1.25.1-2.6 0 0 .83-.27 2.73 1a9.4 9.4 0 0 1 4.96 0c1.9-1.27 2.73-1 2.73-1 .54 1.35.2 2.35.1 2.6.63.68 1.01 1.56 1.01 2.63 0 3.76-2.29 4.59-4.46 4.83.36.31.67.91.67 1.84v2.73c0 .27.16.59.67.48A9.8 9.8 0 0 0 12 2.2Z"
      />
    </svg>
  );
}

function SalesforceLogo() {
  return (
    <svg viewBox="0 0 24 24" width={22} height={22} aria-hidden>
      <path
        fill="#00A1E0"
        d="M10 6.6a3.3 3.3 0 0 1 5.7.9 3.6 3.6 0 0 1 1.5-.3 3.7 3.7 0 0 1 .3 7.3 3 3 0 0 1-3.7 2.4 3.3 3.3 0 0 1-5.9.4 3.1 3.1 0 0 1-.7.1 3.4 3.4 0 0 1-1.2-6.6 3.9 3.9 0 0 1 4-4.2 3.8 3.8 0 0 1 0 0Z"
      />
    </svg>
  );
}

function PipedriveLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <path fill="#017737" d="M11 3.4c2 0 3.4.5 4.5 1.6a6 6 0 0 1 1.7 4.5 6.1 6.1 0 0 1-1.7 4.6 5.8 5.8 0 0 1-4.3 1.7c-1.2 0-2.2-.3-3-.9v6h-3V3.7h2.8l.1 1.3c.9-1 2-1.6 3.1-1.6Zm-.4 2.7c-1 0-1.8.4-2.4 1.1v4.6c.6.7 1.4 1.1 2.4 1.1a3 3 0 0 0 2.3-1 3.7 3.7 0 0 0 .8-2.5 3.6 3.6 0 0 0-.8-2.4 3 3 0 0 0-2.3-.9Z" />
    </svg>
  );
}

function SlackLogo() {
  return (
    <svg viewBox="0 0 24 24" width={20} height={20} aria-hidden>
      <g strokeWidth="0">
        <path fill="#36C5F0" d="M9 3.2a1.7 1.7 0 1 0-1.7 1.7H9V3.2Zm.9 0A1.7 1.7 0 0 1 13.3 3.2v4.2a1.7 1.7 0 0 1-3.4 0V3.2Z" />
        <path fill="#2EB67D" d="M20.8 9a1.7 1.7 0 1 0-1.7-1.7V9h1.7Zm0 .9A1.7 1.7 0 0 1 20.8 13.3h-4.2a1.7 1.7 0 0 1 0-3.4h4.2Z" />
        <path fill="#ECB22E" d="M15 20.8a1.7 1.7 0 1 0 1.7-1.7H15v1.7Zm-.9 0A1.7 1.7 0 0 1 10.7 20.8v-4.2a1.7 1.7 0 0 1 3.4 0v4.2Z" />
        <path fill="#E01E5A" d="M3.2 15a1.7 1.7 0 1 0 1.7 1.7V15H3.2Zm0-.9A1.7 1.7 0 0 1 3.2 10.7h4.2a1.7 1.7 0 0 1 0 3.4H3.2Z" />
      </g>
    </svg>
  );
}

const CONNECTORS: Connector[] = [
  { id: "gcal", name: "Google Calendar", category: "Agenda", desc: "Registra o resumo no evento da reunião.", logo: <GoogleCalendarLogo />, status: "soon" },
  { id: "outlook", name: "Microsoft Outlook", category: "Agenda", desc: "Agenda + resumo no convite, via Microsoft 365.", logo: <OutlookLogo />, status: "soon" },
  { id: "linear", name: "Linear", category: "Tarefas", desc: "Cria issues a partir dos action items detectados.", logo: <LinearLogo />, status: "soon" },
  { id: "jira", name: "Jira", category: "Tarefas", desc: "Abre tarefas no board com contexto da reunião.", logo: <JiraLogo />, status: "soon" },
  { id: "github", name: "GitHub", category: "Código", desc: "Linka a discussão técnica ao PR/issue citado.", logo: <GitHubLogo />, status: "soon" },
  { id: "salesforce", name: "Salesforce", category: "CRM", desc: "Empurra a oportunidade com contexto estruturado.", logo: <SalesforceLogo />, status: "soon" },
  { id: "pipedrive", name: "Pipedrive", category: "CRM", desc: "Atualiza o deal com resumo e próximos passos.", logo: <PipedriveLogo />, status: "soon" },
  { id: "slack", name: "Slack", category: "Comunicação", desc: "Manda o resumo no canal do time.", logo: <SlackLogo />, status: "soon" },
];

export default function IntegracoesPage() {
  const [notice, setNotice] = useState<string | null>(null);

  return (
    <div className="page">
      <header style={{ marginBottom: 14 }}>
        <h1 className="h1">Integrações</h1>
        <p className="lede" style={{ marginTop: 8, maxWidth: 620 }}>
          A NORA se conecta às ferramentas que você já usa via{" "}
          <strong style={{ color: "var(--ink)", fontWeight: 600 }}>MCP (Model Context Protocol)</strong>. Os dados <em>saem</em> do NORA pro
          seu fluxo — sem dupla entrada. Você transcreve uma vez; a NORA distribui.
        </p>
      </header>

      {notice && (
        <div className="notice notice--accent" style={{ margin: "0 0 20px" }}>
          {notice}
        </div>
      )}

      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 12, marginTop: 8 }}>
        {CONNECTORS.map((c) => (
          <div key={c.id} className="card" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 11 }}>
              <div
                style={{
                  width: 36,
                  height: 36,
                  borderRadius: 9,
                  background: "var(--sidebar)",
                  border: "1px solid var(--border)",
                  display: "grid",
                  placeItems: "center",
                  flexShrink: 0,
                }}
              >
                {c.logo}
              </div>
              <div style={{ minWidth: 0 }}>
                <div style={{ fontSize: 14, fontWeight: 500, color: "var(--ink)", letterSpacing: "-0.01em" }}>{c.name}</div>
                <div style={{ fontSize: 11.5, color: "var(--muted)" }}>{c.category}</div>
              </div>
            </div>
            <p style={{ fontSize: 12.5, color: "var(--muted)", lineHeight: 1.5, margin: 0, flex: 1 }}>{c.desc}</p>
            <button
              type="button"
              className="btn btn-secondary btn-sm"
              onClick={() => setNotice(`A integração com ${c.name} via MCP está no roadmap — chega após o piloto.`)}
              style={{ alignSelf: "flex-start", color: "var(--muted)" }}
            >
              <span className="status-dot" style={{ background: "var(--warn)", width: 6, height: 6 }} />
              Em breve
            </button>
          </div>
        ))}
      </div>

      <div style={{ marginTop: 40, paddingTop: 18, borderTop: "1px solid var(--border)", fontSize: 12, color: "var(--muted)", lineHeight: 1.6 }}>
        O NORA também expõe um <strong style={{ color: "var(--ink)", fontWeight: 600 }}>servidor MCP próprio</strong> — pra que assistentes
        (Claude, etc.) leiam suas reuniões e action items com segurança e isolamento por tenant. Em desenvolvimento.
      </div>
    </div>
  );
}
