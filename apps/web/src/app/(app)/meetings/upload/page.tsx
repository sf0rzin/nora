"use client";

/**
 * Meeting upload (Subphase 1.3 U)
 * -------------------------------------------------------------------------
 * Flow:
 *
 *   form -> uploading -> polling -> { completed | failed | timeout }
 *
 * After the POST `/meetings` (which returns `{id, processingStatus: "PENDING"}`),
 * we immediately show the "Transcricao enviada" card with free actions
 * ("Enviar outra transcricao" / "Ir para o Inicio") — the user is NOT stuck
 * waiting for the analysis (PO feedback, 2026-06-12: the automatic redirect
 * looked like a freeze when the analysis took long). Polling keeps running
 * in the background only to animate the status in the card (queued →
 * analyzing → ready, when it becomes the "Ver analise" CTA). On `FAILED`
 * we show an error with a "Tentar novamente" cta.
 *
 * The card also hooks into NORA Flows: if the user has an active flow on any
 * trigger the analysis pipeline fires (ANALYSIS_TRIGGERS), we warn that the
 * flows will fire when the analysis finishes; otherwise, we suggest creating
 * one at /flows.
 *
 * Timeout: 5 minutes (150 polls of 2s). After that we show a notice with a
 * manual link — we do not stop the analysis in the backend, only the polling.
 *
 * Batch (multi-upload): the dropzone accepts several files. With 1 file the
 * flow above stays IDENTICAL (same card, same live status). With 2+ we hide
 * title/start/end/format (the title derives from each file's name and the
 * format from the extension; language/participants/tags apply to all), we
 * send with concurrency 2 (BATCH_CONCURRENCY) and show per-file progress
 * with an individual "Tentar de novo" at the end. The batch card does NOT
 * poll N meetings — the analyses keep running in the background and the
 * Inicio (which already polls PROCESSING) tracks the progress.
 *
 * Decisions:
 * - We keep `useState` + `setInterval` (no react-query/swr) — the backlog
 *   asks for zero new libs and the use case is one-off.
 * - The poll counter lives in a `useRef` so the `setInterval` is not
 *   recreated on every tick (state dependency reruns useEffect).
 * - A polling error (network down on the GET) is tolerated until the
 *   timeout: we do not abort polling on the first 5xx; the backend may
 *   still be processing.
 *
 * Visual: port of the v3 design prototype (upload.html) — dropzone with
 * drag&drop, participant/tag chips, optional start/end fields.
 */

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import type { Route } from "next";
import { ApiRequestError, getMeeting, listWorkflows, splitPreview, uploadMeeting } from "@/lib/api/client";
import type { ProcessingStatus, SplitSegment } from "@/lib/api/types";

type Format = "TXT" | "VTT" | "SRT";

const FORMAT_BY_EXT: Record<string, Format> = {
  txt: "TXT",
  vtt: "VTT",
  srt: "SRT",
};

/** Interval between polls. 2s is a tradeoff between snappiness and backend load. */
const POLL_INTERVAL_MS = 2_000;
/** Max polls before showing timeout (5 min @ 2s). */
const MAX_POLLS = 150;
/** Simultaneous uploads in batch mode — 2 avoids a burst of POSTs on the backend. */
const BATCH_CONCURRENCY = 2;
/** Confidence threshold below which we show the amber warning badge. */
const SPLIT_CONFIDENCE_WARN = 0.7;
/**
 * Triggers the analysis pipeline fires when it finishes — all three come out of the same run
 * (`AnalysisService` publishes the three events), so a flow on any of them will fire from this
 * upload. Checking only the anchor trigger told users with a risk or action-item flow that they
 * had none.
 */
const ANALYSIS_TRIGGERS: string[] = [
  "meeting.analysis_completed",
  "action_item.created",
  "meeting.risk_detected",
];

type Phase = "form" | "split-loading" | "split-confirm" | "uploading" | "polling" | "completed" | "failed" | "timeout";

/** State of the Flows notification hint in the post-send card. */
type FlowsHint = "loading" | "has-flows" | "no-flows" | "unavailable";

/** One file of the batch (2+ files mode). */
interface BatchItem {
  id: number;
  file: File;
  /** Derived from the file name (without extension), as in single mode. */
  title: string;
  format: Format;
  status: "queued" | "uploading" | "done" | "error";
  error?: string;
}

/**
 * State of a segment in the split confirmation screen. Editable title +
 * included flag (default true).
 */
interface ConfirmSegment {
  index: number;
  title: string;
  startLine: number;
  endLine: number;
  confidence: number;
  preview: string;
  included: boolean;
}

/** Converts a title into a slug safe for a file name (.txt). */
function slugify(title: string): string {
  return (
    title
      .toLowerCase()
      .normalize('NFD')
      .replace(/[̀-ͯ]/g, '')
      .replace(/[^a-z0-9]+/g, '-')
      .replace(/^-+|-+$/g, '')
      .slice(0, 80) || 'segmento'
  );
}

/**
 * Slices the original file by the line range (1-based, inclusive).
 * Normalizes CRLF before the split.
 */
function sliceFileLines(text: string, startLine: number, endLine: number): string {
  const lines = text.replace(/\r\n/g, "\n").replace(/\r/g, "\n").split("\n");
  return lines.slice(startLine - 1, endLine).join("\n");
}

function detectFormat(fileName: string): Format {
  const ext = fileName.split(".").pop()?.toLowerCase();
  return (ext && FORMAT_BY_EXT[ext]) || "TXT";
}

function deriveTitle(fileName: string): string {
  return fileName.replace(/\.[^.]+$/, "") || fileName;
}

