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

const BAND_CLASSES: Record<ProductivityBand, string> = {
  LOW: "bg-rose-100 text-rose-700",
  MEDIUM: "bg-amber-100 text-amber-700",
  HIGH: "bg-emerald-100 text-emerald-700",
};

const STATUS_LABEL: Record<CoverageStatus, string> = {
  ADDRESSED: "Tratado",
  PARTIAL: "Parcial",
  MISSED: "Não tratado",
};

const STATUS_TEXT_CLASS: Record<CoverageStatus, string> = {
  ADDRESSED: "text-emerald-700",
  PARTIAL: "text-amber-700",
  MISSED: "text-rose-700",
};

function StatusIcon({ status }: { status: CoverageStatus }) {
  const common =
    "mt-0.5 inline-flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-xs font-semibold";
  if (status === "ADDRESSED") {
    return (
      <span
        aria-hidden="true"
        className={`${common} bg-emerald-100 text-emerald-700`}
      >
        ✓
      </span>
    );
  }
  if (status === "PARTIAL") {
    return (
      <span
        aria-hidden="true"
        className={`${common} bg-amber-100 text-amber-700`}
      >
        ~
      </span>
    );
  }
  return (
    <span aria-hidden="true" className={`${common} bg-rose-100 text-rose-700`}>
      ✗
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
    <article
      className="space-y-6 rounded-lg border border-slate-200 bg-white p-6"
      aria-label="Avaliação de produtividade da reunião"
    >
      <header className="flex flex-wrap items-baseline gap-4">
        <div className="flex items-baseline gap-3">
          <span
            className="text-6xl font-semibold tracking-tight text-slate-900"
            aria-label={`Score ${score} de 100`}
          >
            {score}
          </span>
          <span className="text-base text-slate-500">/ 100</span>
        </div>
        <span
          className={`inline-flex items-center rounded-full px-3 py-1 text-xs font-semibold uppercase tracking-wide ${BAND_CLASSES[band]}`}
        >
          Produtividade {BAND_LABEL[band]}
        </span>
      </header>

      <section className="space-y-3" aria-labelledby="coverage-heading">
        <h3
          id="coverage-heading"
          className="text-sm font-semibold uppercase tracking-wide text-slate-500"
        >
          Cobertura dos outcomes
        </h3>
        {coverage.length === 0 ? (
          <p className="text-sm text-slate-500">
            Nenhum outcome avaliado nesta análise.
          </p>
        ) : (
          <ul className="space-y-3">
            {coverage.map((item, idx) => (
              <li
                key={`${item.expectedOutcome}-${idx}`}
                className="flex items-start gap-3 rounded-md border border-slate-200 p-3"
              >
                <StatusIcon status={item.status} />
                <div className="space-y-1">
                  <p className="font-medium text-slate-900">
                    {item.expectedOutcome}
                  </p>
                  <p
                    className={`text-xs font-medium uppercase tracking-wide ${STATUS_TEXT_CLASS[item.status]}`}
                  >
                    {STATUS_LABEL[item.status]}
                  </p>
                  {item.evidence && (
                    <blockquote className="border-l-2 border-slate-300 pl-3 text-xs italic text-slate-600">
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
          className="grid gap-3 sm:grid-cols-2"
          aria-label="Métricas auxiliares"
        >
          {offTopicRatio !== null && (
            <div className="rounded-md border border-slate-200 p-3">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                Off-topic
              </p>
              <p className="mt-1 text-xl font-semibold text-slate-900">
                {formatPercent(offTopicRatio)}
              </p>
              <p className="text-xs text-slate-500">
                proporção do tempo fora do propósito declarado.
              </p>
            </div>
          )}
          {decisionDensity !== null && (
            <div className="rounded-md border border-slate-200 p-3">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                Densidade de decisões
              </p>
              <p className="mt-1 text-xl font-semibold text-slate-900">
                {formatPercent(decisionDensity)}
              </p>
              <p className="text-xs text-slate-500">
                quanto a conversa converge em decisões objetivas.
              </p>
            </div>
          )}
        </section>
      )}

      <section className="space-y-2" aria-labelledby="rationale-heading">
        <h3
          id="rationale-heading"
          className="text-sm font-semibold uppercase tracking-wide text-slate-500"
        >
          Justificativa
        </h3>
        <p className="text-sm leading-relaxed text-slate-600">{rationale}</p>
      </section>

      <footer className="border-t border-slate-200 pt-3">
        <p className="text-xs text-slate-500">
          Indicador da reunião, não dos participantes.
        </p>
      </footer>
    </article>
  );
}
