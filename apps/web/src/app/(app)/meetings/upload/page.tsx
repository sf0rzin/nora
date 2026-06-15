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
 * Lote (multi-upload): a dropzone aceita varios arquivos. Com 1 arquivo o
 * fluxo acima permanece IDENTICO (mesmo card, mesmo status ao vivo). Com 2+
 * escondemos titulo/inicio/termino/formato (titulo deriva do nome de cada
 * arquivo e o formato da extensao; idioma/participantes/tags valem para
 * todos), enviamos com concorrencia 2 (BATCH_CONCURRENCY) e mostramos
 * progresso por arquivo com "Tentar de novo" individual ao final. O card do
 * lote NAO faz polling de N reunioes — as analises seguem em segundo plano
 * e o Inicio (que ja tem polling de PROCESSING) acompanha o progresso.
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
import { ApiRequestError, getMeeting, listWorkflows, splitPreview, uploadMeeting } from "@/lib/api/client";
import type { ProcessingStatus, SplitSegment } from "@/lib/api/types";

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
/** Uploads simultaneos no modo lote — 2 evita rajada de POSTs no backend. */
const BATCH_CONCURRENCY = 2;
/** Limiar de confianca abaixo do qual mostramos o badge ambar de aviso. */
const SPLIT_CONFIDENCE_WARN = 0.7;

type Phase = "form" | "split-loading" | "split-confirm" | "uploading" | "polling" | "completed" | "failed" | "timeout";

/** Estado da dica de notificacao via Flows no card pos-envio. */
type FlowsHint = "loading" | "has-flows" | "no-flows" | "unavailable";

/** Um arquivo do lote (modo 2+ arquivos). */
interface BatchItem {
  id: number;
  file: File;
  /** Derivado do nome do arquivo (sem extensao), como no modo single. */
  title: string;
  format: Format;
  status: "queued" | "uploading" | "done" | "error";
  error?: string;
}

