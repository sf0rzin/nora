"use client";

import { useEffect, useMemo, useState } from "react";
import Link from "next/link";
import type { Route } from "next";
import {
  listTasks,
  updateTask,
  type TaskListItemDto,
  type TaskStatus,
  ApiRequestError,
} from "@/lib/api/client";

const FILTERS: { value: TaskStatus | "ALL"; label: string }[] = [
  { value: "ALL", label: "Todas" },
  { value: "OPEN", label: "Abertas" },
  { value: "IN_PROGRESS", label: "Em andamento" },
  { value: "DONE", label: "Concluídas" },
];

const STATUS_LABEL: Record<TaskStatus, string> = {
  OPEN: "Aberta",
  IN_PROGRESS: "Em andamento",
  DONE: "Concluída",
};

const PRIORITY_LABEL: Record<string, string> = { LOW: "Baixa", MEDIUM: "Média", HIGH: "Alta" };
const PRIORITY_COLOR: Record<string, string> = { HIGH: "var(--danger)", MEDIUM: "var(--warn)", LOW: "var(--muted)" };

// Datas em pt-BR (Intl, sem date-fns). Mantém o tom do protótipo: vence hoje /
// vence amanhã / venceu DD/mês / vence DD/mês.
function fmtShortDate(iso: string): string {
  return new Date(iso).toLocaleDateString("pt-BR", { day: "2-digit", month: "short" });
}

function relativeDue(iso: string): string {
  const d = new Date(iso);
  const now = new Date();
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate());
  const day = new Date(d.getFullYear(), d.getMonth(), d.getDate());
  const diff = Math.round((day.getTime() - today.getTime()) / 86_400_000);
  if (diff === 0) return "vence hoje";
  if (diff === 1) return "vence amanhã";
  if (diff < 0) return `venceu ${fmtShortDate(iso)}`;
  return `vence ${fmtShortDate(iso)}`;
}

function isLate(t: TaskListItemDto): boolean {
  return Boolean(t.dueDate) && new Date(t.dueDate as string) < new Date() && t.status !== "DONE";
}