export default function UploadMeetingPage() {
  const router = useRouter();

  // Form state
  const [title, setTitle] = useState("");
  const [language, setLanguage] = useState("pt-BR");
  const [format, setFormat] = useState<Format>("TXT");
  const [tags, setTags] = useState<string[]>([]);
  const [participants, setParticipants] = useState<string[]>([]);
  const [startedAt, setStartedAt] = useState("");
  const [endedAt, setEndedAt] = useState("");
  const [files, setFiles] = useState<File[]>([]);
  const [isOver, setIsOver] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);

  // Phase machine (single mode — 0 or 1 file)
  const [phase, setPhase] = useState<Phase>("form");
  const [meetingId, setMeetingId] = useState<string | null>(null);
  const [pollingStatus, setPollingStatus] = useState<ProcessingStatus>("PENDING");
  const [pollingError, setPollingError] = useState<string | null>(null);

  // Batch (2+ files). `null` = outside batch mode; the array exists from the
  // submit on and holds the progress of each file.
  const [batchItems, setBatchItems] = useState<BatchItem[] | null>(null);
  // "separar automaticamente" toggle — visible only with 1 .txt selected.
  const [splitEnabled, setSplitEnabled] = useState(false);

  // Segments of the split confirmation screen.
  const [splitSegments, setSplitSegments] = useState<ConfirmSegment[] | null>(null);
  // Guard for "Criar N reunioes": ref for the synchronous block (double-click
  // / post-cancel race) + state for the visual feedback (disabled button).
  const splitSubmittingRef = useRef(false);
  const [splitSubmitting, setSplitSubmitting] = useState(false);

  // Counter lives in a ref so the interval is not recreated on every tick.
  const pollCountRef = useRef(0);
  // The split toggle only appears with exactly 1 .txt file.
  const singleTxtFile =
    files.length === 1 && files[0].name.toLowerCase().endsWith(".txt")
      ? files[0]
      : null;

  // When the file changes to non-.txt or 2+, disable the toggle.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!singleTxtFile) setSplitEnabled(false);
  }, [singleTxtFile]);


  /** Mirrors title/format for the single file (the usual single behavior). */
  function applySingleDefaults(f: File, opts?: { overwriteTitle?: boolean }) {
    setFormat(detectFormat(f.name));
    if (opts?.overwriteTitle || !title) setTitle(deriveTitle(f.name));
  }

  function applyFiles(incoming: File[]) {
    if (incoming.length === 0) return;
    let next: File[];
    if (files.length === 1 && incoming.length === 1) {
      // 1 file already selected + 1 new = SWAP ("clique pra trocar"),
      // exactly as single-file has always done.
      next = incoming;
    } else {
      // Other cases accumulate (allows dragging in several rounds), with
      // dedupe by name+size.
      next = [...files];
      for (const f of incoming) {
        if (!next.some((m) => m.name === f.name && m.size === f.size)) next.push(f);
      }
    }
    setFiles(next);
    if (next.length === 1) applySingleDefaults(next[0]);
    // Clear the input to allow re-selecting the same files later
    // (the change event would not fire with the same value).
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  function removeFile(index: number) {
    const next = files.filter((_, i) => i !== index);
    setFiles(next);
    // Back to single mode: title/format now reflect the remaining file
    // (in the batch the title came from each file's name).
    if (next.length === 1) applySingleDefaults(next[0], { overwriteTitle: true });
  }

  function onFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    applyFiles(Array.from(e.target.files ?? []));
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault();
    setIsOver(false);
    applyFiles(Array.from(e.dataTransfer.files ?? []));
  }

  // ---------- Split-preview ----------

  async function callSplitPreview() {
    if (!singleTxtFile) return;
    setPhase("split-loading");
    setFormError(null);
    try {
      const result = await splitPreview(singleTxtFile, language);
      const segments: ConfirmSegment[] = result.segments.map((s: SplitSegment) => ({
        index: s.index,
        title: s.title,
        startLine: s.startLine,
        endLine: s.endLine,
        confidence: s.confidence,
        preview: s.preview,
        included: true,
      }));
      setSplitSegments(segments);
      setPhase("split-confirm");
    } catch (err) {
      const msg = err instanceof ApiRequestError ? err.message : "Falha ao analisar o arquivo.";
      setFormError(msg);
      setPhase("form");
    }
  }

  /** Returns from the confirmation screen to the form keeping the file. */
  function cancelSplitConfirm() {
    // Release the guard: an in-flight confirmSplit (await text()) sees the ref
    // false and aborts before firing phantom uploads.
    splitSubmittingRef.current = false;
    setSplitSubmitting(false);
    setSplitSegments(null);
    setPhase("form");
  }

  /**
   * Confirms the split: slices the file client-side and injects the segments
   * into the batch pool from #253 (same pool, same concurrency, same card).
   */
  async function confirmSplit(segments: ConfirmSegment[]) {
    if (!singleTxtFile) return;
    const included = segments.filter((s) => s.included);
    if (included.length === 0) return;
    // Synchronous guard: blocks double-click on "Criar N reunioes" (each click
    // would fire a runBatch and duplicate the uploads). Set BEFORE the await.
    if (splitSubmittingRef.current) return;
    splitSubmittingRef.current = true;
    setSplitSubmitting(true);

    const text = await singleTxtFile.text();
    // Cancelled during the read (user clicked "Voltar")? Abort with no upload.
    if (!splitSubmittingRef.current) return;

    // Disambiguate names: two segments with titles that generate the same slug
    // (e.g. "Reuniao" and "reuniao") cannot become the same file. The batch
    // tracks by id, but equal names would confuse the progress card.
    const usedNames = new Set<string>();
    const items: BatchItem[] = included.map((seg, idx) => {
      const segText = sliceFileLines(text, seg.startLine, seg.endLine);
      const blob = new Blob([segText], { type: "text/plain" });
      const base = slugify(seg.title);
      let fileName = `${base}.txt`;
      let n = 2;
      while (usedNames.has(fileName)) fileName = `${base}-${n++}.txt`;
      usedNames.add(fileName);
      const file = new File([blob], fileName, { type: "text/plain" });
      return {
        id: idx,
        file,
        title: seg.title,
        format: "TXT" as Format,
        status: "queued" as const,
      };
    });

    splitSubmittingRef.current = false;
    setSplitSubmitting(false);
    setBatchItems(items);
    setSplitSegments(null);
    void runBatch(items);
  }

  // ---------- Batch: send with limited concurrency + individual retry ----------

  function patchBatchItem(id: number, patch: Partial<BatchItem>) {
    setBatchItems((prev) =>
      prev ? prev.map((i) => (i.id === id ? { ...i, ...patch } : i)) : prev,
    );
  }

  async function uploadBatchItem(item: BatchItem): Promise<void> {
    patchBatchItem(item.id, { status: "uploading", error: undefined });
    try {
      await uploadMeeting({
        title: item.title,
        language,
        transcriptFormat: item.format,
        participants: participants.map((displayName) => ({ displayName })),
        tags,
        file: item.file,
      });
      patchBatchItem(item.id, { status: "done" });
    } catch (err) {
      // One file's failure does NOT cancel the others — the pool worker moves
      // to the next and the item gets "Tentar de novo" at the end.
      patchBatchItem(item.id, {
        status: "error",
        error: err instanceof ApiRequestError ? err.message : "Falha no upload.",
      });
    }
  }

  async function runBatch(items: BatchItem[]) {
    let cursor = 0;
    async function worker() {
      while (cursor < items.length) {
        const item = items[cursor];
        cursor += 1;
        await uploadBatchItem(item);
      }
    }
    await Promise.all(
      Array.from({ length: Math.min(BATCH_CONCURRENCY, items.length) }, () => worker()),
    );
  }

  function startBatch() {
    const items: BatchItem[] = files.map((file, idx) => ({
      id: idx,
      file,
      title: deriveTitle(file.name),
      format: detectFormat(file.name),
      status: "queued",
    }));
    setBatchItems(items);
    void runBatch(items);
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (files.length === 0) {
      setFormError("Selecione pelo menos um arquivo de transcrição.");
      return;
    }
    // 1 .txt file with split enabled: confirmation screen.
    if (files.length === 1 && splitEnabled && singleTxtFile) {
      await callSplitPreview();
      return;
    }
    if (files.length >= 2) {
      startBatch();
      return;
    }
    const file = files[0];
    setPhase("uploading");
    try {
      const r = await uploadMeeting({
        title: title.trim(),
        language,
        transcriptFormat: format,
        startedAt: startedAt ? new Date(startedAt).toISOString() : undefined,
        endedAt: endedAt ? new Date(endedAt).toISOString() : undefined,
        participants: participants.map((displayName) => ({ displayName })),
        tags,
        file,
      });
      setMeetingId(r.id);
      // The backend already returns a status; we use it as a starting point.
      const initial = (r.processingStatus as ProcessingStatus) ?? "PENDING";
      setPollingStatus(initial);
      pollCountRef.current = 0;
      // Edge case: if the backend already signals terminal (COMPLETED/FAILED
      // on the POST), we skip the polling.
      if (initial === "COMPLETED") {
        setPhase("completed");
      } else if (initial === "FAILED") {
        setPhase("failed");
      } else {
        setPhase("polling");
      }
    } catch (err) {
      setFormError(err instanceof ApiRequestError ? err.message : "Falha no upload.");
      setPhase("form");
    }
  }

  // Status polling. Runs while phase === "polling".
  useEffect(() => {
    if (phase !== "polling" || !meetingId) return;

    let cancelled = false;

    async function tick() {
      if (cancelled) return;
      pollCountRef.current += 1;
      if (pollCountRef.current > MAX_POLLS) {
        setPhase("timeout");
        return;
      }
      try {
        const meeting = await getMeeting(meetingId!);
        if (cancelled) return;
        setPollingStatus(meeting.processingStatus);
        if (meeting.processingStatus === "COMPLETED") {
          setPhase("completed");
        } else if (meeting.processingStatus === "FAILED") {
          setPhase("failed");
        }
      } catch (err) {
        // Tolerant of transient errors: does not abort the polling on the
        // first 5xx. We update an info field in case the user wants to know,
        // but we keep polling until the timeout.
        if (!cancelled) {
          setPollingError(
            err instanceof ApiRequestError
              ? `Falha ao consultar status (${err.status}). Nova tentativa em ${POLL_INTERVAL_MS / 1000}s...`
              : "Falha ao consultar status. Nova tentativa em breve...",
          );
        }
      }
    }

    // The first call is immediate (the worker may complete in <2s); the rest
    // stay on the interval. We keep the cancellation logic via `cancelled`
    // to avoid setState after unmount.
    void tick();
    const id = setInterval(() => {
      void tick();
    }, POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [phase, meetingId]);

  // Flows notification hint in the post-send card: a single fetch per send
  // (guard via ref so it does not refetch on each phase transition). Failure is
  // tolerated — the hint simply does not appear. Applies to single (post-POST)
  // and to the batch (from the submit — flows do not change during the send).
  const [flowsHint, setFlowsHint] = useState<FlowsHint>("loading");
  const flowsHintFetchedRef = useRef(false);
  useEffect(() => {
    const postUploadSingle = phase !== "form" && phase !== "uploading" && phase !== "split-loading" && phase !== "split-confirm" && meetingId !== null;
    if (!postUploadSingle && batchItems === null) return;
    if (flowsHintFetchedRef.current) return;
    flowsHintFetchedRef.current = true;
    let cancelled = false;
    listWorkflows()
      .then((flows) => {
        if (cancelled) return;
        const hasAnalysisFlow = flows.some(
          (f) => f.active && ANALYSIS_TRIGGERS.includes(f.triggerType),
        );
        setFlowsHint(hasAnalysisFlow ? "has-flows" : "no-flows");
      })
      .catch(() => {
        if (!cancelled) setFlowsHint("unavailable");
      });
    return () => {
      cancelled = true;
    };
  }, [phase, meetingId, batchItems]);

  /** Back to a CLEAN form — "Enviar outra transcrição" / "Enviar mais" starts from zero. */
  function resetToForm() {
    setPhase("form");
    setMeetingId(null);
    setPollingStatus("PENDING");
    setPollingError(null);
    pollCountRef.current = 0;
    setFlowsHint("loading");
    flowsHintFetchedRef.current = false;
    setBatchItems(null);
    setFiles([]);
    setTitle("");
    setTags([]);
    setParticipants([]);
    setStartedAt("");
    setEndedAt("");
    setFormError(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  // ---------- Render ----------

  // Split-loading: spinner while waiting for the split-preview.
  if (phase === "split-loading") {
    return (
      <div className="page page--narrow" style={{ maxWidth: 680 }}>
        <Breadcrumb />
        <CardFrame icon={<Spinner tone="accent" />} title="Procurando as fronteiras entre as reuniões…">
          <p style={{ fontSize: 13, color: "var(--muted)", margin: 0 }}>
            Pode levar alguns instantes — estamos lendo o arquivo inteiro.
          </p>
        </CardFrame>
      </div>
    );
  }

  // Split-confirm: review screen for the detected segments.
  if (phase === "split-confirm" && splitSegments !== null) {
    return (
      <div className="page page--narrow" style={{ maxWidth: 680 }}>
        <Breadcrumb />
        <SplitConfirmScreen
          segments={splitSegments}
          onChange={setSplitSegments}
          onConfirm={confirmSplit}
          onCancel={cancelSplitConfirm}
          file={singleTxtFile}
          language={language}
          submitting={splitSubmitting}
        />
      </div>
    );
  }

  // Batch mode takes precedence: `batchItems` only exists after the submit
  // with 2+ files (phase stays "form" — the single machine does not run here).
  if (batchItems) {
    return (
      <div className="page page--narrow" style={{ maxWidth: 680 }}>
        <Breadcrumb />
        <BatchCard
          items={batchItems}
          flowsHint={flowsHint}
          onRetryItem={(item) => void uploadBatchItem(item)}
          onSendMore={resetToForm}
        />
      </div>
    );
  }

  if (phase !== "form") {
    return (
      <div className="page page--narrow" style={{ maxWidth: 680 }}>
        <Breadcrumb />
        <StatusCard
          phase={phase as Exclude<typeof phase, "split-loading" | "split-confirm" | "form">}
          status={pollingStatus}
          meetingId={meetingId}
          meetingTitle={title}
          flowsHint={flowsHint}
          pollingError={pollingError}
          onRetry={resetToForm}
        />
      </div>
    );
  }

  // 2+ files = batch mode in the form: title/start/end/format disappear
  // (the title comes from each file's name, the format from the extension);
  // language, participants and tags apply to all.
  const isBatchForm = files.length >= 2;

  return (
    <div className="page page--narrow" style={{ maxWidth: 680 }}>
      <Breadcrumb />

      <header style={{ marginBottom: 26 }}>
        <h1 className="h1" style={{ fontSize: 26 }}>
          Nova reunião.
        </h1>
        <p className="lede" style={{ marginTop: 8 }}>
          Suba uma ou mais transcrições (.txt, .vtt ou .srt) — a análise começa em segundo plano.
        </p>
      </header>

      <form onSubmit={onSubmit} style={{ display: "flex", flexDirection: "column", gap: 18 }}>
        <Dropzone
          files={files}
          isOver={isOver}
          inputRef={fileInputRef}
          onOver={(v) => setIsOver(v)}
          onDrop={onDrop}
          onFileChange={onFileChange}
        />

        {isBatchForm && <FileList files={files} onRemove={removeFile} />}

        {/* Split toggle — visible only with 1 .txt file */}
        {singleTxtFile && !isBatchForm && (
          <SplitToggle
            checked={splitEnabled}
            onChange={setSplitEnabled}
          />
        )}

        {!isBatchForm && (
          <div className="field">
            <label className="field-label" htmlFor="f-title">
              Título <span className="req">*</span>
            </label>
            <input
              id="f-title"
              className="input"
              required
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Discovery — Acme, junho/2026"
            />
          </div>
        )}

        <div style={{ display: "grid", gridTemplateColumns: isBatchForm ? "1fr" : "1fr 1fr", gap: 14 }}>
          <div className="field">
            <label className="field-label" htmlFor="f-lang">
              Idioma
            </label>
            <select
              id="f-lang"
              className="select"
              value={language}
              onChange={(e) => setLanguage(e.target.value)}
            >
              <option value="pt-BR">Português (BR)</option>
              <option value="en-US">Inglês (US)</option>
              <option value="es-ES">Espanhol</option>
            </select>
          </div>
          {!isBatchForm && (
            <div className="field">
              <label className="field-label" htmlFor="f-format">
                Formato
              </label>
              <select
                id="f-format"
                className="select"
                value={format}
                onChange={(e) => setFormat(e.target.value as Format)}
              >
                <option value="TXT">TXT</option>
                <option value="VTT">VTT</option>
                <option value="SRT">SRT</option>
              </select>
            </div>
          )}
        </div>

        {!isBatchForm && (
          <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
            <div className="field">
              <label className="field-label" htmlFor="f-start">
                Início da reunião
              </label>
              <input
                id="f-start"
                className="input"
                type="datetime-local"
                value={startedAt}
                onChange={(e) => setStartedAt(e.target.value)}
              />
              <div className="field-help">Se vazio, usamos a data do upload.</div>
            </div>
            <div className="field">
              <label className="field-label" htmlFor="f-end">
                Término
              </label>
              <input
                id="f-end"
                className="input"
                type="datetime-local"
                value={endedAt}
                onChange={(e) => setEndedAt(e.target.value)}
              />
            </div>
          </div>
        )}

        <ChipField
          id="p-input"
          label="Participantes"
          help="Aparecem no detalhe da reunião e ajudam a Nora a atribuir action items."
          placeholder="Digite um nome e aperte Enter"
          values={participants}
          onChange={setParticipants}
        />

        <ChipField
          id="t-input"
          label="Tags"
          help="Tags agrupam reuniões em Projetos."
          placeholder="discovery, renovação…"
          values={tags}
          onChange={setTags}
        />

        {formError && (
          <div className="notice notice--danger" role="alert">
            {formError}
          </div>
        )}

        <div style={{ display: "flex", gap: 10, paddingTop: 4 }}>
          <button className="btn btn-primary" type="submit">
            {isBatchForm
              ? `Enviar ${files.length} transcrições`
              : splitEnabled && singleTxtFile
                ? "Analisar e separar"
                : "Enviar e analisar"}
          </button>
          <button className="btn btn-ghost" type="button" onClick={() => router.back()}>
            Cancelar
          </button>
        </div>
      </form>
    </div>
  );
}

// ---------------------------------------------------------------------------
// SplitToggle — discreet checkbox to enable the automatic separation
// ---------------------------------------------------------------------------

function SplitToggle({ checked, onChange }: { checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label
      style={{
        display: "flex",
        alignItems: "center",
        gap: 10,
        padding: "10px 12px",
        border: "1px solid var(--border)",
        borderRadius: 8,
        background: checked ? "color-mix(in oklch, var(--accent) 8%, var(--canvas))" : "var(--canvas)",
        cursor: "pointer",
        transition: "background 120ms ease",
        userSelect: "none",
      }}
    >
      <input
        type="checkbox"
        checked={checked}
        onChange={(e) => onChange(e.target.checked)}
        style={{ width: 15, height: 15, accentColor: "var(--accent)", flexShrink: 0 }}
      />
      <span style={{ fontSize: 13, color: "var(--ink)", lineHeight: 1.4 }}>
        Este arquivo contém várias reuniões — separar automaticamente
      </span>
    </label>
  );
}

// ---------------------------------------------------------------------------
// SplitConfirmScreen — review screen for the detected segments
// ---------------------------------------------------------------------------

function SplitConfirmScreen({
  segments,
  onChange,
  onConfirm,
  onCancel,
  file,
  submitting,
}: {
  segments: ConfirmSegment[];
  onChange: (segs: ConfirmSegment[]) => void;
  onConfirm: (segs: ConfirmSegment[]) => Promise<void>;
  onCancel: () => void;
  file: File | null;
  language: string;
  submitting: boolean;
}) {
  const includedCount = segments.filter((s) => s.included).length;

  function patchSegment(index: number, patch: Partial<ConfirmSegment>) {
    onChange(segments.map((s) => (s.index === index ? { ...s, ...patch } : s)));
  }

  if (segments.length === 1) {
    return (
      <CardFrame
        icon={<SignalIcon tone="warn" kind="clock" />}
        title="Parece ser uma reunião única."
        subtitle={file ? ('Não encontramos divisões claras em "' + file.name + '".') : undefined}
      >
        <div style={{ display: "flex", gap: 10, flexWrap: "wrap", justifyContent: "center" }}>
          <button
            type="button"
            className="btn btn-primary btn-sm"
            disabled={submitting}
            onClick={() => void onConfirm(segments)}
          >
            Enviar como uma reunião
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={onCancel}>
            Voltar
          </button>
        </div>
      </CardFrame>
    );
  }

  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 18 }}>
      <div>
        <h2 className="h1" style={{ fontSize: 22, marginBottom: 6 }}>
          Encontramos {segments.length} reuniões neste arquivo.
        </h2>
        <p style={{ fontSize: 13, color: "var(--muted)", margin: 0 }}>
          Revise os títulos e confirme quais deseja criar. Idioma, participantes e tags do
          formulário valem para todas.
        </p>
      </div>

      <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
        {segments.map((seg) => (
          <SegmentCard key={seg.index} segment={seg} onPatch={patchSegment} />
        ))}
      </div>

      <div style={{ display: "flex", gap: 10, paddingTop: 4 }}>
        <button
          type="button"
          className="btn btn-primary"
          disabled={includedCount === 0 || submitting}
          onClick={() => void onConfirm(segments)}
        >
          {includedCount === 0
            ? "Selecione ao menos uma reunião"
            : ("Criar " + includedCount + " " + (includedCount === 1 ? "reunião" : "reuniões"))}
        </button>
        <button type="button" className="btn btn-ghost" onClick={onCancel}>
          Cancelar
        </button>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// SegmentCard — one editable segment in the confirmation screen
// ---------------------------------------------------------------------------

function SegmentCard({ segment, onPatch }: { segment: ConfirmSegment; onPatch: (index: number, patch: Partial<ConfirmSegment>) => void }) {
  const lowConfidence = segment.confidence < SPLIT_CONFIDENCE_WARN;

  return (
    <div
      style={{
        border: "1px solid var(--border)",
        borderRadius: 10,
        background: "var(--canvas)",
        padding: "14px 16px",
        opacity: segment.included ? 1 : 0.5,
        transition: "opacity 140ms ease",
      }}
    >
      <div style={{ display: "flex", alignItems: "center", gap: 10, marginBottom: 8 }}>
        <input
          type="checkbox"
          checked={segment.included}
          onChange={(e) => onPatch(segment.index, { included: e.target.checked })}
          aria-label={"Incluir " + segment.title}
          style={{ width: 15, height: 15, accentColor: "var(--accent)", flexShrink: 0 }}
        />
        <input
          type="text"
          value={segment.title}
          onChange={(e) => onPatch(segment.index, { title: e.target.value })}
          disabled={!segment.included}
          aria-label="Título da reunião"
          style={{
            flex: 1,
            border: "none",
            outline: "none",
            background: "transparent",
            fontFamily: "var(--sans)",
            fontSize: 13.5,
            fontWeight: 500,
            color: "var(--ink)",
            padding: 0,
          }}
        />
        {lowConfidence && (
          <span
            title="A divisão aqui pode não ser precisa — confira as linhas antes de confirmar."
            style={{
              fontSize: 11,
              fontWeight: 600,
              letterSpacing: "0.03em",
              color: "var(--warn)",
              background: "color-mix(in oklch, var(--warn) 14%, var(--canvas))",
              borderRadius: 999,
              padding: "2px 8px",
              flexShrink: 0,
              whiteSpace: "nowrap",
            }}
          >
            confira esta divisão
          </span>
        )}
      </div>

      <div style={{ paddingLeft: 25 }}>
        <p
          style={{
            fontSize: 12.5,
            color: "var(--muted)",
            margin: "0 0 4px",
            lineHeight: 1.55,
            display: "-webkit-box",
            WebkitLineClamp: 3,
            WebkitBoxOrient: "vertical",
            overflow: "hidden",
          }}
        >
          {segment.preview}
        </p>
        <span style={{ fontSize: 11.5, color: "var(--muted)", opacity: 0.7 }}>
          linhas {segment.startLine}–{segment.endLine}
        </span>
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Breadcrumb
// ---------------------------------------------------------------------------

function Breadcrumb() {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 8,
        fontSize: 12,
        color: "var(--muted)",
        marginBottom: 22,
      }}
    >
      <Link href={"/dashboard" as Route} style={{ color: "var(--muted)" }}>
        Reuniões
      </Link>
      <span style={{ opacity: 0.5 }}>/</span>
      <span>Nova reunião</span>
    </div>
  );
}

// ---------------------------------------------------------------------------
// Dropzone — drag & drop + click to choose files (1 or several)
// ---------------------------------------------------------------------------

interface DropzoneProps {
  files: File[];
  isOver: boolean;
  inputRef: React.RefObject<HTMLInputElement>;
  onOver: (over: boolean) => void;
  onDrop: (e: React.DragEvent) => void;
  onFileChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
}

function Dropzone({ files, isOver, inputRef, onOver, onDrop, onFileChange }: DropzoneProps) {
  const hasFile = files.length > 0;
  const single = files.length === 1 ? files[0] : null;
  const open = () => inputRef.current?.click();

  const border = isOver
    ? "1.5px dashed var(--accent)"
    : hasFile
      ? "1.5px solid var(--border)"
      : "1.5px dashed var(--border-strong)";
  const background = isOver
    ? "var(--accent-soft)"
    : hasFile
      ? "var(--sidebar)"
      : "transparent";

  return (
    <div
      role="button"
      tabIndex={0}
      aria-label="Selecionar arquivos de transcrição"
      onClick={open}
      onKeyDown={(e) => {
        if (e.key === "Enter" || e.key === " ") {
          e.preventDefault();
          open();
        }
      }}
      onDragOver={(e) => {
        e.preventDefault();
        onOver(true);
      }}
      onDragEnter={(e) => {
        e.preventDefault();
        onOver(true);
      }}
      onDragLeave={(e) => {
        e.preventDefault();
        onOver(false);
      }}
      onDrop={onDrop}
      style={{
        border,
        background,
        borderRadius: 12,
        padding: "28px 20px",
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 8,
        textAlign: "center",
        cursor: "pointer",
        transition: "border-color 140ms ease, background 140ms ease",
      }}
    >
      <svg
        width="22"
        height="22"
        viewBox="0 0 24 24"
        fill="none"
        stroke="var(--muted)"
        strokeWidth="1.6"
        strokeLinecap="round"
        strokeLinejoin="round"
        aria-hidden="true"
      >
        <path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4" />
        <polyline points="17 8 12 3 7 8" />
        <line x1="12" y1="3" x2="12" y2="15" />
      </svg>
      <div style={{ fontSize: 13.5, color: "var(--ink)", fontWeight: 500 }}>
        {single
          ? single.name
          : hasFile
            ? `${files.length} arquivos selecionados`
            : "Arraste as transcrições aqui ou clique pra escolher"}
      </div>
      <div style={{ fontSize: 11.5, color: "var(--muted)" }}>
        {single
          ? `${(single.size / 1024).toFixed(1)} KB · clique pra trocar`
          : hasFile
            ? "arraste mais ou clique pra adicionar"
            : ".txt · .vtt · .srt — até 10 MB cada"}
      </div>
      <input
        ref={inputRef}
        type="file"
        multiple
        accept=".txt,.vtt,.srt,text/plain"
        onChange={onFileChange}
        style={{ display: "none" }}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// FileList — batch files in the form (name, size, format, remove)
// ---------------------------------------------------------------------------

function FileList({ files, onRemove }: { files: File[]; onRemove: (index: number) => void }) {
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 6 }}>
      {files.map((f, i) => (
        <div
          key={`${f.name}-${f.size}`}
          style={{
            display: "flex",
            alignItems: "center",
            gap: 10,
            border: "1px solid var(--border)",
            borderRadius: 8,
            padding: "8px 10px",
            background: "var(--canvas)",
          }}
        >
          <span
            style={{
              fontSize: 10.5,
              fontWeight: 600,
              letterSpacing: "0.04em",
              color: "var(--muted)",
              background: "var(--chip)",
              borderRadius: 5,
              padding: "2px 7px",
              flexShrink: 0,
            }}
          >
            {detectFormat(f.name)}
          </span>
          <span
            style={{
              fontSize: 13,
              color: "var(--ink)",
              flex: 1,
              minWidth: 0,
              overflow: "hidden",
              textOverflow: "ellipsis",
              whiteSpace: "nowrap",
            }}
          >
            {f.name}
          </span>
          <span style={{ fontSize: 11.5, color: "var(--muted)", flexShrink: 0 }}>
            {(f.size / 1024).toFixed(1)} KB
          </span>
          <button
            type="button"
            aria-label={`Remover ${f.name}`}
            onClick={() => onRemove(i)}
            style={{
              border: "none",
              background: "transparent",
              cursor: "pointer",
              color: "var(--muted)",
              display: "grid",
              placeItems: "center",
              width: 20,
              height: 20,
              padding: 0,
              borderRadius: "50%",
              flexShrink: 0,
            }}
          >
            <svg
              width="11"
              height="11"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2.2"
              strokeLinecap="round"
              aria-hidden="true"
            >
              <path d="M6 6l12 12M18 6L6 18" />
            </svg>
          </button>
        </div>
      ))}
      <div className="field-help">
        O título de cada reunião vem do nome do arquivo. Idioma, participantes e tags valem
        para todas.
      </div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// ChipField — chip input (participants / tags)
// ---------------------------------------------------------------------------

interface ChipFieldProps {
  id: string;
  label: string;
  help: string;
  placeholder: string;
  values: string[];
  onChange: (next: string[]) => void;
}

function ChipField({ id, label, help, placeholder, values, onChange }: ChipFieldProps) {
  const [draft, setDraft] = useState("");

  function commit() {
    const v = draft.replace(/,/g, "").trim();
    if (v && !values.includes(v)) onChange([...values, v]);
    setDraft("");
  }

  function onKeyDown(e: React.KeyboardEvent<HTMLInputElement>) {
    if ((e.key === "Enter" || e.key === ",") && draft.trim()) {
      e.preventDefault();
      commit();
    } else if (e.key === "Backspace" && !draft && values.length) {
      onChange(values.slice(0, -1));
    }
  }

  function remove(v: string) {
    onChange(values.filter((x) => x !== v));
  }

  return (
    <div className="field">
      <label className="field-label" htmlFor={id}>
        {label}
      </label>
      <div
        onClick={() => document.getElementById(id)?.focus()}
        style={{
          display: "flex",
          flexWrap: "wrap",
          gap: 6,
          alignItems: "center",
          border: "1px solid var(--border)",
          borderRadius: 8,
          padding: "6px 8px",
          background: "var(--canvas)",
          cursor: "text",
        }}
      >
        {values.map((v) => (
          <span
            key={v}
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 6,
              padding: "3px 6px 3px 10px",
              borderRadius: 999,
              background: "var(--chip)",
              fontSize: 12,
              color: "var(--ink)",
            }}
          >
            {v}
            <button
              type="button"
              aria-label={`Remover ${v}`}
              onClick={(e) => {
                e.stopPropagation();
                remove(v);
              }}
              style={{
                border: "none",
                background: "transparent",
                cursor: "pointer",
                color: "var(--muted)",
                display: "grid",
                placeItems: "center",
                width: 16,
                height: 16,
                padding: 0,
                borderRadius: "50%",
              }}
            >
              <svg
                width="10"
                height="10"
                viewBox="0 0 24 24"
                fill="none"
                stroke="currentColor"
                strokeWidth="2.2"
                strokeLinecap="round"
                aria-hidden="true"
              >
                <path d="M6 6l12 12M18 6L6 18" />
              </svg>
            </button>
          </span>
        ))}
        <input
          id={id}
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          onKeyDown={onKeyDown}
          onBlur={() => draft.trim() && commit()}
          placeholder={placeholder}
          style={{
            border: "none",
            outline: "none",
            background: "transparent",
            fontFamily: "var(--sans)",
            fontSize: 13,
            color: "var(--ink)",
            flex: 1,
            minWidth: 140,
            padding: "3px 4px",
          }}
        />
      </div>
      <div className="field-help">{help}</div>
    </div>
  );
}

