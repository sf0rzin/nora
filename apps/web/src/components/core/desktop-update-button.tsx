"use client";

/**
 * Sidebar "Atualização disponível" button — only shows up inside Nora Desktop
 * (Tauri shell) when a new signed version has been published. In a plain browser
 * (nora.systems opened in Chrome/etc.) the component renders nothing.
 *
 * The Desktop loads this remote web UI with `withGlobalTauri` on and a scoped
 * capability (capabilities/updater-remote.json) that releases ONLY the updater
 * and the restart to the nora.systems origin. Endpoints and pubkey are fixed in
 * the app's tauri.conf.json, so remote content cannot point the update
 * somewhere else — the signature check is the guarantee.
 *
 * Flow: check() on mount → if there is an update, show the button → on click,
 * downloadAndInstall() with progress → relaunch().
 */
import { useEffect, useRef, useState } from "react";
import type { Update, DownloadEvent } from "@tauri-apps/plugin-updater";

// With withGlobalTauri the app injects __TAURI_INTERNALS__ (what invoke uses).
// It is the most reliable signal of "I am running inside the Desktop".
function isDesktop(): boolean {
  return typeof window !== "undefined" && "__TAURI_INTERNALS__" in window;
}

type Phase = "idle" | "available" | "downloading" | "error";

export function DesktopUpdateButton() {
  const [phase, setPhase] = useState<Phase>("idle");
  const [version, setVersion] = useState<string | null>(null);
  const [pct, setPct] = useState(0);
  const updateRef = useRef<Update | null>(null);
  const totalRef = useRef(0);
  const gotRef = useRef(0);

  useEffect(() => {
    if (!isDesktop()) return;
    let cancelled = false;
    (async () => {
      try {
        const { check } = await import("@tauri-apps/plugin-updater");
        const update = await check();
        if (cancelled) return;
        if (update && update.available) {
          updateRef.current = update;
          setVersion(update.version);
          setPhase("available");
        }
      } catch {
        // No release/latest.json published yet, offline or invalid signature:
        // keep it hidden instead of polluting the sidebar with an error.
        if (!cancelled) setPhase("idle");
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  if (phase === "idle") return null;

  const onInstall = async () => {
    const update = updateRef.current;
    if (!update) return;
    setPhase("downloading");
    setPct(0);
    gotRef.current = 0;
    totalRef.current = 0;
    try {
      await update.downloadAndInstall((event: DownloadEvent) => {
        if (event.event === "Started") {
          totalRef.current = event.data.contentLength ?? 0;
        } else if (event.event === "Progress") {
          gotRef.current += event.data.chunkLength;
          if (totalRef.current > 0) {
            setPct(Math.min(100, Math.round((gotRef.current / totalRef.current) * 100)));
          }
        } else if (event.event === "Finished") {
          setPct(100);
        }
      });
      const { relaunch } = await import("@tauri-apps/plugin-process");
      await relaunch();
    } catch {
      setPhase("error");
    }
  };

  const downloading = phase === "downloading";
  const label =
    phase === "downloading"
      ? totalRef.current > 0
        ? `Baixando… ${pct}%`
        : "Baixando…"
      : phase === "error"
        ? "Falha — tentar de novo"
        : version
          ? `Atualizar para ${version}`
          : "Atualização disponível";

  return (
    <div style={{ padding: "0 4px 8px" }}>
      <button
        type="button"
        onClick={onInstall}
        disabled={downloading}
        aria-label={label}
        title={
          phase === "error"
            ? "A atualização falhou. Clique para tentar de novo."
            : "Baixa e instala a nova versão do Nora Desktop e reinicia."
        }
        style={{
          width: "100%",
          display: "inline-flex",
          alignItems: "center",
          justifyContent: "center",
          gap: 7,
          padding: "8px 12px",
          fontSize: 12.5,
          fontWeight: 500,
          letterSpacing: "-0.005em",
          borderRadius: "var(--radius)",
          cursor: downloading ? "default" : "pointer",
          color: phase === "error" ? "var(--danger)" : "var(--accent-ink)",
          background: "var(--accent-soft)",
          border: "1px solid var(--accent-soft)",
          opacity: downloading ? 0.75 : 1,
          transition: "background 130ms var(--ease), opacity 130ms var(--ease)",
        }}
      >
        {downloading ? (
          <span
            aria-hidden
            style={{
              width: 13,
              height: 13,
              borderRadius: "50%",
              border: "2px solid color-mix(in srgb, var(--accent-ink) 30%, transparent)",
              borderTopColor: "var(--accent-ink)",
              animation: "nora-spin 0.7s linear infinite",
            }}
          />
        ) : phase === "error" ? (
          <ErrorIcon />
        ) : (
          <DownloadIcon />
        )}
        {label}
      </button>
    </div>
  );
}

function DownloadIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <path d="M12 3v12" />
      <path d="m7 11 5 5 5-5" />
      <path d="M5 21h14" />
    </svg>
  );
}

function ErrorIcon() {
  return (
    <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 8v4" />
      <path d="M12 16h.01" />
    </svg>
  );
}
