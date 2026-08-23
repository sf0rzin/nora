import { AccessDenied } from "@/components/access-denied";
import { guardPage } from "@/lib/access";
import { getBusiness, getCost, getHealth } from "@/lib/data";
import { COST_GROUP_BY, COST_GROUP_BY_LABEL } from "@/lib/contracts";
import type { CostGroupBy, ServiceHealth } from "@/lib/contracts";

export const dynamic = "force-dynamic";

/** Window and aggregation dimension, as the operator typed them into the URL. */
interface TelemetrySearch {
  from?: string;
  to?: string;
  groupBy?: string;
}

/**
 * Only what `<input type="date">` emits gets through. The backend answers 400 for an unparseable
 * date, and a query string is operator-editable: a typo would replace the page with an error screen
 * instead of falling back to the default window.
 */
function isoDate(value: string | undefined): string | undefined {
  return value != null && /^\d{4}-\d{2}-\d{2}$/.test(value) ? value : undefined;
}

function costGroupBy(value: string | undefined): CostGroupBy {
  return COST_GROUP_BY.find((g) => g === value) ?? "service";
}

/**
 * The window and the aggregation dimension live in the URL, and the controls are a plain GET form.
 *
 * Both were unreachable from the console until 2026-08-23: `getCost`/`getBusiness` already took
 * `from`/`to` and nobody passed them, and the query string hardcoded `groupBy=service` — so the
 * "per tenant" in US83's own title was only reachable by curl. A GET form keeps this a server
 * component (no client bundle, no state) and makes a filtered view a shareable link, which for an
 * operator pasting evidence into an incident thread is the difference that matters.
 */
export default async function TelemetriaPage({
  searchParams,
}: {
  searchParams: Promise<TelemetrySearch>;
}) {
  // See the note in app/page.tsx: the layout does not re-run on RSC navigation, so each read
  // gates itself.
  const gate = await guardPage();
  if (!gate.ok) return <AccessDenied reason={gate.reason} />;

  const params = await searchParams;
  const from = isoDate(params.from);
  const to = isoDate(params.to);
  const groupBy = costGroupBy(params.groupBy);

  const [cost, health, business] = await Promise.all([
    getCost(from, to, groupBy),
    getHealth(),
    getBusiness(from, to),
  ]);
  const maxCost = Math.max(...cost.rows.map((r) => r.costUsd), 0.0001);

  return (
    <div style={{ maxWidth: 920, margin: "0 auto", padding: "48px 40px 80px" }}>
      <header style={{ marginBottom: 28 }}>
        <h1 style={{ fontFamily: "var(--display)", fontSize: 28, fontWeight: 500, letterSpacing: "-0.025em", margin: "0 0 6px" }}>
          Telemetria
        </h1>
        <p style={{ fontSize: 14, color: "var(--muted)", margin: 0, lineHeight: 1.6 }}>
          {/* "Application Insights" until 2026-08-23 — ADR 0034 replaced it with Prometheus, and the
              unavailability notice further down this same page already asked for
              NORA_PLATFORM_HEALTH_PROMETHEUS_URL. */}
          Custo de IA por serviço, modelo ou tenant, saúde do sistema (Prometheus) e métricas de
          negócio do banco primário.
        </p>
      </header>

      <form method="get" style={filterRow}>
        <label style={filterField}>
          <span style={filterLabel}>Agrupar por</span>
          <select name="groupBy" defaultValue={groupBy} style={control}>
            {COST_GROUP_BY.map((g) => (
              <option key={g} value={g}>
                {COST_GROUP_BY_LABEL[g]}
              </option>
            ))}
          </select>
        </label>
        <label style={filterField}>
          <span style={filterLabel}>De</span>
          <input type="date" name="from" defaultValue={from ?? ""} style={control} />
        </label>
        <label style={filterField}>
          <span style={filterLabel}>Até</span>
          <input type="date" name="to" defaultValue={to ?? ""} style={control} />
        </label>
        <button type="submit" style={{ ...control, cursor: "pointer" }}>
          Aplicar
        </button>
        <span style={{ fontSize: 11.5, color: "var(--muted)" }}>
          Sem datas, a janela é as últimas 24h.
        </span>
      </form>

      <h2 style={sectionLabel}>
        Custo de IA por {COST_GROUP_BY_LABEL[groupBy].toLowerCase()} · {cost.from} → {cost.to}
      </h2>
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

const filterRow: React.CSSProperties = {
  display: "flex",
  alignItems: "flex-end",
  flexWrap: "wrap",
  gap: 10,
  marginBottom: 20,
};

const filterField: React.CSSProperties = { display: "flex", flexDirection: "column", gap: 4 };

const filterLabel: React.CSSProperties = {
  fontSize: 10.5,
  fontWeight: 500,
  letterSpacing: "0.07em",
  textTransform: "uppercase",
  color: "var(--muted)",
};

const control: React.CSSProperties = {
  fontFamily: "var(--sans)",
  fontSize: 13,
  color: "var(--ink)",
  background: "var(--canvas)",
  border: "1px solid var(--border)",
  borderRadius: 8,
  padding: "7px 10px",
  outline: "none",
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
