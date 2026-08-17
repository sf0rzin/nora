import type { Route } from "next";
import Link from "next/link";

import { getTrends, type TrendsGranularity } from "@/lib/api/client";
import type { TrendsResponse, TrendsTheme, TrendsThemeBucket } from "@/lib/api/types";
import {
  barHeightPercent,
  bucketLabel,
  bucketRange,
  seriesMax,
  seriesTotals,
} from "@/lib/trends/format";
import TrendsFilters from "./Filters";

export const dynamic = "force-dynamic";

/**
 * Trends panel (US21). Two halves: task load over time, from SQL aggregation over
 * `meeting_action_items`, and the themes that keep coming back, from the `topics` each analysis
 * already persisted.
 *
 * The screen never infers emptiness from the numbers. `dataState` and `themes.sufficient` come
 * from the API precisely so that "there is nothing to show" and "this period had no activity"
 * render as two different sentences — a chart of zeros that actually means "no analysed meeting"
 * is a lie the reader has no way to catch.
 */

const PRIORITY_LABEL: Record<string, string> = {
  HIGH: "Alta",
  MEDIUM: "Média",
  LOW: "Baixa",
};
const PRIORITY_COLOR: Record<string, string> = {
  HIGH: "var(--danger)",
  MEDIUM: "var(--warn)",
  LOW: "var(--muted)",
};

function isGranularity(value: string | undefined): value is TrendsGranularity {
  return value === "WEEK" || value === "MONTH";
}

function StatTile({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="card" style={{ flex: "1 1 150px", minWidth: 140 }}>
      <div className="sec-label">{label}</div>
      <div
        style={{
          fontFamily: "var(--display)",
          fontSize: 26,
          fontWeight: 500,
          letterSpacing: "-0.02em",
          marginTop: 6,
          fontVariantNumeric: "tabular-nums",
        }}
      >
        {value}
      </div>
      {hint && <div style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 2 }}>{hint}</div>}
    </div>
  );
}

/** Legend swatch — the two series of the load chart are told apart by colour alone otherwise. */
function Swatch({ color, label }: { color: string; label: string }) {
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 6, fontSize: 11.5, color: "var(--muted)" }}>
      <span style={{ width: 9, height: 9, borderRadius: 2, background: color }} />
      {label}
    </span>
  );
}

/**
 * Opened vs completed per bucket. Plain flex columns rather than a charting library: the repo has
 * none and this needs two bars per period, not a plotting engine.
 */
function LoadChart({ data }: { data: TrendsResponse }) {
  const points = data.taskLoad.buckets;
  const max = seriesMax(points);
  return (
    <div>
      <div style={{ display: "flex", gap: 14, marginBottom: 10 }}>
        <Swatch color="var(--accent-ink)" label="Abertas" />
        <Swatch color="var(--success)" label="Concluídas" />
      </div>
      <div
        role="img"
        aria-label={`Tarefas abertas e concluídas por ${data.granularity === "MONTH" ? "mês" : "semana"}`}
        style={{ display: "flex", alignItems: "flex-end", gap: 6, height: 160, paddingTop: 4 }}
      >
        {points.map((p) => {
          const range = bucketRange(p.bucketStart, data.granularity);
          return (
            <div
              key={p.bucketStart}
              title={`${range.start} a ${range.end} · ${p.opened} abertas · ${p.completed} concluídas`}
              style={{ flex: 1, display: "flex", flexDirection: "column", alignItems: "center", gap: 6, height: "100%" }}
            >
              <div style={{ flex: 1, width: "100%", display: "flex", alignItems: "flex-end", justifyContent: "center", gap: 3 }}>
                <span
                  style={{
                    width: "42%",
                    maxWidth: 18,
                    height: `${barHeightPercent(p.opened, max)}%`,
                    background: "var(--accent-ink)",
                    borderRadius: "3px 3px 0 0",
                  }}
                />
                <span
                  style={{
                    width: "42%",
                    maxWidth: 18,
                    height: `${barHeightPercent(p.completed, max)}%`,
                    background: "var(--success)",
                    borderRadius: "3px 3px 0 0",
                  }}
                />
              </div>
            </div>
          );
        })}
      </div>
      <div style={{ display: "flex", gap: 6, marginTop: 6 }}>
        {points.map((p) => (
          <div
            key={p.bucketStart}
            style={{ flex: 1, textAlign: "center", fontSize: 10, color: "var(--muted)", whiteSpace: "nowrap", overflow: "hidden" }}
          >
            {bucketLabel(p.bucketStart, data.granularity)}
          </div>
        ))}
      </div>
    </div>
  );
}

