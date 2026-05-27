"use client";

/**
 * Upload de reuniao (Subfase 1.3 U)
 * -------------------------------------------------------------------------
 * Fluxo:
 *
 *   form -> uploading -> polling -> { completed | failed | timeout }
 *
 * Apos o POST `/meetings` (que retorna `{id, processingStatus: "PENDING"}`),
 * em vez de redirecionar direto pra `/meetings/{id}` (UX porosa: usuario via
 * pagina PENDING e tinha que recarregar manualmente), entramos em modo
 * polling: chamamos `getMeeting(id)` a cada 2s ate o backend reportar
 * `COMPLETED` ou `FAILED`. Em `COMPLETED` redirecionamos com pequeno delay
 * pra dar feedback visual. Em `FAILED` mostramos erro com cta "Tentar
 * novamente" (volta ao form) e link manual pra reuniao.
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
 */

import { useEffect, useRef, useState } from "react";
import { useRouter } from "next/navigation";
import Link from "next/link";
import type { Route } from "next";
import { ApiRequestError, getMeeting, uploadMeeting } from "@/lib/api/client";
import type { ProcessingStatus } from "@/lib/api/types";
import {
  Banner,
  Button,
  ButtonLink,
  Card,
  Field,
  Input,
  PageHeader,
  Select,
} from "@/components/core/ui";

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
/** Delay de redirect apos COMPLETED — janela curta pro usuario ver o feedback verde. */
const REDIRECT_DELAY_MS = 500;

type Phase = "form" | "uploading" | "polling" | "completed" | "failed" | "timeout";

