"use client";

/**
 * "Exportar" button on the meeting detail (GOAL item 14).
 *
 * Small menu with two real outputs:
 *  - Markdown (.md): generated client-side via meetingToMarkdown() and
 *    downloaded through Blob + <a download> — no server round-trip.
 *  - PDF: navigates to the print route /meetings/{id}/report, where the
 *    user saves the PDF via the native dialog (window.print) — zero libs.
 */
import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import type { Route } from "next";

import type { MeetingDetail } from "@/lib/api/types";
import { meetingReportFileName, meetingToMarkdown } from "@/lib/report/markdown";

function DownloadIcon() {
  return (
    <svg width="13" height="13" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
      <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
      <path d="M7 10l5 5 5-5M12 15V3" />
    </svg>
  );
}
function ChevronDownIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
      <path d="m6 9 6 6 6-6" />
    </svg>
  );
}

export default function ExportMenu({ detail }: { detail: MeetingDetail }) {
  const router = useRouter();
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

  // Closes on outside click / Esc — minimal menu pattern, no lib.
  useEffect(() => {
    if (!open) return;
    function onPointerDown(e: PointerEvent) {
      if (wrapRef.current && !wrapRef.current.contains(e.target as Node)) setOpen(false);
    }
    function onKeyDown(e: KeyboardEvent) {
      if (e.key === "Escape") setOpen(false);
    }
    document.addEventListener("pointerdown", onPointerDown);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("pointerdown", onPointerDown);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [open]);

  function downloadMarkdown() {
    const md = meetingToMarkdown(detail);
    const blob = new Blob([md], { type: "text/markdown;charset=utf-8" });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = meetingReportFileName(detail);
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    setOpen(false);
  }

  function openPrintReport() {
    setOpen(false);
    router.push(`/meetings/${detail.id}/report` as Route);
  }

  return (
    <div ref={wrapRef} style={{ position: "relative", display: "inline-block" }}>
      <button
        type="button"
        className="btn btn-secondary btn-sm"
        aria-haspopup="menu"
        aria-expanded={open}
        onClick={() => setOpen((v) => !v)}
      >
        <DownloadIcon />
        Exportar
        <ChevronDownIcon />
      </button>
      {open && (
        <div className="menu-pop" role="menu" aria-label="Formatos de exportação">
          <button type="button" role="menuitem" className="menu-item" onClick={downloadMarkdown}>
            Markdown (.md)
          </button>
          <button type="button" role="menuitem" className="menu-item" onClick={openPrintReport}>
            PDF
          </button>
        </div>
      )}
    </div>
  );
}
