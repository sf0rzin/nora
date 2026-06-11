import type {
  ConfidenceBand,
  ConfidenceTrend,
  CustomerConfidence,
  Severity,
} from "@/lib/api/types";

const BAND_LABEL: Record<ConfidenceBand, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

// Banda → cor de sinal (danger/warn/success), espelhando o Health Score.
const BAND_COLOR: Record<ConfidenceBand, string> = {
  LOW: "var(--danger)",
  MEDIUM: "var(--warn)",
  HIGH: "var(--success)",
};

const TREND_META: Record<ConfidenceTrend, { glyph: string; label: string }> = {
  IMPROVING: { glyph: "↑", label: "Em melhora" },
  STABLE: { glyph: "→", label: "Estável" },
  DECLINING: { glyph: "↓", label: "Em queda" },
};

const SEVERITY_LABEL: Record<Severity, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

function softBg(color: string): string {
  return `color-mix(in oklch, ${color} 16%, var(--canvas))`;
}

function TrendChip({ trend }: { trend: ConfidenceTrend }) {
  const meta = TREND_META[trend];
  return (
    <span
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 4,
        padding: "3px 11px",
        borderRadius: 999,
        fontSize: 11,
        fontWeight: 500,
        background: "var(--chip)",
        color: "var(--muted)",
      }}
    >
      <span aria-hidden="true">{meta.glyph}</span>
      <span>{meta.label}</span>
    </span>
  );
}

export interface CustomerConfidenceCardProps {
  confidence: CustomerConfidence | null;
}

export default function CustomerConfidenceCard({
  confidence,
}: CustomerConfidenceCardProps) {
  // Reuniões internas (sem conversa de cliente) não têm confidence: nada a renderizar.
  if (!confidence) return null;

  const { score, band, trend, accountName, rationale, buyingSignals, objections } =
    confidence;
  const bandColor = BAND_COLOR[band];

  return (
    <article className="card card--pad" aria-label="Sinal de confiança do cliente nesta reunião">
      <div style={{ display: "flex", alignItems: "flex-start", justifyContent: "space-between", gap: 16, flexWrap: "wrap" }}>
        <div>
          <div className="sec-label" style={{ marginBottom: 4 }}>
            Conta
          </div>
          <div style={{ fontSize: 16, fontWeight: 500, letterSpacing: "-0.01em", color: "var(--ink)" }}>
            {accountName ?? "Conta não identificada"}
          </div>
        </div>
        <div style={{ display: "flex", alignItems: "center", gap: 12, flexWrap: "wrap" }}>
          <div style={{ display: "flex", alignItems: "baseline", gap: 6 }}>
            <span
              style={{ fontSize: 44, fontWeight: 500, letterSpacing: "-0.03em", lineHeight: 1, color: bandColor }}
              aria-label={`Score de confiança ${score} de 100`}
            >
              {score}
            </span>
            <span style={{ fontSize: 13, color: "var(--muted)" }}>/ 100</span>
          </div>
          <span
            style={{
              display: "inline-flex",
              alignItems: "center",
              padding: "3px 11px",
              borderRadius: 999,
              fontSize: 11,
              fontWeight: 500,
              letterSpacing: "0.05em",
              textTransform: "uppercase",
              background: softBg(bandColor),
              color: bandColor,
            }}
          >
            Confiança {BAND_LABEL[band]}
          </span>
          {trend && <TrendChip trend={trend} />}
        </div>
      </div>

      <div style={{ marginTop: 18 }}>
        <div className="sec-label" style={{ marginBottom: 2 }}>
          Sinais de compra · {buyingSignals.length}
        </div>
        {buyingSignals.length === 0 ? (
          <p style={{ fontSize: 13, color: "var(--muted)", margin: "8px 0 0" }}>Nenhum sinal de compra detectado.</p>
        ) : (
          buyingSignals.map((signal, idx) => (
            <div
              key={`${signal.type}-${idx}`}
              style={{
                display: "flex",
                alignItems: "flex-start",
                gap: 12,
                padding: "12px 0",
                borderTop: idx === 0 ? "none" : "1px solid var(--border)",
              }}
            >
              <span
                aria-hidden="true"
                style={{
                  width: 20,
                  height: 20,
                  borderRadius: "50%",
                  display: "grid",
                  placeItems: "center",
                  fontSize: 11,
                  fontWeight: 600,
                  flexShrink: 0,
                  marginTop: 1,
                  background: softBg("var(--success)"),
                  color: "var(--success)",
                }}
              >
                ↑
              </span>
              <div>
                <div style={{ fontSize: 13.5, fontWeight: 500, color: "var(--ink)" }}>{signal.type}</div>
                <div className="quote" style={{ marginTop: 6 }}>
                  {signal.quote}
                </div>
              </div>
            </div>
          ))
        )}
      </div>

      <div style={{ marginTop: 16 }}>
        <div className="sec-label" style={{ marginBottom: 2 }}>
          Objeções · {objections.length}
        </div>
        {objections.length === 0 ? (
          <p style={{ fontSize: 13, color: "var(--muted)", margin: "8px 0 0" }}>Nenhuma objeção registrada.</p>
        ) : (
          objections.map((objection, idx) => (
            <div
              key={`${objection.type}-${idx}`}
              style={{
                display: "flex",
                alignItems: "flex-start",
                gap: 12,
                padding: "12px 0",
                borderTop: idx === 0 ? "none" : "1px solid var(--border)",
              }}
            >
              <span
                aria-hidden="true"
                style={{
                  width: 20,
                  height: 20,
                  borderRadius: "50%",
                  display: "grid",
                  placeItems: "center",
                  fontSize: 11,
                  fontWeight: 600,
                  flexShrink: 0,
                  marginTop: 1,
                  background: softBg("var(--danger)"),
                  color: "var(--danger)",
                }}
              >
                !
              </span>
              <div>
                <div style={{ display: "flex", alignItems: "baseline", gap: 10, flexWrap: "wrap" }}>
                  <span style={{ fontSize: 13.5, fontWeight: 500, color: "var(--ink)" }}>{objection.type}</span>
                  <span style={{ fontSize: 10.5, fontWeight: 500, color: "var(--danger)", letterSpacing: "0.04em", textTransform: "uppercase" }}>
                    Severidade {SEVERITY_LABEL[objection.severity]}
                  </span>
                  {objection.competitor && (
                    <span className="chip">Concorrente: {objection.competitor}</span>
                  )}
                </div>
                <div className="quote" style={{ marginTop: 6 }}>
                  {objection.quote}
                </div>
              </div>
            </div>
          ))
        )}
      </div>

      <div style={{ marginTop: 16, paddingTop: 14, borderTop: "1px solid var(--border)" }}>
        <p style={{ fontSize: 13, color: "var(--muted)", lineHeight: 1.6, margin: 0 }}>{rationale}</p>
        <p style={{ fontSize: 11.5, color: "var(--muted)", margin: "10px 0 0" }}>
          Sinal por reunião — não usar isoladamente pra avaliar o vendedor.
        </p>
      </div>
    </article>
  );
}
