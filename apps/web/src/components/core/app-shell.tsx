"use client";

/**
 * NORA Core — shell do app (sidebar chat-first + área principal).
 *
 * Porte do protótipo do Claude Design (app.jsx) pro Next: navegação por rotas
 * (Link + usePathname), perfil do usuário a partir do cookie de sessão, logout.
 *
 * Nav do Core (Enterprise — IAM, contexto de tenant — fica fora daqui; é gateado
 * pra Enterprise, conforme a fronteira Core/Enterprise da vision).
 */
import Link from "next/link";
import type { Route } from "next";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useMemo, useState, type ReactNode } from "react";

import { NoraLogo } from "@/components/brand/nora-logo";
import { clearSession, getCurrentUser, type SessionUser } from "@/lib/auth";

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
function PlugIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M9 2v6M15 2v6M7 8h10v3a5 5 0 0 1-10 0z" />
      <path d="M12 16v6" />
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

const NAV: NavItem[] = [
  { label: "Início", href: "/dashboard" as Route, icon: <HomeIcon />, matchPrefixes: ["/dashboard", "/meetings"] },
  { label: "Nova sessão", href: "/chat" as Route, icon: <ChatIcon />, plus: true },
  { label: "Projetos", href: "/projetos" as Route, icon: <ProjectsIcon /> },
  { label: "Action items", href: "/tasks" as Route, icon: <TasksIcon /> },
  { label: "Integrações", href: "/integracoes" as Route, icon: <PlugIcon />, hint: "MCP" },
];

function UserOrb() {
  return (
    <div
      aria-hidden
      style={{
        width: 28,
        height: 28,
        borderRadius: "50%",
        flexShrink: 0,
        background:
          "radial-gradient(circle at 30% 25%, #d8f0f6 0%, transparent 38%), radial-gradient(circle at 78% 62%, #e8b8d8 0%, transparent 45%), radial-gradient(circle at 22% 78%, #3a4a4f 0%, transparent 30%), radial-gradient(circle at 60% 40%, #4ec4d8 8%, #6aa8d8 45%, #9778b8 100%)",
        boxShadow: "inset 0 0 6px rgba(255,255,255,0.4), 0 1px 2px rgba(0,0,0,0.06)",
        filter: "saturate(1.15)",
      }}
    />
  );
}

export function AppShell({ children }: { children: ReactNode }) {
  const pathname = usePathname();
  const router = useRouter();
  const [user, setUser] = useState<SessionUser | null>(null);
  const [loggingOut, setLoggingOut] = useState(false);

  useEffect(() => {
    setUser(getCurrentUser());
  }, []);

  const isActive = useMemo(
    () => (item: NavItem) => {
      const prefixes = item.matchPrefixes ?? [item.href];
      return prefixes.some((p) => pathname === p || pathname.startsWith(`${p}/`));
    },
    [pathname],
  );

  async function handleLogout() {
    setLoggingOut(true);
    await clearSession();
    router.push("/auth/login" as Route);
    router.refresh();
  }

  const sidebar = (
    <aside
      style={{
        width: 248,
        flexShrink: 0,
        height: "100dvh",
        background: "var(--sidebar)",
        borderRight: "1px solid var(--border)",
        display: "flex",
        flexDirection: "column",
        padding: "18px 14px 14px",
        gap: 16,
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "0 4px" }}>
        <Link href={"/chat" as Route} aria-label="NORA — nova sessão" style={{ display: "inline-flex", alignItems: "center", gap: 10 }}>
          <NoraLogo size={20} animate={false} />
        </Link>
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
      </div>

      <nav style={{ display: "flex", flexDirection: "column", gap: 1, marginTop: 4 }}>
        {NAV.map((item) => {
          const active = isActive(item);
          return (
            <Link
              key={item.href}
              href={item.href}
              style={{
                display: "flex",
                alignItems: "center",
                gap: 10,
                padding: "8px 10px",
                background: active ? "var(--accent-soft)" : "transparent",
                borderRadius: 7,
                fontSize: 13,
                color: active ? "var(--accent-ink)" : "var(--ink)",
                letterSpacing: "-0.005em",
                transition: "background 120ms ease",
              }}
            >
              <span style={{ display: "grid", placeItems: "center", width: 18, color: active ? "var(--accent-ink)" : "var(--muted)" }}>
                {item.icon}
              </span>
              <span style={{ flex: 1 }}>{item.label}</span>
              {item.plus && (
                <span style={{ display: "grid", placeItems: "center", width: 16, height: 16, color: "var(--muted)" }}>
                  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
                    <path d="M12 5v14M5 12h14" />
                  </svg>
                </span>
              )}
              {item.hint && (
                <span style={{ fontSize: 10, color: "var(--muted)", letterSpacing: "0.06em" }}>{item.hint}</span>
              )}
            </Link>
          );
        })}
      </nav>

      <div style={{ flex: 1 }} />

      <div
        style={{
          display: "flex",
          alignItems: "center",
          gap: 8,
          padding: "14px 4px 2px",
          borderTop: "1px solid var(--border)",
        }}
      >
        <UserOrb />
        <div style={{ flex: 1, minWidth: 0 }}>
          <div style={{ fontSize: 12.5, color: "var(--ink)", letterSpacing: "-0.005em", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {user?.displayName ?? "Carregando…"}
          </div>
          <div style={{ fontSize: 11, color: "var(--muted)", overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>
            {user?.email ? `${user.email} · Core` : "—"}
          </div>
        </div>
        <Link
          href={"/settings/context" as Route}
          aria-label="Configurações"
          title="Configurações"
          style={{ width: 28, height: 28, display: "grid", placeItems: "center", borderRadius: 6, color: "var(--muted)" }}
        >
          <SettingsIcon />
        </Link>
        <button
          type="button"
          onClick={handleLogout}
          disabled={loggingOut}
          aria-label="Sair"
          title="Sair"
          style={{ width: 28, height: 28, display: "grid", placeItems: "center", borderRadius: 6, color: "var(--muted)", background: "transparent", border: "none", cursor: "pointer" }}
        >
          <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
            <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
            <path d="M16 17l5-5-5-5M21 12H9" />
          </svg>
        </button>
      </div>
    </aside>
  );

  return (
    <div style={{ display: "flex", height: "100dvh", background: "var(--canvas)", color: "var(--ink)" }}>
      <div className="hidden md:flex">{sidebar}</div>

      <main style={{ flex: 1, minWidth: 0, height: "100dvh", overflowY: "auto" }}>
        {children}
      </main>
    </div>
  );
}
