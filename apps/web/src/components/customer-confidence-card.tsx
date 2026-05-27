import type {
  ConfidenceBand,
  ConfidenceTrend,
  CustomerConfidence,
  Severity,
} from "@/lib/api/types";
import { Badge, Card } from "@/components/core/ui";

// Band → linguagem de cor danger/warn/success via tokens editoriais, espelhando
// o ProductivityScoreCard (--danger / --warn / --success).
const BAND_LABEL: Record<ConfidenceBand, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

const BAND_TONE: Record<ConfidenceBand, "success" | "warn" | "danger"> = {
  LOW: "danger",
  MEDIUM: "warn",
  HIGH: "success",
};

const BAND_SCORE_COLOR: Record<ConfidenceBand, string> = {
  LOW: "var(--danger)",
  MEDIUM: "var(--warn)",
  HIGH: "var(--success)",
};

const TREND_META: Record<
  ConfidenceTrend,
  { glyph: string; label: string; tone: "default" | "success" | "danger" }
> = {
  IMPROVING: {
    glyph: "↑",
    label: "Confiança em melhora",
    tone: "success",
  },
  STABLE: {
    glyph: "→",
    label: "Confiança estável",
    tone: "default",
  },
  DECLINING: {
    glyph: "↓",
    label: "Confiança em queda",
    tone: "danger",
  },
};

const SEVERITY_LABEL: Record<Severity, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

const SEVERITY_COLOR: Record<Severity, string> = {
  LOW: "var(--warn)",
  MEDIUM: "var(--warn)",
  HIGH: "var(--danger)",
};

