import type {
  CoverageStatus,
  ProductivityAssessment,
  ProductivityBand,
} from "@/lib/api/types";
import { Badge, Card } from "@/components/core/ui";

const BAND_LABEL: Record<ProductivityBand, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

const BAND_TONE: Record<ProductivityBand, "success" | "warn" | "danger"> = {
  LOW: "danger",
  MEDIUM: "warn",
  HIGH: "success",
};

const BAND_SCORE_COLOR: Record<ProductivityBand, string> = {
  LOW: "var(--danger)",
  MEDIUM: "var(--warn)",
  HIGH: "var(--success)",
};

const STATUS_LABEL: Record<CoverageStatus, string> = {
  ADDRESSED: "Tratado",
  PARTIAL: "Parcial",
  MISSED: "Não tratado",
};

const STATUS_COLOR: Record<CoverageStatus, string> = {
  ADDRESSED: "var(--success)",
  PARTIAL: "var(--warn)",
  MISSED: "var(--danger)",
};

const STATUS_BG: Record<CoverageStatus, string> = {
  ADDRESSED: "oklch(0.94 0.05 155)",
  PARTIAL: "oklch(0.95 0.06 70)",
  MISSED: "oklch(0.95 0.04 25)",
};

const STATUS_GLYPH: Record<CoverageStatus, string> = {
  ADDRESSED: "✓",
  PARTIAL: "~",
  MISSED: "✗",
};

function StatusIcon({ status }: { status: CoverageStatus }) {
  return (
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
        background: STATUS_BG[status],
        color: STATUS_COLOR[status],
      }}
    >
      {STATUS_GLYPH[status]}
    </span>
  );
}

function formatPercent(value: number): string {
  return `${Math.round(value * 100)}%`;
}

export interface ProductivityScoreCardProps {
  assessment: ProductivityAssessment;
}

export default function ProductivityScoreCard({
  assessment,
}: ProductivityScoreCardProps) {
  const {
    score,
    band,
    coverage,
    offTopicRatio,
    decisionDensity,
    rationale,
  } = assessment;

  return (
    <Card>
      <div
        style={{ display: "flex", flexDirection: "column", gap: 24 }}
        aria-label="Avaliação de produtividade da reunião"
      >
        <header style={{ display: "flex", flexWrap: "wrap", alignItems: "baseline", gap: 16 }}>
          <div style={{ display: "flex", alignItems: "baseline", gap: 12 }}>
            <span
              style={{
                fontFamily: "var(--display)",
                fontSize: 56,
                fontWeight: 600,
                letterSpacing: "-0.02em",
                lineHeight: 1,
                color: BAND_SCORE_COLOR[band],
              }}
              aria-label={`Score ${score} de 100`}
            >
              {score}
            </span>
            <span style={{ fontSize: 16, color: "var(--muted)" }}>/ 100</span>
          </div>
          <Badge tone={BAND_TONE[band]}>Produtividade {BAND_LABEL[band]}</Badge>
        </header>

        <section
          style={{ display: "flex", flexDirection: "column", gap: 12 }}
          aria-labelledby="coverage-heading"
        >
          <h3 id="coverage-heading" className="nora-section-title" style={{ marginBottom: 0 }}>
            Cobertura dos outcomes
          </h3>
          {coverage.length === 0 ? (
            <p style={{ fontSize: 13.5, color: "var(--muted)" }}>
              Nenhum outcome avaliado nesta análise.
            </p>
          ) : (
            <ul style={{ display: "flex", flexDirection: "column", gap: 12, margin: 0, padding: 0, listStyle: "none" }}>
              {coverage.map((item, idx) => (
                <li
                  key={`${item.expectedOutcome}-${idx}`}
                  style={{
                    display: "flex",
                    alignItems: "flex-start",
                    gap: 12,
                    border: "1px solid var(--border)",
                    borderRadius: "var(--radius-sm)",
                    padding: 12,
                  }}
                >
                  <StatusIcon status={item.status} />
                  <div style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                    <p style={{ fontSize: 14, fontWeight: 500, color: "var(--ink)" }}>
                      {item.expectedOutcome}
                    </p>
                    <p
                      style={{
                        fontSize: 11,
                        fontWeight: 600,
                        letterSpacing: "0.06em",
                        textTransform: "uppercase",
                        color: STATUS_COLOR[item.status],
                      }}
                    >
                      {STATUS_LABEL[item.status]}
                    </p>
                    {item.evidence && (
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
                        “{item.evidence}”
                      </blockquote>
                    )}
                  </div>
                </li>
              ))}
            </ul>
          )}
        </section>

        {(offTopicRatio !== null || decisionDensity !== null) && (
          <section
            style={{
              display: "grid",
              gap: 12,
              gridTemplateColumns: "repeat(auto-fit, minmax(180px, 1fr))",
            }}
            aria-label="Métricas auxiliares"
          >
            {offTopicRatio !== null && (
              <div
                style={{
                  border: "1px solid var(--border)",
                  borderRadius: "var(--radius-sm)",
                  padding: 12,
                }}
              >
                <p className="nora-section-title" style={{ marginBottom: 0 }}>
                  Off-topic
                </p>
                <p style={{ marginTop: 4, fontSize: 20, fontWeight: 600, color: "var(--ink)" }}>
                  {formatPercent(offTopicRatio)}
                </p>
                <p style={{ fontSize: 12, color: "var(--muted)" }}>
                  proporção do tempo fora do propósito declarado.
                </p>
              </div>
            )}
            {decisionDensity !== null && (
              <div
                style={{
                  border: "1px solid var(--border)",
                  borderRadius: "var(--radius-sm)",
                  padding: 12,
                }}
              >
                <p className="nora-section-title" style={{ marginBottom: 0 }}>
                  Densidade de decisões
                </p>
                <p style={{ marginTop: 4, fontSize: 20, fontWeight: 600, color: "var(--ink)" }}>
                  {formatPercent(decisionDensity)}
                </p>
                <p style={{ fontSize: 12, color: "var(--muted)" }}>
                  quanto a conversa converge em decisões objetivas.
                </p>
              </div>
            )}
          </section>
        )}

        <section
          style={{ display: "flex", flexDirection: "column", gap: 8 }}
          aria-labelledby="rationale-heading"
        >
          <h3 id="rationale-heading" className="nora-section-title" style={{ marginBottom: 0 }}>
            Justificativa
          </h3>
          <p style={{ fontSize: 13.5, lineHeight: 1.6, color: "var(--muted)" }}>{rationale}</p>
        </section>

        <footer style={{ borderTop: "1px solid var(--border)", paddingTop: 12 }}>
          <p style={{ fontSize: 12, color: "var(--muted)" }}>
            Indicador da reunião, não dos participantes.
          </p>
        </footer>
      </div>
    </Card>
  );
}