// ---------------------------------------------------------------------------
// StatusCard — visual feedback during upload + polling
// ---------------------------------------------------------------------------

interface StatusCardProps {
  phase: Exclude<Phase, "form" | "split-loading" | "split-confirm">;
  status: ProcessingStatus;
  meetingId: string | null;
  meetingTitle: string;
  flowsHint: FlowsHint;
  pollingError: string | null;
  onRetry: () => void;
}

function StatusCard({
  phase,
  status,
  meetingId,
  meetingTitle,
  flowsHint,
  pollingError,
  onRetry,
}: StatusCardProps) {
  // "uploading" = POST in flight; "failed" = terminal error. The rest
  // (polling/completed/timeout) share the "Transcrição enviada" card
  // with live status — the user is free from the first second on.
  if (phase === "uploading") {
    return (
      <CardFrame icon={<Spinner tone="accent" />} title="Enviando transcrição…" />
    );
  }

  if (phase === "failed") {
    return (
      <CardFrame
        icon={<SignalIcon tone="danger" kind="x" />}
        title="Falha na análise"
        subtitle="O processamento da reunião falhou. Tente novamente ou abra os detalhes para mais informações."
      >
        <div style={{ display: "flex", flexDirection: "column", gap: 8, alignItems: "center" }}>
          <button type="button" className="btn btn-primary btn-sm" onClick={onRetry}>
            Tentar novamente
          </button>
          {meetingId && (
            <Link
              href={`/meetings/${meetingId}` as Route}
              style={{
                fontSize: 12.5,
                color: "var(--muted)",
                textDecoration: "underline",
                textUnderlineOffset: 2,
              }}
            >
              Ver detalhes da reunião
            </Link>
          )}
        </div>
      </CardFrame>
    );
  }

  // Sent: live analysis status + free actions.
  const live =
    phase === "completed"
      ? { icon: <MiniCheck />, label: "Análise pronta." }
      : phase === "timeout"
        ? { icon: <MiniSpinner tone="muted" />, label: "Ainda processando — está demorando mais que o normal." }
        : status === "PROCESSING"
          ? { icon: <MiniSpinner tone="accent" />, label: "Analisando com IA…" }
          : { icon: <MiniSpinner tone="muted" />, label: "Na fila de análise…" };

  return (
    <CardFrame
      icon={<SignalIcon tone="success" kind="check" />}
      title="Transcrição enviada."
      subtitle={
        meetingTitle
          ? `A análise de "${meetingTitle}" continua em segundo plano.`
          : "A análise continua em segundo plano."
      }
    >
      <div
        style={{
          display: "inline-flex",
          alignItems: "center",
          gap: 8,
          padding: "7px 14px",
          borderRadius: 999,
          background: "var(--chip)",
          fontSize: 12.5,
          color: "var(--ink)",
        }}
      >
        {live.icon}
        {live.label}
      </div>

      <FlowsHintNote hint={flowsHint} />

      <div style={{ display: "flex", gap: 10, flexWrap: "wrap", justifyContent: "center", paddingTop: 4 }}>
        {phase === "completed" && meetingId && (
          <Link className="btn btn-primary btn-sm" href={`/meetings/${meetingId}` as Route}>
            Ver análise
          </Link>
        )}
        <button type="button" className="btn btn-secondary btn-sm" onClick={onRetry}>
          Enviar outra transcrição
        </button>
        <Link className="btn btn-ghost btn-sm" href={"/dashboard" as Route}>
          Ir para o Início
        </Link>
      </div>

      {pollingError && phase === "polling" && (
        <div className="notice" style={{ fontSize: 12, maxWidth: 380 }}>
          {pollingError}
        </div>
      )}
      {phase === "timeout" && meetingId && (
        <Link
          href={`/meetings/${meetingId}` as Route}
          style={{ fontSize: 12.5, color: "var(--muted)", textDecoration: "underline", textUnderlineOffset: 2 }}
        >
          Acompanhar na página da reunião
        </Link>
      )}
    </CardFrame>
  );
}

