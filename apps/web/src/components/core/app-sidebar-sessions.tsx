"use client";

/**
 * NORA Core — sidebar "Sessões" block.
 *
 * Port of the prototype (shell.js · sessionsBlock), now with real data:
 * the sessions come from `listChatSessions()` (scoped to the logged-in user
 * inside the tenant). The active session is highlighted by reading `?s=` from
 * the /chat route.
 *
 * The list stays live without a full reload: it re-fetches when the route/`?s=`
 * changes and when the chat fires the `nora:sessions-changed` event (session
 * creation / new message — see lib/chat-sessions-sync.ts).
 *
 * Per-session management (hover): rename (inline input) and delete (inline
 * 2-click confirmation). Deleting the active session navigates to a clean /chat.
 *
 * Stratfy change: the "Sessões" label uses `.side-sec-label--tight` (pinned
 * to the category title).
 */
import Link from "next/link";
import type { Route } from "next";
import { usePathname, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useRef, useState } from "react";

import { deleteChatSession, listChatSessions, renameChatSession } from "@/lib/api/client";
import type { ChatSessionSummary } from "@/lib/api/types";
import { SESSIONS_CHANGED_EVENT, notifySessionsChanged } from "@/lib/chat-sessions-sync";

function relTime(iso: string): string {
  try {
    const then = new Date(iso).getTime();
    const diff = Date.now() - then;
    const min = Math.round(diff / 60000);
    if (min < 1) return "agora";
    if (min < 60) return `${min}min`;
    const hr = Math.round(min / 60);
    if (hr < 24) return `${hr}h`;
    const day = Math.round(hr / 24);
    if (day < 7) return `${day}d`;
    return new Date(iso).toLocaleDateString("pt-BR", { day: "2-digit", month: "short" });
  } catch {
    return "";
  }
}

function PencilIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M17 3a2.85 2.85 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z" />
    </svg>
  );
}
function TrashIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 6h18M8 6V4a1 1 0 0 1 1-1h6a1 1 0 0 1 1 1v2M19 6l-1 14a2 2 0 0 1-2 2H8a2 2 0 0 1-2-2L5 6" />
    </svg>
  );
}
function CheckIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="M20 6 9 17l-5-5" />
    </svg>
  );
}
function XIcon() {
  return (
    <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  );
}

/** Edit state of a session: renaming or confirming deletion. */
type EditState =
  | { kind: "rename"; id: string }
  | { kind: "confirm-delete"; id: string }
  | null;

