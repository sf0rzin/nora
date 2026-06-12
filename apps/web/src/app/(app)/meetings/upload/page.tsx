"use client";

/**
 * Upload de reuniao (Subfase 1.3 U)
 * -------------------------------------------------------------------------
 * Fluxo:
 *
 *   form -> uploading -> polling -> { completed | failed | timeout }
 *
 * Apos o POST `/meetings` (que retorna `{id, processingStatus: "PENDING"}`),
 * mostramos imediatamente o card "Transcricao enviada" com acoes livres
 * ("Enviar outra transcricao" / "Ir para o Inicio") — o usuario NAO fica
 * preso esperando a analise (feedback do PO, 2026-06-12: o redirect
 * automatico parecia travamento quando a analise demorava). O polling
 * continua em segundo plano so pra animar o status no card (na fila →
 * analisando → pronta, quando vira CTA "Ver analise"). Em `FAILED`
 * mostramos erro com cta "Tentar novamente".
 *
 * O card tambem conecta com o NORA Flows: se o usuario tem fluxo ativo com
 * gatilho `meeting.analysis_completed`, avisamos que os fluxos vao disparar
 * quando a analise terminar; senao, sugerimos criar um em /fluxos.
 *
 * Timeout: 5 minutos (150 polls de 2s). Apos isso mostramos aviso com link
 * manual — nao paramos a analise no backend, apenas paramos de pollar.
 *
 * Decisoes:
 * - Mantemos `useState` + `setInterval` (sem react-query/swr) — o backlog
 *   pede zero libs novas e o caso de uso e pontual.
 * - Contador de polls vive num `useRef` pra nao recriar o `setInterval` a
 *   cada tick (state dependency reruns useEffect).
 * - Erro do polling (rede caida no GET) e tolerado ate o timeout: nao
 *   abortamos o polling no primeiro 5xx; backend ainda pode estar processando.
 *
 * Visual: porte do prototipo do Claude Design (upload.html) — dropzone com
 * drag&drop, chips de participantes/tags, campos opcionais de inicio/termino.
 */

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import type { Route } from "next";
import { ApiRequestError, getMeeting, listWorkflows, uploadMeeting } from "@/lib/api/client";
import type { ProcessingStatus } from "@/lib/api/types";

type Format = "TXT" | "VTT" | "SRT";

const FORMAT_BY_EXT: Record<string, Format> = {
  txt: "TXT",
  vtt: "VTT",
  srt: "SRT",
};

/** Intervalo entre polls. 2s e tradeoff entre snappiness e carga no backend. */
const POLL_INTERVAL_MS = 2_000;
/** Maximo de polls antes de mostrar timeout (5 min @ 2s). */
const MAX_POLLS = 150;

type Phase = "form" | "uploading" | "polling" | "completed" | "failed" | "timeout";

