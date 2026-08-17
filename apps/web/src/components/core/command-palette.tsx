"use client";

/**
 * NORA Core — command palette (⌘K) with semantic meeting search.
 *
 * Port of the v3 design prototype (shell.js · mountPalette/renderPalette):
 * fixed commands + meeting results via `searchMeetings(q)` (real RAG, scoped
 * to the tenant). No mock: the meeting list comes from the backend.
 *
 * Keyboard shortcuts (bindShortcuts) and the open state live in the AppShell;
 * this component only renders/controls the overlay when `open` is true.
 */
import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { searchMeetings } from "@/lib/api/client";

type CommandItem = {
  kind: "cmd";
  label: string;
  sub?: string;
  icon: "plus" | "chat" | "tasks" | "trends" | "flow" | "gear" | "shield";
  href: Route;
};

type ResolvedItem = { href: Route };

const COMMANDS: CommandItem[] = [
  { kind: "cmd", label: "Nova reunião", sub: "N", icon: "plus", href: "/meetings/upload" as Route },
  { kind: "cmd", label: "Nova sessão de chat", icon: "chat", href: "/chat" as Route },
  { kind: "cmd", label: "Ver action items", icon: "tasks", href: "/tasks" as Route },
  { kind: "cmd", label: "Tendências — temas e carga de tarefas", icon: "trends", href: "/trends" as Route },
  { kind: "cmd", label: "Fluxos", icon: "flow", href: "/flows" as Route },
  { kind: "cmd", label: "Abrir configurações", icon: "gear", href: "/settings" as Route },
  { kind: "cmd", label: "IAM — usuários, grupos e policies", icon: "shield", href: "/settings/iam" as Route },
  { kind: "cmd", label: "Credenciais MCP", sub: "clientes externos", icon: "shield", href: "/settings/mcp" as Route },
];

function CmdIcon({ name }: { name: CommandItem["icon"] | "doc" | "search" }) {
  switch (name) {
    case "plus":
      return (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
          <path d="M12 5v14M5 12h14" />
        </svg>
      );
    case "chat":
      return (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
        </svg>
      );
    case "tasks":
      return (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <path d="M9 11l3 3L22 4" />
          <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
        </svg>
      );
    case "trends":
      return (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <path d="M3 20h18" />
          <path d="M6 20v-6M11 20V8M16 20v-9M21 20V5" />
        </svg>
      );
    case "flow":
      return (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <rect x="3" y="3" width="7" height="7" rx="1.5" />
          <rect x="14" y="14" width="7" height="7" rx="1.5" />
          <path d="M10 6.5h5.5a2 2 0 0 1 2 2V14" />
        </svg>
      );
    case "gear":
      return (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="12" cy="12" r="3" />
          <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H7a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
        </svg>
      );
    case "shield":
      return (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <path d="M12 3l7 3v5.5c0 4.4-2.9 8.3-7 9.5-4.1-1.2-7-5.1-7-9.5V6z" />
          <path d="M9.5 12l1.8 1.8 3.2-3.6" />
        </svg>
      );
    case "doc":
      return (
        <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
          <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z" />
          <path d="M14 2v6h6" />
        </svg>
      );
    case "search":
      return (
        <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.5-3.5" />
        </svg>
      );
    default:
      return null;
  }
}

function fmtDate(iso?: string): string {
  if (!iso) return "";
  try {
    return new Date(iso).toLocaleDateString("pt-BR", { day: "2-digit", month: "short", year: "numeric" });
  } catch {
    return "";
  }
}

type MeetingHit = { id: string; title: string; summarySnippet?: string; startedAt?: string };

