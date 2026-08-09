import { requireAccess } from "@/lib/access";
import { getBusiness, getCost, getHealth } from "@/lib/data";
import type { ServiceHealth } from "@/lib/contracts";

export const dynamic = "force-dynamic";

export default async function TelemetriaPage() {
  // See the note in app/page.tsx: the layout does not re-run on RSC navigation, so each read
  // gates itself.
  await requireAccess();

  const [cost, health, business] = await Promise.all([getCost(), getHealth(), getBusiness()]);
  const maxCost = Math.max(...cost.rows.map((r) => r.costUsd), 0.0001);

  return (
    <div style={{ maxWidth: 920, margin: "0 auto", padding: "48px 40px 80px" }}>
      <header style={{ marginBottom: 28 }}>
        <h1 style={{ fontFamily: "var(--display)", fontSize: 28, fontWeight: 500, letterSpacing: "-0.025em", margin: "0 0 6px" }}>
          Telemetria
        </h1>
        <p style={{ fontSize: 14, color: "var(--muted)", margin: 0, lineHeight: 1.6 }}>
          Custo de IA por serviço, saúde do sistema (Application Insights) e métricas de negócio do banco primário.
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
                <span style={{ fontVariantNumeric: "tabular-nums" }}>
                  ${r.costUsd.toFixed(3)} <span style={{ color: "var(--muted)" }}>· {r.calls} calls</span>
                </span>
              </div>
              <div style={{ height: 8, background: "var(--chip)", borderRadius: 999, overflow: "hidden" }}>
                <div style={{ width: `${(r.costUsd / maxCost) * 100}%`, height: "100%", background: "var(--accent)", borderRadius: 999 }} />
              </div>
              <div style={{ fontSize: 11, color: "var(--muted)", fontVariantNumeric: "tabular-nums", marginTop: 3 }}>
                {(r.promptTokens / 1000).toFixed(0)}k in · {(r.completionTokens / 1000).toFixed(0)}k out
              </div>
            </div>
          ))}
          {cost.rows.length === 0 && (
            <p style={{ fontSize: 13, color: "var(--muted)", margin: 0 }}>
              Sem chamadas registradas na janela. Gere tráfego (chat/análise) para popular.
            </p>
          )}
        </div>
      </div>

      <h2 style={sectionLabel}>
        Saúde do sistema · janela {health.window}
        {health.degraded && (
          <span style={{ marginLeft: 10, color: "var(--danger, #c2410c)", textTransform: "none", letterSpacing: 0 }}>
            ● degradado
          </span>
        )}
      </h2>
      {health.source === "unavailable" ? (
        <Aviso
          texto={
            health.note ??
            "Prometheus não configurado neste ambiente (NORA_PLATFORM_HEALTH_PROMETHEUS_URL)."
          }
        />
      ) : (
        <div style={{ border: "1px solid var(--border)", borderRadius: 12, overflow: "hidden", marginBottom: 32 }}>
          <table style={{ width: "100%", borderCollapse: "collapse", fontSize: 13 }}>
            <thead>
              <tr style={{ background: "var(--chip)", textAlign: "left" }}>
                <th style={th}>Serviço</th>
                <th style={{ ...th, textAlign: "right" }}>Requisições</th>
                <th style={{ ...th, textAlign: "right" }}>Falhas</th>
                <th style={{ ...th, textAlign: "right" }}>Taxa de erro</th>
                <th style={{ ...th, textAlign: "right" }}>p95</th>
              </tr>
            </thead>
            <tbody>
              {health.services.map((s) => (
                <LinhaSaude key={s.role} s={s} />
              ))}
              {health.services.length === 0 && (
                <tr>
                  <td colSpan={5} style={{ ...td, color: "var(--muted)" }}>
                    Sem requisições na janela.
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      )}

      <h2 style={{ ...sectionLabel, marginTop: 32 }}>
        Métricas de negócio · {business.from} → {business.to}
      </h2>
      {!business.enabled ? (
        <Aviso texto="Métricas de negócio desligadas neste ambiente (nora.platform.business.enabled=false)." />
      ) : (
        <div style={{ display: "grid", gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))", gap: 12 }}>
          <Kpi rotulo="Reuniões analisadas" valor={String(business.analyses)} />
          <Kpi rotulo="Tenants ativos" valor={String(business.tenantsActive)} />
          <Kpi
            rotulo="Productivity médio"
            valor={business.productivityAvg == null ? "—" : business.productivityAvg.toFixed(1)}
            sufixo={business.productivityAvg == null ? undefined : "/100"}
          />
          <Kpi
            rotulo="Confidence médio"
            valor={
              business.customerConfidenceAvg == null
                ? "—"
                : business.customerConfidenceAvg.toFixed(1)
            }
            sufixo={business.customerConfidenceAvg == null ? undefined : "/100"}
          />
        </div>
      )}
    </div>
  );
}

function LinhaSaude({ s }: { s: ServiceHealth }) {
  const erroAlto = s.failureRate > 0.05;
  return (
    <tr style={{ borderTop: "1px solid var(--border)" }}>
      <td style={td}>{s.role}</td>
      <td style={{ ...td, textAlign: "right" }}>{s.requests.toLocaleString("pt-BR")}</td>
      <td style={{ ...td, textAlign: "right" }}>{s.failed.toLocaleString("pt-BR")}</td>
      <td
        style={{
          ...td,
          textAlign: "right",
          color: erroAlto ? "var(--danger, #c2410c)" : undefined,
          fontWeight: erroAlto ? 600 : undefined,
        }}
      >
        {(s.failureRate * 100).toFixed(2)}%
      </td>
      <td style={{ ...td, textAlign: "right", fontVariantNumeric: "tabular-nums" }}>
        {s.p95LatencyMs == null ? "—" : `${Math.round(s.p95LatencyMs)} ms`}
      </td>
    </tr>
  );
}

function Kpi({ rotulo, valor, sufixo }: { rotulo: string; valor: string; sufixo?: string }) {
  return (
    <div style={{ border: "1px solid var(--border)", borderRadius: 12, padding: "16px 18px" }}>
      <div style={{ fontSize: 11, color: "var(--muted)", textTransform: "uppercase", letterSpacing: "0.07em", marginBottom: 6 }}>
        {rotulo}
      </div>
      <div style={{ fontFamily: "var(--display)", fontSize: 26, fontWeight: 600, letterSpacing: "-0.02em" }}>
        {valor}
        {sufixo && <span style={{ fontSize: 13, color: "var(--muted)", fontWeight: 400 }}>{sufixo}</span>}
      </div>
    </div>
  );
}

const sectionLabel: React.CSSProperties = {
  fontSize: 10.5,
  fontWeight: 500,
  letterSpacing: "0.08em",
  textTransform: "uppercase",
  color: "var(--muted)",
  margin: "0 0 12px",
};

const th: React.CSSProperties = {
  padding: "9px 14px",
  fontSize: 10.5,
  fontWeight: 500,
  letterSpacing: "0.07em",
  textTransform: "uppercase",
  color: "var(--muted)",
};

const td: React.CSSProperties = { padding: "10px 14px" };

function Aviso({ texto }: { texto: string }) {
  return (
    <div
      style={{
        border: "1px dashed var(--border-strong)",
        borderRadius: 12,
        padding: "20px 18px",
        fontSize: 13,
        color: "var(--muted)",
        lineHeight: 1.55,
        marginBottom: 32,
      }}
    >
      {texto}
    </div>
  );
}
