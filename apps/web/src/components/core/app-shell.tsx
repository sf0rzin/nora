"use client";

/**
 * NORA Core — app shell (chat-first sidebar + main area).
 *
 * Visual port of the v3 design prototype (shell.js) to Next: route-based
 * navigation (Link + usePathname), user profile from the session cookie,
 * logout, ⌘K command palette with semantic search and mobile drawer.
 *
 * Core nav (Enterprise — IAM, tenant context — stays out of here; it is gated
 * to Enterprise, per the Core/Enterprise boundary in the vision).
 *
 * Deliberate deviations from the default shell:
 *  - the sidebar logo is only the "Nora" wordmark (no soundwave);
 *  - "Integrações" leaves the main nav and becomes the "Conectores" category;
 *  - the "Sessões" block uses `.side-sec-label--tight` (label pinned close).
 */
import Link from "next/link";
import type { Route } from "next";
import { usePathname, useRouter } from "next/navigation";
import { DesktopUpdateButton } from "./desktop-update-button";
import { Suspense, useCallback, useEffect, useMemo, useState, type ReactNode } from "react";

import { SESSION_USER_EVENT, clearSession, getCurrentUser, type SessionUser } from "@/lib/auth";

import { AppSidebarSessions } from "./app-sidebar-sessions";
import { Avatar } from "./avatar";
import { CommandPalette } from "./command-palette";

type NavItem = {
  label: string;
  href: Route;
  icon: ReactNode;
  plus?: boolean;
  hint?: string;
  matchPrefixes?: string[];
};

function HomeIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 10.5 12 3l9 7.5" />
      <path d="M5 9.5V21h14V9.5" />
    </svg>
  );
}
function ChatIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 15a2 2 0 0 1-2 2H8l-5 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
    </svg>
  );
}
function ProjectsIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M3 7a2 2 0 0 1 2-2h4l2 2h8a2 2 0 0 1 2 2v8a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z" />
    </svg>
  );
}
function TasksIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M9 11l3 3L22 4" />
      <path d="M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11" />
    </svg>
  );
}
function FlowsIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <rect x="3" y="3" width="7" height="7" rx="1.5" />
      <rect x="14" y="14" width="7" height="7" rx="1.5" />
      <path d="M10 6.5h5.5a2 2 0 0 1 2 2V14" />
    </svg>
  );
}
function PlugIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M9 2v6M15 2v6M7 8h10v3a5 5 0 0 1-10 0z" />
      <path d="M12 16v6" />
    </svg>
  );
}
function PlusIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
      <path d="M12 5v14M5 12h14" />
    </svg>
  );
}
function SettingsIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="12" cy="12" r="3" />
      <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33H7a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51 1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82V9a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
    </svg>
  );
}
function LogoutIcon() {
  return (
    <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
      <path d="M16 17l5-5-5-5M21 12H9" />
    </svg>
  );
}
function BurgerIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
      <path d="M4 7h16M4 12h16M4 17h16" />
    </svg>
  );
}
function CloseIcon() {
  return (
    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  );
}
function SearchIcon() {
  return (
    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <circle cx="11" cy="11" r="7" />
      <path d="m20 20-3.5-3.5" />
    </svg>
  );
}

/** "Nora" wordmark — no soundwave, by design. DM Sans 500, -0.02em. */
function NoraWordmark() {
  return (
    <span
      role="img"
      aria-label="Nora"
      style={{
        fontFamily: "var(--font-sans), system-ui, sans-serif",
        fontWeight: 500,
        fontSize: 19,
        letterSpacing: "-0.02em",
        lineHeight: 1,
        color: "var(--ink)",
      }}
    >
      Nora
    </span>
  );
}

const NAV: NavItem[] = [
  { label: "Início", href: "/dashboard" as Route, icon: <HomeIcon />, matchPrefixes: ["/dashboard", "/meetings"] },
  { label: "Nova sessão", href: "/chat" as Route, icon: <ChatIcon />, plus: true },
  { label: "Projetos", href: "/projects" as Route, icon: <ProjectsIcon /> },
  { label: "Action items", href: "/tasks" as Route, icon: <TasksIcon /> },
  { label: "Fluxos", href: "/flows" as Route, icon: <FlowsIcon />, matchPrefixes: ["/flows"] },
];

const CONNECTORS: NavItem = {
  label: "Integrações",
  href: "/integrations" as Route,
  icon: <PlugIcon />,
  hint: "MCP",
};

