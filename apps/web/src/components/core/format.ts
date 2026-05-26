/**
 * Helpers puros (server + client safe) do Core: agrupamento por recência,
 * formatação de hora/duração e mapeamento de status. SEM "use client" de
 * propósito — o server component do inbox chama estas funções com um `nowMs`
 * único (fixado no servidor) pra que SSR e hidratação batam (sem mismatch de
 * timezone). O shell client passa `Date.now()` por padrão.
 */

import type { MeetingListItem, ProcessingStatus } from "@/lib/api/types";

export const GROUP_ORDER = ["Hoje", "Ontem", "Esta semana", "Mais antigas"] as const;
export type GroupKey = (typeof GROUP_ORDER)[number];

const DAY_MS = 86_400_000;

function startOfDay(ms: number): number {
  const d = new Date(ms);
  return new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
}

/** Agrupa por recência relativa ao "hoje" de `nowMs`. */
export function groupOf(iso: string, nowMs: number = Date.now()): GroupKey {
  const t = new Date(iso).getTime();
  if (Number.isNaN(t)) return "Mais antigas";
  const today = startOfDay(nowMs);
  if (t >= today) return "Hoje";
  if (t >= today - DAY_MS) return "Ontem";
  if (t >= today - 6 * DAY_MS) return "Esta semana";
  return "Mais antigas";
}

export function fmtTime(iso: string): string {
  try {
    return new Date(iso).toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" });
  } catch {
    return "";
  }
}

/** Rótulo curto de dia: "Hoje" / "Ontem" / "14 mai". */
export function dayLabel(iso: string, nowMs: number = Date.now()): string {
  const g = groupOf(iso, nowMs);
  if (g === "Hoje" || g === "Ontem") return g;
  try {
    return new Date(iso).toLocaleDateString("pt-BR", { day: "2-digit", month: "short" });
  } catch {
    return "";
  }
}

export function fmtDuration(seconds?: number): string | null {
  if (!seconds || seconds <= 0) return null;
  return `${Math.round(seconds / 60)}min`;
}

export interface StatusMeta {
  label: string;
  /** "orb" = renderiza o ShaderOrb pulsante; "dot" = bolinha de cor. */
  kind: "dot" | "orb";
  color?: string;
  /** Reuniões em processamento/fila ainda não abrem (sem análise pronta). */
  clickable: boolean;
}

export function statusMeta(status: ProcessingStatus): StatusMeta {
  switch (status) {
    case "COMPLETED":
      return { label: "Analisada", kind: "dot", color: "#62b585", clickable: true };
    case "PROCESSING":
      return { label: "Analisando…", kind: "orb", clickable: false };
    case "FAILED":
      return { label: "Falhou", kind: "dot", color: "#c97766", clickable: true };
    case "PENDING":
    default:
      return { label: "Na fila", kind: "dot", color: "#c5c2bc", clickable: false };
  }
}

export function initialsOf(name: string): string {
  const clean = name.replace(/\(.*?\)/g, "").trim();
  if (!clean) return "·";
  const parts = clean.split(/\s+/);
  if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
  return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
}

/** Cor estável (low-chroma) derivada do nome. */
export function colorOf(name: string): string {
  let h = 0;
  for (let i = 0; i < name.length; i++) h = (h * 31 + name.charCodeAt(i)) >>> 0;
  return `oklch(0.78 0.07 ${h % 360})`;
}

// ---------- Inbox view-model ----------
// Pré-formatado no servidor (com um `nowMs` único) e passado pronto pro client,
// pra que datas/horas não divirjam entre SSR e hidratação.

export interface InboxRow {
  id: string;
  title: string;
  status: ProcessingStatus;
  statusLabel: string;
  statusKind: "dot" | "orb";
  statusColor?: string;
  clickable: boolean;
  timeLabel: string;
  durationLabel: string | null;
  tag: string | null;
  actionItemCount: number;
  riskCount: number;
  opportunityCount: number;
  ownerName: string;
}

export interface InboxGroup {
  key: GroupKey;
  rows: InboxRow[];
}

function toRow(m: MeetingListItem): InboxRow {
  const s = statusMeta(m.processingStatus);
  return {
    id: m.id,
    title: m.title,
    status: m.processingStatus,
    statusLabel: s.label,
    statusKind: s.kind,
    statusColor: s.color,
    clickable: s.clickable,
    timeLabel: fmtTime(m.startedAt),
    durationLabel: fmtDuration(m.durationSeconds),
    tag: (m.tags ?? [])[0] ?? null,
    actionItemCount: m.actionItemCount ?? 0,
    riskCount: m.riskCount ?? 0,
    opportunityCount: m.opportunityCount ?? 0,
    ownerName: m.ownerName,
  };
}

/** Agrupa + ordena (mais recente primeiro) + pré-formata pro inbox. */
export function buildInbox(
  items: MeetingListItem[],
  nowMs: number,
): { groups: InboxGroup[]; todayCount: number } {
  const sorted = [...items].sort(
    (a, b) => new Date(b.startedAt).getTime() - new Date(a.startedAt).getTime(),
  );
  const buckets = new Map<GroupKey, InboxRow[]>();
  for (const m of sorted) {
    const key = groupOf(m.startedAt, nowMs);
    if (!buckets.has(key)) buckets.set(key, []);
    buckets.get(key)!.push(toRow(m));
  }
  const groups: InboxGroup[] = GROUP_ORDER.filter((k) => buckets.has(k)).map((k) => ({
    key: k,
    rows: buckets.get(k)!,
  }));
  return { groups, todayCount: buckets.get("Hoje")?.length ?? 0 };
}
