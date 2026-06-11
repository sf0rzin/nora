"use client";

/**
 * NORA Core — bloco "Sessões" da sidebar.
 *
 * Porte do protótipo (shell.js · sessionsBlock), agora com dados reais:
 * as sessões vêm de `listChatSessions()` (escopado ao usuário logado dentro
 * do tenant). A sessão ativa é destacada lendo `?s=` da rota /chat.
 *
 * Mudança Stratfy: o label "Sessões" usa `.side-sec-label--tight` (colado
 * no título da categoria).
 */
import Link from "next/link";
import type { Route } from "next";
import { usePathname, useSearchParams } from "next/navigation";
import { useEffect, useState } from "react";

import { listChatSessions } from "@/lib/api/client";
import type { ChatSessionSummary } from "@/lib/api/types";

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

export function AppSidebarSessions() {
  const pathname = usePathname();
  const searchParams = useSearchParams();
  const current = searchParams.get("s");
  const onChat = pathname === "/chat" || pathname.startsWith("/chat/");

  const [sessions, setSessions] = useState<ChatSessionSummary[]>([]);

  useEffect(() => {
    let alive = true;
    listChatSessions()
      .then((list) => {
        if (alive) setSessions(list);
      })
      .catch(() => {
        if (alive) setSessions([]);
      });
    return () => {
      alive = false;
    };
  }, []);

  if (sessions.length === 0) return null;

  return (
    <div>
      <div className="side-sec-label side-sec-label--tight">Sessões</div>
      <div style={{ display: "flex", flexDirection: "column", gap: 1 }}>
        {sessions.map((s) => {
          const active = onChat && current === s.id;
          return (
            <Link
              key={s.id}
              className={`side-session${active ? " is-active" : ""}`}
              href={`/chat?s=${s.id}` as Route}
              title={s.title}
            >
              {s.title}
              <span className="when">{relTime(s.updatedAt)}</span>
            </Link>
          );
        })}
      </div>
    </div>
  );
}