export function AppSidebarSessions() {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const router = useRouter();
  const current = searchParams.get("s");
  const onChat = pathname === "/chat" || pathname.startsWith("/chat/");

  const [sessions, setSessions] = useState<ChatSessionSummary[]>([]);
  const [edit, setEdit] = useState<EditState>(null);
  const [renameValue, setRenameValue] = useState("");
  const [busy, setBusy] = useState(false);

  // Guard against out-of-order responses: route + events fire refreshes in
  // bursts and an old response landing last used to overwrite the new list
  // (the section "flickered"). Only the response of the most recent request
  // may apply state.
  const refreshSeqRef = useRef(0);

  const refresh = useCallback(() => {
    const seq = ++refreshSeqRef.current;
    listChatSessions()
      .then((next) => {
        if (seq === refreshSeqRef.current) setSessions(next);
      })
      .catch(() => {
        // A transient error does NOT clear the list — clearing made the whole
        // section vanish and come back (length === 0 returns null). Keep the
        // last good one.
      });
  }, []);

  // Mount + any route/active-session change → re-fetch (covers creating a
  // session via URL, coming back from the chat etc. without a full reload).
  useEffect(() => {
    refresh();
  }, [refresh, pathname, current]);

  // Custom event from the chat (session creation / persisted message) and from
  // the sidebar's own actions — keeps desktop + mobile drawer in sync.
  useEffect(() => {
    window.addEventListener(SESSIONS_CHANGED_EVENT, refresh);
    return () => window.removeEventListener(SESSIONS_CHANGED_EVENT, refresh);
  }, [refresh]);

  const startRename = useCallback((s: ChatSessionSummary) => {
    setEdit({ kind: "rename", id: s.id });
    setRenameValue(s.title);
  }, []);

  const commitRename = useCallback(
    async (s: ChatSessionSummary) => {
      const title = renameValue.trim();
      setEdit(null);
      if (!title || title === s.title) return;
      setBusy(true);
      try {
        await renameChatSession(s.id, title);
        notifySessionsChanged();
      } catch {
        // Keeps the old title; the event re-fetch does not happen on error.
      } finally {
        setBusy(false);
      }
    },
    [renameValue],
  );

  const confirmDelete = useCallback(
    async (s: ChatSessionSummary) => {
      setBusy(true);
      try {
        await deleteChatSession(s.id);
        setEdit(null);
        notifySessionsChanged();
        // Deleted the session open in the chat → back to a clean /chat.
        if (onChat && current === s.id) router.push("/chat" as Route);
      } catch {
        setEdit(null);
      } finally {
        setBusy(false);
      }
    },
    [onChat, current, router],
  );

  if (sessions.length === 0) return null;

  return (
    <div>
      <div className="side-sec-label side-sec-label--tight">Sessões</div>
      <div style={{ display: "flex", flexDirection: "column", gap: 1 }}>
        {sessions.map((s) => {
          const active = onChat && current === s.id;
          const renaming = edit?.kind === "rename" && edit.id === s.id;
          const confirming = edit?.kind === "confirm-delete" && edit.id === s.id;

          if (renaming) {
            return (
              <div key={s.id} className="side-session-row is-editing">
                <input
                  className="side-session-input"
                  value={renameValue}
                  autoFocus
                  aria-label="Novo nome da sessão"
                  placeholder="Nome da sessão"
                  onChange={(e) => setRenameValue(e.target.value)}
                  onKeyDown={(e) => {
                    if (e.key === "Enter") {
                      e.preventDefault();
                      void commitRename(s);
                    } else if (e.key === "Escape") {
                      setEdit(null);
                    }
                  }}
                  onBlur={() => void commitRename(s)}
                  disabled={busy}
                />
              </div>
            );
          }

          if (confirming) {
            return (
              <div key={s.id} className="side-session-row is-editing">
                <span className="side-session-confirm">Apagar esta sessão?</span>
                <span className="side-session-acts" style={{ display: "inline-flex" }}>
                  <button
                    type="button"
                    className="side-session-act is-danger"
                    aria-label="Confirmar exclusão"
                    title="Apagar de vez"
                    onClick={() => void confirmDelete(s)}
                    disabled={busy}
                  >
                    <CheckIcon />
                  </button>
                  <button
                    type="button"
                    className="side-session-act"
                    aria-label="Cancelar exclusão"
                    title="Cancelar"
                    onClick={() => setEdit(null)}
                    disabled={busy}
                  >
                    <XIcon />
                  </button>
                </span>
              </div>
            );
          }

          return (
            <div key={s.id} className={`side-session-row${active ? " is-active" : ""}`}>
              <Link className="side-session-link" href={`/chat?s=${s.id}` as Route} title={s.title}>
                {s.title}
                <span className="when">{relTime(s.updatedAt)}</span>
              </Link>
              <span className="side-session-acts">
                <button
                  type="button"
                  className="side-session-act"
                  aria-label={`Renomear sessão ${s.title}`}
                  title="Renomear"
                  onClick={() => startRename(s)}
                >
                  <PencilIcon />
                </button>
                <button
                  type="button"
                  className="side-session-act is-danger"
                  aria-label={`Apagar sessão ${s.title}`}
                  title="Apagar"
                  onClick={() => setEdit({ kind: "confirm-delete", id: s.id })}
                >
                  <TrashIcon />
                </button>
              </span>
            </div>
          );
        })}
      </div>
    </div>
  );
}
