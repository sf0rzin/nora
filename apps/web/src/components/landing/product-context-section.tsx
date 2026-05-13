"use client";

import type { CSSProperties } from "react";

/* =========================================================
   TenantCard
   ========================================================= */

type TenantCardProps = {
  name: string;
  products: string[];
  accent?: boolean;
};

function TenantCard({ name, products, accent = false }: TenantCardProps) {
  return (
    <div className={`tenant-card ${accent ? "is-accent" : ""}`}>
      <div className="tenant-name">{name}</div>
      <div className="tenant-products">
        {products.map((p) => (
          <div key={p} className="tenant-product">
            {p}
          </div>
        ))}
      </div>
      <style jsx>{`
        .tenant-card {
          padding: 20px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: var(--radius);
        }
        .tenant-card.is-accent {
          border-color: var(--accent-dim);
          background: linear-gradient(
            180deg,
            var(--surface),
            color-mix(in oklab, var(--accent) 8%, var(--surface))
          );
        }
        .tenant-name {
          font-size: 18px;
          font-weight: 500;
          margin-bottom: 12px;
          letter-spacing: -0.01em;
        }
        .tenant-products {
          display: flex;
          flex-direction: column;
          gap: 4px;
        }
        .tenant-product {
          font-family: var(--font-mono);
          font-size: 12px;
          padding: 4px 0;
          color: var(--fg-muted);
          border-bottom: 1px dashed var(--border);
        }
        .tenant-product:last-child {
          border-bottom: 0;
        }
      `}</style>
    </div>
  );
}

/* =========================================================
   RAGDiagram
   ========================================================= */

function RAGDiagram() {
  return (
    <div className="rag">
      <div className="rag-col rag-col--input">
        <div className="kicker">Catálogo do tenant</div>
        <div className="rag-doc">Produtos · descrições · diferenciais</div>
        <div className="rag-doc">Concorrentes · mercado</div>
        <div className="rag-doc">Glossário · processos</div>
      </div>

      {/* ViewBox 80x240 casa com a coluna real (.rag-arrows: 80x240px).
       * Antes era 200x240 + preserveAspectRatio="none", o que distorcia
       * as curvas horizontalmente em telas largas e quebrava o gradient.
       * Agora paths recalculados pra 80 unidades em x: catalog (esquerda)
       * → engine (direita), com 3 curvas convergindo no meio vertical. */}
      <svg className="rag-arrows" viewBox="0 0 80 240" aria-hidden="true">
        <defs>
          <linearGradient id="ragLine" x1="0" x2="1">
            <stop offset="0%" stopColor="oklch(0.30 0.012 250)" />
            <stop offset="100%" stopColor="oklch(0.70 0.17 248)" />
          </linearGradient>
        </defs>
        <path d="M0 30 C 32 30, 48 100, 80 100" stroke="url(#ragLine)" strokeWidth="1.4" fill="none" />
        <path d="M0 120 L 80 120" stroke="url(#ragLine)" strokeWidth="1.4" fill="none" />
        <path d="M0 210 C 32 210, 48 140, 80 140" stroke="url(#ragLine)" strokeWidth="1.4" fill="none" />
      </svg>

      <div className="rag-col rag-col--engine">
        <div className="rag-engine">
          <div className="rag-engine-tag mono">RAG · Azure AI Search</div>
          <div className="rag-engine-title">Embeddings vetoriais</div>
          <div className="rag-engine-meta mono">text-embedding-3-small</div>
          <div className="rag-engine-pulse" />
        </div>
      </div>

      {/* Linha unica engine → output (centro vertical). ViewBox alinhado
       * com a coluna 80x240. Reusa o gradient #ragLine definido acima. */}
      <svg className="rag-arrows" viewBox="0 0 80 240" aria-hidden="true">
        <path d="M0 120 L 80 120" stroke="url(#ragLine)" strokeWidth="1.4" fill="none" />
      </svg>

      <div className="rag-col rag-col--output">
        <div className="kicker">Saída contextualizada</div>
        <div className="rag-out">
          <span className="mono" style={{ color: "var(--accent-bright)" }}>
            oportunidade
          </span>{" "}
          — cliente mencionou <strong>Protheus</strong> como sistema atual; alta probabilidade de
          fit com <strong>RM Folha</strong> integrado.
        </div>
      </div>

      <style jsx>{`
        .rag {
          display: grid;
          grid-template-columns: 1fr 80px 1fr 80px 1fr;
          gap: 0;
          align-items: center;
          padding: 32px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: var(--radius-lg);
        }
        .rag-col {
          display: flex;
          flex-direction: column;
          gap: 8px;
        }
        .rag-col--engine {
          align-items: center;
        }
        .rag-doc {
          padding: 10px 14px;
          background: var(--bg-deep);
          border: 1px solid var(--border);
          border-radius: var(--radius-sm);
          font-family: var(--font-mono);
          font-size: 12px;
          color: var(--fg-muted);
        }
        .rag-arrows {
          width: 80px;
          height: 240px;
        }
        .rag-engine {
          position: relative;
          padding: 24px;
          background: var(--bg-deep);
          border: 1px solid var(--accent-dim);
          border-radius: var(--radius);
          text-align: center;
          width: 100%;
          overflow: hidden;
        }
        .rag-engine-tag {
          font-size: 10px;
          letter-spacing: 0.14em;
          text-transform: uppercase;
          color: var(--accent-bright);
        }
        .rag-engine-title {
          font-size: 18px;
          font-weight: 500;
          margin: 8px 0 4px;
          letter-spacing: -0.01em;
        }
        .rag-engine-meta {
          font-size: 11px;
          color: var(--fg-dim);
        }
        .rag-engine-pulse {
          position: absolute;
          inset: -50%;
          background: radial-gradient(circle, var(--accent-glow) 0%, transparent 60%);
          animation: pulse 3.2s ease-in-out infinite;
          pointer-events: none;
        }
        @keyframes pulse {
          0%,
          100% {
            opacity: 0.3;
            transform: scale(0.9);
          }
          50% {
            opacity: 0.7;
            transform: scale(1.1);
          }
        }
        .rag-out {
          padding: 16px;
          background: var(--bg-deep);
          border: 1px solid var(--border);
          border-radius: var(--radius);
          font-size: 14px;
          line-height: 1.6;
          color: var(--fg-muted);
        }
        .rag-out :global(strong) {
          color: var(--fg);
          font-weight: 500;
        }

        @media (max-width: 880px) {
          .rag {
            grid-template-columns: 1fr;
            gap: 16px;
          }
          .rag-arrows {
            display: none;
          }
        }
      `}</style>
    </div>
  );
}

