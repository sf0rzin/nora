"use client";

/**
 * "Exportar" button on the usage panel — the consolidated period report (US34).
 *
 * Same minimal menu as the meeting detail and the task list: closes on outside click and on Esc,
 * two outputs generated client-side by the pure helpers in `@/lib/report/usage-report`, downloaded
 * through Blob + `<a download>` with no server round-trip. Both files carry exactly the range the
 * screen resolved, which the caption states so a shorter file than expected does not read as a bug.
 */
import { useEffect, useRef, useState } from "react";

import type { UsageResponse } from "@/lib/api/client";
import { usageReportFileName, usageToCsv, usageToMarkdown } from "@/lib/report/usage-report";

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

export default function UsageExportMenu({ data }: { data: UsageResponse }) {
  const [open, setOpen] = useState(false);
  const wrapRef = useRef<HTMLDivElement>(null);

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

  function download(text: string, fileName: string, mime: string) {
    const blob = new Blob([text], { type: `${mime};charset=utf-8` });
    const url = URL.createObjectURL(blob);
    const a = document.createElement("a");
    a.href = url;
    a.download = fileName;
    document.body.appendChild(a);
    a.click();
    a.remove();
    URL.revokeObjectURL(url);
    setOpen(false);
  }

  const periods = data.meetingBuckets.length;

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
          <p style={{ margin: 0, padding: "6px 10px 8px", fontSize: 11, color: "var(--muted)", lineHeight: 1.4 }}>
            {data.from.slice(0, 10)} a {data.to.slice(0, 10)} · {periods}{" "}
            {periods === 1 ? "período" : "períodos"}
          </p>
          <button
            type="button"
            role="menuitem"
            className="menu-item"
            onClick={() => download(usageToCsv(data), usageReportFileName("csv", data), "text/csv")}
          >
            CSV (.csv)
          </button>
          <button
            type="button"
            role="menuitem"
            className="menu-item"
            onClick={() =>
              download(usageToMarkdown(data), usageReportFileName("md", data), "text/markdown")
            }
          >
            Markdown (.md)
          </button>
        </div>
      )}
    </div>
  );
}