/** Estado da dica de notificacao via Flows no card pos-envio. */
type FlowsHint = "loading" | "has-flows" | "no-flows" | "unavailable";

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
  const [file, setFile] = useState<File | null>(null);
  const [isOver, setIsOver] = useState(false);
  const [formError, setFormError] = useState<string | null>(null);

  const fileInputRef = useRef<HTMLInputElement>(null);

  // Phase machine
  const [phase, setPhase] = useState<Phase>("form");
  const [meetingId, setMeetingId] = useState<string | null>(null);
  const [pollingStatus, setPollingStatus] = useState<ProcessingStatus>("PENDING");
  const [pollingError, setPollingError] = useState<string | null>(null);

  // Counter vive em ref pra nao recriar o interval a cada tick.
  const pollCountRef = useRef(0);

  function applyFile(f: File | null) {
    setFile(f);
    if (f) {
      const ext = f.name.split(".").pop()?.toLowerCase();
      if (ext && FORMAT_BY_EXT[ext]) setFormat(FORMAT_BY_EXT[ext]);
      if (!title) setTitle(f.name.replace(/\.[^.]+$/, ""));
    }
  }

  function onFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    applyFile(e.target.files?.[0] ?? null);
  }

  function onDrop(e: React.DragEvent) {
    e.preventDefault();
    setIsOver(false);
    const f = e.dataTransfer.files?.[0];
    if (f) applyFile(f);
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!file) {
      setFormError("Selecione um arquivo de transcrição.");
      return;
    }
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
      // Backend ja devolve um status; usamos como ponto de partida.
      const initial = (r.processingStatus as ProcessingStatus) ?? "PENDING";
      setPollingStatus(initial);
      pollCountRef.current = 0;
      // Edge case: se backend ja sinaliza terminal (COMPLETED/FAILED no POST),
      // saltamos o polling.
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

  // Polling do status. Roda enquanto phase === "polling".
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
        // Tolerante a erros transientes: nao aborta o polling no primeiro
        // 5xx. Atualizamos um campo de info caso o usuario queira saber,
        // mas continuamos pollando ate o timeout.
        if (!cancelled) {
          setPollingError(
            err instanceof ApiRequestError
              ? `Falha ao consultar status (${err.status}). Nova tentativa em ${POLL_INTERVAL_MS / 1000}s...`
              : "Falha ao consultar status. Nova tentativa em breve...",
          );
        }
      }
    }

    // Primeira chamada e imediata (worker pode completar em <2s); demais
    // ficam no intervalo. Mantemos a logica de cancelamento via `cancelled`
    // pra evitar setState apos unmount.
    void tick();
    const id = setInterval(() => {
      void tick();
    }, POLL_INTERVAL_MS);

    return () => {
      cancelled = true;
      clearInterval(id);
    };
  }, [phase, meetingId]);

  // Dica de notificacao via Flows no card pos-envio: um fetch unico por envio
  // (guard via ref pra nao refetchar a cada transicao de phase). Falha e
  // tolerada — a dica simplesmente nao aparece.
  const [flowsHint, setFlowsHint] = useState<FlowsHint>("loading");
  const flowsHintFetchedForRef = useRef<string | null>(null);
  useEffect(() => {
    if (phase === "form" || phase === "uploading" || !meetingId) return;
    if (flowsHintFetchedForRef.current === meetingId) return;
    flowsHintFetchedForRef.current = meetingId;
    let cancelled = false;
    listWorkflows()
      .then((flows) => {
        if (cancelled) return;
        const hasAnalysisFlow = flows.some(
          (f) => f.active && f.triggerType === "meeting.analysis_completed",
        );
        setFlowsHint(hasAnalysisFlow ? "has-flows" : "no-flows");
      })
      .catch(() => {
        if (!cancelled) setFlowsHint("unavailable");
      });
    return () => {
      cancelled = true;
    };
  }, [phase, meetingId]);

  /** Volta pro form LIMPO — "Enviar outra transcrição" começa do zero. */
  function resetToForm() {
    setPhase("form");
    setMeetingId(null);
    setPollingStatus("PENDING");
    setPollingError(null);
    pollCountRef.current = 0;
    setFlowsHint("loading");
    setFile(null);
    setTitle("");
    setTags([]);
    setParticipants([]);
    setStartedAt("");
    setEndedAt("");
    setFormError(null);
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  // ---------- Render ----------

  if (phase !== "form") {
    return (
      <div className="page page--narrow" style={{ maxWidth: 680 }}>
        <Breadcrumb />
        <StatusCard
          phase={phase}
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

  return (
    <div className="page page--narrow" style={{ maxWidth: 680 }}>
      <Breadcrumb />

      <header style={{ marginBottom: 26 }}>
        <h1 className="h1" style={{ fontSize: 26 }}>
          Nova reunião.
        </h1>
        <p className="lede" style={{ marginTop: 8 }}>
          Suba uma transcrição (.txt, .vtt ou .srt) — a análise começa em segundo plano.
        </p>
      </header>

      <form onSubmit={onSubmit} style={{ display: "flex", flexDirection: "column", gap: 18 }}>
        <Dropzone
          file={file}
          isOver={isOver}
          inputRef={fileInputRef}
          onOver={(v) => setIsOver(v)}
          onDrop={onDrop}
          onFileChange={onFileChange}
        />

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

        <div style={{ display: "grid", gridTemplateColumns: "1fr 1fr", gap: 14 }}>
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
        </div>

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

        <ChipField
          id="p-input"
          label="Participantes"
          help="Aparecem no detalhe da reunião e ajudam a NORA a atribuir action items."
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
            Enviar e analisar
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
// Dropzone — drag & drop + clique pra escolher arquivo
// ---------------------------------------------------------------------------

interface DropzoneProps {
  file: File | null;
  isOver: boolean;
  inputRef: React.RefObject<HTMLInputElement>;
  onOver: (over: boolean) => void;
  onDrop: (e: React.DragEvent) => void;
  onFileChange: (e: React.ChangeEvent<HTMLInputElement>) => void;
}

function Dropzone({ file, isOver, inputRef, onOver, onDrop, onFileChange }: DropzoneProps) {
  const hasFile = file !== null;
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
      aria-label="Selecionar arquivo de transcrição"
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
        {hasFile ? file.name : "Arraste a transcrição aqui ou clique pra escolher"}
      </div>
      <div style={{ fontSize: 11.5, color: "var(--muted)" }}>
        {hasFile
          ? `${(file.size / 1024).toFixed(1)} KB · clique pra trocar`
          : ".txt · .vtt · .srt — até 10 MB"}
      </div>
      <input
        ref={inputRef}
        type="file"
        accept=".txt,.vtt,.srt,text/plain"
        onChange={onFileChange}
        style={{ display: "none" }}
      />
    </div>
  );
}

// ---------------------------------------------------------------------------
// ChipField — input de chips (participantes / tags)
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
// StatusCard — feedback visual durante upload + polling
// ---------------------------------------------------------------------------

interface StatusCardProps {
  phase: Exclude<Phase, "form">;
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
  // "uploading" = POST em andamento; "failed" = erro terminal. Os demais
  // (polling/completed/timeout) compartilham o card "Transcrição enviada"
  // com status ao vivo — o usuário fica livre desde o primeiro segundo.
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

  // Enviado: status da análise ao vivo + ações livres.
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

      {flowsHint === "has-flows" && (
        <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0, maxWidth: 400, lineHeight: 1.55 }}>
          Seus fluxos ativos serão executados quando a análise terminar.
        </p>
      )}
      {flowsHint === "no-flows" && (
        <p style={{ fontSize: 12.5, color: "var(--muted)", margin: 0, maxWidth: 400, lineHeight: 1.55 }}>
          Para receber um aviso ao fim da análise, crie um fluxo com o gatilho
          &quot;Reunião analisada&quot; em{" "}
          <Link href={"/fluxos" as Route} style={{ color: "var(--accent-ink)", textDecoration: "underline", textUnderlineOffset: 2 }}>
            Fluxos
          </Link>
          .
        </p>
      )}

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
      {/* keyframe local do spinner — nao existe global noraSpin no design system */}
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

/** Spinner pequeno pro chip de status ao vivo. */
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

// ---------------------------------------------------------------------------
// Icones inline (sem libs novas — projeto ja usa SVG inline)
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