/* =========================================================
   NLPPipeline
   ========================================================= */

type PipelineStep = {
  n: string;
  title: string;
  desc: string;
  color: string;
};

const PIPELINE_STEPS: PipelineStep[] = [
  { n: "01", title: "PII Shield", desc: "presidio + regex BR · LGPD-first", color: "var(--success)" },
  { n: "02", title: "Limpeza textual", desc: "lowercase, pontuação, stopwords", color: "var(--fg-muted)" },
  { n: "03", title: "TF-IDF baseline", desc: "interpretável · pré-LLM", color: "var(--fg-muted)" },
  { n: "04", title: "RAG Retrieval", desc: "Product Context do tenant", color: "var(--accent)" },
  { n: "05", title: "Embeddings", desc: "OpenAI text-embedding-3-small", color: "var(--accent)" },
  { n: "06", title: "LLM Provider-agnóstico", desc: "json_schema strict · gpt-4o-mini default", color: "var(--accent-bright)" },
  { n: "07", title: "Health Scoring", desc: "temporal · por tenant", color: "var(--warn)" },
];

function NLPPipeline() {
  return (
    <div className="pipeline">
      <div className="pipeline-head">
        <span className="kicker">Pipeline NLPWorker · Python · Azure Container Apps</span>
      </div>
      <div className="pipeline-flow">
        <div className="pipeline-input">
          <span className="mono" style={{ fontSize: 11, color: "var(--fg-dim)" }}>
            INPUT
          </span>
          <div className="pipeline-input-text">
            &quot;...e o cliente mencionou que está avaliando o concorrente, mas tem dúvidas sobre
            integração com o ERP atual...&quot;
          </div>
        </div>

        <div className="pipeline-steps">
          {PIPELINE_STEPS.map((s, i) => {
            const stepStyle: CSSProperties & Record<"--i" | "--c", string> = {
              "--i": String(i),
              "--c": s.color,
            };
            return (
              <div key={s.n} className="pipeline-step" style={stepStyle}>
                <div className="pipeline-step-n">{s.n}</div>
                <div className="pipeline-step-body">
                  <div className="pipeline-step-title">{s.title}</div>
                  <div className="pipeline-step-desc">{s.desc}</div>
                </div>
                <div className="pipeline-step-bar" />
              </div>
            );
          })}
        </div>

        <div className="pipeline-output">
          <span className="mono" style={{ fontSize: 11, color: "var(--accent-bright)" }}>
            OUTPUT · JSON STRICT
          </span>
          <pre className="pipeline-json">{`{
  "opportunities": [{ "product": "ERP Cloud", "p": 0.62 }],
  "competitor_mentions": [{ "name": "X", "stage": "evaluating" }],
  "objections": ["integração com ERP atual"],
  "confidence_score": 0.71,
  "next_best_action": "agendar demo técnica em 48h"
}`}</pre>
        </div>
      </div>

      <style jsx>{`
        .pipeline {
          margin-top: 80px;
          padding: 32px;
          background: var(--bg-deep);
          border: 1px solid var(--border);
          border-radius: var(--radius-lg);
        }
        .pipeline-head {
          margin-bottom: 24px;
        }
        .pipeline-flow {
          display: grid;
          grid-template-columns: 1fr 1.4fr 1fr;
          gap: 20px;
          align-items: stretch;
        }
        .pipeline-input,
        .pipeline-output {
          padding: 16px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: var(--radius);
          display: flex;
          flex-direction: column;
          gap: 10px;
          min-height: 220px;
        }
        .pipeline-input-text {
          font-family: var(--font-mono);
          font-size: 12px;
          color: var(--fg-muted);
          line-height: 1.7;
        }
        .pipeline-json {
          font-family: var(--font-mono);
          font-size: 11px;
          color: var(--fg);
          line-height: 1.6;
          margin: 0;
          white-space: pre-wrap;
          word-break: break-word;
        }
        .pipeline-steps {
          display: flex;
          flex-direction: column;
          gap: 4px;
          padding: 4px;
          background: var(--surface);
          border: 1px solid var(--border);
          border-radius: var(--radius);
        }
        .pipeline-step {
          display: grid;
          grid-template-columns: 36px 1fr 4px;
          gap: 12px;
          align-items: center;
          padding: 8px 12px;
          border-radius: var(--radius-sm);
          position: relative;
          animation: stepIn 600ms var(--ease-out-expo) backwards;
          animation-delay: calc(var(--i) * 80ms);
        }
        .pipeline-step:hover {
          background: var(--bg-deep);
        }
        .pipeline-step-n {
          font-family: var(--font-mono);
          font-size: 11px;
          color: var(--fg-dim);
          letter-spacing: 0.06em;
        }
        .pipeline-step-title {
          font-size: 14px;
          font-weight: 500;
        }
        .pipeline-step-desc {
          font-family: var(--font-mono);
          font-size: 10px;
          color: var(--fg-dim);
          margin-top: 2px;
        }
        .pipeline-step-bar {
          width: 4px;
          height: 24px;
          background: var(--c);
          border-radius: 2px;
          opacity: 0.8;
        }
        @keyframes stepIn {
          from {
            opacity: 0;
            transform: translateX(-8px);
          }
          to {
            opacity: 1;
            transform: none;
          }
        }

        @media (max-width: 980px) {
          .pipeline-flow {
            grid-template-columns: 1fr;
          }
        }
      `}</style>
    </div>
  );
}