/**
 * Estado de um segmento na tela de confirmacao do split. Titulo editavel +
 * flag included (default true).
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

/** Converte titulo em slug seguro para nome de arquivo (.txt). */
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
 * Fatia o arquivo original pelo intervalo de linhas (1-based, inclusivo).
 * Normaliza CRLF antes do split.
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

  // Phase machine (modo single — 0 ou 1 arquivo)
  const [phase, setPhase] = useState<Phase>("form");
  const [meetingId, setMeetingId] = useState<string | null>(null);
  const [pollingStatus, setPollingStatus] = useState<ProcessingStatus>("PENDING");
  const [pollingError, setPollingError] = useState<string | null>(null);

  // Lote (2+ arquivos). `null` = fora do modo lote; o array existe a partir
  // do submit e guarda o progresso de cada arquivo.
  const [batchItems, setBatchItems] = useState<BatchItem[] | null>(null);
  // Toggle "separar automaticamente" — visivel so com 1 .txt selecionado.
  const [splitEnabled, setSplitEnabled] = useState(false);

  // Segmentos da tela de confirmacao do split.
  const [splitSegments, setSplitSegments] = useState<ConfirmSegment[] | null>(null);
  // Guard do "Criar N reunioes": ref pro bloqueio sincrono (duplo-clique /
  // race pos-cancelamento) + state pro feedback visual (botao desabilitado).
  const splitSubmittingRef = useRef(false);
  const [splitSubmitting, setSplitSubmitting] = useState(false);

  // Counter vive em ref pra nao recriar o interval a cada tick.
  const pollCountRef = useRef(0);
  // O toggle split so aparece com exatamente 1 arquivo .txt.
  const singleTxtFile =
    files.length === 1 && files[0].name.toLowerCase().endsWith(".txt")
      ? files[0]
      : null;

  // Quando o arquivo muda para nao-.txt ou 2+, desabilita o toggle.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => {
    if (!singleTxtFile) setSplitEnabled(false);
  }, [singleTxtFile]);


  /** Espelha titulo/formato pro arquivo unico (comportamento single de sempre). */
  function applySingleDefaults(f: File, opts?: { overwriteTitle?: boolean }) {
    setFormat(detectFormat(f.name));
    if (opts?.overwriteTitle || !title) setTitle(deriveTitle(f.name));
  }

  function applyFiles(incoming: File[]) {
    if (incoming.length === 0) return;
    let next: File[];
    if (files.length === 1 && incoming.length === 1) {
      // 1 arquivo ja selecionado + 1 novo = TROCA ("clique pra trocar"),
      // exatamente como o single-file sempre fez.
      next = incoming;
    } else {
      // Demais casos acumulam (permite arrastar em varias levas), com
      // dedupe por nome+tamanho.
      next = [...files];
      for (const f of incoming) {
        if (!next.some((m) => m.name === f.name && m.size === f.size)) next.push(f);
      }
    }
    setFiles(next);
    if (next.length === 1) applySingleDefaults(next[0]);
    // Limpa o input pra permitir re-selecionar os mesmos arquivos depois
    // (o evento change nao dispararia com o mesmo value).
    if (fileInputRef.current) fileInputRef.current.value = "";
  }

  function removeFile(index: number) {
    const next = files.filter((_, i) => i !== index);
    setFiles(next);
    // De volta ao modo single: titulo/formato passam a refletir o arquivo
    // restante (no lote o titulo vinha do nome de cada arquivo).
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

  /** Volta da tela de confirmacao pro form com o arquivo mantido. */
  function cancelSplitConfirm() {
    // Libera o guard: um confirmSplit em voo (await text()) ve o ref false e
    // aborta antes de disparar uploads-fantasma.
    splitSubmittingRef.current = false;
    setSplitSubmitting(false);
    setSplitSegments(null);
    setPhase("form");
  }

  /**
   * Confirma o split: fatia o arquivo client-side e injeta os segmentos no
   * pool de lote do #253 (mesmo pool, mesma concorrencia, mesmo card).
   */
  async function confirmSplit(segments: ConfirmSegment[]) {
    if (!singleTxtFile) return;
    const included = segments.filter((s) => s.included);
    if (included.length === 0) return;
    // Guard sincrono: bloqueia duplo-clique no "Criar N reunioes" (cada clique
    // dispararia um runBatch e duplicaria os uploads). Setado ANTES do await.
    if (splitSubmittingRef.current) return;
    splitSubmittingRef.current = true;
    setSplitSubmitting(true);

    const text = await singleTxtFile.text();
    // Cancelado durante a leitura (usuario clicou "Voltar")? Aborta sem upload.
    if (!splitSubmittingRef.current) return;

    // Desambigua nomes: dois segmentos com titulos que geram o mesmo slug
    // (ex.: "Reuniao" e "reuniao") nao podem virar o mesmo arquivo. O lote
    // rastreia por id, mas nomes iguais confundiriam o card de progresso.
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

  // ---------- Lote: envio com concorrencia limitada + retry individual ----------

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
      // Falha de um arquivo NAO cancela os demais — o worker do pool segue
      // pro proximo e o item ganha "Tentar de novo" no final.
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
    // 1 arquivo .txt com split habilitado: tela de confirmacao.
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
  // tolerada — a dica simplesmente nao aparece. Vale pro single (pos-POST)
  // e pro lote (a partir do submit — fluxos nao mudam durante o envio).
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
  }, [phase, meetingId, batchItems]);

  /** Volta pro form LIMPO — "Enviar outra transcrição" / "Enviar mais" começa do zero. */
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

  // Split-loading: spinner enquanto aguarda o split-preview.
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

  // Split-confirm: tela de revisao dos segmentos detectados.
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

  // Modo lote tem precedencia: `batchItems` so existe apos o submit com 2+
  // arquivos (phase fica em "form" — a maquina single nao roda no lote).
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

  // 2+ arquivos = modo lote no form: titulo/inicio/termino/formato somem
  // (titulo vem do nome de cada arquivo, formato da extensao); idioma,
  // participantes e tags valem para todos.
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

        {/* Toggle de split — visivel so com 1 arquivo .txt */}
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
// SplitToggle — checkbox discreto para habilitar a separacao automatica
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
// SplitConfirmScreen — tela de revisao dos segmentos detectados
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
// SegmentCard — um segmento editavel na tela de confirmacao
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
// Dropzone — drag & drop + clique pra escolher arquivos (1 ou varios)
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
// FileList — arquivos do lote no form (nome, tamanho, formato, remover)
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
 * Dica de notificacao via Flows — compartilhada entre o card single e o card
 * do lote. `plural` ajusta a frase pro caso de varias analises.
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
          href={"/fluxos" as Route}
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
// BatchCard — progresso do lote (2+ arquivos): por-arquivo + resumo final.
// Sem polling de status das N reunioes — as analises seguem em segundo plano
// e o Inicio (polling de PROCESSING) acompanha o progresso.
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