export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [user, setUser] = useState<SessionUser | null>(null);
  const [loggingOut, setLoggingOut] = useState(false);
  const [drawerOpen, setDrawerOpen] = useState(false);
  const [paletteOpen, setPaletteOpen] = useState(false);

  useEffect(() => {
    setUser(getCurrentUser());
    // Profile edited in settings (displayName) → sidebar reflects it right away.
    const onUserUpdated = () => setUser(getCurrentUser());
    window.addEventListener(SESSION_USER_EVENT, onUserUpdated);
    return () => window.removeEventListener(SESSION_USER_EVENT, onUserUpdated);
  }, []);

  // Closes the drawer on route change (link navigation from inside it).
  useEffect(() => {
    setDrawerOpen(false);
  }, [pathname]);

  const isActive = useMemo(
    () => (item: NavItem) => {
      const prefixes = item.matchPrefixes ?? [item.href];
      return prefixes.some((p) => pathname === p || pathname.startsWith(`${p}/`));
    },
    [pathname],
  );

  const handleLogout = useCallback(async () => {
    setLoggingOut(true);
    await clearSession();
    router.push("/auth/login" as Route);
    router.refresh();
  }, [router]);

  const togglePalette = useCallback(() => setPaletteOpen((v) => !v), []);
  const openPalette = useCallback(() => setPaletteOpen(true), []);
  const closePalette = useCallback(() => setPaletteOpen(false), []);

  // Global shortcuts (§3.9): ⌘K opens/closes the palette; N → new meeting;
  // / → search (focus the #search-input if it exists, otherwise open the palette).
  useEffect(() => {
    function onKeyDown(e: KeyboardEvent) {
      const target = e.target as HTMLElement | null;
      const tag = (target?.tagName ?? "").toLowerCase();
      const typing = tag === "input" || tag === "textarea" || tag === "select" || target?.isContentEditable === true;

      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        togglePalette();
        return;
      }
      if (typing) return;
      if (e.key.toLowerCase() === "n") {
        e.preventDefault();
        router.push("/meetings/upload" as Route);
      } else if (e.key === "/") {
        e.preventDefault();
        const s = document.getElementById("search-input") as HTMLInputElement | null;
        if (s) {
          s.focus();
          s.select?.();
        } else {
          openPalette();
        }
      }
    }
    document.addEventListener("keydown", onKeyDown);
    return () => document.removeEventListener("keydown", onKeyDown);
  }, [togglePalette, openPalette, router]);

  function NavLink({ item, onNavigate }: { item: NavItem; onNavigate?: () => void }) {
    const active = isActive(item);
    return (
      <Link
        href={item.href}
        className={`side-link${active ? " is-active" : ""}`}
        onClick={onNavigate}
      >
        <span className="icon">{item.icon}</span>
        <span className="grow">{item.label}</span>
        {item.plus && (
          <span className="icon">
            <PlusIcon />
          </span>
        )}
        {item.hint && <span className="hint">{item.hint}</span>}
      </Link>
    );
  }

  const userBlock = (
    <div className="side-user">
      <Avatar seed={user?.email} size={28} />
      <div className="meta">
        <div className="name">{user?.displayName ?? "Carregando…"}</div>
        <div className="mail">{user?.email ? `${user.email} · Core` : "—"}</div>
      </div>
      <Link className="icon-btn" href={"/settings/context" as Route} aria-label="Configurações" title="Configurações">
        <SettingsIcon />
      </Link>
      <button type="button" className="icon-btn" onClick={handleLogout} disabled={loggingOut} aria-label="Sair" title="Sair">
        <LogoutIcon />
      </button>
    </div>
  );

  /** Content shared between the desktop sidebar and the mobile drawer. */
  function SidebarBody({ onNavigate }: { onNavigate?: () => void }) {
    return (
      <>
        <nav className="side-nav">
          {NAV.map((item) => (
            <NavLink key={item.href} item={item} onNavigate={onNavigate} />
          ))}
        </nav>

        <div>
          <div className="side-sec-label">Conectores</div>
          <NavLink item={CONNECTORS} onNavigate={onNavigate} />
        </div>

        <Suspense fallback={null}>
          <AppSidebarSessions />
        </Suspense>

        <div style={{ flex: 1 }} />

        {/* Only renders inside Nora Desktop when there is a signed update. */}
        <DesktopUpdateButton />

        {userBlock}
      </>
    );
  }

  // Routes that lock the viewport (fixed composer + their own inner scroll).
  // The other pages scroll on the document — see "Shell" in components.css.
  const viewportLocked = pathname === "/chat" || pathname.startsWith("/chat/");

  return (
    <div className={`app${viewportLocked ? " app--viewport" : ""}`}>
      <aside className="side">
        <div className="side-brand">
          <Link href={"/chat" as Route} aria-label="Nora — nova sessão" style={{ display: "inline-flex", alignItems: "center" }}>
            <NoraWordmark />
          </Link>
          <span className="plan-badge">Core</span>
        </div>
        <SidebarBody />
      </aside>

      <main className="app-main">
        <div className="mhead">
          <button type="button" className="mhead-btn" aria-label="Abrir menu" onClick={() => setDrawerOpen(true)}>
            <BurgerIcon />
          </button>
          <Link href={"/dashboard" as Route} aria-label="Nora" style={{ display: "inline-flex", alignItems: "center" }}>
            <NoraWordmark />
          </Link>
          <span className="grow" />
          <button type="button" className="mhead-btn" aria-label="Buscar" onClick={openPalette}>
            <SearchIcon />
          </button>
        </div>

        {children}
      </main>

      {/* Mobile drawer */}
      <div
        className={`drawer-veil${drawerOpen ? " is-open" : ""}`}
        onClick={() => setDrawerOpen(false)}
        aria-hidden
      />
      <nav className={`drawer${drawerOpen ? " is-open" : ""}`} aria-label="Navegação">
        <div className="side-brand" style={{ justifyContent: "space-between" }}>
          <span style={{ display: "inline-flex", alignItems: "center", gap: 8 }}>
            <NoraWordmark />
            <span className="plan-badge">Core</span>
          </span>
          <button
            type="button"
            className="icon-btn"
            aria-label="Fechar menu"
            onClick={() => setDrawerOpen(false)}
            style={{ width: 40, height: 40 }}
          >
            <CloseIcon />
          </button>
        </div>
        <SidebarBody onNavigate={() => setDrawerOpen(false)} />
      </nav>

      <CommandPalette open={paletteOpen} onClose={closePalette} />
    </div>
  );
}
