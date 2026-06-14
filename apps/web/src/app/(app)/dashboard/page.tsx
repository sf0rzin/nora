import Link from "next/link";
import type { Route } from "next";
import { cookies } from "next/headers";

import { listMeetings, type ListMeetingsParams } from "@/lib/api/client";
import type { MeetingListItem, ProcessingStatus } from "@/lib/api/types";
import DashboardFilters, { ProcessingPoller } from "./Filters";

export const dynamic = "force-dynamic";

const STATUS_VALUES = ["PENDING", "PROCESSING", "COMPLETED", "FAILED"] as const;

const STATUS_META: Record<ProcessingStatus, { label: string; color: string }> = {
  PENDING: { label: "Na fila", color: "var(--muted)" },
  PROCESSING: { label: "Analisando…", color: "var(--accent-ink)" },
  COMPLETED: { label: "Analisada", color: "var(--success)" },
  FAILED: { label: "Falhou", color: "var(--danger)" },
};

const GROUP_ORDER = ["Hoje", "Ontem", "Esta semana", "Mais antigas"] as const;

function isStatus(s: string | undefined): s is ListMeetingsParams["status"] {
  return !!s && (STATUS_VALUES as readonly string[]).includes(s);
}

async function firstName(): Promise<string> {
  try {
    const raw = (await cookies()).get("nora_user")?.value;
    if (!raw) return "";
    const u = JSON.parse(decodeURIComponent(raw)) as { displayName?: string };
    return (u.displayName ?? "").trim().split(/\s+/)[0] ?? "";
  } catch {
    return "";
  }
}

function greeting(): string {
  const h = new Date().getHours();
  if (h < 6) return "Boa madrugada";
  if (h < 12) return "Bom dia";
  if (h < 18) return "Boa tarde";
  return "Boa noite";
}

function groupOf(startedAt: string): (typeof GROUP_ORDER)[number] {
  const d = new Date(startedAt);
  if (Number.isNaN(d.getTime())) return "Mais antigas";
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const day = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const diff = Math.round((today.getTime() - day.getTime()) / 86_400_000);
  if (diff <= 0) return "Hoje";
  if (diff === 1) return "Ontem";
  if (diff <= 7) return "Esta semana";
  return "Mais antigas";
}

function timeLabel(startedAt: string): string {
  const d = new Date(startedAt);
  if (Number.isNaN(d.getTime())) return "";
  return d.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" });
}

function durationLabel(seconds?: number): string | null {
  if (!seconds || seconds <= 0) return null;
  return `${Math.round(seconds / 60)}min`;
}

function StatusDot({ status }: { status: ProcessingStatus }) {
  const meta = STATUS_META[status] ?? STATUS_META.PENDING;
  if (status === "PROCESSING") {
    return <span className="status-dot status-dot--processing" title={meta.label} />;
  }
  return <span className="status-dot" title={meta.label} style={{ background: meta.color }} />;
}

function TagChips({ tags }: { tags: string[] }) {
  if (!tags || tags.length === 0) return null;
  const shown = tags.slice(0, 3);
  const rest = tags.length - shown.length;
  return (
    <>
      {shown.map((t) => (
        <span key={t} className="chip">
          {t}
        </span>
      ))}
      {rest > 0 && (
        <span className="chip" style={{ color: "var(--muted)" }}>
          +{rest}
        </span>
      )}
    </>
  );
}

const BAND_STYLE: Record<string, { bg: string; fg: string; label: string }> = {
  HIGH: { bg: "rgba(46,125,50,0.12)", fg: "var(--success)", label: "Alta" },
  MEDIUM: { bg: "rgba(176,124,12,0.14)", fg: "var(--warn)", label: "Média" },
  LOW: { bg: "rgba(190,44,44,0.12)", fg: "var(--danger)", label: "Baixa" },
};

/** Pill de produtividade (banda + score) — só aparece quando a reunião foi avaliada. */
function ProductivityPill({ band, score }: { band?: string | null; score?: number | null }) {
  if (!band) return null;
  const s = BAND_STYLE[band] ?? BAND_STYLE.MEDIUM;
  return (
    <span
      title={`Produtividade ${s.label}${typeof score === "number" ? ` · ${score}/100` : ""}`}
      style={{
        display: "inline-flex",
        alignItems: "center",
        gap: 5,
        flexShrink: 0,
        padding: "2px 8px",
        borderRadius: 999,
        fontSize: 11,
        fontWeight: 500,
        background: s.bg,
        color: s.fg,
        fontVariantNumeric: "tabular-nums",
      }}
    >
      <span style={{ width: 6, height: 6, borderRadius: 999, background: s.fg }} />
      {typeof score === "number" ? score : s.label}
    </span>
  );
}

