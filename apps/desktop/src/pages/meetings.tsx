import { useEffect, useMemo, useRef, useState } from "react";
import { useAuth } from "@/hooks/use-auth";
import { listMeetings } from "@/lib/meetings";
import type { MeetingSummary, ApiError } from "@/lib/types";
import { ShaderOrb } from "@/components/brand/shader-orb";
import { Button, Input } from "@/components/ui";
import { Spinner } from "@/components/spinner";
import { Chip } from "@/components/chip";
import { openNewMeetingModal } from "@/App";

type Group = "Hoje" | "Ontem" | "Esta semana" | "Mais antigas";
const GROUP_ORDER: Group[] = ["Hoje", "Ontem", "Esta semana", "Mais antigas"];

function startOfDay(d: Date) {
  const c = new Date(d);
  c.setHours(0, 0, 0, 0);
  return c;
}

function groupOf(m: MeetingSummary): Group {
  const now = startOfDay(new Date());
  const day = startOfDay(new Date(m.startedAt));
  const diffMs = now.getTime() - day.getTime();
  const diffDays = Math.round(diffMs / 86400000);
  if (diffDays <= 0) return "Hoje";
  if (diffDays === 1) return "Ontem";
  if (diffDays <= 7) return "Esta semana";
  return "Mais antigas";
}

function timeLabel(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleTimeString("pt-BR", { hour: "2-digit", minute: "2-digit" });
}

function dateLabel(iso: string): string {
  const d = new Date(iso);
  return d.toLocaleDateString("pt-BR", {
    weekday: "short",
    day: "2-digit",
    month: "short",
  });
}

function StatusDot({ status }: { status: MeetingSummary["processingStatus"] }) {
  if (status === "PROCESSING" || status === "PENDING") {
    return (
      <span
        className="block rounded-full overflow-hidden"
        title="Analisando…"
        style={{
          width: 16,
          height: 16,
          animation: "orbPulse 1.6s ease-in-out infinite",
        }}
      >
        <ShaderOrb size={16} speed={1.8} intensity={1} />
      </span>
    );
  }
  const map: Record<string, { color: string; label: string }> = {
    COMPLETED: { color: "var(--success)", label: "Pronto" },
    FAILED: { color: "var(--danger)", label: "Falhou" },
  };
  const s = map[status] || { color: "var(--border-strong)", label: "Rascunho" };
  return (
    <span
      title={s.label}
      style={{
        display: "block",
        width: 8,
        height: 8,
        borderRadius: "50%",
        background: s.color,
        margin: "0 4px",
      }}
    />
  );
}

function MeetingRow({ m, query }: { m: MeetingSummary; query: string }) {
  const [hover, setHover] = useState(false);
  const isProcessing =
    m.processingStatus === "PROCESSING" || m.processingStatus === "PENDING";
  const isFailed = m.processingStatus === "FAILED";
  const group = groupOf(m);

  const hoverable = !isProcessing;

  return (
    <a
      href={hoverable ? `#/meetings/${m.id}` : undefined}
      onMouseEnter={() => setHover(true)}
      onMouseLeave={() => setHover(false)}
      className="w-full grid items-center relative"
      style={{
        gridTemplateColumns: "auto 80px 1fr auto auto",
        gap: 14,
        padding: "12px 14px",
        borderRadius: "var(--radius)",
        background: hover && hoverable ? "var(--sidebar)" : "transparent",
        cursor: hoverable ? "pointer" : "default",
        color: "var(--ink)",
        textAlign: "left",
        textDecoration: "none",
        transition: "background 120ms ease",
      }}
    >
      <span className="grid place-items-center" style={{ width: 16 }}>
        <StatusDot status={m.processingStatus} />
      </span>

      <span
        className="whitespace-nowrap"
        style={{
          fontSize: 12,
          color: "var(--muted)",
          fontVariantNumeric: "tabular-nums",
        }}
      >
        {group === "Hoje" || group === "Ontem"
          ? timeLabel(m.startedAt)
          : dateLabel(m.startedAt)}
      </span>

      <span className="flex flex-col gap-[3px] min-w-0">
        <span
          className="truncate"
          style={{
            fontSize: 14.5,
            fontWeight: 500,
            color: "var(--ink)",
            letterSpacing: "-0.012em",
          }}
        >
          <Highlight text={m.title || "(sem título)"} query={query} />
        </span>
        <span
          className="flex items-center gap-2.5"
          style={{ fontSize: 12, color: "var(--muted)" }}
        >
          {m.durationSeconds && (
            <span style={{ fontVariantNumeric: "tabular-nums" }}>
              {Math.max(1, Math.round(m.durationSeconds / 60))}min
            </span>
          )}
          {isProcessing && (
            <span style={{ color: "var(--accent-ink)" }}>Analisando…</span>
          )}
          {isFailed && (
            <span style={{ color: "var(--danger-ink)" }}>Falha no processamento</span>
          )}
          {!isProcessing && !isFailed && m.actionItemCount > 0 && (
            <span style={{ fontVariantNumeric: "tabular-nums" }}>
              {m.actionItemCount} {m.actionItemCount === 1 ? "action" : "actions"}
            </span>
          )}
          {m.tags.slice(0, 2).map((tag) => (
            <Chip key={tag} fontSize={11} padding="1px 8px">{tag}</Chip>
          ))}
        </span>
      </span>

      <span style={{ width: 0 }} />

      <span
        className="flex items-center gap-1"
        style={{
          opacity: hover && hoverable ? 1 : 0,
          transition: "opacity 140ms ease",
          pointerEvents: hover && hoverable ? "auto" : "none",
        }}
      >
        <span
          aria-hidden
          style={{
            width: 26,
            height: 26,
            display: "grid",
            placeItems: "center",
            borderRadius: "var(--radius-sm)",
            color: "var(--muted)",
          }}
        >
          <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
            <circle cx="5" cy="12" r="1" />
            <circle cx="12" cy="12" r="1" />
            <circle cx="19" cy="12" r="1" />
          </svg>
        </span>
      </span>
    </a>
  );
}

