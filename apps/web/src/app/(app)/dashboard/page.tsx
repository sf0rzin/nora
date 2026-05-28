import Link from "next/link";
import type { Route } from "next";
import { cookies } from "next/headers";

import { listMeetings, type ListMeetingsParams } from "@/lib/api/client";
import type { MeetingListItem, ProcessingStatus } from "@/lib/api/types";
import { ShaderOrb } from "@/components/brand/shader-orb";
import DashboardFilters from "./Filters";

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

function firstName(): string {
  try {
    const raw = cookies().get("nora_user")?.value;
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
    return (
      <span style={{ width: 14, height: 14, display: "inline-block", borderRadius: "50%", overflow: "hidden" }} title={meta.label}>
        <ShaderOrb size={14} speed={1.8} intensity={1} />
      </span>
    );
  }
  return <span title={meta.label} style={{ width: 8, height: 8, borderRadius: "50%", background: meta.color, display: "inline-block" }} />;
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
        <span style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 12, color: "var(--muted)", flexWrap: "wrap" }}>
          {m.tags?.[0] && (
            <span style={{ padding: "1px 8px", borderRadius: 999, background: "var(--chip)", color: "var(--ink)", fontSize: 11 }}>{m.tags[0]}</span>
          )}
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
      <span style={{ flexShrink: 0, fontSize: 11, color: meta.color, letterSpacing: "0.02em" }}>{meta.label}</span>
    </Link>
  );
}

export default async function DashboardPage({
  searchParams,
}: {
  searchParams: { search?: string; status?: string; from?: string; to?: string };
}) {
  const filters: ListMeetingsParams = {
    search: searchParams.search?.trim() || undefined,
    status: isStatus(searchParams.status) ? searchParams.status : undefined,
    from: searchParams.from || undefined,
    to: searchParams.to || undefined,
  };

  let data;
  let errorMessage: string | null = null;
  try {
    data = await listMeetings(filters);
  } catch (err) {
    errorMessage = err instanceof Error ? err.message : "Falha ao carregar reuniões.";
    data = { items: [] as MeetingListItem[], page: 0, size: 20, totalItems: 0, totalPages: 0 };
  }

  const name = firstName();
  const hasFilters = Boolean(filters.search || filters.status || filters.from || filters.to);

  const grouped: Record<string, MeetingListItem[]> = {};
  for (const m of data.items) {
    const g = groupOf(m.startedAt);
    (grouped[g] ||= []).push(m);
  }
  const todayCount = grouped["Hoje"]?.length ?? 0;

  return (
    <div style={{ maxWidth: 920, margin: "0 auto", padding: "56px 40px 80px" }}>
      <header style={{ display: "flex", alignItems: "flex-end", justifyContent: "space-between", gap: 24, marginBottom: 28 }}>
        <div>
          <div style={{ fontSize: 13, color: "var(--muted)", marginBottom: 6 }}>
            {greeting()}
            {name ? `, ${name}.` : "."}
          </div>
          <h1 style={{ fontFamily: "var(--display)", fontSize: 30, fontWeight: 500, letterSpacing: "-0.025em", lineHeight: 1.1, color: "var(--ink)", margin: 0 }}>
            {todayCount > 0
              ? `${todayCount} ${todayCount === 1 ? "reunião" : "reuniões"} hoje.`
              : `${data.totalItems} ${data.totalItems === 1 ? "reunião" : "reuniões"} no total.`}
          </h1>
        </div>
        <Link
          href={"/meetings/upload" as Route}
          style={{ display: "inline-flex", alignItems: "center", gap: 7, padding: "9px 15px", background: "var(--ink)", color: "var(--canvas)", borderRadius: 9, fontSize: 13, fontWeight: 500, flexShrink: 0 }}
        >
          <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round"><path d="M12 5v14M5 12h14" /></svg>
          Nova reunião
        </Link>
      </header>

      <DashboardFilters defaults={filters} />

      {errorMessage && (
        <div style={{ marginTop: 16, padding: "10px 14px", borderRadius: 9, border: "1px solid var(--border)", background: "var(--chip)", fontSize: 13, color: "var(--muted)" }}>
          Não consegui carregar as reuniões agora ({errorMessage}). Verifique a conexão com a API.
        </div>
      )}

      {data.items.length === 0 && !errorMessage ? (
        <div style={{ display: "flex", flexDirection: "column", alignItems: "center", gap: 20, padding: "64px 24px", textAlign: "center" }}>
          <ShaderOrb size={110} speed={1} intensity={0.85} />
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
            <Link href={"/meetings/upload" as Route} style={{ display: "inline-flex", padding: "10px 18px", background: "var(--ink)", color: "var(--canvas)", borderRadius: 9, fontSize: 13, fontWeight: 500 }}>
              Fazer upload
            </Link>
          )}
        </div>
      ) : (
        <div style={{ display: "flex", flexDirection: "column", gap: 28, marginTop: 24 }}>
          {GROUP_ORDER.filter((g) => grouped[g]).map((g) => (
            <section key={g}>
              <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 8, padding: "0 14px" }}>
                <h3 style={{ fontSize: 11, fontWeight: 500, color: "var(--muted)", letterSpacing: "0.08em", textTransform: "uppercase", margin: 0 }}>{g}</h3>
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
    </div>
  );
}