function initialsOf(name: string): string {
  const parts = name.trim().split(/\s+/).filter(Boolean);
  if (parts.length === 0) return "?";
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/** Stack de avatares (iniciais) dos participantes, com overflow +N. */
function AvatarStack({ names }: { names?: string[] }) {
  if (!names || names.length === 0) return null;
  const shown = names.slice(0, 3);
  const overflow = names.length - shown.length;
  const chip: React.CSSProperties = {
    width: 22,
    height: 22,
    borderRadius: 999,
    display: "grid",
    placeItems: "center",
    fontSize: 9.5,
    fontWeight: 600,
    background: "var(--chip)",
    color: "var(--muted)",
    border: "1.5px solid var(--canvas)",
    letterSpacing: "0.02em",
  };
  return (
    <span
      style={{ display: "inline-flex", alignItems: "center", flexShrink: 0 }}
      aria-label={`${names.length} participante${names.length === 1 ? "" : "s"}`}
    >
      {shown.map((n, i) => (
        <span key={i} title={n} style={{ ...chip, marginLeft: i === 0 ? 0 : -7 }}>
          {initialsOf(n)}
        </span>
      ))}
      {overflow > 0 && <span style={{ ...chip, marginLeft: -7 }}>+{overflow}</span>}
    </span>
  );
}

function MeetingRow({ m }: { m: MeetingListItem }) {
  const meta = STATUS_META[m.processingStatus] ?? STATUS_META.PENDING;
  const duration = durationLabel(m.durationSeconds);
  return (
    <Link
      href={`/meetings/${m.id}` as Route}
      className="nora-row"
      style={{ display: "flex", alignItems: "center", gap: 14, padding: "12px 14px", borderRadius: 10, color: "var(--ink)" }}
    >
      <span style={{ display: "grid", placeItems: "center", width: 16, flexShrink: 0 }}>
        <StatusDot status={m.processingStatus} />
      </span>
      <span style={{ width: 56, flexShrink: 0, fontSize: 12, color: "var(--muted)", fontVariantNumeric: "tabular-nums" }}>
        {timeLabel(m.startedAt)}
      </span>
      <span style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 3 }}>
        <span style={{ fontSize: 14.5, fontWeight: 500, letterSpacing: "-0.012em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
          {m.title}
        </span>
        <span style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12, color: "var(--muted)", flexWrap: "wrap" }}>
          <TagChips tags={m.tags} />
          {m.processingStatus === "PROCESSING" ? (
            <span style={{ color: "var(--accent-ink)" }}>{meta.label}</span>
          ) : (
            <>
              {duration && <span>{duration}</span>}
              {m.actionItemCount > 0 && <span>{m.actionItemCount} action items</span>}
              {m.riskCount > 0 && <span>{m.riskCount} riscos</span>}
              {m.opportunityCount > 0 && <span>{m.opportunityCount} oportunidades</span>}
            </>
          )}
        </span>
      </span>
      <AvatarStack names={m.participants} />
      <ProductivityPill band={m.productivityBand} score={m.productivityScore} />
      <span style={{ flexShrink: 0, fontSize: 11, color: meta.color, letterSpacing: "0.02em" }}>{meta.label}</span>
    </Link>
  );
}

/** Constrói a query string preservando filtros e definindo a página alvo. */
function pageHref(filters: ListMeetingsParams, page: number): Route {
  const qs = new URLSearchParams();
  if (filters.search) qs.set("search", filters.search);
  if (filters.status) qs.set("status", filters.status);
  if (filters.from) qs.set("from", filters.from);
  if (filters.to) qs.set("to", filters.to);
  if (page > 0) qs.set("page", String(page));
  const q = qs.toString();
  return (q ? `/dashboard?${q}` : "/dashboard") as Route;
}