function Highlight({ text, query }: { text: string; query: string }) {
  if (!query.trim()) return <>{text}</>;
  const safeRe = query.trim().replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
  const parts = text.split(new RegExp(`(${safeRe})`, "ig"));
  return (
    <>
      {parts.map((p, i) =>
        p.toLowerCase() === query.toLowerCase() ? (
          <mark
            key={i}
            style={{
              background: "var(--accent-soft)",
              color: "var(--accent-ink)",
              padding: "0 2px",
              borderRadius: 3,
            }}
          >
            {p}
          </mark>
        ) : (
          <span key={i}>{p}</span>
        ),
      )}
    </>
  );
}

function Kbd({ children }: { children: React.ReactNode }) {
  return (
    <kbd
      style={{
        fontFamily: "var(--sans)",
        fontSize: 10.5,
        padding: "1px 6px",
        border: "1px solid var(--border)",
        borderRadius: 4,
        background: "var(--canvas)",
        color: "var(--muted)",
        letterSpacing: "0.02em",
      }}
    >
      {children}
    </kbd>
  );
}

function SearchBar({
  value,
  onChange,
  busy,
  resultCount,
}: {
  value: string;
  onChange: (v: string) => void;
  busy?: boolean;
  resultCount?: number;
}) {
  const inputRef = useRef<HTMLInputElement | null>(null);

  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      const target = e.target as HTMLElement | null;
      const typing =
        target &&
        (target.tagName === "INPUT" ||
          target.tagName === "TEXTAREA" ||
          target.isContentEditable);
      if (typing) return;
      if (e.key === "/") {
        e.preventDefault();
        inputRef.current?.focus();
      }
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, []);

  return (
    <div
      className="relative w-full"
      style={{ maxWidth: 420 }}
    >
      <svg
        width="14"
        height="14"
        viewBox="0 0 24 24"
        fill="none"
        stroke="currentColor"
        strokeWidth="2"
        strokeLinecap="round"
        strokeLinejoin="round"
        style={{
          position: "absolute",
          left: 12,
          top: 11,
          color: "var(--muted)",
        }}
      >
        <circle cx="11" cy="11" r="7" />
        <path d="m20 20-3.5-3.5" />
      </svg>
      <Input
        ref={inputRef}
        type="text"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        placeholder="Buscar reuniões, tags, conteúdo…"
        style={{ paddingLeft: 36, paddingRight: 38 }}
      />
      <div
        className="flex items-center gap-1.5"
        style={{
          position: "absolute",
          right: 8,
          top: 7,
          pointerEvents: "none",
        }}
      >
        {busy ? (
          <Spinner size={11} thickness={1.5} />
        ) : value ? (
          <span
            style={{
              fontSize: 11,
              color: "var(--muted)",
              fontVariantNumeric: "tabular-nums",
            }}
          >
            {resultCount ?? 0}
          </span>
        ) : (
          <Kbd>/</Kbd>
        )}
      </div>
    </div>
  );
}