function TrendBadge({ trend }: { trend: ConfidenceTrend }) {
  const meta = TREND_META[trend];
  return (
    <Badge tone={meta.tone}>
      <span aria-hidden="true">{meta.glyph}</span>
      <span>{meta.label}</span>
    </Badge>
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

  const {
    score,
    band,
    trend,
    accountName,
    rationale,
    buyingSignals,
    objections,
  } = confidence;

  return (
    <Card>
      <div
        style={{ display: "flex", flexDirection: "column", gap: 24 }}
        aria-label="Sinal de confiança do cliente nesta reunião"
      >
        <header
          style={{
            display: "flex",
            flexWrap: "wrap",
            alignItems: "flex-start",
            justifyContent: "space-between",
            gap: 16,
          }}
        >
          <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
            <p className="nora-section-title" style={{ marginBottom: 0 }}>
              Confiança do cliente
            </p>
            <p style={{ fontSize: 18, fontWeight: 600, color: "var(--ink)" }}>
              {accountName ?? "Conta não identificada"}
            </p>
          </div>
          <div style={{ display: "flex", flexWrap: "wrap", alignItems: "center", gap: 12 }}>
            <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
              <span
                style={{
                  fontFamily: "var(--display)",
                  fontSize: 48,
                  fontWeight: 600,
                  letterSpacing: "-0.02em",
                  lineHeight: 1,
                  color: BAND_SCORE_COLOR[band],
                }}
                aria-label={`Score de confiança ${score} de 100`}
              >
                {score}
              </span>
              <span style={{ fontSize: 14, color: "var(--muted)" }}>/ 100</span>
            </div>
            <Badge tone={BAND_TONE[band]}>Confiança {BAND_LABEL[band]}</Badge>
            {trend && <TrendBadge trend={trend} />}
          </div>
        </header>

        <section
          style={{ display: "flex", flexDirection: "column", gap: 12 }}
          aria-labelledby="buying-signals-heading"
        >
          <h3
            id="buying-signals-heading"
            className="nora-section-title"
            style={{ marginBottom: 0 }}
          >
            Sinais de compra ({buyingSignals.length})
          </h3>
          {buyingSignals.length === 0 ? (
            <p style={{ fontSize: 13.5, color: "var(--muted)" }}>
              Nenhum sinal de compra detectado.
            </p>
          ) : (
            <ul
              style={{
                display: "flex",
                flexDirection: "column",
                gap: 12,
                margin: 0,
                padding: 0,
                listStyle: "none",
              }}
            >
              {buyingSignals.map((signal, idx) => (
                <li
                  key={`${signal.type}-${idx}`}
                  style={{
                    display: "flex",
                    alignItems: "flex-start",
                    gap: 12,
                    border: "1px solid var(--border)",
                    borderRadius: "var(--radius-sm)",
                    padding: 12,
                  }}
                >
                  <span
                    aria-hidden="true"
                    style={{
                      marginTop: 2,
                      display: "inline-flex",
                      height: 20,
                      width: 20,
                      flexShrink: 0,
                      alignItems: "center",
                      justifyContent: "center",
                      borderRadius: "var(--radius-pill)",
                      fontSize: 12,
                      fontWeight: 600,
                      background: "oklch(0.94 0.05 155)",
                      color: "var(--success)",
                    }}
                  >
                    ↑
                  </span>
                  <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                    <p style={{ fontSize: 14, fontWeight: 500, color: "var(--ink)" }}>
                      {signal.type}
                    </p>
                    <blockquote
                      style={{
                        borderLeft: "2px solid var(--border-strong)",
                        paddingLeft: 12,
                        fontSize: 12,
                        fontStyle: "italic",
                        color: "var(--muted)",
                        margin: 0,
                      }}
                    >
                      “{signal.quote}”
                    </blockquote>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section
          style={{ display: "flex", flexDirection: "column", gap: 12 }}
          aria-labelledby="objections-heading"
        >
          <h3
            id="objections-heading"
            className="nora-section-title"
            style={{ marginBottom: 0 }}
          >
            Objeções ({objections.length})
          </h3>
          {objections.length === 0 ? (
            <p style={{ fontSize: 13.5, color: "var(--muted)" }}>
              Nenhuma objeção registrada.
            </p>
          ) : (
            <ul
              style={{
                display: "flex",
                flexDirection: "column",
                gap: 12,
                margin: 0,
                padding: 0,
                listStyle: "none",
              }}
            >
              {objections.map((objection, idx) => (
                <li
                  key={`${objection.type}-${idx}`}
                  style={{
                    display: "flex",
                    alignItems: "flex-start",
                    gap: 12,
                    border: "1px solid var(--border)",
                    borderRadius: "var(--radius-sm)",
                    padding: 12,
                  }}
                >
                  <span
                    aria-hidden="true"
                    style={{
                      marginTop: 2,
                      display: "inline-flex",
                      height: 20,
                      width: 20,
                      flexShrink: 0,
                      alignItems: "center",
                      justifyContent: "center",
                      borderRadius: "var(--radius-pill)",
                      fontSize: 12,
                      fontWeight: 600,
                      background: "oklch(0.95 0.04 25)",
                      color: "var(--danger)",
                    }}
                  >
                    !
                  </span>
                  <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                    <div
                      style={{
                        display: "flex",
                        flexWrap: "wrap",
                        alignItems: "baseline",
                        columnGap: 12,
                        rowGap: 4,
                      }}
                    >
                      <p style={{ fontSize: 14, fontWeight: 500, color: "var(--ink)" }}>
                        {objection.type}
                      </p>
                      <span
                        style={{
                          fontSize: 11,
                          fontWeight: 600,
                          letterSpacing: "0.06em",
                          textTransform: "uppercase",
                          color: SEVERITY_COLOR[objection.severity],
                        }}
                      >
                        Severidade {SEVERITY_LABEL[objection.severity]}
                      </span>
                      {objection.competitor && (
                        <Badge>Concorrente: {objection.competitor}</Badge>
                      )}
                    </div>
                    <blockquote
                      style={{
                        borderLeft: "2px solid var(--border-strong)",
                        paddingLeft: 12,
                        fontSize: 12,
                        fontStyle: "italic",
                        color: "var(--muted)",
                        margin: 0,
                      }}
                    >
                      “{objection.quote}”
                    </blockquote>
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>

        <section
          style={{ display: "flex", flexDirection: "column", gap: 8 }}
          aria-labelledby="confidence-rationale-heading"
        >
          <h3
            id="confidence-rationale-heading"
            className="nora-section-title"
            style={{ marginBottom: 0 }}
          >
            Justificativa
          </h3>
          <p style={{ fontSize: 13.5, lineHeight: 1.6, color: "var(--muted)" }}>
            {rationale}
          </p>
        </section>

        <footer style={{ borderTop: "1px solid var(--border)", paddingTop: 12 }}>
          <p style={{ fontSize: 12, color: "var(--muted)" }}>
            Sinal de confiança por reunião — não usar isoladamente para avaliar o
            vendedor.
          </p>
        </footer>
      </div>
    </Card>
  );
}
