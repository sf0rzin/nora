import type {
  ConfidenceBand,
  ConfidenceTrend,
  CustomerConfidence,
  Severity,
} from "@/lib/api/types";

// Band → linguagem de cor danger/warning/success, espelhando o Health Score
// da landing (--danger / --warn / --success) traduzida pra paleta Tailwind
// do app (rose / amber / emerald), igual ao ProductivityScoreCard.
const BAND_LABEL: Record<ConfidenceBand, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

const BAND_CLASSES: Record<ConfidenceBand, string> = {
  LOW: "bg-rose-100 text-rose-700",
  MEDIUM: "bg-amber-100 text-amber-700",
  HIGH: "bg-emerald-100 text-emerald-700",
};

const SCORE_TEXT_CLASS: Record<ConfidenceBand, string> = {
  LOW: "text-rose-600",
  MEDIUM: "text-amber-600",
  HIGH: "text-emerald-600",
};

const TREND_META: Record<
  ConfidenceTrend,
  { glyph: string; label: string; className: string }
> = {
  IMPROVING: {
    glyph: "↑",
    label: "Confiança em melhora",
    className: "bg-emerald-100 text-emerald-700",
  },
  STABLE: {
    glyph: "→",
    label: "Confiança estável",
    className: "bg-slate-100 text-slate-600",
  },
  DECLINING: {
    glyph: "↓",
    label: "Confiança em queda",
    className: "bg-rose-100 text-rose-700",
  },
};

const SEVERITY_LABEL: Record<Severity, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

const SEVERITY_TEXT_CLASS: Record<Severity, string> = {
  LOW: "text-amber-700",
  MEDIUM: "text-orange-700",
  HIGH: "text-rose-700",
};

function TrendBadge({ trend }: { trend: ConfidenceTrend }) {
  const meta = TREND_META[trend];
  return (
    <span
      className={`inline-flex items-center gap-1.5 rounded-full px-3 py-1 text-xs font-semibold ${meta.className}`}
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
    <article
      className="space-y-6 rounded-lg border border-slate-200 bg-white p-6"
      aria-label="Sinal de confiança do cliente nesta reunião"
    >
      <header className="flex flex-wrap items-start justify-between gap-4">
        <div className="space-y-1">
          <p className="text-xs font-semibold uppercase tracking-wide text-slate-500">
            Confiança do cliente
          </p>
          <p className="text-lg font-semibold text-slate-900">
            {accountName ?? "Conta não identificada"}
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-3">
          <div className="flex items-baseline gap-2">
            <span
              className={`text-5xl font-semibold tracking-tight ${SCORE_TEXT_CLASS[band]}`}
              aria-label={`Score de confiança ${score} de 100`}
            >
              {score}
            </span>
            <span className="text-sm text-slate-500">/ 100</span>
          </div>
          <span
            className={`inline-flex items-center rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wide ${BAND_CLASSES[band]}`}
          >
            Confiança {BAND_LABEL[band]}
          </span>
          {trend && <TrendBadge trend={trend} />}
        </div>
      </header>

      <section className="space-y-3" aria-labelledby="buying-signals-heading">
        <h3
          id="buying-signals-heading"
          className="text-sm font-semibold uppercase tracking-wide text-slate-500"
        >
          Sinais de compra ({buyingSignals.length})
        </h3>
        {buyingSignals.length === 0 ? (
          <p className="text-sm text-slate-500">
            Nenhum sinal de compra detectado.
          </p>
        ) : (
          <ul className="space-y-3">
            {buyingSignals.map((signal, idx) => (
              <li
                key={`${signal.type}-${idx}`}
                className="flex items-start gap-3 rounded-md border border-slate-200 p-3"
              >
                <span
                  aria-hidden="true"
                  className="mt-0.5 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-emerald-100 text-xs font-semibold text-emerald-700"
                >
                  ↑
                </span>
                <div className="space-y-1">
                  <p className="font-medium text-slate-900">{signal.type}</p>
                  <blockquote className="border-l-2 border-slate-300 pl-3 text-xs italic text-slate-600">
                    “{signal.quote}”
                  </blockquote>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="space-y-3" aria-labelledby="objections-heading">
        <h3
          id="objections-heading"
          className="text-sm font-semibold uppercase tracking-wide text-slate-500"
        >
          Objeções ({objections.length})
        </h3>
        {objections.length === 0 ? (
          <p className="text-sm text-slate-500">Nenhuma objeção registrada.</p>
        ) : (
          <ul className="space-y-3">
            {objections.map((objection, idx) => (
              <li
                key={`${objection.type}-${idx}`}
                className="flex items-start gap-3 rounded-md border border-slate-200 p-3"
              >
                <span
                  aria-hidden="true"
                  className="mt-0.5 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-rose-100 text-xs font-semibold text-rose-700"
                >
                  !
                </span>
                <div className="space-y-1">
                  <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                    <p className="font-medium text-slate-900">
                      {objection.type}
                    </p>
                    <span
                      className={`text-xs font-medium uppercase tracking-wide ${SEVERITY_TEXT_CLASS[objection.severity]}`}
                    >
                      Severidade {SEVERITY_LABEL[objection.severity]}
                    </span>
                    {objection.competitor && (
                      <span className="inline-flex items-center rounded-full bg-slate-100 px-2 py-0.5 text-xs font-medium text-slate-600">
                        Concorrente: {objection.competitor}
                      </span>
                    )}
                  </div>
                  <blockquote className="border-l-2 border-slate-300 pl-3 text-xs italic text-slate-600">
                    “{objection.quote}”
                  </blockquote>
                </div>
              </li>
            ))}
          </ul>
        )}
      </section>

      <section className="space-y-2" aria-labelledby="confidence-rationale-heading">
        <h3
          id="confidence-rationale-heading"
          className="text-sm font-semibold uppercase tracking-wide text-slate-500"
        >
          Justificativa
        </h3>
        <p className="text-sm leading-relaxed text-slate-600">{rationale}</p>
      </section>

      <footer className="border-t border-slate-200 pt-3">
        <p className="text-xs text-slate-500">
          Sinal de confiança por reunião — não usar isoladamente para avaliar o
          vendedor.
        </p>
      </footer>
    </article>
  );
}