export default async function DashboardPage({
  searchParams,
}: {
  searchParams: Promise<{ search?: string; status?: string; from?: string; to?: string; page?: string }>;
}) {
  const sp = await searchParams;
  const pageParam = Number.parseInt(sp.page ?? "", 10);
  const page = Number.isFinite(pageParam) && pageParam > 0 ? pageParam : 0;

  const filters: ListMeetingsParams = {
    search: sp.search?.trim() || undefined,
    status: isStatus(sp.status) ? sp.status : undefined,
    from: sp.from || undefined,
    to: sp.to || undefined,
    page,
  };

  let data;
  let errorMessage: string | null = null;
  try {
    data = await listMeetings(filters);
  } catch (err) {
    errorMessage = err instanceof Error ? err.message : "Falha ao carregar reuniões.";
    data = { items: [] as MeetingListItem[], page: 0, size: 20, totalItems: 0, totalPages: 0 };
  }

  const name = await firstName();
  const hasFilters = Boolean(filters.search || filters.status || filters.from || filters.to);

  const grouped: Record<string, MeetingListItem[]> = {};
  for (const m of data.items) {
    const g = groupOf(m.startedAt);
    (grouped[g] ||= []).push(m);
  }
  const todayCount = grouped["Hoje"]?.length ?? 0;

  const hasProcessing = data.items.some((m) => m.processingStatus === "PROCESSING" || m.processingStatus === "PENDING");

  const currentPage = data.page ?? page;
  const totalPages = data.totalPages ?? 0;
  const hasPrev = currentPage > 0;
  const hasNext = totalPages > 0 ? currentPage < totalPages - 1 : false;
  const showPagination = data.items.length > 0 && (hasPrev || hasNext);
  const rangeStart = data.totalItems > 0 ? currentPage * (data.size || 20) + 1 : 0;
  const rangeEnd = currentPage * (data.size || 20) + data.items.length;

  return (
    <div className="page">
      <ProcessingPoller active={hasProcessing} />

      <header style={{ display: "flex", alignItems: "flex-end", justifyContent: "space-between", gap: 24, marginBottom: 28 }}>
        <div>
          <div className="eyebrow">
            {greeting()}
            {name ? `, ${name}.` : "."}
          </div>
          <h1 className="h1">
            {todayCount > 0
              ? `${todayCount} ${todayCount === 1 ? "reunião" : "reuniões"} hoje.`
              : `${data.totalItems} ${data.totalItems === 1 ? "reunião" : "reuniões"} no total.`}
          </h1>
        </div>
        <Link href={"/meetings/upload" as Route} className="btn btn-primary" style={{ flexShrink: 0 }}>
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
          Nova reunião
        </Link>
      </header>

      {(data.items.length > 0 || hasFilters) && <DashboardFilters defaults={filters} />}

      {errorMessage && (
        <div className="notice" style={{ marginTop: 16 }}>
          Não consegui carregar as reuniões agora ({errorMessage}). Verifique a conexão com a API.
        </div>
      )}

      {data.items.length === 0 && !errorMessage ? (
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 20, padding: "72px 24px", textAlign: "center" }}>
          <div style={{ maxWidth: 400 }}>
            <h2 style={{ fontFamily: "var(--display)", fontSize: 19, fontWeight: 500, letterSpacing: "-0.018em", margin: "0 0 6px", color: "var(--ink)" }}>
              {hasFilters ? "Nenhuma reunião com esses filtros." : "Sua primeira reunião está a um upload de distância."}
            </h2>
            <p style={{ fontSize: 13.5, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
              {hasFilters
                ? "Ajuste a busca ou limpe os filtros."
                : "A NORA processa a transcrição e devolve resumo, decisões e action items em segundos."}
            </p>
          </div>
          {!hasFilters && (
            <Link href={"/meetings/upload" as Route} className="btn btn-primary">
              Fazer upload
            </Link>
          )}
        </div>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 28, marginTop: 24 }}>
          {GROUP_ORDER.filter((g) => grouped[g]).map((g) => (
            <section key={g}>
              <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 8, padding: "0 14px" }}>
                <h3 className="sec-label">{g}</h3>
                <div style={{ flex: 1, height: 1, background: "var(--border)" }} />
                <span style={{ fontSize: 11, color: "var(--muted)" }}>{grouped[g].length}</span>
              </div>
              <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
                {grouped[g].map((m) => (
                  <MeetingRow key={m.id} m={m} />
                ))}
              </div>
            </section>
          ))}
        </div>
      )}

      {showPagination && (
        <nav
          aria-label="Paginação"
          style={{ marginTop: 28, display: "flex", alignItems: "center", justifyContent: "space-between", gap: 12 }}
        >
          <span style={{ fontSize: 12, color: "var(--muted)" }}>
            {rangeStart}–{rangeEnd} de {data.totalItems} reuniões · página {currentPage + 1} de {Math.max(totalPages, 1)}
          </span>
          <div style={{ display: "flex", gap: 6 }}>
            {hasPrev ? (
              <Link href={pageHref(filters, currentPage - 1)} className="btn btn-ghost btn-sm">
                Anterior
              </Link>
            ) : (
              <button className="btn btn-ghost btn-sm" type="button" disabled>
                Anterior
              </button>
            )}
            {hasNext ? (
              <Link href={pageHref(filters, currentPage + 1)} className="btn btn-ghost btn-sm">
                Próxima
              </Link>
            ) : (
              <button className="btn btn-ghost btn-sm" type="button" disabled>
                Próxima
              </button>
            )}
          </div>
        </nav>
      )}

      <div
        style={{
          marginTop: 48,
          paddingTop: 18,
          borderTop: "1px solid var(--border)",
          display: "flex",
          alignItems: "center",
          gap: 16,
          fontSize: 11.5,
          color: "var(--muted)",
          flexWrap: "wrap",
        }}
      >
        <span>Atalhos:</span>
        <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
          <kbd className="kbd">N</kbd> nova reunião
        </span>
        <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
          <kbd className="kbd">/</kbd> buscar
        </span>
        <span style={{ display: "inline-flex", alignItems: "center", gap: 6 }}>
          <kbd className="kbd">⌘K</kbd> comandos
        </span>
      </div>
    </div>
  );
}