/**
 * Flows notification hint — shared between the single card and the batch
 * card. `plural` adjusts the sentence for the case of several analyses.
 */
function FlowsHintNote({ hint, plural = false }: { hint: FlowsHint; plural?: boolean }) {
  const noteStyle: React.CSSProperties = {
    fontSize: 12.5,
    color: "var(--muted)",
    margin: 0,
    maxWidth: 400,
    lineHeight: 1.55,
  };
  if (hint === "has-flows") {
    return (
      <p style={noteStyle}>
        {plural
          ? "Seus fluxos ativos serão executados quando cada análise terminar."
          : "Seus fluxos ativos serão executados quando a análise terminar."}
      </p>
    );
  }
  if (hint === "no-flows") {
    return (
      <p style={noteStyle}>
        {plural
          ? "Para receber um aviso ao fim de cada análise, crie um fluxo com o gatilho "
          : "Para receber um aviso ao fim da análise, crie um fluxo com o gatilho "}
        &quot;Reunião analisada&quot; em{" "}
        <Link
          href={"/flows" as Route}
          style={{ color: "var(--accent-ink)", textDecoration: "underline", textUnderlineOffset: 2 }}
        >
          Fluxos
        </Link>
        .
      </p>
    );
  }
  return null;
}

// ---------------------------------------------------------------------------
// BatchCard — batch progress (2+ files): per-file + final summary.
// No status polling of the N meetings — the analyses keep running in the
// background and the Inicio (PROCESSING polling) tracks the progress.
// ---------------------------------------------------------------------------

