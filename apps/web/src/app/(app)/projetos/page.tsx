import Link from "next/link";
import type { Route } from "next";

import { listMeetings } from "@/lib/api/client";
import type { MeetingListItem, ProcessingStatus } from "@/lib/api/types";
import { ShaderOrb } from "@/components/brand/shader-orb";

/**
 * NORA Core — Projetos.
 *
 * Projetos agrupam reuniões e action items por TAG, sem preenchimento manual
 * (vision: "Core — Projetos"). O agrupamento é feito client-side a partir das
 * tags que já vêm em cada reunião (MeetingListItem.tags). Cada frente de
 * trabalho aparece como um card; o drill-down filtra a lista por tag via
 * ?tag= na URL (sem backend novo).
 */

export const dynamic = "force-dynamic";

const STATUS_META: Record<ProcessingStatus, { label: string; color: string }> = {
  PENDING: { label: "Na fila", color: "var(--muted)" },
  PROCESSING: { label: "Analisando…", color: "var(--accent-ink)" },
  COMPLETED: { label: "Analisada", color: "var(--success)" },
  FAILED: { label: "Falhou", color: "var(--danger)" },
};

type Project = {
  tag: string;
  name: string;
  meetings: MeetingListItem[];
  open: number;
  risks: number;
  last: string;
};

function prettyTag(tag: string): string {
  const fixes: Record<string, string> = {
    saude: "Saúde",
    logistica: "Logística",
    cafe: "Café",
    estrategia: "Estratégia",
    producao: "Produção",
  };
  return tag
    .split(/[-_\s]+/)
    .filter(Boolean)
    .map((w) => fixes[w.toLowerCase()] ?? w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

function buildProjects(items: MeetingListItem[]): Project[] {
  const byTag = new Map<string, MeetingListItem[]>();
  for (const m of items) {
    for (const tag of m.tags ?? []) {
      const key = tag.trim();
      if (!key) continue;
      let bucket = byTag.get(key);
      if (!bucket) {
        bucket = [];
        byTag.set(key, bucket);
      }
      bucket.push(m);
    }
  }

  const projects: Project[] = [];
  for (const [tag, meetings] of byTag) {
    const open = meetings.reduce((a, m) => a + (m.actionItemCount ?? 0), 0);
    const risks = meetings.reduce((a, m) => a + (m.riskCount ?? 0), 0);
    const last = meetings
      .map((m) => m.startedAt)
      .sort()
      .reverse()[0];
    projects.push({ tag, name: prettyTag(tag), meetings, open, risks, last });
  }

  return projects.sort((a, b) => (a.last < b.last ? 1 : -1));
}

function fmtDate(iso: string): string {
  try {
    return new Date(iso).toLocaleDateString("pt-BR", { day: "2-digit", month: "short", year: "numeric" });
  } catch {
    return iso;
  }
}

function Header() {
  return (
    <header style={{ marginBottom: 8 }}>
      <h1 className="h1">Projetos</h1>
      <p className="lede" style={{ marginTop: 8, maxWidth: 600 }}>
        A NORA agrupa reuniões e action items por tag — cada frente de trabalho num só lugar, sem preencher nada.
      </p>
    </header>
  );
}

function ProjectCard({ p }: { p: Project }) {
  return (
    <Link
      href={`/projetos?tag=${encodeURIComponent(p.tag)}` as Route}
      className="nora-row"
      style={{
        border: "1px solid var(--border)",
        borderRadius: 14,
        background: "var(--canvas)",
        padding: 18,
        display: "flex",
        flexDirection: "column",
        gap: 14,
        textAlign: "left",
        color: "var(--ink)",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", gap: 10 }}>
        <span style={{ fontSize: 15, fontWeight: 500, letterSpacing: "-0.012em", color: "var(--ink)" }}>{p.name}</span>
        <span className="chip">{p.tag}</span>
      </div>
      <div style={{ display: "flex", gap: 14, fontSize: 12, color: "var(--muted)" }}>
        <span>
          <strong style={{ fontWeight: 500, color: "var(--ink)" }}>{p.meetings.length}</strong>{" "}
          {p.meetings.length === 1 ? "reunião" : "reuniões"}
        </span>
        <span>
          <strong style={{ fontWeight: 500, color: "var(--ink)" }}>{p.open}</strong> abertos
        </span>
        {p.risks > 0 && (
          <span style={{ color: "var(--danger)" }}>
            <strong style={{ fontWeight: 500, color: "var(--danger)" }}>{p.risks}</strong>{" "}
            {p.risks === 1 ? "risco" : "riscos"}
          </span>
        )}
      </div>
      <div style={{ fontSize: 11.5, color: "var(--muted)", borderTop: "1px solid var(--border)", paddingTop: 12 }}>
        Última atividade {fmtDate(p.last)}
      </div>
    </Link>
  );
}

function MeetingRow({ m }: { m: MeetingListItem }) {
  const meta = STATUS_META[m.processingStatus] ?? STATUS_META.PENDING;
  return (
    <Link
      href={`/meetings/${m.id}` as Route}
      className="nora-row"
      style={{ display: "flex", alignItems: "center", gap: 14, padding: "12px 14px", borderRadius: 10, color: "var(--ink)" }}
    >
      <span className="status-dot" style={{ background: meta.color, flexShrink: 0 }} title={meta.label} />
      <span
        style={{
          flex: 1,
          minWidth: 0,
          fontSize: 14,
          fontWeight: 500,
          letterSpacing: "-0.01em",
          overflow: "hidden",
          textOverflow: "ellipsis",
          whiteSpace: "nowrap",
        }}
      >
        {m.title}
      </span>
      <span style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 12, color: "var(--muted)", flexShrink: 0 }}>
        {m.actionItemCount > 0 && <span>{m.actionItemCount} action items</span>}
        {m.riskCount > 0 && <span style={{ color: "var(--danger)" }}>{m.riskCount} riscos</span>}
        <span>{fmtDate(m.startedAt)}</span>
      </span>
    </Link>
  );
}

function ListView({ projects }: { projects: Project[] }) {
  return (
    <>
      <Header />
      <div
        style={{
          display: "grid",
          gridTemplateColumns: "repeat(auto-fill, minmax(264px, 1fr))",
          gap: 12,
          marginTop: 24,
        }}
      >
        {projects.map((p) => (
          <ProjectCard key={p.tag} p={p} />
        ))}
      </div>
      <p style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 24, lineHeight: 1.6 }}>
        Projetos vêm das tags das suas reuniões. Adicione tags no upload — ou edite depois no detalhe da reunião.
      </p>
    </>
  );
}

