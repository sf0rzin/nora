import { useAuth } from "@/hooks/use-auth";
import { NoraLogo } from "@/components/brand/nora-logo";
import { Avatar } from "@/components/brand/avatar";
import { useState, useEffect } from "react";

interface NavItem {
  label: string;
  hash: string;
  icon: React.ReactElement;
}

const ICON_CHAT = (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    <path d="M21 15a2 2 0 0 1-2 2H7l-4 4V5a2 2 0 0 1 2-2h14a2 2 0 0 1 2 2z" />
  </svg>
);

const ICON_MEETINGS = (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    <rect x="3" y="4" width="18" height="18" rx="2" />
    <path d="M16 2v4M8 2v4M3 10h18" />
  </svg>
);

const ICON_GEAR = (
  <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
    <circle cx="12" cy="12" r="3" />
    <path d="M19.4 15a1.65 1.65 0 0 0 .33 1.82l.06.06a2 2 0 1 1-2.83 2.83l-.06-.06a1.65 1.65 0 0 0-1.82-.33 1.65 1.65 0 0 0-1 1.51V21a2 2 0 1 1-4 0v-.09a1.65 1.65 0 0 0-1-1.51 1.65 1.65 0 0 0-1.82.33l-.06.06a2 2 0 1 1-2.83-2.83l.06-.06a1.65 1.65 0 0 0 .33-1.82 1.65 1.65 0 0 0-1.51-1H3a2 2 0 1 1 0-4h.09a1.65 1.65 0 0 0 1.51-1 1.65 1.65 0 0 0-.33-1.82l-.06-.06a2 2 0 1 1 2.83-2.83l.06.06a1.65 1.65 0 0 0 1.82.33h0a1.65 1.65 0 0 0 1-1.51V3a2 2 0 1 1 4 0v.09a1.65 1.65 0 0 0 1 1.51h0a1.65 1.65 0 0 0 1.82-.33l.06-.06a2 2 0 1 1 2.83 2.83l-.06.06a1.65 1.65 0 0 0-.33 1.82v0a1.65 1.65 0 0 0 1.51 1H21a2 2 0 1 1 0 4h-.09a1.65 1.65 0 0 0-1.51 1z" />
  </svg>
);

const ICON_SIGNOUT = (
  <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
    <path d="M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4" />
    <polyline points="16 17 21 12 16 7" />
    <line x1="21" y1="12" x2="9" y2="12" />
  </svg>
);

const NAV_ITEMS: NavItem[] = [
  { label: "Conversar", hash: "#/chat", icon: ICON_CHAT },
  { label: "Reuniões", hash: "#/meetings", icon: ICON_MEETINGS },
];

export function Sidebar() {
  const { user, logout } = useAuth();
  const [route, setRoute] = useState(window.location.hash || "#/meetings");

  useEffect(() => {
    const handler = () => setRoute(window.location.hash || "#/meetings");
    window.addEventListener("hashchange", handler);
    return () => window.removeEventListener("hashchange", handler);
  }, []);

  const isActive = (hash: string) => {
    if (hash === "#/meetings") return route === "" || route.startsWith("#/meetings");
    return route.startsWith(hash);
  };
  const settingsActive = route.startsWith("#/settings");

  return (
    <aside
      className="h-full flex flex-col shrink-0 relative"
      style={{
        width: 248,
        background: "var(--sidebar)",
        borderRight: "1px solid var(--border)",
        padding: "18px 14px 14px",
      }}
    >
      <div className="flex items-center justify-between px-1 mb-4">
        <a
          href="#/chat"
          aria-label="Conversar com a NORA"
          title="Conversar com a NORA"
          className="inline-flex items-center"
          style={{
            margin: "-4px -6px",
            padding: "4px 6px",
            borderRadius: 7,
            background: "transparent",
            border: "none",
            cursor: "pointer",
            textDecoration: "none",
          }}
          onMouseEnter={(e) =>
            (e.currentTarget.style.background = "rgba(0,0,0,0.04)")
          }
          onMouseLeave={(e) =>
            (e.currentTarget.style.background = "transparent")
          }
        >
          <NoraLogo animate />
        </a>
        <span
          className="px-1.5 py-0.5 rounded text-[9.5px] font-medium uppercase tracking-[0.06em]"
          style={{
            color: "var(--muted)",
            border: "1px solid var(--border)",
            background: "var(--canvas)",
          }}
        >
          Core
        </span>
      </div>

      <nav className="flex flex-col gap-px">
        {NAV_ITEMS.map((item) => {
          const active = isActive(item.hash);
          return (
            <a
              key={item.hash}
              href={item.hash}
              className="flex items-center gap-2.5 px-2.5 py-2 rounded-md text-[13px] transition-colors"
              style={{
                background: active ? "var(--accent-soft)" : "transparent",
                color: active ? "var(--accent-ink)" : "var(--ink)",
                letterSpacing: "-0.005em",
              }}
              onMouseEnter={(e) => {
                if (!active) e.currentTarget.style.background = "rgba(0,0,0,0.03)";
              }}
              onMouseLeave={(e) => {
                e.currentTarget.style.background = active
                  ? "var(--accent-soft)"
                  : "transparent";
              }}
            >
              <span className="opacity-80">{item.icon}</span>
              {item.label}
            </a>
          );
        })}
      </nav>

      <div className="flex-1" />

      <div
        className="flex items-center gap-2 pt-3.5 px-1"
        style={{ borderTop: "1px solid var(--border)" }}
      >
        <Avatar name={user?.displayName || "Você"} size={28} me />
        <div className="flex-1 min-w-0">
          <div
            className="truncate"
            style={{
              fontSize: 12.5,
              color: "var(--ink)",
              letterSpacing: "-0.005em",
            }}
          >
            {user?.displayName || "Usuário"}
          </div>
          <div
            className="truncate"
            style={{ fontSize: 11, color: "var(--muted)" }}
          >
            {user?.email || ""}
          </div>
        </div>
        <a
          href="#/settings"
          aria-label="Configurações"
          title="Configurações"
          className="grid place-items-center w-7 h-7 rounded-md transition-colors"
          style={{
            color: settingsActive ? "var(--accent-ink)" : "var(--muted)",
            background: settingsActive ? "var(--accent-soft)" : "transparent",
          }}
          onMouseEnter={(e) => {
            if (!settingsActive)
              e.currentTarget.style.background = "rgba(0,0,0,0.04)";
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.background = settingsActive
              ? "var(--accent-soft)"
              : "transparent";
          }}
        >
          {ICON_GEAR}
        </a>
        <button
          onClick={logout}
          aria-label="Sair"
          title="Sair"
          className="grid place-items-center w-7 h-7 rounded-md transition-colors"
          style={{ color: "var(--muted)", background: "transparent", border: "none", cursor: "pointer" }}
          onMouseEnter={(e) => {
            e.currentTarget.style.background = "rgba(0,0,0,0.04)";
            e.currentTarget.style.color = "var(--ink)";
          }}
          onMouseLeave={(e) => {
            e.currentTarget.style.background = "transparent";
            e.currentTarget.style.color = "var(--muted)";
          }}
        >
          {ICON_SIGNOUT}
        </button>
      </div>
    </aside>
  );
}