interface BatchCardProps {
  items: BatchItem[];
  flowsHint: FlowsHint;
  onRetryItem: (item: BatchItem) => void;
  onSendMore: () => void;
}

function BatchCard({ items, flowsHint, onRetryItem, onSendMore }: BatchCardProps) {
  const sent = items.filter((i) => i.status === "done").length;
  const failed = items.filter((i) => i.status === "error").length;
  const settled = sent + failed === items.length;

  const icon = !settled ? (
    <Spinner tone="accent" />
  ) : sent > 0 ? (
    <SignalIcon tone="success" kind="check" />
  ) : (
    <SignalIcon tone="danger" kind="x" />
  );

  const title = !settled
    ? `Enviando transcrições… (${sent + failed} de ${items.length})`
    : sent === 0
      ? "Nenhuma transcrição foi enviada."
      : sent === 1
        ? "1 transcrição enviada."
        : `${sent} transcrições enviadas.`;

  const subtitle = !settled
    ? undefined
    : sent === 0
      ? "Todos os envios falharam. Tente de novo por arquivo abaixo."
      : "As análises continuam em segundo plano — o Início acompanha o progresso de cada uma.";

  return (
    <CardFrame icon={icon} title={title} subtitle={subtitle}>
      <div
        style={{
          width: "100%",
          maxWidth: 440,
          display: "flex",
          flexDirection: "column",
          gap: 6,
          textAlign: "left",
        }}
      >
        {items.map((item) => (
          <BatchRow
            key={item.id}
            item={item}
            settled={settled}
            onRetry={() => onRetryItem(item)}
          />
        ))}
      </div>

      {settled && sent > 0 && <FlowsHintNote hint={flowsHint} plural />}

      {settled && (
        <div style={{ display: "flex", gap: 10, flexWrap: "wrap", justifyContent: "center", paddingTop: 4 }}>
          <button type="button" className="btn btn-secondary btn-sm" onClick={onSendMore}>
            Enviar mais
          </button>
          <Link className="btn btn-ghost btn-sm" href={"/dashboard" as Route}>
            Ir para o Início
          </Link>
        </div>
      )}
    </CardFrame>
  );
}