function DetailView({ p }: { p: Project }) {
  return (
    <>
      <div style={{ display: "flex", alignItems: "center", gap: 8, fontSize: 12, color: "var(--muted)", marginBottom: 22 }}>
        <Link href={"/projetos" as Route} style={{ color: "var(--muted)" }}>
          Projetos
        </Link>
        <span style={{ opacity: 0.5 }}>/</span>
        <span style={{ color: "var(--ink)" }}>{p.name}</span>
      </div>

      <header
        style={{
          display: "flex",
          alignItems: "flex-end",
          justifyContent: "space-between",
          gap: 20,
          marginBottom: 30,
          flexWrap: "wrap",
        }}
      >
        <div>
          <h1 className="h1" style={{ fontSize: 26 }}>
            {p.name}
          </h1>
          <div style={{ display: "flex", gap: 14, fontSize: 12.5, color: "var(--muted)", marginTop: 10, flexWrap: "wrap" }}>
            <span>
              {p.meetings.length} {p.meetings.length === 1 ? "reunião" : "reuniões"}
            </span>
            <span>{p.open} action items</span>
            {p.risks > 0 && <span style={{ color: "var(--danger)" }}>{p.risks} riscos</span>}
            <span>Última atividade {fmtDate(p.last)}</span>
          </div>
        </div>
        <span className="chip chip--lg">{p.tag}</span>
      </header>

      <section className="section">
        <div className="section-head">
          <h2 className="sec-label">Reuniões</h2>
          <span className="section-count">{p.meetings.length}</span>
        </div>
        <div style={{ display: "flex", flexDirection: "column", gap: 2 }}>
          {p.meetings.map((m) => (
            <MeetingRow key={m.id} m={m} />
          ))}
        </div>
      </section>
    </>
  );
}

function EmptyView() {
  return (
    <>
      <Header />
      <div
        style={{
          border: "1px dashed var(--border-strong)",
          borderRadius: 16,
          padding: "56px 24px",
          display: "flex",
          flexDirection: "column",
          alignItems: "center",
          gap: 20,
          textAlign: "center",
          marginTop: 24,
          background: "var(--canvas)",
        }}
      >
        <ShaderOrb size={92} speed={0.9} intensity={0.8} />
        <div style={{ maxWidth: 420 }}>
          <h2 style={{ fontFamily: "var(--display)", fontSize: 18, fontWeight: 500, letterSpacing: "-0.018em", margin: "0 0 6px", color: "var(--ink)" }}>
            Seus projetos aparecem aqui conforme você usa a NORA.
          </h2>
          <p style={{ fontSize: 13.5, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
            Envie reuniões com tags — a NORA conecta os pontos e organiza tudo por projeto.
          </p>
        </div>
        <div style={{ display: "flex", gap: 10, flexWrap: "wrap", justifyContent: "center" }}>
          <Link
            href={"/meetings/upload" as Route}
            style={{ display: "inline-flex", alignItems: "center", gap: 7, padding: "9px 16px", background: "var(--ink)", color: "var(--canvas)", borderRadius: 9, fontSize: 13, fontWeight: 500 }}
          >
            Enviar reunião
          </Link>
          <Link
            href={"/chat" as Route}
            style={{ display: "inline-flex", alignItems: "center", gap: 7, padding: "9px 16px", background: "var(--sidebar)", color: "var(--ink)", border: "1px solid var(--border)", borderRadius: 9, fontSize: 13, fontWeight: 500 }}
          >
            Conversar com a NORA
          </Link>
        </div>
      </div>
    </>
  );
}

export default async function ProjetosPage({
  searchParams,
}: {
  searchParams: Promise<{ tag?: string }>;
}) {
  const sp = await searchParams;
  const activeTag = sp.tag?.trim() || undefined;

  let items: MeetingListItem[] = [];
  let errorMessage: string | null = null;
  try {
    const data = await listMeetings({ size: 100 });
    items = data.items;
  } catch (err) {
    errorMessage = err instanceof Error ? err.message : "Falha ao carregar reuniões.";
  }

  const projects = buildProjects(items);
  const active = activeTag ? projects.find((p) => p.tag === activeTag) : undefined;

  return (
    <div className="page">
      {errorMessage && (
        <div style={{ marginBottom: 16, padding: "10px 14px", borderRadius: 9, border: "1px solid var(--border)", background: "var(--chip)", fontSize: 13, color: "var(--muted)" }}>
          Não consegui carregar os projetos agora ({errorMessage}). Verifique a conexão com a API.
        </div>
      )}

      {active ? (
        <DetailView p={active} />
      ) : projects.length > 0 ? (
        <ListView projects={projects} />
      ) : (
        <EmptyView />
      )}
    </div>
  );
}
