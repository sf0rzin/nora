/**
 * MetricsStrip — faixa editorial com a visao rapida de atividade do tenant (US33).
 * Componente puramente apresentacional (sem estado, sem fetch): recebe os contadores
 * ja resolvidos e renderiza uma linha responsiva de tiles. Usa os tokens editoriais
 * (`--ink`, `--muted`, `--surface`, `--border`, `--radius`) pra coerencia com o shell.
 */

import type { TenantMetrics } from "@/lib/api/client";

type Tile = { label: string; value: number };

function StatTile({ label, value }: Tile) {
  return (
    <div
      style={{
        flex: "1 1 140px",
        minWidth: 0,
        padding: "12px 14px",
        border: "1px solid var(--border)",
        borderRadius: "var(--radius)",
        background: "var(--surface)",
      }}
    >
      <div
        style={{
          fontSize: 22,
          fontWeight: 600,
          lineHeight: 1.1,
          color: "var(--ink)",
          fontVariantNumeric: "tabular-nums",
        }}
      >
        {value}
      </div>
      <div
        style={{
          marginTop: 4,
          fontSize: 12,
          color: "var(--muted)",
        }}
      >
        {label}
      </div>
    </div>
  );
}

export default function MetricsStrip({ metrics }: { metrics: TenantMetrics }) {
  const tiles: Tile[] = [
    { label: "Reuniões", value: metrics.totalMeetings },
    { label: "Este mês", value: metrics.meetingsThisMonth },
    { label: "Concluídas", value: metrics.completed },
    { label: "Em processamento", value: metrics.processing },
    { label: "Action items abertos", value: metrics.openActionItems },
  ];

  return (
    <div
      aria-label="Resumo de atividade do tenant"
      style={{
        display: "flex",
        flexWrap: "wrap",
        gap: 12,
        marginBottom: 28,
      }}
    >
      {tiles.map((t) => (
        <StatTile key={t.label} label={t.label} value={t.value} />
      ))}
    </div>
  );
}