function BatchRow({
  item,
  settled,
  onRetry,
}: {
  item: BatchItem;
  settled: boolean;
  onRetry: () => void;
}) {
  return (
    <div
      style={{
        display: "flex",
        alignItems: "center",
        gap: 10,
        border: "1px solid var(--border)",
        borderRadius: 8,
        padding: "9px 12px",
        background: "var(--canvas)",
      }}
    >
      <span style={{ flexShrink: 0, width: 14, display: "grid", placeItems: "center" }}>
        {item.status === "queued" && <MiniSpinner tone="muted" />}
        {item.status === "uploading" && <MiniSpinner tone="accent" />}
        {item.status === "done" && <MiniCheck />}
        {item.status === "error" && <MiniX />}
      </span>
      <div style={{ flex: 1, minWidth: 0 }}>
        <div
          style={{
            fontSize: 12.5,
            color: "var(--ink)",
            overflow: "hidden",
            textOverflow: "ellipsis",
            whiteSpace: "nowrap",
          }}
        >
          {item.file.name}
        </div>
        <div
          style={{
            fontSize: 11.5,
            color: item.status === "error" ? "var(--danger)" : "var(--muted)",
          }}
        >
          {item.status === "queued" && "Na fila…"}
          {item.status === "uploading" && "Enviando…"}
          {item.status === "done" && "Enviado"}
          {item.status === "error" && (item.error ?? "Falha no upload.")}
        </div>
      </div>
      {item.status === "error" && settled && (
        <button
          type="button"
          className="btn btn-secondary btn-sm"
          onClick={onRetry}
          style={{ flexShrink: 0 }}
        >
          Tentar de novo
        </button>
      )}
    </div>
  );
}