export function CommandPalette({ open, onClose }: { open: boolean; onClose: () => void }) {
  const router = useRouter();
  const inputRef = useRef<HTMLInputElement | null>(null);
  const [query, setQuery] = useState("");
  const [meetings, setMeetings] = useState<MeetingHit[]>([]);
  const [sel, setSel] = useState(0);

  // Focuses the input on open and clears the search. Closing does not need to
  // wipe everything — the reset happens on the next open.
  useEffect(() => {
    if (!open) return;
    setQuery("");
    setSel(0);
    const t = setTimeout(() => inputRef.current?.focus(), 30);
    return () => clearTimeout(t);
  }, [open]);

  // Debounced semantic search. Empty query → shows recent meetings
  // (searchMeetings with empty q returns the recent ones from the backend).
  useEffect(() => {
    if (!open) return;
    let alive = true;
    const handle = setTimeout(async () => {
      try {
        const k = query.trim() ? 6 : 4;
        const res = await searchMeetings(query.trim(), k);
        if (alive) setMeetings(res.items ?? []);
      } catch {
        if (alive) setMeetings([]);
      }
    }, query.trim() ? 180 : 0);
    return () => {
      alive = false;
      clearTimeout(handle);
    };
  }, [query, open]);

  const q = query.trim().toLowerCase();
  const cmds = COMMANDS.filter((c) => !q || c.label.toLowerCase().includes(q));

  // Flattened list in visual order, for keyboard navigation.
  const items: ResolvedItem[] = [
    ...cmds.map((c) => ({ href: c.href })),
    ...meetings.map((m) => ({ href: `/meetings/${m.id}` as Route })),
  ];

  // Keeps the selection within bounds when the list changes.
  useEffect(() => {
    setSel((s) => Math.min(Math.max(s, 0), Math.max(items.length - 1, 0)));
  }, [items.length]);

  const go = useCallback(
    (href: Route) => {
      onClose();
      router.push(href);
    },
    [onClose, router],
  );

  function handleKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if (e.key === "ArrowDown") {
      e.preventDefault();
      setSel((s) => Math.min(s + 1, items.length - 1));
    } else if (e.key === "ArrowUp") {
      e.preventDefault();
      setSel((s) => Math.max(s - 1, 0));
    } else if (e.key === "Enter") {
      const item = items[sel];
      if (item) go(item.href);
    } else if (e.key === "Escape") {
      onClose();
    }
  }

  if (!open) return null;

  const cmdOffset = cmds.length;

  return (
    <div
      className="palette-veil is-open"
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div className="palette" role="dialog" aria-label="Busca e comandos">
        <div className="palette-input-row">
          <CmdIcon name="search" />
          <input
            ref={inputRef}
            className="palette-input"
            placeholder="Buscar reuniões ou digitar um comando…"
            autoComplete="off"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={handleKeyDown}
          />
          <kbd className="kbd">esc</kbd>
        </div>

        <div className="palette-list">
          {cmds.length > 0 && <div className="palette-group">Comandos</div>}
          {cmds.map((c, i) => (
            <div
              key={c.label}
              className={`palette-item${sel === i ? " is-sel" : ""}`}
              data-i={i}
              onClick={() => go(c.href)}
              onMouseMove={() => setSel(i)}
            >
              <span className="icon">
                <CmdIcon name={c.icon} />
              </span>
              <span className="grow">{c.label}</span>
              {c.sub && <kbd className="kbd">{c.sub}</kbd>}
            </div>
          ))}

          {meetings.length > 0 && (
            <div className="palette-group">{q ? "Reuniões — busca semântica" : "Reuniões recentes"}</div>
          )}
          {meetings.map((m, i) => {
            const idx = cmdOffset + i;
            return (
              <div
                key={m.id}
                className={`palette-item${sel === idx ? " is-sel" : ""}`}
                data-i={idx}
                onClick={() => go(`/meetings/${m.id}` as Route)}
                onMouseMove={() => setSel(idx)}
              >
                <span className="icon">
                  <CmdIcon name="doc" />
                </span>
                <span className="grow">{m.title}</span>
                {m.startedAt && <span className="sub">{fmtDate(m.startedAt)}</span>}
              </div>
            );
          })}

          {items.length === 0 && (
            <div className="palette-empty">
              {query.trim() ? `Nada encontrado pra “${query.trim()}”.` : "Nenhuma reunião ainda."}
            </div>
          )}
        </div>

        <div className="palette-foot">
          <span>
            <kbd className="kbd">↑↓</kbd> navegar
          </span>
          <span>
            <kbd className="kbd">↵</kbd> abrir
          </span>
          <span>Busca semântica nas suas reuniões</span>
        </div>
      </div>
    </div>
  );
}
