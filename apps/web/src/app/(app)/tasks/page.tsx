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
    <div className="space-y-6">
      <header className="flex flex-col gap-2 sm:flex-row sm:items-end sm:justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Tarefas</h1>
          <p className="text-sm text-slate-500">
            Action items extraídos das suas reuniões. Atualize o status conforme avança.
          </p>
        </div>
        <div className="flex flex-wrap items-center gap-2 text-sm">
          {STATUS_OPTIONS.map((opt) => (
            <button
              key={opt.value}
              type="button"
              onClick={() => setFilter(opt.value)}
              className={
                filter === opt.value
                  ? "rounded-md bg-slate-900 px-3 py-1.5 text-white"
                  : "rounded-md border border-slate-300 px-3 py-1.5 text-slate-700 hover:bg-slate-50"
              }
            >
              {opt.label}
            </button>
          ))}
          <span className="mx-1 hidden h-4 w-px bg-slate-200 sm:block" aria-hidden="true" />
          <button
            type="button"
            onClick={() => exportTasks("csv")}
            disabled={items.length === 0}
            title="Exportar action items em CSV"
            className="rounded-md border border-slate-300 px-3 py-1.5 text-slate-700 hover:bg-slate-50 disabled:opacity-40"
          >
            CSV
          </button>
          <button
            type="button"
            onClick={() => exportTasks("md")}
            disabled={items.length === 0}
            title="Exportar action items em Markdown"
            className="rounded-md border border-slate-300 px-3 py-1.5 text-slate-700 hover:bg-slate-50 disabled:opacity-40"
          >
            MD
          </button>
        </div>
      </header>

      {error && (
        <div className="rounded-md border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {loading ? (
        <div className="rounded-md border border-slate-200 bg-white p-8 text-center text-sm text-slate-500">
          Carregando...
        </div>
      ) : items.length === 0 ? (
        <div className="rounded-md border border-dashed border-slate-300 bg-white p-12 text-center">
          <p className="text-sm text-slate-600">Nenhuma tarefa encontrada.</p>
          <p className="mt-1 text-xs text-slate-500">
            Faça upload de uma reunião para que a NORA extraia os próximos passos.
          </p>
        </div>
      ) : (
        <ul className="divide-y divide-slate-200 rounded-md border border-slate-200 bg-white">
          {items.map((t) => (
            <li key={t.id} className="flex flex-col gap-2 p-4 sm:flex-row sm:items-center sm:gap-4">
              <input
                type="checkbox"
                checked={t.status === "DONE"}
                onChange={(e) => changeStatus(t, e.target.checked ? "DONE" : "OPEN")}
                className="h-4 w-4 rounded border-slate-300"
                aria-label={`Marcar "${t.title}" como ${t.status === "DONE" ? "aberta" : "concluída"}`}
              />

              <div className="flex-1 min-w-0">
                {editingId === t.id ? (
                  <div className="flex flex-col gap-2 sm:flex-row">
                    <input
                      autoFocus
                      value={editTitle}
                      onChange={(e) => setEditTitle(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") saveEdit(t);
                        if (e.key === "Escape") cancelEdit();
                      }}
                      className="flex-1 rounded-md border border-slate-300 px-3 py-1.5 text-sm focus:border-slate-500 focus:outline-none"
                    />
                    <div className="flex gap-2">
                      <button
                        type="button"
                        onClick={() => saveEdit(t)}
                        className="rounded-md bg-slate-900 px-3 py-1.5 text-sm text-white hover:bg-slate-800"
                      >
                        Salvar
                      </button>
                      <button
                        type="button"
                        onClick={cancelEdit}
                        className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-50"
                      >
                        Cancelar
                      </button>
                    </div>
                  </div>
                ) : (
                  <>
                    <p
                      className={
                        t.status === "DONE"
                          ? "text-sm font-medium text-slate-400 line-through"
                          : "text-sm font-medium text-slate-900"
                      }
                    >
                      {t.title}
                    </p>
                    <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1 text-xs text-slate-500">
                      <Link
                        href={`/meetings/${t.meetingId}` as Route}
                        className="hover:underline"
                      >
                        {t.meetingTitle}
                      </Link>
                      <span>· Prioridade {PRIORITY_LABEL[t.priority] ?? t.priority}</span>
                      {t.assignee && <span>· {t.assignee}</span>}
                      {t.dueDate && <span>· vence {t.dueDate}</span>}
                    </div>
                  </>
                )}
              </div>

              <div className="flex items-center gap-2">
                <select
                  value={t.status}
                  onChange={(e) => changeStatus(t, e.target.value as TaskStatus)}
                  className="rounded-md border border-slate-300 px-2 py-1 text-xs focus:border-slate-500 focus:outline-none"
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
                    className="rounded-md border border-slate-300 px-2 py-1 text-xs text-slate-700 hover:bg-slate-50"
                  >
                    Editar
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
