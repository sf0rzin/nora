"use client";

/**
 * NORA Flows — lista de fluxos (/fluxos).
 *
 * Automações do tenant: cada card mostra nome, gatilho, estado (ativo/pausado)
 * e a última atualização. Tudo vem do backend real (GET /workflows) — sem mock.
 */
import Link from "next/link";
import type { Route } from "next";
import { useEffect, useState } from "react";

import { ApiRequestError, listWorkflows, type WorkflowResponse } from "@/lib/api/client";

import { metaDoBloco } from "./catalogo";
import { tempoRelativo } from "./tempo-relativo";

/** Ilustração do estado vazio — três nós ligados, no traço dos ícones do app. */
function IlustracaoFluxo() {
  return (
    <svg
      width="150"
      height="84"
      viewBox="0 0 150 84"
      fill="none"
      aria-hidden
      style={{ display: "block" }}
    >
      {/* arestas */}
      <path
        d="M44 42h18M96 42h18"
        stroke="var(--border-strong)"
        strokeWidth="1.5"
        strokeLinecap="round"
        strokeDasharray="3 4"
      />
      {/* gatilho */}
      <rect x="8" y="26" width="36" height="32" rx="9" fill="var(--accent-soft)" stroke="var(--accent)" strokeWidth="1.5" />
      <path d="M27.5 33 22 41h4l-1.5 7L30 40h-4z" fill="none" stroke="var(--accent)" strokeWidth="1.5" strokeLinejoin="round" />
      {/* condição */}
      <rect x="62" y="26" width="34" height="32" rx="9" fill="var(--canvas)" stroke="var(--border-strong)" strokeWidth="1.5" />
      <path d="M72 36h12l-4.5 5v4l-3-1.5v-2.5z" fill="none" stroke="var(--warn)" strokeWidth="1.5" strokeLinejoin="round" />
      {/* ação */}
      <rect x="114" y="26" width="28" height="32" rx="9" fill="var(--canvas)" stroke="var(--border-strong)" strokeWidth="1.5" />
      <path d="m134 36-9 9M134 36l-5.5 12-1.8-4.7-4.7-1.8z" fill="none" stroke="var(--success)" strokeWidth="1.4" strokeLinejoin="round" />
    </svg>
  );
}

function CardFluxo({ fluxo }: { fluxo: WorkflowResponse }) {
  const gatilho = metaDoBloco(fluxo.triggerType)?.nome ?? fluxo.triggerType;
  const condicoes = fluxo.definition.nodes.filter((n) => n.kind === "condition").length;
  const acoes = fluxo.definition.nodes.filter((n) => n.kind === "action").length;
  return (
    <Link href={`/fluxos/${fluxo.id}` as Route} className="card flows-card">
      <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 10 }}>
        <span
          style={{
            fontSize: 14.5,
            fontWeight: 500,
            letterSpacing: "-0.012em",
            lineHeight: 1.35,
            overflow: "hidden",
            display: "-webkit-box",
            WebkitLineClamp: 2,
            WebkitBoxOrient: "vertical",
          }}
        >
          {fluxo.name}
        </span>
        {fluxo.active ? (
          <span className="chip" style={{ flexShrink: 0, color: "var(--success)" }}>
            <span className="status-dot" style={{ background: "var(--success)", width: 6, height: 6 }} />
            Ativo
          </span>
        ) : (
          <span className="chip" style={{ flexShrink: 0, color: "var(--muted)" }}>
            <span className="status-dot" style={{ background: "var(--border-strong)", width: 6, height: 6 }} />
            Pausado
          </span>
        )}
      </div>

      <div style={{ display: "flex", alignItems: "center", gap: 6, flexWrap: "wrap" }}>
        <span className="chip">{gatilho}</span>
        {condicoes > 0 && (
          <span style={{ fontSize: 11.5, color: "var(--muted)" }}>
            {condicoes} {condicoes === 1 ? "condição" : "condições"}
          </span>
        )}
        <span style={{ fontSize: 11.5, color: "var(--muted)" }}>
          {acoes} {acoes === 1 ? "ação" : "ações"}
        </span>
      </div>

      <span style={{ fontSize: 11.5, color: "var(--muted)" }}>
        Atualizado {tempoRelativo(fluxo.updatedAt)}
      </span>
    </Link>
  );
}

export default function FluxosPage() {
  const [fluxos, setFluxos] = useState<WorkflowResponse[]>([]);
  const [carregando, setCarregando] = useState(true);
  const [erro, setErro] = useState<string | null>(null);
  const [tentativa, setTentativa] = useState(0);

  useEffect(() => {
    let ativo = true;
    setCarregando(true);
    setErro(null);
    listWorkflows()
      .then((r) => {
        if (ativo) setFluxos(r);
      })
      .catch((e) => {
        if (!ativo) return;
        setErro(e instanceof ApiRequestError ? e.message : "Falha ao carregar os fluxos.");
      })
      .finally(() => {
        if (ativo) setCarregando(false);
      });
    return () => {
      ativo = false;
    };
  }, [tentativa]);

  return (
    <div className="page">
      <header
        style={{
          display: "flex",
          alignItems: "flex-end",
          justifyContent: "space-between",
          gap: 24,
          marginBottom: 28,
        }}
      >
        <div>
          <div className="eyebrow">NORA Flows</div>
          <h1 className="h1">Fluxos</h1>
          <p className="lede" style={{ marginTop: 8 }}>
            Automações que reagem às suas reuniões — gatilhos, condições e ações reais.
          </p>
        </div>
        <Link href={"/fluxos/novo" as Route} className="btn btn-primary" style={{ flexShrink: 0 }}>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
          Novo fluxo
        </Link>
      </header>

      {erro && (
        <div
          className="notice notice--danger"
          style={{ marginBottom: 16, display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12 }}
        >
          <span>Não consegui carregar os fluxos agora ({erro}).</span>
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => setTentativa((t) => t + 1)}>
            Tentar de novo
          </button>
        </div>
      )}

      {carregando ? (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 14 }}>
          {[0, 1, 2].map((i) => (
            <div key={i} className="card" style={{ display: "flex", flexDirection: "column", gap: 12 }}>
              <div className="skel" style={{ width: "65%", height: 15 }} />
              <div className="skel" style={{ width: "45%", height: 12 }} />
              <div className="skel" style={{ width: "35%", height: 11 }} />
            </div>
          ))}
        </div>
      ) : fluxos.length === 0 && !erro ? (
        <div
          style={{
            display: "flex",
            flexDirection: "column",
            alignItems: "center",
            gap: 18,
            padding: "56px 24px",
            textAlign: "center",
            border: "1px dashed var(--border-strong)",
            borderRadius: 14,
          }}
        >
          <IlustracaoFluxo />
          <div style={{ maxWidth: 380 }}>
            <h2
              style={{
                fontFamily: "var(--display)",
                fontSize: 18,
                fontWeight: 500,
                letterSpacing: "-0.018em",
                margin: "0 0 6px",
                color: "var(--ink)",
              }}
            >
              Nenhum fluxo ainda
            </h2>
            <p style={{ fontSize: 13.5, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
              Um fluxo conecta um gatilho a ações — por exemplo, enviar um e-mail sempre que uma
              reunião terminar com o score baixo.
            </p>
          </div>
          <Link href={"/fluxos/novo" as Route} className="btn btn-primary">
            Criar primeiro fluxo
          </Link>
        </div>
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 14 }}>
          {fluxos.map((f) => (
            <CardFluxo key={f.id} fluxo={f} />
          ))}
        </div>
      )}
    </div>
  );
}
