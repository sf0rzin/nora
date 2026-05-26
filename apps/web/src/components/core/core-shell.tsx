"use client";

/**
 * CoreShell — chrome do app logado (porto de `project/app.jsx` Sidebar +
 * HistoryPanel + CommandPalette do Claude Design). Renderiza a sidebar fixa, o
 * popover de histórico, a paleta de comandos (⌘K) e os atalhos globais; o
 * conteúdo de cada rota entra como `children`.
 *
 * Tudo fiado no backend REAL: histórico e paleta puxam `listMeetings()` (lazy,
 * na primeira abertura); rodapé do usuário vem de `getCurrentUser()`; sair usa
 * `clearSession()`. Sem dados mockados.
 */

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useRef,
  useState,
} from "react";
import Link from "next/link";
import type { Route } from "next";
import { usePathname, useRouter } from "next/navigation";

import { NoraLogo } from "@/components/brand/nora-logo";
import { listMeetings } from "@/lib/api/client";
import { clearSession, getCurrentUser, type SessionUser } from "@/lib/auth";
import type { MeetingListItem } from "@/lib/api/types";

import { Avatar, Kbd } from "./bits";
import { dayLabel, fmtDuration, fmtTime, groupOf, GROUP_ORDER } from "./format";
import "./core.css";

const SIDEBAR_WIDTH = 248;

interface CoreShellApi {
  openPalette: () => void;
}
const CoreShellContext = createContext<CoreShellApi | null>(null);

/** Hook pra rotas filhas dispararem a paleta de comandos (ex.: botão "Buscar"). */
export function useCoreShell(): CoreShellApi {
  const ctx = useContext(CoreShellContext);
  if (!ctx) throw new Error("useCoreShell deve ser usado dentro de <CoreShell>");
  return ctx;
}

export default function CoreShell({ children }: { children: React.ReactNode }) {
  const router = useRouter();
  const [historyOpen, setHistoryOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);

  // Lista de reuniões compartilhada por histórico + paleta. Carrega só na
  // primeira vez que um dos dois abre (lazy), e fica em cache na sessão.
  const [meetings, setMeetings] = useState<MeetingListItem[] | null>(null);
  const [meetingsError, setMeetingsError] = useState<string | null>(null);
  const loadingRef = useRef(false);

  const ensureMeetings = useCallback(async () => {
    if (meetings !== null || loadingRef.current) return;
    loadingRef.current = true;
    try {
      const res = await listMeetings({ size: 50 });
      setMeetings(res.items);
      setMeetingsError(null);
    } catch (err) {
      setMeetings([]);
      setMeetingsError(err instanceof Error ? err.message : "Falha ao carregar reuniões.");
    } finally {
      loadingRef.current = false;
    }
  }, [meetings]);

  const openPalette = useCallback(() => {
    setPaletteOpen(true);
    void ensureMeetings();
  }, [ensureMeetings]);

  const toggleHistory = useCallback(() => {
    setHistoryOpen((open) => {
      if (!open) void ensureMeetings();
      return !open;
    });
  }, [ensureMeetings]);

  // Atalhos globais: ⌘K / N / /
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const typing =
        !!target &&
        (target.tagName === "INPUT" ||
          target.tagName === "TEXTAREA" ||
          target.isContentEditable);
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setPaletteOpen((open) => {
          if (!open) void ensureMeetings();
          return !open;
        });
        return;
      }
      if (typing) return;
      if (e.key === "n" || e.key === "N") {
        e.preventDefault();
        router.push("/meetings/upload");
      } else if (e.key === "/") {
        e.preventDefault();
        openPalette();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [ensureMeetings, openPalette, router]);

  const api = useMemo<CoreShellApi>(() => ({ openPalette }), [openPalette]);

  return (
    <CoreShellContext.Provider value={api}>
      <div
        className="nora-core"
        style={{ display: "flex", minHeight: "100vh", position: "relative" }}
      >
        <Sidebar historyOpen={historyOpen} onToggleHistory={toggleHistory} />

        {historyOpen && (
          <HistoryPanel
            meetings={meetings}
            error={meetingsError}
            onClose={() => setHistoryOpen(false)}
          />
        )}

        <main style={{ flex: 1, height: "100vh", overflowY: "auto", background: "var(--canvas)" }}>
          <div className="mx-auto w-full max-w-6xl p-6 md:p-10">{children}</div>
        </main>
      </div>

      <CommandPalette
        open={paletteOpen}
        meetings={meetings}
        onClose={() => setPaletteOpen(false)}
      />
    </CoreShellContext.Provider>
  );
}

