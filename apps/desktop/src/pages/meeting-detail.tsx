import { useCallback, useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import { getMeeting, reprocessMeeting } from "@/lib/meetings";
import type {
  MeetingDetail,
  ApiError,
  ActionItem,
  Decision,
  Risk,
  Opportunity,
} from "@/lib/types";
import { ShaderOrb } from "@/components/brand/shader-orb";
import { AvatarStack } from "@/components/brand/avatar";
import { Spinner } from "@/components/spinner";
import { Chip } from "@/components/chip";

function Section({
  label,
  right,
  children,
}: {
  label: string;
  right?: React.ReactNode;
  children: React.ReactNode;
}) {
  return (
    <section style={{ marginBottom: 36 }}>
      <div
        className="flex items-baseline justify-between"
        style={{ marginBottom: 14, paddingBottom: 8, borderBottom: "1px solid var(--border)" }}
      >
        <h2
          style={{
            fontSize: 10.5,
            fontWeight: 500,
            letterSpacing: "0.08em",
            textTransform: "uppercase",
            color: "var(--muted)",
            margin: 0,
          }}
        >
          {label}
        </h2>
        {right && (
          <span style={{ fontSize: 11, color: "var(--muted)" }}>{right}</span>
        )}
      </div>
      {children}
    </section>
  );
}

function ActionRow({ a, first }: { a: ActionItem; first: boolean }) {
  const [open, setOpen] = useState(false);
  const prColor =
    a.priority === "HIGH"
      ? "#a04c3e"
      : a.priority === "MEDIUM"
        ? "#a37528"
        : "var(--muted)";
  const statusColor =
    a.status === "DONE"
      ? "#3f8a5e"
      : a.status === "IN_PROGRESS"
        ? "#a37528"
        : "var(--muted)";

  return (
    <div
      style={{
        borderTop: first ? "none" : "1px solid var(--border)",
        padding: "12px 0",
      }}
    >
      <div
        className="flex items-start gap-3"
        style={{ cursor: a.sourceQuote ? "pointer" : "default" }}
        onClick={() => a.sourceQuote && setOpen(!open)}
      >
        <input
          type="checkbox"
          checked={a.status === "DONE"}
          readOnly
          disabled
          title={a.status === "DONE" ? "Concluído" : "Pendente"}
          style={{ marginTop: 4, accentColor: "var(--accent)" }}
          onClick={(e) => e.stopPropagation()}
        />
        <div className="flex-1 min-w-0">
          <div style={{ fontSize: 14, color: "var(--ink)", lineHeight: 1.4 }}>
            {a.title}
          </div>
          <div
            className="flex items-center gap-3 flex-wrap"
            style={{ marginTop: 4, fontSize: 11.5, color: "var(--muted)" }}
          >
            {a.assignee && <span>{a.assignee}</span>}
            {a.dueDate && <span>Vence {a.dueDate}</span>}
            <span style={{ color: prColor, letterSpacing: "0.04em" }}>{a.priority}</span>
            <span style={{ color: statusColor }}>{a.status}</span>
          </div>
        </div>
        {a.sourceQuote && (
          <span
            className="grid place-items-center"
            style={{
              width: 26,
              height: 26,
              transform: open ? "rotate(180deg)" : "none",
              transition: "transform 0.15s",
              color: "var(--muted)",
            }}
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round">
              <polyline points="6 9 12 15 18 9" />
            </svg>
          </span>
        )}
      </div>
      {open && a.sourceQuote && (
        <div
          style={{
            marginLeft: 28,
            marginTop: 10,
            padding: "10px 14px",
            background: "var(--chip)",
            borderRadius: 6,
            fontSize: 12.5,
            color: "var(--muted)",
            fontStyle: "italic",
            lineHeight: 1.5,
            borderLeft: "2px solid var(--accent)",
          }}
        >
          "{a.sourceQuote}"
        </div>
      )}
    </div>
  );
}

function RiskCard({ r }: { r: Risk }) {
  const colors = {
    HIGH: { bg: "rgba(201,119,102,0.12)", border: "rgba(201,119,102,0.32)", fg: "#a04c3e" },
    MEDIUM: { bg: "rgba(212,160,76,0.12)", border: "rgba(212,160,76,0.32)", fg: "#a37528" },
    LOW: { bg: "var(--sidebar)", border: "var(--border)", fg: "var(--muted)" },
  };
  const c = colors[r.severity];
  return (
    <div
      style={{
        padding: "10px 12px",
        background: c.bg,
        border: `1px solid ${c.border}`,
        borderRadius: 8,
        fontSize: 13,
        lineHeight: 1.5,
        color: "var(--ink)",
      }}
    >
      <span style={{ color: c.fg, fontSize: 10.5, letterSpacing: "0.06em", textTransform: "uppercase", fontWeight: 500 }}>
        {r.severity}
      </span>
      <span style={{ margin: "0 8px", color: "var(--muted)" }}>·</span>
      <span>{r.text}</span>
    </div>
  );
}

function OpportunityCard({ o }: { o: Opportunity }) {
  return (
    <div
      style={{
        padding: "10px 12px",
        background: "rgba(98,181,133,0.10)",
        border: "1px solid rgba(98,181,133,0.30)",
        borderRadius: 8,
        fontSize: 13,
        lineHeight: 1.5,
        color: "var(--ink)",
      }}
    >
      <span style={{ color: "#3f8a5e", fontSize: 10.5, letterSpacing: "0.06em", textTransform: "uppercase", fontWeight: 500 }}>
        {o.category}
      </span>
      <span style={{ margin: "0 8px", color: "var(--muted)" }}>·</span>
      <span>{o.text}</span>
      {o.estimatedValue && (
        <span style={{ marginLeft: 8, fontSize: 11.5, color: "var(--muted)" }}>
          ({o.estimatedValue})
        </span>
      )}
    </div>
  );
}

export function MeetingDetailPage({ meetingId }: { meetingId: string }) {
  const [meeting, setMeeting] = useState<MeetingDetail | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [reprocessing, setReprocessing] = useState(false);
  const [reprocessError, setReprocessError] = useState("");
  const aliveRef = useRef(true);

  useEffect(() => {
    aliveRef.current = true;
    return () => {
      aliveRef.current = false;
    };
  }, []);

  const refetch = useCallback(async (opts?: { silent?: boolean }) => {
    try {
      const d = await getMeeting(meetingId);
      if (aliveRef.current) {
        setMeeting(d);
        setError("");
      }
    } catch (err: unknown) {
      // Falha de polling em segundo plano NÃO derruba a tela já carregada.
      // Só o load inicial (silent=false) seta o erro de página inteira — que é
      // o que o gate `if (error || !meeting)` usa pra mostrar a tela de erro.
      // Sem isso, um blip de rede / 401 durante o polling fazia a reunião
      // sumir e voltar a cada 4s.
      const apiErr = err as ApiError;
      if (aliveRef.current && !opts?.silent)
        setError(apiErr?.message || "Erro ao carregar reunião");
    }
  }, [meetingId]);

  useEffect(() => {
    setLoading(true);
    setError("");
    refetch().finally(() => {
      if (aliveRef.current) setLoading(false);
    });
  }, [refetch]);

  // Enquanto está PENDING/PROCESSING, faz polling pra refletir o resultado
  // (sucesso OU falha) sem o usuário precisar sair e voltar. Para sozinho
  // quando o status sai desses estados.
  useEffect(() => {
    const status = meeting?.processingStatus;
    if (status !== "PENDING" && status !== "PROCESSING") return;
    const id = setInterval(() => {
      void refetch({ silent: true });
    }, 4000);
    return () => clearInterval(id);
  }, [meeting?.processingStatus, refetch]);

  const handleReprocess = useCallback(async () => {
    if (reprocessing) return;
    setReprocessing(true);
    setReprocessError("");
    try {
      await reprocessMeeting(meetingId);
      // Otimista: volta pra PENDING e o polling acima assume daqui.
      setMeeting((m) => (m ? { ...m, processingStatus: "PENDING" } : m));
      // silent: um blip nesse refetch não deve derrubar a tela — o estado já
      // está otimisticamente em PENDING e o polling reconcilia.
      await refetch({ silent: true });
    } catch (err: unknown) {
      const apiErr = err as ApiError;
      if (aliveRef.current)
        setReprocessError(apiErr?.message || "Não foi possível reprocessar agora.");
    } finally {
      if (aliveRef.current) setReprocessing(false);
    }
  }, [meetingId, reprocessing, refetch]);

  if (loading) {
    return (
      <main
        className="flex-1 flex items-center justify-center"
        style={{ background: "var(--canvas)", color: "var(--muted)" }}
      >
        Carregando reunião…
      </main>
    );
  }

  if (error || !meeting) {
    return (
      <main className="flex-1 flex items-center justify-center" style={{ background: "var(--canvas)" }}>
        <div className="text-center max-w-md">
          <p style={{ color: "var(--danger-ink)", fontSize: 14, marginBottom: 12 }}>
            {error || "Reunião não encontrada."}
          </p>
          <a
            href="#/meetings"
            style={{ fontSize: 13, color: "var(--accent-ink)" }}
            className="hover:underline"
          >
            ← Voltar para reuniões
          </a>
        </div>
      </main>
    );
  }

  const analysis = meeting.analysis;
  const participants = meeting.participants?.map((p) => p.displayName) ?? [];
  const isProcessing =
    meeting.processingStatus === "PROCESSING" ||
    meeting.processingStatus === "PENDING";
  const isFailed = meeting.processingStatus === "FAILED";
  const isReady = meeting.processingStatus === "COMPLETED" && analysis;

  const date = new Date(meeting.startedAt);
  const dateLabel = date.toLocaleString("pt-BR", {
    day: "2-digit",
    month: "short",
    hour: "2-digit",
    minute: "2-digit",
  });

  return (
    <main className="flex-1 overflow-y-auto" style={{ background: "var(--canvas)" }}>
      <div style={{ maxWidth: 760, margin: "0 auto", padding: "44px 56px 80px" }}>
        <div
          className="flex items-center gap-2"
          style={{
            fontSize: 12,
            color: "var(--muted)",
            marginBottom: 22,
            letterSpacing: "-0.005em",
          }}
        >
          <a
            href="#/meetings"
            style={{ color: "var(--muted)" }}
            onMouseEnter={(e) => (e.currentTarget.style.color = "var(--ink)")}
            onMouseLeave={(e) => (e.currentTarget.style.color = "var(--muted)")}
          >
            Reuniões
          </a>
          <span style={{ opacity: 0.5 }}>/</span>
          <span style={{ color: "var(--accent-ink)" }}>
            {meeting.tags[0] || "Sem projeto"}
          </span>
        </div>

        <div
          className="flex items-start gap-8 mb-7"
          style={{ marginBottom: 28 }}
        >
          <div className="flex-1 min-w-0">
            <h1
              style={{
                fontFamily: "var(--display)",
                fontWeight: 500,
                fontSize: 26,
                letterSpacing: "-0.022em",
                lineHeight: 1.2,
                color: "var(--ink)",
                margin: "0 0 14px",
              }}
            >
              {meeting.title}
            </h1>
            <div className="flex items-center gap-2 flex-wrap">
              {participants.length > 0 ? (
                participants.map((p, i) => (
                  <Chip key={i}>{p}</Chip>
                ))
              ) : (
                <Chip>{meeting.ownerName || meeting.owner?.displayName || "Você"}</Chip>
              )}
              {meeting.tags.slice(1).map((t) => (
                <Chip key={t} variant="accent" fontSize={11}>{t}</Chip>
              ))}
            </div>
          </div>

          <div className="flex flex-col items-end gap-3.5 shrink-0">
            <div
              className="text-right whitespace-nowrap"
              style={{ fontSize: 12, color: "var(--muted)" }}
            >
              {dateLabel}
              {meeting.durationSeconds && (
                <> · {Math.max(1, Math.round(meeting.durationSeconds / 60))}min</>
              )}
            </div>
            <ShaderOrb
              size={72}
              speed={isProcessing ? 1.6 : 1}
              intensity={isReady ? 0.85 : isProcessing ? 1 : 0.5}
            />
          </div>
        </div>

        {participants.length > 0 && (
          <div className="flex justify-end mb-7" style={{ marginTop: -12 }}>
            <AvatarStack names={participants} />
          </div>
        )}

        {isProcessing && (
          <div
            style={{
              padding: "16px 18px",
              background: "var(--accent-soft)",
              border: "1px solid var(--accent-soft)",
              borderRadius: 12,
              fontSize: 13,
              color: "var(--accent-ink)",
              marginBottom: 36,
            }}
          >
            NORA está processando essa reunião — resumo, decisões e action items aparecem aqui em alguns segundos.
          </div>
        )}

        {isFailed && (
          <div
            className="flex items-start justify-between gap-4"
            style={{
              padding: "16px 18px",
              background: "var(--danger-soft-bg)",
              border: "1px solid rgba(201,119,102,0.30)",
              borderRadius: 12,
              marginBottom: 36,
            }}
          >
            <div className="flex-1 min-w-0">
              <div
                style={{
                  fontSize: 13.5,
                  fontWeight: 500,
                  color: "var(--danger-ink)",
                  marginBottom: 3,
                  letterSpacing: "-0.005em",
                }}
              >
                Falha no processamento
              </div>
              <div style={{ fontSize: 12.5, color: "var(--danger-ink)", opacity: 0.85, lineHeight: 1.5 }}>
                A NORA não conseguiu analisar essa transcrição. Ela já está salva —
                você pode reprocessar agora.
                {reprocessError && (
                  <span style={{ display: "block", marginTop: 4, opacity: 0.95 }}>
                    {reprocessError}
                  </span>
                )}
              </div>
            </div>
            <button
              onClick={handleReprocess}
              disabled={reprocessing}
              className="inline-flex items-center gap-1.5 shrink-0"
              style={{
                padding: "7px 13px",
                background: "var(--ink)",
                color: "var(--canvas)",
                border: "none",
                borderRadius: 8,
                fontSize: 12.5,
                fontWeight: 500,
                letterSpacing: "-0.005em",
                cursor: reprocessing ? "default" : "pointer",
                opacity: reprocessing ? 0.6 : 1,
                fontFamily: "var(--sans)",
              }}
            >
              {reprocessing ? (
                <>
                  <Spinner size={10} thickness={1.5} color="rgba(253,253,252,0.4)" topColor="var(--canvas)" />
                  Reprocessando…
                </>
              ) : (
                "Reprocessar"
              )}
            </button>
          </div>
        )}

        {isReady && (
          <>
            <Section
              label="Resumo"
              right={analysis.topics.length > 0 ? analysis.topics.join(" · ") : undefined}
            >
              {analysis.summary ? (
                <div className="nora-prose">
                  <ReactMarkdown>{analysis.summary}</ReactMarkdown>
                </div>
              ) : (
                <p style={{ fontSize: 13.5, color: "var(--muted)", fontStyle: "italic" }}>
                  Nenhum resumo disponível.
                </p>
              )}
              <div className="flex items-center gap-2 mt-3" style={{ fontSize: 11, color: "var(--muted)" }}>
                <span>Sentimento</span>
                <span style={{ color: "var(--ink)" }}>{analysis.sentimentOverall}</span>
              </div>
            </Section>

            {analysis.decisions.length > 0 && (
              <Section label="Decisões" right={`${analysis.decisions.length} registradas`}>
                <ul className="flex flex-col gap-2.5 m-0 p-0" style={{ listStyle: "none" }}>
                  {analysis.decisions.map((d: Decision, i: number) => (
                    <li
                      key={d.id}
                      className="flex gap-3"
                      style={{ fontSize: 14.5, lineHeight: 1.55, color: "var(--ink)" }}
                    >
                      <span
                        style={{
                          color: "var(--accent-ink)",
                          fontSize: 11.5,
                          marginTop: 3,
                          minWidth: 22,
                          fontVariantNumeric: "tabular-nums",
                        }}
                      >
                        {String(i + 1).padStart(2, "0")}
                      </span>
                      <span className="flex-1">
                        {d.text}
                        {d.confidence !== undefined && d.confidence < 0.85 && (
                          <span style={{ marginLeft: 8, fontSize: 11, color: "var(--muted)" }}>
                            · {Math.round(d.confidence * 100)}%
                          </span>
                        )}
                      </span>
                    </li>
                  ))}
                </ul>
              </Section>
            )}

            {analysis.actionItems.length > 0 && (
              <Section
                label="Action items"
                right={`${analysis.actionItems.length} detectados`}
              >
                <div className="flex flex-col">
                  {analysis.actionItems.map((a, i) => (
                    <ActionRow key={a.id} a={a} first={i === 0} />
                  ))}
                </div>
              </Section>
            )}

            {(analysis.risks.length > 0 || analysis.opportunities.length > 0) && (
              <div className="grid grid-cols-2 gap-4 mb-9">
                {analysis.risks.length > 0 && (
                  <section>
                    <h3
                      style={{
                        fontSize: 10.5,
                        fontWeight: 500,
                        letterSpacing: "0.08em",
                        textTransform: "uppercase",
                        color: "var(--muted)",
                        margin: "0 0 10px",
                      }}
                    >
                      Riscos
                    </h3>
                    <div className="flex flex-col gap-2">
                      {analysis.risks.map((r) => (
                        <RiskCard key={r.id} r={r} />
                      ))}
                    </div>
                  </section>
                )}
                {analysis.opportunities.length > 0 && (
                  <section>
                    <h3
                      style={{
                        fontSize: 10.5,
                        fontWeight: 500,
                        letterSpacing: "0.08em",
                        textTransform: "uppercase",
                        color: "var(--muted)",
                        margin: "0 0 10px",
                      }}
                    >
                      Oportunidades
                    </h3>
                    <div className="flex flex-col gap-2">
                      {analysis.opportunities.map((o) => (
                        <OpportunityCard key={o.id} o={o} />
                      ))}
                    </div>
                  </section>
                )}
              </div>
            )}
          </>
        )}

        <div
          className="flex items-center gap-3"
          style={{
            marginTop: 48,
            paddingTop: 18,
            borderTop: "1px solid var(--border)",
            fontSize: 11,
            color: "var(--muted)",
          }}
        >
          <span>Transcrição original</span>
          <span style={{ opacity: 0.4 }}>·</span>
          <span>Exportar resumo</span>
          <span className="ml-auto inline-flex items-center gap-1.5">
            <span style={{ width: 6, height: 6, borderRadius: "50%", background: "#62b585" }} />
            PII Shield aplicado
          </span>
        </div>
      </div>
    </main>
  );
}