function CardFrame({
  icon,
  title,
  subtitle,
  children,
}: {
  icon: React.ReactNode;
  title: string;
  subtitle?: string;
  children?: React.ReactNode;
}) {
  return (
    <div
      className="card"
      role="status"
      aria-live="polite"
      style={{
        display: "flex",
        flexDirection: "column",
        alignItems: "center",
        gap: 18,
        padding: "64px 24px",
        textAlign: "center",
      }}
    >
      {/* local spinner keyframe — no global noraSpin exists in the design system */}
      <style>{"@keyframes noraSpin { to { transform: rotate(360deg); } }"}</style>
      {icon}
      <div>
        <h2
          style={{
            fontSize: 17,
            fontWeight: 500,
            letterSpacing: "-0.015em",
            margin: "0 0 5px",
          }}
        >
          {title}
        </h2>
        {subtitle && (
          <p
            style={{
              fontSize: 13,
              color: "var(--muted)",
              margin: 0,
              lineHeight: 1.55,
              maxWidth: 400,
            }}
          >
            {subtitle}
          </p>
        )}
      </div>
      {children}
    </div>
  );
}

/** Small spinner for the live status chip. */
function MiniSpinner({ tone }: { tone: "accent" | "muted" }) {
  return (
    <span
      aria-hidden="true"
      style={{
        width: 12,
        height: 12,
        borderRadius: "50%",
        border: "2px solid var(--border)",
        borderTopColor: tone === "accent" ? "var(--accent)" : "var(--muted)",
        animation: "noraSpin 0.9s linear infinite",
        flexShrink: 0,
      }}
    />
  );
}