export default function UploadMeetingPage() {
  const router = useRouter();

  // Form state
  const [title, setTitle] = useState("");
  const [language, setLanguage] = useState("pt-BR");
  const [format, setFormat] = useState<Format>("TXT");
  const [tags, setTags] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [formError, setFormError] = useState<string | null>(null);

  // Phase machine
  const [phase, setPhase] = useState<Phase>("form");
  const [meetingId, setMeetingId] = useState<string | null>(null);
  const [pollingStatus, setPollingStatus] = useState<ProcessingStatus>("PENDING");
  const [pollingError, setPollingError] = useState<string | null>(null);

  // Counter vive em ref pra nao recriar o interval a cada tick.
  const pollCountRef = useRef(0);

  function onFileChange(e: React.ChangeEvent<HTMLInputElement>) {
    const f = e.target.files?.[0] ?? null;
    setFile(f);
    if (f) {
      const ext = f.name.split(".").pop()?.toLowerCase();
      if (ext && FORMAT_BY_EXT[ext]) setFormat(FORMAT_BY_EXT[ext]);
      if (!title) setTitle(f.name.replace(/\.[^.]+$/, ""));
    }
  }

  async function onSubmit(e: React.FormEvent) {
    e.preventDefault();
    setFormError(null);
    if (!file) {
      setFormError("Selecione um arquivo de transcricao.");
      return;
    }
    setPhase("uploading");
    try {
      const r = await uploadMeeting({
        title: title.trim(),
        language,
        transcriptFormat: format,
        tags: tags
          .split(",")
          .map((t) => t.trim())
          .filter(Boolean),
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

  // Quando completed, redireciona com pequeno delay pro feedback visual.
  useEffect(() => {
    if (phase !== "completed" || !meetingId) return;
    const t = setTimeout(() => {
      router.push(`/meetings/${meetingId}` as Route);
      router.refresh();
    }, REDIRECT_DELAY_MS);
    return () => clearTimeout(t);
  }, [phase, meetingId, router]);

  function resetToForm() {
    setPhase("form");
    setMeetingId(null);
    setPollingStatus("PENDING");
    setPollingError(null);
    pollCountRef.current = 0;
  }

  // ---------- Render ----------

  if (phase !== "form") {
    return (
      <div style={{ maxWidth: 640 }}>
        <StatusCard
          phase={phase}
          status={pollingStatus}
          meetingId={meetingId}
          pollingError={pollingError}
          onRetry={resetToForm}
        />
      </div>
    );
  }

  return (
    <div style={{ maxWidth: 640 }}>
      <PageHeader
        title="Nova reunião"
        subtitle="Suba uma transcrição (.txt, .vtt ou .srt) — a análise começa em segundo plano."
      />

      <Card>
        <form onSubmit={onSubmit}>
          <Field label="Título" htmlFor="meeting-title" required>
            <Input
              id="meeting-title"
              required
              value={title}
              onChange={(e) => setTitle(e.target.value)}
              placeholder="Discovery Acme – outubro/2025"
            />
          </Field>

          <div
            style={{
              display: "grid",
              gridTemplateColumns: "1fr 1fr",
              gap: 16,
            }}
          >
            <Field label="Idioma" htmlFor="meeting-language">
              <Select
                id="meeting-language"
                value={language}
                onChange={(e) => setLanguage(e.target.value)}
              >
                <option value="pt-BR">Português (BR)</option>
                <option value="en-US">Inglês (US)</option>
                <option value="es-ES">Espanhol</option>
              </Select>
            </Field>

            <Field label="Formato" htmlFor="meeting-format">
              <Select
                id="meeting-format"
                value={format}
                onChange={(e) => setFormat(e.target.value as Format)}
              >
                <option value="TXT">TXT</option>
                <option value="VTT">VTT</option>
                <option value="SRT">SRT</option>
              </Select>
            </Field>
          </div>

          <Field label="Tags (separadas por vírgula)" htmlFor="meeting-tags">
            <Input
              id="meeting-tags"
              value={tags}
              onChange={(e) => setTags(e.target.value)}
              placeholder="discovery, renovacao"
            />
          </Field>

          <Field label="Arquivo de transcrição" htmlFor="meeting-file">
            <input
              id="meeting-file"
              type="file"
              accept=".txt,.vtt,.srt,text/plain"
              onChange={onFileChange}
              style={{ display: "block", width: "100%", fontSize: 13, color: "var(--ink)" }}
            />
            {file && (
              <p style={{ fontSize: 11.5, color: "var(--muted)", marginTop: 6 }}>
                {file.name} · {(file.size / 1024).toFixed(1)} KB
              </p>
            )}
          </Field>

          {formError && (
            <div style={{ marginBottom: 16 }}>
              <Banner tone="error">{formError}</Banner>
            </div>
          )}

          <div style={{ display: "flex", gap: 8 }}>
            <Button type="submit" variant="primary">
              Enviar e analisar
            </Button>
            <Button type="button" onClick={() => router.back()}>
              Cancelar
            </Button>
          </div>
        </form>
      </Card>
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
  pollingError: string | null;
  onRetry: () => void;
}

function StatusCard({ phase, status, meetingId, pollingError, onRetry }: StatusCardProps) {
  // phase = "uploading" e o passo do POST; phase = "polling" usa `status`.
  const view = pickView(phase, status);

  return (
    <Card style={{ padding: 32, textAlign: "center" }}>
      <section role="status" aria-live="polite">
        <div
          style={{
            margin: "0 auto",
            display: "flex",
            maxWidth: 384,
            flexDirection: "column",
            alignItems: "center",
            gap: 16,
          }}
        >
          <div
            style={{
              display: "flex",
              height: 56,
              width: 56,
              alignItems: "center",
              justifyContent: "center",
            }}
          >
            {view.icon}
          </div>
          <div>
            <h2 style={{ fontSize: 16, fontWeight: 500, color: "var(--ink)", margin: 0 }}>
              {view.title}
            </h2>
            {view.subtitle && (
              <p style={{ fontSize: 13.5, color: "var(--muted)", margin: "6px 0 0", lineHeight: 1.5 }}>
                {view.subtitle}
              </p>
            )}
          </div>

          {pollingError && phase === "polling" && (
            <p
              style={{
                border: "1px solid oklch(0.85 0.08 70)",
                background: "oklch(0.96 0.05 70)",
                color: "var(--warn)",
                borderRadius: "var(--radius-sm)",
                padding: "8px 12px",
                fontSize: 11.5,
                margin: 0,
              }}
            >
              {pollingError}
            </p>
          )}

          {phase === "failed" && (
            <div
              style={{
                display: "flex",
                flexDirection: "column",
                alignItems: "stretch",
                gap: 8,
                paddingTop: 8,
              }}
            >
              <Button type="button" variant="primary" onClick={onRetry}>
                Tentar novamente
              </Button>
              {meetingId && (
                <Link
                  href={`/meetings/${meetingId}` as Route}
                  style={{ fontSize: 13, color: "var(--accent-ink)", textDecoration: "none" }}
                >
                  Ver detalhes da reunião
                </Link>
              )}
            </div>
          )}

          {phase === "timeout" && meetingId && (
            <ButtonLink href={`/meetings/${meetingId}` as Route}>Ver reunião</ButtonLink>
          )}
        </div>
      </section>
    </Card>
  );
}

interface View {
  icon: React.ReactNode;
  title: string;
  subtitle?: string;
}

function pickView(phase: Exclude<Phase, "form">, status: ProcessingStatus): View {
  if (phase === "uploading") {
    return { icon: <Spinner tone="slate" />, title: "Enviando transcrição..." };
  }
  if (phase === "completed") {
    return {
      icon: <CheckIcon />,
      title: "Análise pronta",
      subtitle: "Redirecionando...",
    };
  }
  if (phase === "failed") {
    return {
      icon: <XIcon />,
      title: "Falha na análise",
      subtitle:
        "O processamento da reunião falhou. Tente novamente ou abra os detalhes para mais informações.",
    };
  }
  if (phase === "timeout") {
    return {
      icon: <AlertIcon />,
      title: "Processamento demorou mais que o esperado",
      subtitle:
        "Você pode acompanhar a análise diretamente na página da reunião — ela continua rodando em segundo plano.",
    };
  }
  // phase === "polling": detalha o status atual
  if (status === "PROCESSING") {
    return {
      icon: <Spinner tone="blue" />,
      title: "Analisando reunião com IA...",
      subtitle: "Isso pode levar até 30 segundos.",
    };
  }
  // PENDING (ou FAILED/COMPLETED que ja foram tratados acima)
  return {
    icon: <Spinner tone="slate" />,
    title: "Aguardando processamento",
    subtitle: "A reunião está na fila do worker.",
  };
}

// ---------------------------------------------------------------------------
// Icones inline (sem libs novas — projeto ja usa SVG inline)
// ---------------------------------------------------------------------------

function Spinner({ tone }: { tone: "slate" | "blue" }) {
  const color = tone === "blue" ? "var(--accent)" : "var(--muted)";
  return (
    <svg
      className="animate-spin"
      style={{ height: 40, width: 40, color }}
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      aria-hidden="true"
    >
      <circle
        cx="12"
        cy="12"
        r="10"
        stroke="currentColor"
        strokeWidth="4"
        style={{ opacity: 0.25 }}
      />
      <path
        fill="currentColor"
        d="M4 12a8 8 0 018-8v4a4 4 0 00-4 4H4z"
        style={{ opacity: 0.75 }}
      />
    </svg>
  );
}

function CheckIcon() {
  return (
    <svg
      style={{ height: 40, width: 40, color: "var(--success)" }}
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth="2"
      aria-hidden="true"
    >
      <path strokeLinecap="round" strokeLinejoin="round" d="M5 13l4 4L19 7" />
    </svg>
  );
}

function XIcon() {
  return (
    <svg
      style={{ height: 40, width: 40, color: "var(--danger)" }}
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth="2"
      aria-hidden="true"
    >
      <path strokeLinecap="round" strokeLinejoin="round" d="M6 18L18 6M6 6l12 12" />
    </svg>
  );
}

function AlertIcon() {
  return (
    <svg
      style={{ height: 40, width: 40, color: "var(--warn)" }}
      xmlns="http://www.w3.org/2000/svg"
      fill="none"
      viewBox="0 0 24 24"
      stroke="currentColor"
      strokeWidth="2"
      aria-hidden="true"
    >
      <path
        strokeLinecap="round"
        strokeLinejoin="round"
        d="M12 9v3.75m0 3.75h.008v.008H12v-.008zM10.29 3.86L1.82 18a2 2 0 001.71 3h16.94a2 2 0 001.71-3L13.71 3.86a2 2 0 00-3.42 0z"
      />
    </svg>
  );
}
