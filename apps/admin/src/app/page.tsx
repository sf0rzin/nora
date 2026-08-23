import { AccessDenied } from "@/components/access-denied";
import { guardPage } from "@/lib/access";
import { getBindings, getCost, getFlags, getModels, modelOf } from "@/lib/data";
import { SERVICE_LABEL } from "@/lib/contracts";

export const dynamic = "force-dynamic";

export default async function OverviewPage() {
  // The RootLayout gate is not enough for reads: the App Router does partial rendering and
  // does NOT reinvoke the layout of an unchanged parent segment on a client-side RSC navigation.
  // A request with the router state tree already filled in renders the page and returns the
  // RSC payload without the layout's checkAccess ever running. Same reason the server
  // actions call requireAccess() (see lib/access.ts) — here it applies to the reads.
  const gate = await guardPage();
  if (!gate.ok) return <AccessDenied reason={gate.reason} />;

  const [models, bindings, flags, cost] = await Promise.all([
    getModels(),
    getBindings(),
    getFlags(),
    getCost(),
  ]);

  return (
    <div style={{ maxWidth: 920, margin: "0 auto", padding: "48px 40px 80px" }}>
      <header style={{ marginBottom: 28 }}>
        <h1 style={{ fontFamily: "var(--display)", fontSize: 28, fontWeight: 500, letterSpacing: "-0.025em", margin: "0 0 6px" }}>
          Visão geral
        </h1>
        <p style={{ fontSize: 14, color: "var(--muted)", margin: 0, lineHeight: 1.6 }}>
          O que está rodando na plataforma agora — modelo de IA por serviço, status e custo do período.
        </p>
      </header>

      <SectionLabel>Modelos ativos por serviço</SectionLabel>
      <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fill, minmax(260px, 1fr))", gap: 12, marginBottom: 32 }}>
        {bindings.map((b) => {
          const m = modelOf(models, b.modelId);
          return (
            <div key={b.service} style={card}>
              <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 8 }}>
                <span style={{ fontSize: 12.5, color: "var(--muted)" }}>{SERVICE_LABEL[b.service]}</span>
                <StatusPill on={b.enabled} />
              </div>
              <div style={{ fontSize: 16, fontWeight: 600, letterSpacing: "-0.01em", marginTop: 6 }}>{m?.label ?? b.modelId}</div>
              <div style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 2, fontVariantNumeric: "tabular-nums" }}>
                {m ? `${m.provider} · $${m.inputCostPer1M}/$${m.outputCostPer1M} por 1M` : "modelo desconhecido"}
              </div>
            </div>
          );
        })}
      </div>

      <SectionLabel>Custo de IA no período ({cost.from} → {cost.to})</SectionLabel>
      <div style={{ ...card, marginBottom: 32, display: "flex", alignItems: "baseline", gap: 24 }}>
        <div>
          <div style={{ fontSize: 11, color: "var(--muted)", textTransform: "uppercase", letterSpacing: "0.06em" }}>Total</div>
          <div style={{ fontFamily: "var(--display)", fontSize: 32, fontWeight: 600, letterSpacing: "-0.03em" }}>
            ${cost.totalCostUsd.toFixed(2)}
          </div>
        </div>
        <div>
          <div style={{ fontSize: 11, color: "var(--muted)", textTransform: "uppercase", letterSpacing: "0.06em" }}>Chamadas</div>
          <div style={{ fontFamily: "var(--display)", fontSize: 32, fontWeight: 600, letterSpacing: "-0.03em" }}>{cost.totalCalls}</div>
        </div>
      </div>

      <SectionLabel>Serviços (feature flags)</SectionLabel>
      {/*
        Read-only, and said out loud. The control plane exposes GET /admin/platform/flags and
        nothing else — there is no PUT anywhere in the API, which the contract records as a v1
        choice ("v1 is read-only (no toggle PUT yet)"). Showing the state without saying it cannot
        be changed here leaves the operator hunting for a toggle that does not exist; the sentence
        below costs one line and ends the hunt. The per-service switch further up this page is a
        different thing (llm_config binding), which is exactly why the two get told apart.
      */}
      <p style={{ fontSize: 11.5, color: "var(--muted)", margin: "-6px 0 10px", lineHeight: 1.5 }}>
        Somente leitura nesta versão — ligar ou desligar uma flag ainda é mudança de configuração no
        banco do plano de controle. O botão Ativo/Desligado da tela de Modelos é outra coisa: ele
        controla o binding do serviço, não a flag.
      </p>
      <div style={{ border: "1px solid var(--border)", borderRadius: 12, overflow: "hidden" }}>
        {flags.map((f, i) => (
          <div
            key={f.key}
            style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12, padding: "12px 16px", borderTop: i === 0 ? "none" : "1px solid var(--border)" }}
          >
            <div>
              <div style={{ fontSize: 14 }}>{f.description}</div>
              <div style={{ fontSize: 11.5, color: "var(--muted)" }}>{f.key}</div>
            </div>
            <StatusPill on={f.enabled} />
          </div>
        ))}
      </div>
    </div>
  );
}

const card: React.CSSProperties = {
  border: "1px solid var(--border)",
  borderRadius: 12,
  padding: 16,
  background: "var(--canvas)",
};

function SectionLabel({ children }: { children: React.ReactNode }) {
  return (
    <h2 style={{ fontSize: 10.5, fontWeight: 500, letterSpacing: "0.08em", textTransform: "uppercase", color: "var(--muted)", margin: "0 0 12px" }}>
      {children}
    </h2>
  );
}

function StatusPill({ on }: { on: boolean }) {
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 11.5, color: on ? "var(--success)" : "var(--muted)" }}>
      <span style={{ width: 7, height: 7, borderRadius: "50%", background: on ? "var(--success)" : "var(--border-strong)" }} />
      {on ? "Ativo" : "Desligado"}
    </span>
  );
}