// ---------- Sidebar ----------

const iconBtn: React.CSSProperties = {
  width: 28,
  height: 28,
  display: "grid",
  placeItems: "center",
  background: "transparent",
  border: "none",
  borderRadius: 6,
  cursor: "pointer",
  color: "var(--muted)",
};

interface NavItem {
  label: string;
  href: Route;
  plus?: boolean;
}

const NAV: NavItem[] = [
  { label: "Início", href: "/dashboard" as Route },
  { label: "Nova reunião", href: "/meetings/upload" as Route, plus: true },
  { label: "Action items", href: "/tasks" as Route },
  { label: "Contexto", href: "/settings/context" as Route },
  { label: "IAM", href: "/settings/iam" as Route },
];

function isActive(pathname: string, href: string): boolean {
  if (href === "/dashboard") {
    return (
      pathname === "/dashboard" ||
      (pathname.startsWith("/meetings/") && pathname !== "/meetings/upload")
    );
  }
  return pathname === href || pathname.startsWith(href + "/");
}

function Sidebar({
  historyOpen,
  onToggleHistory,
}: {
  historyOpen: boolean;
  onToggleHistory: () => void;
}) {
  const pathname = usePathname();
  const router = useRouter();
  const [user, setUser] = useState<SessionUser | null>(null);
  const [signingOut, setSigningOut] = useState(false);

  // getCurrentUser lê cookie via document.cookie → client-only. Setar após
  // mount evita mismatch de hidratação (SSR não tem o cookie do browser).
  useEffect(() => {
    setUser(getCurrentUser());
  }, []);

  async function handleSignOut() {
    if (signingOut) return;
    setSigningOut(true);
    await clearSession();
    router.replace("/auth/login");
    router.refresh();
  }

  return (
    <aside
      className="hidden md:flex"
      style={{
        width: SIDEBAR_WIDTH,
        flexShrink: 0,
        height: "100vh",
        background: "var(--sidebar)",
        borderRight: "1px solid var(--border)",
        flexDirection: "column",
        padding: "18px 14px 14px",
        gap: 16,
        position: "relative",
        zIndex: 2,
      }}
    >
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "0 4px",
        }}
      >
        <Link
          href={"/dashboard" as Route}
          style={{ display: "flex", alignItems: "center", gap: 8, textDecoration: "none" }}
          title="Início"
        >
          <NoraLogo size={20} animate={false} />
          <span
            style={{
              fontSize: 9.5,
              padding: "2px 6px",
              border: "1px solid var(--border)",
              borderRadius: 4,
              color: "var(--muted)",
              letterSpacing: "0.06em",
              textTransform: "uppercase",
            }}
          >
            Core
          </span>
        </Link>
        <button
          data-history-toggle
          onClick={onToggleHistory}
          style={{
            ...iconBtn,
            color: historyOpen ? "var(--accent-ink)" : "var(--muted)",
            background: historyOpen ? "var(--accent-soft)" : "transparent",
          }}
          aria-label="Histórico de reuniões"
          title="Histórico de reuniões"
        >
          <svg
            width="15"
            height="15"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M3 12a9 9 0 1 0 3-6.7L3 8" />
            <polyline points="3 3 3 8 8 8" />
            <path d="M12 8v4l3 2" />
          </svg>
        </button>
      </div>

      <nav style={{ display: "flex", flexDirection: "column", gap: 1, marginTop: 4 }}>
        {NAV.map((item) => (
          <SideNav key={item.href} item={item} active={isActive(pathname, item.href)} />
        ))}
      </nav>

      <div style={{ flex: 1 }} />

      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 8,
          padding: "8px 4px 2px",
          borderTop: "1px solid var(--border)",
          paddingTop: 14,
          marginTop: 4,
        }}
      >
        <Avatar name={user?.displayName ?? "?"} size={28} />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div
            style={{
              fontSize: 12.5,
              color: "var(--ink)",
              letterSpacing: "-0.005em",
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
            }}
          >
            {user?.displayName ?? "—"}
          </div>
          <div
            style={{
              fontSize: 11,
              color: "var(--muted)",
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
            }}
          >
            {user?.email ?? ""}
          </div>
        </div>
        <Link
          href={"/settings/context" as Route}
          style={{
            ...iconBtn,
            color: isActive(pathname, "/settings/context") ? "var(--accent-ink)" : "var(--muted)",
          }}
          aria-label="Configurações"
          title="Configurações"
        >
          <svg
            width="15"
            height="15"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.6"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <circle cx="12" cy="12" r="3" />
            <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h0a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h0a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
          </svg>
        </Link>
        <button
          onClick={handleSignOut}
          disabled={signingOut}
          style={{ ...iconBtn, opacity: signingOut ? 0.5 : 1 }}
          aria-label="Sair"
          title="Sair"
        >
          <svg
            width="15"
            height="15"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="1.7"
            strokeLinecap="round"
            strokeLinejoin="round"
          >
            <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
            <polyline points="16 17 21 12 16 7" />
            <line x1="21" y1="12" x2="9" y2="12" />
          </svg>
        </button>
      </div>
    </aside>
  );
}

