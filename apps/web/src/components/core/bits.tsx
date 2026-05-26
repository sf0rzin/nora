"use client";

/**
 * Peças JSX de apresentação do Core (avatar com iniciais, tecla). As helpers
 * puras (datas, status, cores) ficam em `./format` pra serem usadas também no
 * server component do inbox. Porto de `project/home.jsx` do Claude Design.
 */

import { colorOf, initialsOf } from "./format";

export function Avatar({ name, size = 22 }: { name: string; size?: number }) {
  return (
    <div
      title={name}
      style={{
        width: size,
        height: size,
        borderRadius: "50%",
        background: colorOf(name),
        color: "rgba(0,0,0,0.62)",
        boxShadow: "0 0 0 2px var(--canvas)",
        fontSize: size * 0.4,
        fontWeight: 500,
        display: "grid",
        placeItems: "center",
        flexShrink: 0,
      }}
    >
      {initialsOf(name)}
    </div>
  );
}

export function Kbd({ children }: { children: React.ReactNode }) {
  return (
    <kbd
      style={{
        fontFamily: "var(--sans)",
        fontSize: 10.5,
        padding: "1px 6px",
        border: "1px solid var(--border)",
        borderRadius: 4,
        background: "var(--canvas)",
        color: "var(--muted)",
        letterSpacing: "0.02em",
      }}
    >
      {children}
    </kbd>
  );
}