/* =========================================================
   Product Context Section (composição)
   ========================================================= */

export function ProductContextSection() {
  return (
    <section className="section" id="contexto" data-screen-label="04 Product Context">
      <div className="container">
        <div className="ctx-head">
          <div className="eyebrow">Product Context System</div>
          <h2 className="h-section">
            Inteligência calibrada ao
            <br />
            <em
              style={{
                fontFamily: "var(--font-serif)",
                fontStyle: "italic",
                color: "var(--accent-bright)",
              }}
            >
              seu
            </em>{" "}
            negócio. Não a um genérico.
          </h2>
          <p className="h-sub">
            Cada tenant configura o próprio catálogo, glossário e concorrentes. A NORA injeta esse
            contexto no prompt via RAG — extraindo oportunidades e riscos com o vocabulário da sua
            empresa.
          </p>
        </div>

        <RAGDiagram />

        <div className="ctx-tenants">
          <div className="kicker" style={{ marginBottom: 16 }}>
            Mesmo motor · contextos distintos
          </div>
          <div className="ctx-tenants-grid">
            <TenantCard name="TOTVS" products={["Protheus", "RM", "Fluig"]} accent />
            <TenantCard
              name="Fintech XYZ"
              products={["Conta PJ", "Antecipação", "API Pix"]}
            />
            <TenantCard
              name="Distribuidora"
              products={["B2B Marketplace", "Logística", "WMS"]}
            />
          </div>
        </div>

        <NLPPipeline />
      </div>
      <style jsx>{`
        .ctx-head {
          max-width: 760px;
          margin-bottom: 56px;
        }
        .ctx-tenants {
          margin: 80px 0;
        }
        .ctx-tenants-grid {
          display: grid;
          grid-template-columns: 1fr 1fr 1fr;
          gap: 12px;
        }
        @media (max-width: 760px) {
          .ctx-tenants-grid {
            grid-template-columns: 1fr;
          }
        }
      `}</style>
    </section>
  );
}
