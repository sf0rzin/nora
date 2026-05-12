"use client";

import { useEffect } from "react";

/**
 * Reveal on scroll — adiciona classe `.in` em elementos com `.reveal`
 * quando entram no viewport, disparando transição opacity/transform.
 *
 * Portado de nora-app.jsx (linhas 13-27). Observer disconnect no cleanup.
 */
export function useReveal(): void {
  useEffect(() => {
    if (typeof window === "undefined") return;
    const els = document.querySelectorAll<HTMLElement>(".reveal");
    const io = new IntersectionObserver(
      (entries) => {
        entries.forEach((e) => {
          if (e.isIntersecting) {
            e.target.classList.add("in");
            io.unobserve(e.target);
          }
        });
      },
      { threshold: 0.15, rootMargin: "0px 0px -60px 0px" },
    );
    els.forEach((el) => io.observe(el));
    return () => io.disconnect();
  }, []);
}
