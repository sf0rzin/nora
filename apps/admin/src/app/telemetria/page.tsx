import { getCost } from "@/lib/data";

export const dynamic = "force-dynamic";

export default async function TelemetriaPage() {
  const cost = await getCost();
  const maxCost = Math.max(...cost.rows.map((r) => r.costUsd), 0.0001);

  return (
    <div style={{ maxWidth: 920, margin: "0 auto", padding: "48px 40px 80px" }}>
      <header style={{ marginBottom: 28 }}>
        <h1 style={{ fontFamily: "var(--display)", fontSize: 28, fontWeight: 500, letterSpacing: "-0.025em", margin: "0 0 6px" }}>
          Telemetria
        </h1>
        <p style={{ fontSize: 14, color: "var(--muted)", margin: 0, lineHeight: 1.6 }}>
          Custo de IA por serviço (prioridade 1). Saúde do sistema e métricas de negócio entram nas próximas fatias.
        </p>
      </header>

      <h2 style={sectionLabel}>Custo de IA · {cost.from} → {cost.to}</h2>
      <div style={{ border: "1px solid var(--border)", borderRadius: 12, padding: 20, marginBottom: 32 }}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 8, marginBottom: 18 }}>
          <span style={{ fontFamily: "var(--display)", fontSize: 34, fontWeight: 600, letterSpacing: "-0.03em" }}>
            ${cost.totalCostUsd.toFixed(2)}
          </span>
          <span style={{ fontSize: 13, color: "var(--muted)" }}>em {cost.totalCalls} chamadas</span>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 14 }}>
          {cost.rows.map((r) => (
            <div key={r.key}>
              <div style={{ display: "flex", justifyContent: "space-between", fontSize: 13, marginBottom: 5 }}>
                <span>{r.label}</span>
                <span style={{ fontFamily: "var(--mono)" }}>
                  ${r.costUsd.toFixed(3)} <span style={{ color: "var(--muted)" }}>· {r.calls} calls</span>
                </span>
              </div>
              <div style={{ height: 8, background: "var(--chip)", borderRadius: 999, overflow: "hidden" }}>
                <div style={{ width: `${(r.costUsd / maxCost) * 100}%`, height: "100%", background: "var(--accent)", borderRadius: 999 }} />
              </div>
              <div style={{ fontSize: 11, color: "var(--muted)", fontFamily: "var(--mono)", marginTop: 3 }}>
                {(r.promptTokens / 1000).toFixed(0)}k in · {(r.completionTokens / 1000).toFixed(0)}k out
              </div>
            </div>
          ))}
        </div>
      </div>

      <h2 style={sectionLabel}>Saúde do sistema</h2>
      <Placeholder texto="Latência, taxa de erro e throughput — lidos do Application Insights (já provisionado). Próxima fatia." />

      <h2 style={{ ...sectionLabel, marginTop: 32 }}>Métricas de negócio</h2>
      <Placeholder texto="Reuniões processadas, chats/dia, conversão de signup. Agregado do banco primário (cortável se o prazo apertar)." />
    </div>
  );
}

const sectionLabel: React.CSSProperties = {
  fontFamily: "var(--mono)",
  fontSize: 10.5,
  fontWeight: 500,
  letterSpacing: "0.08em",
  textTransform: "uppercase",
  color: "var(--muted)",
  margin: "0 0 12px",
};

function Placeholder({ texto }: { texto: string }) {
  return (
    <div
      style={{
        border: "1px dashed var(--border-strong)",
        borderRadius: 12,
        padding: "24px 18px",
        fontSize: 13,
        color: "var(--muted)",
        lineHeight: 1.55,
      }}
    >
      {texto}
    </div>
  );
}