/** Horizontal ranking. `meetings` is a count of distinct meetings, not of mentions. */
function ThemeRanking({ themes }: { themes: TrendsTheme[] }) {
  const top = themes[0]?.meetings ?? 1;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
      {themes.map((t) => (
        <div key={t.label} style={{ display: "flex", alignItems: "center", gap: 10 }}>
          <span
            style={{ flex: "0 0 42%", fontSize: 13, overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}
            title={t.label}
          >
            {t.label}
          </span>
          <span style={{ flex: 1, height: 8, background: "var(--chip)", borderRadius: 999, overflow: "hidden" }}>
            <span
              style={{
                display: "block",
                height: "100%",
                width: `${Math.max(4, Math.round((t.meetings / Math.max(top, 1)) * 100))}%`,
                background: "var(--accent)",
                borderRadius: 999,
              }}
            />
          </span>
          <span style={{ flex: "0 0 92px", fontSize: 11.5, color: "var(--muted)", fontVariantNumeric: "tabular-nums" }}>
            {t.meetings} {t.meetings === 1 ? "reunião" : "reuniões"}
          </span>
        </div>
      ))}
    </div>
  );
}

/** Per-bucket top themes. A bucket the API marked insufficient says so instead of showing a list. */
function ThemeTimeline({ buckets, granularity }: { buckets: TrendsThemeBucket[]; granularity: TrendsGranularity }) {
  const withContent = buckets.filter((b) => b.meetings > 0);
  if (withContent.length === 0) return null;
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 2, marginTop: 20 }}>
      {withContent.map((b) => (
        <div
          key={b.bucketStart}
          style={{ display: "flex", alignItems: "baseline", gap: 12, padding: "8px 0", borderTop: "1px solid var(--border)" }}
        >
          <span style={{ flex: "0 0 62px", fontSize: 11.5, color: "var(--muted)", fontVariantNumeric: "tabular-nums" }}>
            {bucketLabel(b.bucketStart, granularity)}
          </span>
          <span style={{ flex: 1, display: "flex", flexWrap: "wrap", gap: 6, fontSize: 12.5 }}>
            {b.sufficient ? (
              b.items.map((t) => (
                <span key={t.label} className="chip" title={`${t.meetings} reuniões`}>
                  {t.label}
                </span>
              ))
            ) : (
              <span style={{ color: "var(--muted)" }}>
                {b.meetings} {b.meetings === 1 ? "reunião analisada" : "reuniões analisadas"} — dado insuficiente
              </span>
            )}
          </span>
        </div>
      ))}
    </div>
  );
}

