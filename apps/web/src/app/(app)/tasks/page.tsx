"use client";

import { useEffect, useState, type CSSProperties } from "react";
import Link from "next/link";
import type { Route } from "next";
import {
  listTasks,
  updateTask,
  type TaskListItemDto,
  type TaskStatus,
  ApiRequestError,
} from "@/lib/api/client";

const STATUS_OPTIONS: { value: TaskStatus | "ALL"; label: string }[] = [
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

const inputStyle: CSSProperties = {
  fontFamily: "var(--sans)",
  fontSize: 12.5,
  color: "var(--ink)",
  background: "var(--canvas)",
  border: "1px solid var(--border)",
  borderRadius: 7,
  padding: "5px 9px",
  outline: "none",
};

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

  async function changeStatus(t: TaskListItemDto, next: TaskStatus) {
    const previous = items;
    setItems((curr) => curr.map((x) => (x.id === t.id ? { ...x, status: next } : x)));
    try {
      const updated = await updateTask(t.id, { status: next });
      setItems((curr) => curr.map((x) => (x.id === t.id ? updated : x)));
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

  return (
    <div style={{ maxWidth: 880, margin: "0 auto", padding: "56px 40px 80px" }}>
      <header style={{ marginBottom: 22 }}>
        <h1 style={{ fontFamily: "var(--display)", fontSize: 30, fontWeight: 500, letterSpacing: "-0.025em", color: "var(--ink)", margin: "0 0 8px" }}>
          Action items
        </h1>
        <p style={{ fontSize: 14, color: "var(--muted)", margin: 0, lineHeight: 1.6 }}>
          Tarefas extraídas das suas reuniões. Atualize o status conforme avança.
        </p>
      </header>

      <div style={{ display: "flex", gap: 6, marginBottom: 20, flexWrap: "wrap" }}>
        {STATUS_OPTIONS.map((opt) => {
          const active = filter === opt.value;
          return (
            <button
              key={opt.value}
              type="button"
              onClick={() => setFilter(opt.value)}
              style={{
                fontFamily: "var(--sans)",
                fontSize: 12.5,
                padding: "6px 13px",
                borderRadius: 999,
                cursor: "pointer",
                border: "1px solid var(--border)",
                background: active ? "var(--ink)" : "var(--canvas)",
                color: active ? "var(--canvas)" : "var(--muted)",
              }}
            >
              {opt.label}
            </button>
          );
        })}
      </div>

      {error && (
        <div style={{ marginBottom: 16, padding: "10px 14px", borderRadius: 9, border: "1px solid var(--border)", background: "var(--chip)", fontSize: 13, color: "var(--muted)" }}>
          {error}
        </div>
      )}

      {loading ? (
        <div style={{ padding: "48px 24px", textAlign: "center", fontSize: 13, color: "var(--muted)" }}>Carregando…</div>
      ) : items.length === 0 ? (
        <div style={{ border: "1px dashed var(--border-strong)", borderRadius: 14, padding: "48px 24px", textAlign: "center" }}>
          <p style={{ fontSize: 14, color: "var(--ink)", margin: "0 0 4px" }}>Nenhum action item por aqui.</p>
          <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0 }}>
            Envie uma reunião pra NORA extrair os próximos passos automaticamente.
          </p>
        </div>
      ) : (
        <div style={{ border: "1px solid var(--border)", borderRadius: 12, overflow: "hidden", background: "var(--canvas)" }}>
          {items.map((t, idx) => (
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
                checked={t.status === "DONE"}
                onChange={(e) => changeStatus(t, e.target.checked ? "DONE" : "OPEN")}
                style={{ marginTop: 3, accentColor: "var(--accent)", width: 16, height: 16, flexShrink: 0 }}
                aria-label={`Marcar "${t.title}" como ${t.status === "DONE" ? "aberta" : "concluída"}`}
              />

              <div style={{ flex: 1, minWidth: 0 }}>
                {editingId === t.id ? (
                  <div style={{ display: "flex", gap: 8, flexWrap: "wrap" }}>
                    <input
                      autoFocus
                      value={editTitle}
                      onChange={(e) => setEditTitle(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") void saveEdit(t);
                        if (e.key === "Escape") cancelEdit();
                      }}
                      style={{ ...inputStyle, flex: 1, minWidth: 200, fontSize: 14 }}
                    />
                    <button type="button" onClick={() => void saveEdit(t)} style={{ ...inputStyle, background: "var(--ink)", color: "var(--canvas)", border: "none", cursor: "pointer" }}>
                      Salvar
                    </button>
                    <button type="button" onClick={cancelEdit} style={{ ...inputStyle, color: "var(--muted)", cursor: "pointer" }}>
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
                        color: t.status === "DONE" ? "var(--muted)" : "var(--ink)",
                        textDecoration: t.status === "DONE" ? "line-through" : "none",
                      }}
                    >
                      {t.title}
                    </p>
                    <div style={{ marginTop: 5, display: "flex", flexWrap: "wrap", alignItems: "center", gap: 10, fontSize: 11.5, color: "var(--muted)" }}>
                      <Link href={`/meetings/${t.meetingId}` as Route} style={{ color: "var(--muted)", textDecoration: "underline", textUnderlineOffset: 2 }}>
                        {t.meetingTitle}
                      </Link>
                      <span style={{ color: PRIORITY_COLOR[t.priority] ?? "var(--muted)" }}>{PRIORITY_LABEL[t.priority] ?? t.priority}</span>
                      {t.assignee && <span>{t.assignee}</span>}
                      {t.dueDate && <span>vence {t.dueDate}</span>}
                    </div>
                  </>
                )}
              </div>

              <div style={{ display: "flex", alignItems: "center", gap: 6, flexShrink: 0 }}>
                <select value={t.status} onChange={(e) => changeStatus(t, e.target.value as TaskStatus)} style={inputStyle} aria-label="Alterar status">
                  <option value="OPEN">{STATUS_LABEL.OPEN}</option>
                  <option value="IN_PROGRESS">{STATUS_LABEL.IN_PROGRESS}</option>
                  <option value="DONE">{STATUS_LABEL.DONE}</option>
                </select>
                {editingId !== t.id && (
                  <button type="button" onClick={() => beginEdit(t)} style={{ ...inputStyle, color: "var(--muted)", cursor: "pointer" }}>
                    Editar
                  </button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