export default function TasksPage() {
  const [items, setItems] = useState<TaskListItemDto[]>([]);
  const [filter, setFilter] = useState<TaskStatus | "ALL">("ALL");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<string | null>(null);
  const [editTitle, setEditTitle] = useState("");

  useEffect(() => {
    let active = true;
    setLoading(true);
    setError(null);
    listTasks(filter === "ALL" ? undefined : filter)
      .then((r) => {
        if (active) setItems(r.items);
      })
      .catch((e) => {
        if (!active) return;
        setError(e instanceof ApiRequestError ? e.message : "Falha ao carregar action items.");
      })
      .finally(() => {
        if (active) setLoading(false);
      });
    return () => {
      active = false;
    };
  }, [filter]);

  // §3.11 — filtro consistente: ao mudar o status com filtro ativo, o item sai
  // da lista se deixar de casar com o filtro selecionado.
  function applyLocalUpdate(curr: TaskListItemDto[], updated: TaskListItemDto): TaskListItemDto[] {
    const mapped = curr.map((x) => (x.id === updated.id ? updated : x));
    if (filter === "ALL") return mapped;
    return mapped.filter((x) => x.status === filter);
  }

  async function changeStatus(t: TaskListItemDto, next: TaskStatus) {
    if (t.status === next) return;
    const previous = items;
    const optimistic: TaskListItemDto = { ...t, status: next };
    setItems((curr) => applyLocalUpdate(curr, optimistic));
    try {
      const updated = await updateTask(t.id, { status: next });
      setItems((curr) => applyLocalUpdate(curr, updated));
    } catch (e) {
      setItems(previous);
      setError(e instanceof ApiRequestError ? e.message : "Falha ao atualizar status.");
    }
  }

  function beginEdit(t: TaskListItemDto) {
    setEditingId(t.id);
    setEditTitle(t.title);
  }
  function cancelEdit() {
    setEditingId(null);
    setEditTitle("");
  }
  async function saveEdit(t: TaskListItemDto) {
    const trimmed = editTitle.trim();
    if (!trimmed || trimmed === t.title) {
      cancelEdit();
      return;
    }
    const previous = items;
    setItems((curr) => curr.map((x) => (x.id === t.id ? { ...x, title: trimmed } : x)));
    cancelEdit();
    try {
      const updated = await updateTask(t.id, { title: trimmed });
      setItems((curr) => curr.map((x) => (x.id === t.id ? updated : x)));
    } catch (e) {
      setItems(previous);
      setError(e instanceof ApiRequestError ? e.message : "Falha ao atualizar título.");
    }
  }

  // Contadores por filtro. Quando o filtro é ALL temos a lista inteira; com
  // filtro ativo só temos o subconjunto carregado, então mostramos a contagem
  // disponível apenas para o filtro vigente.
  const counts = useMemo(() => {
    const base: Record<string, number | undefined> = {};
    if (filter === "ALL") {
      base.ALL = items.length;
      base.OPEN = items.filter((t) => t.status === "OPEN").length;
      base.IN_PROGRESS = items.filter((t) => t.status === "IN_PROGRESS").length;
      base.DONE = items.filter((t) => t.status === "DONE").length;
    } else {
      base[filter] = items.length;
    }
    return base;
  }, [items, filter]);

  return (
    <div className="page" style={{ maxWidth: 880 }}>
      <header style={{ marginBottom: 22 }}>
        <h1 className="h1">Action items</h1>
        <p className="lede" style={{ marginTop: 8 }}>
          Tarefas extraídas das suas reuniões. Atualize o status conforme avança.
        </p>
      </header>

      <div style={{ display: "flex", gap: 6, marginBottom: 20, flexWrap: "wrap" }}>
        {FILTERS.map((f) => {
          const active = filter === f.value;
          const count = counts[f.value];
          return (
            <button
              key={f.value}
              type="button"
              className={`filter-pill${active ? " is-active" : ""}`}
              onClick={() => setFilter(f.value)}
            >
              {f.label}
              {typeof count === "number" && <span style={{ opacity: 0.65, marginLeft: 5 }}>{count}</span>}
            </button>
          );
        })}
      </div>

      {error && (
        <div className="notice notice--danger" style={{ marginBottom: 16 }}>
          {error}
        </div>
      )}

      {loading ? (
        <div style={{ border: "1px solid var(--border)", borderRadius: 12, overflow: "hidden", background: "var(--canvas)" }}>
          {[0, 1, 2].map((i) => (
            <div
              key={i}
              style={{
                display: "flex",
                alignItems: "flex-start",
                gap: 12,
                padding: "14px 16px",
                borderTop: i === 0 ? "none" : "1px solid var(--border)",
              }}
            >
              <div className="skel" style={{ width: 16, height: 16, borderRadius: 5, marginTop: 3, flexShrink: 0 }} />
              <div style={{ flex: 1, minWidth: 0 }}>
                <div className="skel" style={{ width: "70%", height: 14 }} />
                <div className="skel" style={{ width: "40%", height: 11, marginTop: 8 }} />
              </div>
            </div>
          ))}
        </div>
      ) : items.length === 0 ? (
        <div style={{ border: "1px dashed var(--border-strong)", borderRadius: 14, padding: "48px 24px", textAlign: "center" }}>
          {filter === "ALL" ? (
            <>
              <p style={{ fontSize: 14, color: "var(--ink)", margin: "0 0 4px" }}>Nenhum action item por aqui.</p>
              <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0 }}>
                Envie uma reunião pra Nora extrair os próximos passos automaticamente.
              </p>
              <div style={{ marginTop: 16 }}>
                <Link className="btn btn-primary btn-sm" href={"/meetings/upload" as Route}>
                  Enviar reunião
                </Link>
              </div>
            </>
          ) : (
            <>
              <p style={{ fontSize: 14, color: "var(--ink)", margin: "0 0 4px" }}>Nada com esse status.</p>
              <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0 }}>
                Troque o filtro ou volte pra “Todas”.
              </p>
            </>
          )}
        </div>
      ) : (
        <div style={{ border: "1px solid var(--border)", borderRadius: 12, overflow: "hidden", background: "var(--canvas)" }}>
          {items.map((t, idx) => {
            const done = t.status === "DONE";
            const late = isLate(t);
            return (
              <div
                key={t.id}
                style={{
                  display: "flex",
                  alignItems: "flex-start",
                  gap: 12,
                  padding: "14px 16px",
                  borderTop: idx === 0 ? "none" : "1px solid var(--border)",
                }}
              >
                <input
                  type="checkbox"
                  checked={done}
                  onChange={(e) => changeStatus(t, e.target.checked ? "DONE" : "OPEN")}
                  style={{ marginTop: 3, accentColor: "var(--ink)", width: 16, height: 16, flexShrink: 0, cursor: "pointer" }}
                  aria-label={`Marcar "${t.title}" como ${done ? "aberta" : "concluída"}`}
                />

                <div style={{ flex: 1, minWidth: 0 }}>
                  {editingId === t.id ? (
                    <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                      <input
                        autoFocus
                        className="input"
                        value={editTitle}
                        onChange={(e) => setEditTitle(e.target.value)}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") void saveEdit(t);
                          if (e.key === "Escape") cancelEdit();
                        }}
                        style={{ flex: 1, minWidth: 200, fontSize: 14, padding: "5px 9px" }}
                      />
                      <button type="button" className="btn btn-primary btn-sm" onClick={() => void saveEdit(t)}>
                        Salvar
                      </button>
                      <button type="button" className="btn btn-ghost btn-sm" onClick={cancelEdit}>
                        Cancelar
                      </button>
                    </div>
                  ) : (
                    <>
                      <p
                        style={{
                          fontSize: 14,
                          margin: 0,
                          lineHeight: 1.4,
                          color: done ? "var(--muted)" : "var(--ink)",
                          textDecoration: done ? "line-through" : "none",
                        }}
                      >
                        {t.title}
                      </p>
                      <div
                        style={{
                          marginTop: 5,
                          display: "flex",
                          flexWrap: "wrap",
                          alignItems: "center",
                          gap: 10,
                          fontSize: 11.5,
                          color: "var(--muted)",
                        }}
                      >
                        <Link
                          href={`/meetings/${t.meetingId}` as Route}
                          style={{ color: "var(--muted)", textDecoration: "underline", textUnderlineOffset: 2 }}
                        >
                          {t.meetingTitle}
                        </Link>
                        <span style={{ color: PRIORITY_COLOR[t.priority] ?? "var(--muted)" }}>
                          {PRIORITY_LABEL[t.priority] ?? t.priority}
                        </span>
                        {t.assignee && <span>{t.assignee}</span>}
                        {t.dueDate && (
                          <span style={{ color: late ? "var(--danger)" : "var(--muted)" }}>{relativeDue(t.dueDate)}</span>
                        )}
                      </div>
                    </>
                  )}
                </div>

                <div style={{ display: "flex", alignItems: "center", gap: 6, flexShrink: 0 }}>
                  <select
                    className="select"
                    value={t.status}
                    onChange={(e) => changeStatus(t, e.target.value as TaskStatus)}
                    style={{ fontSize: 12.5, padding: "5px 9px", borderRadius: 7 }}
                    aria-label="Alterar status"
                  >
                    <option value="OPEN">{STATUS_LABEL.OPEN}</option>
                    <option value="IN_PROGRESS">{STATUS_LABEL.IN_PROGRESS}</option>
                    <option value="DONE">{STATUS_LABEL.DONE}</option>
                  </select>
                  {editingId !== t.id && (
                    <button
                      type="button"
                      onClick={() => beginEdit(t)}
                      title="Editar título"
                      style={{
                        fontFamily: "var(--sans)",
                        fontSize: 12.5,
                        color: "var(--muted)",
                        background: "transparent",
                        border: "1px solid var(--border)",
                        borderRadius: 7,
                        padding: "5px 9px",
                        cursor: "pointer",
                      }}
                    >
                      Editar
                    </button>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}