function NotEnoughData({
  title,
  body,
  ctaHref,
  ctaLabel,
}: {
  title: string;
  body: string;
  ctaHref: Route;
  ctaLabel: string;
}) {
  return (
    <div
      style={{
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 14,
        padding: "64px 24px",
        textAlign: "center",
      }}
    >
      <div style={{ maxWidth: 440 }}>
        <h2
          style={{
            fontFamily: "var(--display)",
            fontSize: 19,
            fontWeight: 500,
            letterSpacing: "-0.018em",
            margin: "0 0 6px",
            color: "var(--ink)",
          }}
        >
          {title}
        </h2>
        <p style={{ fontSize: 13.5, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>{body}</p>
      </div>
      <Link href={ctaHref} className="btn btn-primary">
        {ctaLabel}
      </Link>
    </div>
  );
}

export default async function TrendsPage({
  searchParams,
}: {
  searchParams: Promise<{ granularity?: string; from?: string; to?: string }>;
}) {
  const sp = await searchParams;
  const granularity: TrendsGranularity = isGranularity(sp.granularity) ? sp.granularity : "WEEK";

  let data: TrendsResponse | null = null;
  let errorMessage: string | null = null;
  try {
    data = await getTrends({ granularity, from: sp.from || undefined, to: sp.to || undefined });
  } catch (err) {
    errorMessage = err instanceof Error ? err.message : "Falha ao carregar tendências.";
  }

  const totals = data ? seriesTotals(data.taskLoad.buckets) : { opened: 0, completed: 0 };
  const unitLabel = (data?.granularity ?? granularity) === "MONTH" ? "mês" : "semana";

  return (
    <div className="page">
      <header style={{ display: "flex", alignItems: "flex-end", justifyContent: "space-between", gap: 24, marginBottom: 24 }}>
        <div>
          <div className="eyebrow">Tendências</div>
          <h1 className="h1">O que se repete, e quanto trabalho gera.</h1>
        </div>
      </header>

      <TrendsFilters granularity={granularity} from={sp.from} to={sp.to} />

      {errorMessage && (
        <div className="notice" style={{ marginTop: 16 }}>
          Não consegui carregar as tendências agora ({errorMessage}). Verifique a conexão com a API.
        </div>
      )}

      {data && data.dataState === "NO_MEETINGS" && (
        <NotEnoughData
          title="Sem dados suficientes."
          body="Nenhuma reunião sua no período selecionado. Ajuste o período ou envie uma transcrição — a partir da primeira análise este painel começa a mostrar carga de tarefas e temas recorrentes."
          ctaHref={"/meetings/upload" as Route}
          ctaLabel="Fazer upload"
        />
      )}

      {data && data.dataState === "NO_ANALYSED_MEETINGS" && (
        <NotEnoughData
          title="Sem dados suficientes."
          body={`Há ${data.meetings} ${data.meetings === 1 ? "reunião" : "reuniões"} no período, mas nenhuma foi analisada ainda. Este painel lê o resultado da análise, então ele fica vazio até a primeira terminar — isto não significa que não houve atividade.`}
          ctaHref={"/dashboard" as Route}
          ctaLabel="Ver reuniões"
        />
      )}

      {data && data.dataState === "OK" && (
        <>
          <section style={{ marginTop: 24 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 10 }}>
              <h3 className="sec-label">Carga de tarefas</h3>
              <div style={{ flex: 1, height: 1, background: "var(--border)" }} />
            </div>

            <div style={{ display: "flex", flexWrap: "wrap", gap: 10, marginBottom: 20 }}>
              <StatTile label="Abertas no período" value={String(totals.opened)} hint={`por ${unitLabel}, no recorte atual`} />
              <StatTile label="Concluídas no período" value={String(totals.completed)} hint={`por ${unitLabel}, no recorte atual`} />
              <StatTile label="Em aberto hoje" value={String(data.taskLoad.open)} hint="foto de agora, fora do recorte" />
              <StatTile label="Vencidas hoje" value={String(data.taskLoad.overdue)} hint="prazo no passado e não concluídas" />
            </div>

            {data.taskLoad.hasData ? (
              <>
                <LoadChart data={data} />
                <div style={{ display: "flex", flexWrap: "wrap", gap: 10, marginTop: 20 }}>
                  {(["HIGH", "MEDIUM", "LOW"] as const).map((p) => (
                    <span
                      key={p}
                      className="chip chip--lg"
                      style={{ display: "inline-flex", alignItems: "center", gap: 6 }}
                    >
                      <span style={{ width: 7, height: 7, borderRadius: 999, background: PRIORITY_COLOR[p] }} />
                      {PRIORITY_LABEL[p]}: {data.taskLoad.byPriority[p] ?? 0} em aberto
                    </span>
                  ))}
                </div>
              </>
            ) : (
              <p style={{ fontSize: 13, color: "var(--muted)", margin: 0 }}>
                As reuniões do período foram analisadas, mas nenhuma gerou action item. Não é ausência de dado: é
                ausência de tarefa.
              </p>
            )}
          </section>

          <section style={{ marginTop: 40 }}>
            <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 12 }}>
              <h3 className="sec-label">Temas recorrentes</h3>
              <div style={{ flex: 1, height: 1, background: "var(--border)" }} />
              <span style={{ fontSize: 11, color: "var(--muted)" }}>
                {data.analysedMeetings} de {data.meetings} analisadas
              </span>
            </div>

            {data.themes.sufficient && data.themes.overall.length > 0 ? (
              <>
                <ThemeRanking themes={data.themes.overall} />
                <ThemeTimeline buckets={data.themes.buckets} granularity={data.granularity} />
              </>
            ) : (
              <p style={{ fontSize: 13, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
                Sem dados suficientes para um ranking de temas. São necessárias ao menos{" "}
                {data.themes.minimumMeetings} reuniões analisadas no período e há {data.analysedMeetings}. Um
                ranking sobre poucas reuniões é ruído com cara de sinal, então preferimos não desenhá-lo.
              </p>
            )}
          </section>

          <footer
            style={{
              marginTop: 44,
              paddingTop: 16,
              borderTop: "1px solid var(--border)",
              fontSize: 11.5,
              color: "var(--muted)",
              lineHeight: 1.6,
            }}
          >
            <p style={{ margin: 0 }}>
              Semanas e meses são fechados no fuso {data.timezone}, e não em UTC — uma reunião de sexta à noite
              conta na semana em que ela aconteceu.
            </p>
            <p style={{ margin: "6px 0 0" }}>
              Os temas vêm dos tópicos extraídos pela própria análise de cada reunião. A comparação é literal:
              grafias diferentes da mesma palavra são unificadas, mas sinônimos como preço e precificação contam
              separado. O painel não depende do índice de busca semântica.
            </p>
          </footer>
        </>
      )}
    </div>
  );
}
