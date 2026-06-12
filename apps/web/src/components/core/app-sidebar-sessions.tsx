"use client";

/**
 * NORA Core — bloco "Sessões" da sidebar.
 *
 * Porte do protótipo (shell.js · sessionsBlock), agora com dados reais:
 * as sessões vêm de `listChatSessions()` (escopado ao usuário logado dentro
 * do tenant). A sessão ativa é destacada lendo `?s=` da rota /chat.
 *
 * A lista fica viva sem full reload: refaz o fetch quando a rota/`?s=` muda
 * e quando o chat dispara o evento `nora:sessions-changed` (criação de
 * sessão / nova mensagem — ver lib/chat-sessions-sync.ts).
 *
 * Gestão por sessão (hover): renomear (input inline) e apagar (confirmação
 * inline em 2 cliques). Apagar a sessão ativa navega pra /chat limpo.
 *
 * Mudança Stratfy: o label "Sessões" usa `.side-sec-label--tight` (colado
 * no título da categoria).
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

/** Estado de edição de uma sessão: renomeando ou confirmando exclusão. */
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

  // Guarda contra respostas fora de ordem: rota + eventos disparam refreshes
  // em rajada e uma resposta antiga aterrissando por último sobrescrevia a
  // lista nova (a seção "piscava"). Só a resposta do request mais recente
  // pode aplicar estado.
  const refreshSeqRef = useRef(0);

  const refresh = useCallback(() => {
    const seq = ++refreshSeqRef.current;
    listChatSessions()
      .then((next) => {
        if (seq === refreshSeqRef.current) setSessions(next);
      })
      .catch(() => {
        // Erro transiente NÃO zera a lista — zerar fazia a seção inteira
        // sumir e voltar (length === 0 retorna null). Mantém a última boa.
      });
  }, []);

  // Mount + qualquer mudança de rota/sessão ativa → re-fetch (cobre criar
  // sessão via URL, voltar do chat etc. sem precisar de full reload).
  useEffect(() => {
    refresh();
  }, [refresh, pathname, current]);

  // Evento custom do chat (criação de sessão / mensagem persistida) e das
  // ações da própria sidebar — mantém desktop + drawer mobile em sincronia.
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
        // Mantém o título antigo; o re-fetch do evento não acontece em erro.
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
        // Apagou a sessão aberta no chat → volta pro /chat limpo.
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
