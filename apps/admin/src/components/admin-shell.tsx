"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import type { ReactNode } from "react";

import type { Operator } from "@/lib/operator";

const NAV = [
  { href: "/", label: "Visão geral" },
  { href: "/modelos", label: "Modelos & IA" },
  { href: "/telemetria", label: "Telemetria" },
];

export function AdminShell({ operator, children }: { operator: Operator; children: ReactNode }) {
  const pathname = usePathname();

  return (
    <div style={{ display: "flex", minHeight: "100dvh", background: "var(--canvas)", color: "var(--ink)" }}>
      <aside
        style={{
          width: 244,
          flexShrink: 0,
          background: "var(--sidebar)",
          borderRight: "1px solid var(--border)",
          display: "flex",
          flexDirection: "column",
          padding: "18px 14px 14px",
          gap: 16,
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: 8, padding: "0 4px" }}>
          <SoundwaveMark />
          <span style={{ fontFamily: "var(--display)", fontSize: 15, fontWeight: 600, letterSpacing: "-0.01em" }}>Nora</span>
          <span
            style={{
              fontSize: 9.5,
              padding: "2px 6px",
              border: "1px solid var(--border-strong)",
              borderRadius: 4,
              color: "var(--accent-ink)",
              letterSpacing: "0.06em",
              textTransform: "uppercase",
            }}
          >
            Operador
          </span>
        </div>

        <nav style={{ display: "flex", flexDirection: "column", gap: 1, marginTop: 4 }}>
          {NAV.map((item) => {
            const active = item.href === "/" ? pathname === "/" : pathname.startsWith(item.href);
            return (
              <Link
                key={item.href}
                href={item.href}
                style={{
                  padding: "8px 10px",
                  borderRadius: 7,
                  fontSize: 13,
                  color: active ? "var(--accent-ink)" : "var(--ink)",
                  background: active ? "var(--accent-soft)" : "transparent",
                  letterSpacing: "-0.005em",
                }}
              >
                {item.label}
              </Link>
            );
          })}
        </nav>

        <div style={{ flex: 1 }} />

        <div style={{ padding: "14px 6px 2px", borderTop: "1px solid var(--border)", fontSize: 11.5, color: "var(--muted)", lineHeight: 1.5 }}>
          <div style={{ color: "var(--ink)", fontSize: 12.5 }}>{operator.name}</div>
          <div style={{ overflow: "hidden", textOverflow: "ellipsis", whiteSpace: "nowrap" }}>{operator.email}</div>
          <div style={{ marginTop: 6, display: "inline-flex", alignItems: "center", gap: 6 }}>
            <span
              style={{
                width: 6,
                height: 6,
                borderRadius: "50%",
                background: operator.authenticated ? "var(--success)" : "var(--warn)",
              }}
            />
            {operator.authenticated ? "Entra ID" : "dev (sem Easy Auth)"}
          </div>
        </div>
      </aside>

      <main style={{ flex: 1, minWidth: 0, overflowY: "auto" }}>{children}</main>
    </div>
  );
}

function SoundwaveMark() {
  const heights = [50, 75, 100, 75, 50];
  return (
    <span style={{ display: "inline-flex", alignItems: "center", gap: 2, height: 18 }} aria-hidden>
      {heights.map((h, i) => (
        <span
          key={i}
          style={{ width: 2.5, height: `${h}%`, background: "var(--ink)", borderRadius: 2, opacity: i === 2 ? 1 : 0.85 }}
        />
      ))}
    </span>
  );
}