export function MeetingsPage() {
  const { user } = useAuth();
  const [meetings, setMeetings] = useState<MeetingSummary[]>([]);
  const [loading, setLoading] = useState(true);
  const [searching, setSearching] = useState(false);
  const [error, setError] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [activeQuery, setActiveQuery] = useState("");

  // Debounce search input → activeQuery
  useEffect(() => {
    if (searchInput === activeQuery) return;
    const t = setTimeout(() => setActiveQuery(searchInput.trim()), 280);
    return () => clearTimeout(t);
  }, [searchInput, activeQuery]);

  // Fetch — initial + on query change
  useEffect(() => {
    let alive = true;
    const isInitial = meetings.length === 0 && !activeQuery;
    if (isInitial) setLoading(true);
    else setSearching(true);
    setError("");
    listMeetings({ size: 50, search: activeQuery || undefined })
      .then((page) => alive && setMeetings(page.items))
      .catch((err: unknown) => {
        if (!alive) return;
        const apiErr = err as ApiError;
        setError(apiErr?.message || "Erro ao carregar reuniões");
      })
      .finally(() => {
        if (!alive) return;
        setLoading(false);
        setSearching(false);
      });
    return () => {
      alive = false;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeQuery]);

  const grouped = useMemo(() => {
    const g: Record<Group, MeetingSummary[]> = {
      Hoje: [],
      Ontem: [],
      "Esta semana": [],
      "Mais antigas": [],
    };
    meetings.forEach((m) => g[groupOf(m)].push(m));
    return g;
  }, [meetings]);

  const hour = new Date().getHours();
  const greeting =
    hour < 6
      ? "Boa madrugada"
      : hour < 12
        ? "Bom dia"
        : hour < 18
          ? "Boa tarde"
          : "Boa noite";

  const firstName = user?.displayName?.split(" ")[0] || "você";
  const hasAny = meetings.length > 0;
  const hasToday = grouped["Hoje"].length > 0;
  const isSearching = activeQuery.length > 0;

  return (
    <main className="flex-1 overflow-y-auto" style={{ background: "var(--canvas)" }}>
      <div style={{ maxWidth: 920, margin: "0 auto", padding: "56px 40px 80px" }}>
        <header
          className="flex items-end justify-between gap-6 flex-wrap"
          style={{ marginBottom: 28 }}
        >
          <div>
            <div
              style={{
                fontSize: 13,
                color: "var(--muted)",
                marginBottom: 6,
                letterSpacing: "-0.005em",
              }}
            >
              {greeting}, {firstName}.
            </div>
            <h1
              style={{
                fontFamily: "var(--display)",
                fontSize: 30,
                fontWeight: 500,
                letterSpacing: "-0.025em",
                lineHeight: 1.1,
                color: "var(--ink)",
                margin: 0,
              }}
            >
              {loading
                ? "Carregando reuniões…"
                : isSearching
                  ? meetings.length === 0
                    ? `Nada encontrado para "${activeQuery}"`
                    : `${meetings.length} ${meetings.length === 1 ? "resultado" : "resultados"} para "${activeQuery}"`
                  : hasToday
                    ? `${grouped["Hoje"].length} ${grouped["Hoje"].length === 1 ? "reunião" : "reuniões"} hoje.`
                    : "Sem reuniões hoje."}
            </h1>
          </div>
          <div className="flex items-center gap-2">
            <Button variant="primary" onClick={openNewMeetingModal}>
              <svg width="15" height="15" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2.4" strokeLinecap="round">
                <path d="M12 5v14M5 12h14" />
              </svg>
              Nova reunião
            </Button>
          </div>
        </header>

        <div style={{ marginBottom: 24 }}>
          <SearchBar
            value={searchInput}
            onChange={setSearchInput}
            busy={searching}
            resultCount={meetings.length}
          />
        </div>

        {error && (
          <div
            className="mb-6"
            style={{
              padding: "10px 12px",
              background: "var(--danger-soft-bg)",
              border: "1px solid var(--danger-soft-border)",
              borderRadius: "var(--radius)",
              fontSize: 13,
              color: "var(--danger-ink)",
              lineHeight: 1.45,
            }}
          >
            {error}
          </div>
        )}

        {loading && (
          <div className="flex flex-col gap-1.5">
            {[0, 1, 2].map((i) => (
              <div
                key={i}
                style={{
                  height: 56,
                  background:
                    "linear-gradient(90deg, var(--sidebar) 0%, var(--chip) 50%, var(--sidebar) 100%)",
                  backgroundSize: "200% 100%",
                  animation: "shimmer 1.6s linear infinite",
                  borderRadius: "var(--radius)",
                  opacity: 0.6,
                }}
              />
            ))}
            <style>{`@keyframes shimmer { from { background-position: 0% 0; } to { background-position: -200% 0; } }`}</style>
          </div>
        )}

        {!loading && !hasAny && !error && (
          isSearching ? (
            <div className="flex flex-col items-center gap-3 py-16 text-center">
              <div
                style={{
                  width: 44,
                  height: 44,
                  borderRadius: "50%",
                  background: "var(--chip)",
                  display: "grid",
                  placeItems: "center",
                  color: "var(--muted)",
                }}
              >
                <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
                  <circle cx="11" cy="11" r="7" />
                  <path d="m20 20-3.5-3.5" />
                </svg>
              </div>
              <div style={{ maxWidth: 360 }}>
                <h2
                  style={{
                    fontFamily: "var(--display)",
                    fontSize: 18,
                    fontWeight: 500,
                    letterSpacing: "-0.018em",
                    margin: "0 0 6px",
                    color: "var(--ink)",
                  }}
                >
                  Nada encontrado.
                </h2>
                <p
                  style={{
                    fontSize: 13,
                    color: "var(--muted)",
                    margin: 0,
                    lineHeight: 1.55,
                  }}
                >
                  Tente outros termos ou{" "}
                  <button
                    onClick={() => {
                      setSearchInput("");
                      setActiveQuery("");
                    }}
                    style={{
                      background: "transparent",
                      border: "none",
                      padding: 0,
                      color: "var(--accent-ink)",
                      cursor: "pointer",
                      font: "inherit",
                      textDecoration: "underline",
                    }}
                  >
                    limpar a busca
                  </button>
                  .
                </p>
              </div>
            </div>
          ) : (
            <div className="flex flex-col items-center gap-5 py-16 text-center">
              <ShaderOrb size={120} speed={1} intensity={0.85} />
              <div style={{ maxWidth: 380 }}>
                <h2
                  style={{
                    fontFamily: "var(--display)",
                    fontSize: 19,
                    fontWeight: 500,
                    letterSpacing: "-0.018em",
                    margin: "0 0 6px",
                    color: "var(--ink)",
                  }}
                >
                  Sua primeira reunião está a uma gravação de distância.
                </h2>
                <p
                  style={{
                    fontSize: 13.5,
                    color: "var(--muted)",
                    margin: 0,
                    lineHeight: 1.55,
                  }}
                >
                  NORA captura o áudio, processa e devolve resumo, decisões e
                  action items.
                </p>
              </div>
              <Button variant="primary" onClick={openNewMeetingModal}>
                Começar a gravar
              </Button>
            </div>
          )
        )}

        {!loading && hasAny && (
          <div className="flex flex-col gap-7">
            {GROUP_ORDER.map((g) =>
              grouped[g].length > 0 ? (
                <section key={g}>
                  <div
                    className="flex items-center gap-2.5 mb-2"
                    style={{ padding: "0 14px" }}
                  >
                    <h3
                      style={{
                        fontSize: 11,
                        fontWeight: 500,
                        color: "var(--muted)",
                        letterSpacing: "0.08em",
                        textTransform: "uppercase",
                        margin: 0,
                      }}
                    >
                      {g}
                    </h3>
                    <div
                      className="flex-1 h-px"
                      style={{ background: "var(--border)" }}
                    />
                    <span style={{ fontSize: 11, color: "var(--muted)" }}>
                      {grouped[g].length}
                    </span>
                  </div>
                  <div className="flex flex-col gap-0.5">
                    {grouped[g].map((m) => (
                      <MeetingRow key={m.id} m={m} query={activeQuery} />
                    ))}
                  </div>
                </section>
              ) : null,
            )}
          </div>
        )}

        <div
          className="flex items-center justify-between"
          style={{
            marginTop: 48,
            paddingTop: 18,
            borderTop: "1px solid var(--border)",
            fontSize: 11.5,
            color: "var(--muted)",
          }}
        >
          <div className="flex gap-4">
            <span>Atalhos:</span>
            <span>
              <Kbd>/</Kbd> buscar
            </span>
            <span>
              <Kbd>N</Kbd> nova reunião
            </span>
          </div>
          <span>PII Shield ativo</span>
        </div>
      </div>
    </main>
  );
}