function MiniCheck() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 24 24"
      fill="none"
      stroke="var(--success)"
      strokeWidth="2.6"
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      style={{ flexShrink: 0 }}
    >
      <polyline points="20 6 9 17 4 12" />
    </svg>
  );
}

function MiniX() {
  return (
    <svg
      width="13"
      height="13"
      viewBox="0 0 24 24"
      fill="none"
      stroke="var(--danger)"
      strokeWidth="2.6"
      strokeLinecap="round"
      aria-hidden="true"
      style={{ flexShrink: 0 }}
    >
      <path d="M6 6l12 12M18 6L6 18" />
    </svg>
  );
}

// ---------------------------------------------------------------------------
// Inline icons (no new libs — the project already uses inline SVG)
// ---------------------------------------------------------------------------

function Spinner({ tone }: { tone: "accent" | "muted" }) {
  const topColor = tone === "accent" ? "var(--accent)" : "var(--muted)";
  return (
    <span
      aria-hidden="true"
      style={{
        width: 36,
        height: 36,
        borderRadius: "50%",
        border: "2.5px solid var(--chip)",
        borderTopColor: topColor,
        animation: "noraSpin 0.9s linear infinite",
      }}
    />
  );
}

function SignalIcon({
  tone,
  kind,
}: {
  tone: "success" | "danger" | "warn";
  kind: "check" | "x" | "clock";
}) {
  const color = `var(--${tone})`;
  const background = `color-mix(in oklch, var(--${tone}) 14%, var(--canvas))`;
  return (
    <span
      aria-hidden="true"
      style={{
        width: 44,
        height: 44,
        borderRadius: "50%",
        display: "grid",
        placeItems: "center",
        background,
        color,
      }}
    >
      {kind === "check" && (
        <svg
          width="22"
          height="22"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <polyline points="20 6 9 17 4 12" />
        </svg>
      )}
      {kind === "x" && (
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.2"
          strokeLinecap="round"
        >
          <path d="M6 6l12 12M18 6L6 18" />
        </svg>
      )}
      {kind === "clock" && (
        <svg
          width="20"
          height="20"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <circle cx="12" cy="12" r="9" />
          <path d="M12 7v5l3 3" />
        </svg>
      )}
    </span>
  );
}