function SideNav({ item, active }: { item: NavItem; active: boolean }) {
  return (
    <Link
      href={item.href}
      style={{
        display: "flex",
        alignItems: "center",
        justifyContent: "space-between",
        padding: "8px 10px",
        background: active ? "var(--accent-soft)" : "transparent",
        borderRadius: 7,
        textDecoration: "none",
        fontFamily: "var(--sans)",
        fontSize: 13,
        color: active ? "var(--accent-ink)" : "var(--ink)",
        letterSpacing: "-0.005em",
      }}
      onMouseEnter={(e) => {
        if (!active) e.currentTarget.style.background = "rgba(0,0,0,0.03)";
      }}
      onMouseLeave={(e) => {
        e.currentTarget.style.background = active ? "var(--accent-soft)" : "transparent";
      }}
    >
      <span>{item.label}</span>
      {item.plus && (
        <span style={{ display: "grid", placeItems: "center", width: 16, height: 16, color: "var(--muted)" }}>
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
            <path d="M12 5v14M5 12h14" />
          </svg>
        </span>
      )}
    </Link>
  );
}

// ---------- History popover ----------

function HistoryPanel({
  meetings,
  error,
  onClose,
}: {
  meetings: MeetingListItem[] | null;
  error: string | null;
  onClose: () => void;
}) {
  const router = useRouter();
  const [query, setQuery] = useState("");
  const panelRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    const onDown = (e: MouseEvent) => {
      const t = e.target as HTMLElement;
      if (panelRef.current && !panelRef.current.contains(t) && !t.closest("[data-history-toggle]")) {
        onClose();
      }
    };
    window.addEventListener("keydown", onKey);
    document.addEventListener("mousedown", onDown);
    return () => {
      window.removeEventListener("keydown", onKey);
      document.removeEventListener("mousedown", onDown);
    };
  }, [onClose]);

  const filtered = useMemo(() => {
    const list = meetings ?? [];
    if (!query.trim()) return list;
    const needle = query.toLowerCase();
    return list.filter((m) =>
      (m.title + " " + (m.tags ?? []).join(" ") + " " + (m.summarySnippet ?? ""))
        .toLowerCase()
        .includes(needle),
    );
  }, [meetings, query]);

  const grouped = useMemo(() => {
    const g: Record<string, MeetingListItem[]> = {};
    for (const m of filtered) (g[groupOf(m.startedAt)] ||= []).push(m);
    return g;
  }, [filtered]);

  function open(id: string) {
    router.push(`/meetings/${id}` as Route);
    onClose();
  }

  return (
    <div
      ref={panelRef}
      style={{
        position: "absolute",
        left: SIDEBAR_WIDTH,
        top: 14,
        width: 320,
        maxHeight: "calc(100vh - 28px)",
        background: "var(--canvas)",
        border: "1px solid var(--border)",
        borderLeft: "none",
        borderRadius: "0 16px 16px 0",
        boxShadow: "0 18px 48px -16px rgba(15, 23, 42, 0.18), 0 4px 12px rgba(15, 23, 42, 0.04)",
        zIndex: 5,
        display: "flex",
        flexDirection: "column",
        padding: 14,
        gap: 12,
        animation: "noraPanelIn 200ms cubic-bezier(.2,.8,.2,1)",
        transformOrigin: "left center",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", justifyContent: "space-between", padding: "0 4px" }}>
        <div style={{ fontSize: 13, fontWeight: 500, color: "var(--ink)" }}>Reuniões recentes</div>
        <button onClick={onClose} style={iconBtn} aria-label="Fechar">
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
            <path d="M18 6L6 18M6 6l12 12" />
          </svg>
        </button>
      </div>

      <div style={{ position: "relative" }}>
        <svg
          width="13"
          height="13"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
          style={{ position: "absolute", left: 10, top: 9, color: "var(--muted)" }}
        >
          <circle cx="11" cy="11" r="7" />
          <path d="m20 20-3.5-3.5" />
        </svg>
        <input
          autoFocus
          value={query}
          onChange={(e) => setQuery(e.target.value)}
          placeholder="Buscar reuniões…"
          style={{
            width: "100%",
            padding: "7px 10px 7px 28px",
            fontSize: 13,
            fontFamily: "var(--sans)",
            background: "var(--sidebar)",
            border: "1px solid var(--border)",
            borderRadius: 7,
            color: "var(--ink)",
            outline: "none",
          }}
        />
      </div>

      <div style={{ flex: 1, overflowY: "auto", margin: "0 -4px", padding: "0 4px" }}>
        {meetings === null && !error && (
          <div style={{ padding: "24px 12px", fontSize: 12.5, color: "var(--muted)", textAlign: "center" }}>
            Carregando…
          </div>
        )}
        {error && (
          <div style={{ padding: "20px 12px", fontSize: 12.5, color: "#a04c3e", textAlign: "center" }}>
            {error}
          </div>
        )}
        {meetings !== null &&
          GROUP_ORDER.map(
            (g) =>
              grouped[g] && (
                <div key={g} style={{ marginBottom: 14 }}>
                  <div
                    style={{
                      fontSize: 10.5,
                      color: "var(--muted)",
                      letterSpacing: "0.08em",
                      padding: "0 8px 6px",
                      textTransform: "uppercase",
                      fontWeight: 500,
                    }}
                  >
                    {g}
                  </div>
                  <div style={{ display: "flex", flexDirection: "column", gap: 1 }}>
                    {grouped[g].map((m) => (
                      <button
                        key={m.id}
                        onClick={() => open(m.id)}
                        style={{
                          textAlign: "left",
                          padding: "8px 10px",
                          background: "transparent",
                          border: "none",
                          borderRadius: 7,
                          cursor: "pointer",
                          fontFamily: "var(--sans)",
                          color: "var(--ink)",
                          display: "flex",
                          flexDirection: "column",
                          gap: 2,
                        }}
                        onMouseEnter={(e) => (e.currentTarget.style.background = "rgba(0,0,0,0.03)")}
                        onMouseLeave={(e) => (e.currentTarget.style.background = "transparent")}
                      >
                        <span style={{ fontSize: 13, lineHeight: 1.3, color: "var(--ink)", letterSpacing: "-0.005em" }}>
                          {m.title}
                        </span>
                        <span style={{ fontSize: 11, color: "var(--muted)" }}>
                          {dayLabel(m.startedAt)}, {fmtTime(m.startedAt)}
                          {fmtDuration(m.durationSeconds) ? ` · ${fmtDuration(m.durationSeconds)}` : ""}
                        </span>
                      </button>
                    ))}
                  </div>
                </div>
              ),
          )}
        {meetings !== null && !error && filtered.length === 0 && (
          <div style={{ padding: "24px 12px", fontSize: 12.5, color: "var(--muted)", textAlign: "center" }}>
            Nenhuma reunião encontrada.
          </div>
        )}
      </div>
    </div>
  );
}

// ---------- Command palette (⌘K) ----------

interface PaletteAction {
  kind: "action" | "meeting";
  id: string;
  label: string;
  sub?: string;
  hint?: string;
  run: () => void;
}

function CommandPalette({
  open,
  meetings,
  onClose,
}: {
  open: boolean;
  meetings: MeetingListItem[] | null;
  onClose: () => void;
}) {
  const router = useRouter();
  const [q, setQ] = useState("");
  const [idx, setIdx] = useState(0);
  const inputRef = useRef<HTMLInputElement>(null);

  useEffect(() => {
    if (open) {
      setQ("");
      setIdx(0);
      const t = setTimeout(() => inputRef.current?.focus(), 30);
      return () => clearTimeout(t);
    }
  }, [open]);

  const items = useMemo<PaletteAction[]>(() => {
    const actions: PaletteAction[] = [
      {
        kind: "action",
        id: "new",
        label: "Nova reunião",
        hint: "N",
        run: () => {
          router.push("/meetings/upload");
          onClose();
        },
      },
      {
        kind: "action",
        id: "tasks",
        label: "Ir pra Action items",
        run: () => {
          router.push("/tasks");
          onClose();
        },
      },
    ];
    const meetingItems: PaletteAction[] = (meetings ?? []).map((m) => ({
      kind: "meeting",
      id: m.id,
      label: m.title,
      sub: `${dayLabel(m.startedAt)}, ${fmtTime(m.startedAt)}${(m.tags ?? [])[0] ? ` · ${m.tags[0]}` : ""}`,
      run: () => {
        router.push(`/meetings/${m.id}` as Route);
        onClose();
      },
    }));
    const all = [...actions, ...meetingItems];
    if (!q.trim()) return all;
    const needle = q.toLowerCase();
    return all.filter((it) => (it.label + " " + (it.sub ?? "")).toLowerCase().includes(needle));
  }, [q, meetings, router, onClose]);

  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      else if (e.key === "ArrowDown") {
        e.preventDefault();
        setIdx((i) => Math.min(i + 1, items.length - 1));
      } else if (e.key === "ArrowUp") {
        e.preventDefault();
        setIdx((i) => Math.max(i - 1, 0));
      } else if (e.key === "Enter") {
        e.preventDefault();
        items[idx]?.run();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, items, idx, onClose]);

  if (!open) return null;

  return (
    <div
      onClick={onClose}
      style={{
        position: "fixed",
        inset: 0,
        zIndex: 50,
        background: "rgba(20, 22, 26, 0.18)",
        display: "flex",
        alignItems: "flex-start",
        justifyContent: "center",
        paddingTop: "12vh",
        backdropFilter: "blur(2px)",
        animation: "noraPaletteFadeIn 150ms ease",
      }}
    >
      <div
        onClick={(e) => e.stopPropagation()}
        style={{
          width: "min(620px, 92vw)",
          background: "var(--canvas)",
          border: "1px solid var(--border)",
          borderRadius: 14,
          boxShadow: "0 24px 60px -20px rgba(15, 23, 42, 0.28), 0 4px 14px rgba(15, 23, 42, 0.06)",
          overflow: "hidden",
          display: "flex",
          flexDirection: "column",
          maxHeight: "70vh",
          animation: "noraPaletteSlideIn 180ms cubic-bezier(.2,.8,.2,1)",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 10, padding: "12px 16px", borderBottom: "1px solid var(--border)" }}>
          <svg
            width="14"
            height="14"
            viewBox="0 0 24 24"
            fill="none"
            stroke="currentColor"
            strokeWidth="2"
            strokeLinecap="round"
            strokeLinejoin="round"
            style={{ color: "var(--muted)" }}
          >
            <circle cx="11" cy="11" r="7" />
            <path d="m20 20-3.5-3.5" />
          </svg>
          <input
            ref={inputRef}
            value={q}
            onChange={(e) => {
              setQ(e.target.value);
              setIdx(0);
            }}
            placeholder="Buscar reuniões, comandos…"
            style={{
              flex: 1,
              border: "none",
              outline: "none",
              fontFamily: "var(--sans)",
              fontSize: 14.5,
              color: "var(--ink)",
              background: "transparent",
            }}
          />
          <Kbd>esc</Kbd>
        </div>
        <div style={{ flex: 1, overflowY: "auto", padding: 6 }}>
          {items.length === 0 && (
            <div style={{ padding: "28px 16px", textAlign: "center", color: "var(--muted)", fontSize: 13 }}>
              Nada encontrado pra “{q}”.
            </div>
          )}
          {items.map((it, i) => (
            <button
              key={it.kind + ":" + it.id}
              onMouseEnter={() => setIdx(i)}
              onClick={() => it.run()}
              style={{
                width: "100%",
                textAlign: "left",
                display: "flex",
                alignItems: "center",
                gap: 12,
                padding: "9px 12px",
                borderRadius: 8,
                background: idx === i ? "var(--sidebar)" : "transparent",
                border: "none",
                cursor: "pointer",
                fontFamily: "var(--sans)",
                color: "var(--ink)",
              }}
            >
              <span
                style={{
                  width: 22,
                  height: 22,
                  display: "grid",
                  placeItems: "center",
                  background: "var(--chip)",
                  color: "var(--muted)",
                  borderRadius: 6,
                  flexShrink: 0,
                }}
              >
                {it.kind === "meeting" ? (
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <rect x="3" y="4" width="18" height="18" rx="2" />
                    <path d="M16 2v4M8 2v4M3 10h18" />
                  </svg>
                ) : (
                  <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <path d="M12 5v14M5 12h14" />
                  </svg>
                )}
              </span>
              <span style={{ flex: 1, minWidth: 0, display: "flex", flexDirection: "column", gap: 2 }}>
                <span style={{ fontSize: 13.5, color: "var(--ink)", whiteSpace: "nowrap", overflow: "hidden", textOverflow: "ellipsis" }}>
                  {it.label}
                </span>
                {it.sub && <span style={{ fontSize: 11.5, color: "var(--muted)" }}>{it.sub}</span>}
              </span>
              {it.hint && <Kbd>{it.hint}</Kbd>}
            </button>
          ))}
        </div>
      </div>
    </div>
  );
}
