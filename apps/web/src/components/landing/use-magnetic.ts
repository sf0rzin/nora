"use client";

import { useEffect } from "react";

/**
 * Magnetic cursor — em elementos com `.magnetic`, mousemove translada o
 * elemento sutilmente em direção ao cursor (factor 0.10).
 *
 * Portado de nora-app.jsx (linhas 29-52). Listeners limpos no cleanup.
 */
export function useMagnetic(): void {
  useEffect(() => {
    if (typeof window === "undefined") return;
    const handlers = new Map<
      HTMLElement,
      { onMove: (e: MouseEvent) => void; onLeave: () => void }
    >();
    const els = document.querySelectorAll<HTMLElement>(".magnetic");
    els.forEach((el) => {
      const onMove = (e: MouseEvent): void => {
        const r = el.getBoundingClientRect();
        const dx = e.clientX - (r.left + r.width / 2);
        const dy = e.clientY - (r.top + r.height / 2);
        el.style.transform = `translate(${dx * 0.1}px, ${dy * 0.1}px)`;
      };
      const onLeave = (): void => {
        el.style.transform = "";
      };
      el.addEventListener("mousemove", onMove);
      el.addEventListener("mouseleave", onLeave);
      handlers.set(el, { onMove, onLeave });
    });
    return () => {
      handlers.forEach((h, el) => {
        el.removeEventListener("mousemove", h.onMove);
        el.removeEventListener("mouseleave", h.onLeave);
      });
    };
  });
}
