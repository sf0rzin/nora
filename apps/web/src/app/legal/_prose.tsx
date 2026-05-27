import type { ReactNode } from "react";

/** Primitivas tipográficas compartilhadas pelas páginas legais (editorial). */

export function LegalTitle({ children, updated }: { children: ReactNode; updated: string }) {
  return (
    <header style={{ marginBottom: 28 }}>
      <h1
        style={{
          fontFamily: "var(--display)",
          fontSize: 34,
          fontWeight: 500,
          letterSpacing: "-0.02em",
          margin: 0,
          color: "var(--ink)",
        }}
      >
        {children}
      </h1>
      <p style={{ fontSize: 12.5, color: "var(--muted)", marginTop: 8 }}>
        Última atualização: {updated}
      </p>
    </header>
  );
}

export function H2({ children }: { children: ReactNode }) {
  return (
    <h2
      style={{
        fontSize: 16,
        fontWeight: 600,
        color: "var(--ink)",
        margin: "30px 0 10px",
        letterSpacing: "-0.01em",
      }}
    >
      {children}
    </h2>
  );
}

export function P({ children }: { children: ReactNode }) {
  return (
    <p style={{ fontSize: 14.5, lineHeight: 1.7, color: "var(--ink)", margin: "0 0 12px" }}>
      {children}
    </p>
  );
}

export function UL({ children }: { children: ReactNode }) {
  return (
    <ul
      style={{
        margin: "0 0 12px",
        paddingLeft: 20,
        fontSize: 14.5,
        lineHeight: 1.7,
        color: "var(--ink)",
        display: "flex",
        flexDirection: "column",
        gap: 4,
      }}
    >
      {children}
    </ul>
  );
}
