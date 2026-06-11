import type {
  CoverageStatus,
  ProductivityAssessment,
  ProductivityBand,
} from "@/lib/api/types";

const BAND_LABEL: Record<ProductivityBand, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

// Banda → cor de sinal (success/warn/danger), espelhando o Health Score.
const BAND_COLOR: Record<ProductivityBand, string> = {
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

const STATUS_GLYPH: Record<CoverageStatus, string> = {
  ADDRESSED: "✓",
  PARTIAL: "~",
  MISSED: "✗",
};

function softBg(color: string): string {
  return `color-mix(in oklch, ${color} 16%, var(--canvas))`;
}

function CoverIcon({ status }: { status: CoverageStatus }) {
  const color = STATUS_COLOR[status];
  return (
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
        background: softBg(color),
        color,
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
  const { score, band, coverage, offTopicRatio, decisionDensity, rationale } =
    assessment;
  const bandColor = BAND_COLOR[band];

  return (
    <article className="card card--pad" aria-label="Avaliação de produtividade da reunião">
      <div style={{ display: "flex", alignItems: "baseline", gap: 14, flexWrap: "wrap" }}>
        <div style={{ display: "flex", alignItems: "baseline", gap: 8 }}>
          <span
            style={{ fontSize: 56, fontWeight: 500, letterSpacing: "-0.03em", lineHeight: 1, color: "var(--ink)" }}
            aria-label={`Score ${score} de 100`}
          >
            {score}
          </span>
          <span style={{ fontSize: 14, color: "var(--muted)" }}>/ 100</span>
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
          Produtividade {BAND_LABEL[band]}
        </span>
      </div>

      <div style={{ marginTop: 20 }}>
        <div className="sec-label" style={{ marginBottom: 8 }}>
          Cobertura dos outcomes
        </div>
        {coverage.length === 0 ? (
          <p style={{ fontSize: 13, color: "var(--muted)", margin: 0 }}>
            Nenhum outcome avaliado nesta análise.
          </p>
        ) : (
          coverage.map((item, idx) => {
            const color = STATUS_COLOR[item.status];
            return (
              <div
                key={`${item.expectedOutcome}-${idx}`}
                style={{
                  display: "flex",
                  alignItems: "flex-start",
                  gap: 12,
                  padding: "12px 0",
                  borderTop: idx === 0 ? "none" : "1px solid var(--border)",
                }}
              >
                <CoverIcon status={item.status} />
                <div>
                  <div style={{ fontSize: 13.5, fontWeight: 500, color: "var(--ink)" }}>{item.expectedOutcome}</div>
                  <div style={{ fontSize: 11, color, letterSpacing: "0.05em", textTransform: "uppercase", marginTop: 2 }}>
                    {STATUS_LABEL[item.status]}
                  </div>
                  {item.evidence && (
                    <div className="quote" style={{ marginTop: 6 }}>
                      {item.evidence}
                    </div>
                  )}
                </div>
              </div>
            );
          })
        )}
      </div>

      {(offTopicRatio !== null || decisionDensity !== null) && (
        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 10, marginTop: 16 }}>
          {offTopicRatio !== null && (
            <div style={{ border: "1px solid var(--border)", borderRadius: 10, padding: "12px 14px" }}>
              <div className="sec-label">Off-topic</div>
              <div style={{ fontSize: 20, fontWeight: 500, marginTop: 4, color: "var(--ink)" }}>{formatPercent(offTopicRatio)}</div>
              <div style={{ fontSize: 11.5, color: "var(--muted)", lineHeight: 1.4 }}>do tempo fora do propósito declarado</div>
            </div>
          )}
          {decisionDensity !== null && (
            <div style={{ border: "1px solid var(--border)", borderRadius: 10, padding: "12px 14px" }}>
              <div className="sec-label">Densidade de decisões</div>
              <div style={{ fontSize: 20, fontWeight: 500, marginTop: 4, color: "var(--ink)" }}>{formatPercent(decisionDensity)}</div>
              <div style={{ fontSize: 11.5, color: "var(--muted)", lineHeight: 1.4 }}>da conversa converge em decisões objetivas</div>
            </div>
          )}
        </div>
      )}

      <div style={{ marginTop: 16, paddingTop: 14, borderTop: "1px solid var(--border)" }}>
        <p style={{ fontSize: 13, color: "var(--muted)", lineHeight: 1.6, margin: 0 }}>{rationale}</p>
        <p style={{ fontSize: 11.5, color: "var(--muted)", margin: "10px 0 0" }}>Indicador da reunião, não dos participantes.</p>
      </div>
    </article>
  );
}
