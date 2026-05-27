"use client";

import { useEffect, useState } from "react";
import Link from "next/link";
import type { Route } from "next";
import {
  listTasks,
  updateTask,
  type TaskListItemDto,
  type TaskStatus,
  ApiRequestError,
} from "@/lib/api/client";
import { Banner, Button, EmptyState, PageHeader } from "@/components/core/ui";

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

const PRIORITY_LABEL: Record<string, string> = {
  LOW: "Baixa",
  MEDIUM: "Média",
  HIGH: "Alta",
};

// ---------- Export (US25) ----------

function csvCell(v: string | undefined | null): string {
  const s = (v ?? "").replace(/"/g, '""');
  return /[",\n]/.test(s) ? `"${s}"` : s;
}

function buildTasksCsv(items: TaskListItemDto[]): string {
  const header = ["Titulo", "Status", "Prioridade", "Responsavel", "Prazo", "Reuniao"];
  const rows = items.map((t) =>
    [
      csvCell(t.title),
      csvCell(STATUS_LABEL[t.status] ?? t.status),
      csvCell(PRIORITY_LABEL[t.priority] ?? t.priority),
      csvCell(t.assignee),
      csvCell(t.dueDate),
      csvCell(t.meetingTitle),
    ].join(","),
  );
  return [header.join(","), ...rows].join("\r\n");
}

function buildTasksMarkdown(items: TaskListItemDto[]): string {
  const lines = ["# Action items — NORA", ""];
  for (const t of items) {
    const box = t.status === "DONE" ? "[x]" : "[ ]";
    const meta = [
      PRIORITY_LABEL[t.priority] ?? t.priority,
      t.assignee,
      t.dueDate ? `vence ${t.dueDate}` : null,
      t.meetingTitle,
    ]
      .filter(Boolean)
      .join(" · ");
    lines.push(`- ${box} ${t.title}${meta ? `  \n  _${meta}_` : ""}`);
  }
  return lines.join("\n");
}

function downloadText(filename: string, content: string, mime: string) {
  const blob = new Blob([content], { type: mime });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  URL.revokeObjectURL(url);
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
        if (e instanceof ApiRequestError) setError(e.message);
        else setError("Falha ao carregar tarefas.");
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
      if (e instanceof ApiRequestError) setError(e.message);
      else setError("Falha ao atualizar status.");
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
      if (e instanceof ApiRequestError) setError(e.message);
      else setError("Falha ao atualizar título.");
    }
  }

  function exportTasks(format: "csv" | "md") {
    if (items.length === 0) return;
    const stamp = new Date().toISOString().slice(0, 10);
    if (format === "csv") {
      downloadText(`action-items-${stamp}.csv`, buildTasksCsv(items), "text/csv;charset=utf-8");
    } else {
      downloadText(
        `action-items-${stamp}.md`,
        buildTasksMarkdown(items),
        "text/markdown;charset=utf-8",
      );
    }
  }

  return (
    <div>
      <PageHeader
        title="Action items"
        subtitle="Tarefas extraídas das suas reuniões. Atualize o status conforme avança."
        actions={
          <>
            {STATUS_OPTIONS.map((opt) => (
              <Button
                key={opt.value}
                variant={filter === opt.value ? "primary" : "ghost"}
                size="sm"
                onClick={() => setFilter(opt.value)}
              >
                {opt.label}
              </Button>
            ))}
            <span
              aria-hidden="true"
              style={{ width: 1, height: 20, background: "var(--border)", margin: "0 2px" }}
            />
            <Button
              size="sm"
              onClick={() => exportTasks("csv")}
              disabled={items.length === 0}
              title="Exportar action items em CSV"
            >
              CSV
            </Button>
            <Button
              size="sm"
              onClick={() => exportTasks("md")}
              disabled={items.length === 0}
              title="Exportar action items em Markdown"
            >
              MD
            </Button>
          </>
        }
      />

      {error && (
        <div style={{ marginBottom: 16 }}>
          <Banner tone="error">{error}</Banner>
        </div>
      )}

      {loading ? (
        <EmptyState>Carregando…</EmptyState>
      ) : items.length === 0 ? (
        <EmptyState>
          <div style={{ fontWeight: 500, color: "var(--ink)", marginBottom: 4 }}>
            Nenhuma tarefa encontrada.
          </div>
          Faça upload de uma reunião para a NORA extrair os próximos passos.
        </EmptyState>
      ) : (
        <div
          style={{
            border: "1px solid var(--border)",
            borderRadius: "var(--radius)",
            overflow: "hidden",
            background: "var(--surface)",
          }}
        >
          {items.map((t, i) => (
            <div
              key={t.id}
              style={{
                display: "flex",
                flexWrap: "wrap",
                alignItems: "center",
                gap: 12,
                padding: "12px 16px",
                borderTop: i === 0 ? "none" : "1px solid var(--border)",
              }}
            >
              <input
                type="checkbox"
                checked={t.status === "DONE"}
                onChange={(e) => changeStatus(t, e.target.checked ? "DONE" : "OPEN")}
                style={{ width: 16, height: 16, accentColor: "var(--accent)", flexShrink: 0 }}
                aria-label={`Marcar "${t.title}" como ${t.status === "DONE" ? "aberta" : "concluída"}`}
              />

              <div style={{ flex: 1, minWidth: 0 }}>
                {editingId === t.id ? (
                  <div style={{ display: "flex", flexWrap: "wrap", gap: 8 }}>
                    <input
                      autoFocus
                      className="nora-input"
                      style={{ flex: 1, minWidth: 180 }}
                      value={editTitle}
                      onChange={(e) => setEditTitle(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") saveEdit(t);
                        if (e.key === "Escape") cancelEdit();
                      }}
                    />
                    <Button variant="primary" size="sm" onClick={() => saveEdit(t)}>
                      Salvar
                    </Button>
                    <Button size="sm" onClick={cancelEdit}>
                      Cancelar
                    </Button>
                  </div>
                ) : (
                  <>
                    <div
                      style={{
                        fontSize: 14,
                        fontWeight: 500,
                        color: t.status === "DONE" ? "var(--muted)" : "var(--ink)",
                        textDecoration: t.status === "DONE" ? "line-through" : "none",
                      }}
                    >
                      {t.title}
                    </div>
                    <div
                      style={{
                        marginTop: 3,
                        display: "flex",
                        flexWrap: "wrap",
                        gap: 10,
                        fontSize: 12,
                        color: "var(--muted)",
                      }}
                    >
                      <Link
                        href={`/meetings/${t.meetingId}` as Route}
                        style={{ color: "var(--accent-ink)", textDecoration: "none" }}
                      >
                        {t.meetingTitle}
                      </Link>
                      <span>Prioridade {PRIORITY_LABEL[t.priority] ?? t.priority}</span>
                      {t.assignee && <span>{t.assignee}</span>}
                      {t.dueDate && <span>vence {t.dueDate}</span>}
                    </div>
                  </>
                )}
              </div>

              <div style={{ display: "flex", alignItems: "center", gap: 8, flexShrink: 0 }}>
                <select
                  className="nora-select"
                  style={{ width: "auto", fontSize: 12, padding: "5px 8px" }}
                  value={t.status}
                  onChange={(e) => changeStatus(t, e.target.value as TaskStatus)}
                  aria-label="Alterar status"
                >
                  <option value="OPEN">{STATUS_LABEL.OPEN}</option>
                  <option value="IN_PROGRESS">{STATUS_LABEL.IN_PROGRESS}</option>
                  <option value="DONE">{STATUS_LABEL.DONE}</option>
                </select>
                {editingId !== t.id && (
                  <Button size="sm" onClick={() => beginEdit(t)}>
                    Editar
                  </Button>
                )}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
