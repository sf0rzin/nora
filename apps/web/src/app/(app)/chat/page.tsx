"use client";

/**
 * NORA Core — AI Chat.
 *
 * Center of the Core: talk to NORA about the workspace's meetings/action
 * items/projects. Consumes the text stream from `/api/chat` (server-side, OpenAI
 * via ADR 0004) and renders markdown in the answers. The LLM key never touches
 * the client.
 *
 * Persistence: opening with `?s={id}` loads the session (getChatSession). The
 * first message of a new conversation creates the session (createChatSession) and
 * updates the URL; each exchange (question + answer) is persisted via
 * appendChatMessage. Generation can be interrupted (AbortController) and resent
 * after an error/interruption.
 */
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { useRouter, useSearchParams } from "next/navigation";
import type { Route } from "next";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

import { ShaderOrb } from "@/components/brand/shader-orb";
import {
  appendChatMessage,
  createChatSession,
  getChatSession,
} from "@/lib/api/client";
import { notifySessionsChanged } from "@/lib/chat-sessions-sync";

type Role = "user" | "assistant";
interface Msg {
  role: Role;
  content: string;
  /**
   * The model's chain of thought, kept SEPARATE from `content` on purpose.
   *
   * The configured chat model reasons before answering and streams that reasoning first —
   * measured, 105 reasoning chunks ahead of 48 content chunks on a one-line question. Folding
   * it into `content` would make the thinking read as the answer. Shown dimmed while it
   * streams, so the user sees progress instead of an empty bubble.
   */
  reasoning?: string;
  /** Marks an answer cut off by the user (stop button) — enables "Tentar de novo". */
  interrupted?: boolean;
}

// §3.7 — generic product suggestions, no customer names and no internal jargon.
const SUGGESTIONS = [
  "Resuma minha última reunião",
  "O que ficou pendente esta semana?",
  "Quais action items eu tenho pra hoje?",
  "Quais riscos apareceram nas reuniões?",
];

export default function ChatPage() {
  return (
    <Suspense fallback={<ChatFallback />}>
      <ChatRoom />
    </Suspense>
  );
}

function ChatRoom() {
  const router = useRouter();
  const searchParams = useSearchParams();
  const sessionParam = searchParams.get("s");

  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [title, setTitle] = useState("Nova sessão");
  const [loading, setLoading] = useState(false);

  // Persisted session. Lives in a ref so it is available inside `send` without
  // recreating the callback; the state only mirrors it for the UI/URL.
  const sessionIdRef = useRef<string | null>(sessionParam);
  const abortRef = useRef<AbortController | null>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const taRef = useRef<HTMLTextAreaElement>(null);

  // Hydrates the session when opened via ?s={id}.
  useEffect(() => {
    sessionIdRef.current = sessionParam;
    if (!sessionParam) {
      setMessages([]);
      setTitle("Nova sessão");
      return;
    }
    let cancelled = false;
    setLoading(true);
    getChatSession(sessionParam)
      .then((detail) => {
        if (cancelled) return;
        setMessages(
          detail.messages.map((m) => ({ role: m.role, content: m.content })),
        );
        setTitle(detail.title?.trim() || "Sessão");
      })
      .catch(() => {
        if (cancelled) return;
        setMessages([]);
        setTitle("Sessão indisponível");
      })
      .finally(() => {
        if (!cancelled) setLoading(false);
      });
    return () => {
      cancelled = true;
    };
  }, [sessionParam]);

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [messages, busy]);

  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = `${Math.min(ta.scrollHeight, 200)}px`;
  }, [input]);

  // Persists a message in the current session without breaking the flow if the back-end fails.
  const persist = useCallback(async (role: Role, content: string) => {
    const id = sessionIdRef.current;
    if (!id) return;
    try {
      await appendChatMessage(id, { role, content });
      // Live sidebar: the title (derived from the 1st message), the snippet and
      // the ordering by updatedAt change on every persisted message.
      notifySessionsChanged();
    } catch {
      // Persistence is best-effort: the conversation goes on even if history fails.
    }
  }, []);

  const run = useCallback(
    async (history: Msg[]) => {
      setBusy(true);
      // assistant bubble we keep filling in as the stream arrives.
      setMessages([...history, { role: "assistant", content: "" }]);

      const controller = new AbortController();
      abortRef.current = controller;
      let acc = "";
      let aborted = false;

      try {
        const res = await fetch("/api/chat", {
          method: "POST",
          headers: { "Content-Type": "application/json" },
          body: JSON.stringify({ messages: history }),
          signal: controller.signal,
        });

        if (!res.ok || !res.body) {
          const err = (await res.json().catch(() => ({}))) as { error?: string };
          throw new Error(err.error ?? `Erro ${res.status}`);
        }

        // The body is NDJSON now: one `{"t":"r"|"c","c":"..."}` per line. `r` is the model
        // thinking out loud, `c` is the answer. They are accumulated apart because the
        // reasoning must never end up inside `content` — it would read as the answer, and a
        // reasoning model spends far more tokens thinking than replying.
        //
        // `frames` holds the tail of a chunk that split mid-line: SSE and NDJSON both cut on
        // arbitrary byte boundaries, so the last piece is never assumed to be whole.
        const reader = res.body.getReader();
        const decoder = new TextDecoder();
        let thinking = "";
        let frames = "";
        for (;;) {
          const { done, value } = await reader.read();
          if (done) break;
          frames += decoder.decode(value, { stream: true });
          const lines = frames.split("\n");
          frames = lines.pop() ?? "";
          for (const line of lines) {
            if (!line.trim()) continue;
            try {
              const f = JSON.parse(line) as { t?: string; c?: string };
              if (f.t === "r" && f.c) thinking += f.c;
              else if (f.t === "c" && f.c) acc += f.c;
            } catch {
              // a frame that does not parse is dropped rather than shown: printing raw
              // protocol at the user is worse than losing one delta.
            }
          }
          setMessages([
            ...history,
            { role: "assistant", content: acc, reasoning: thinking || undefined },
          ]);
        }
      } catch (e) {
        if (e instanceof DOMException && e.name === "AbortError") {
          aborted = true;
        } else {
          const reason = e instanceof Error ? e.message : "erro desconhecido";
          setMessages([
            ...history,
            {
              role: "assistant",
              content: `Não consegui responder agora (${reason}).`,
              interrupted: true,
            },
          ]);
          return;
        }
      } finally {
        abortRef.current = null;
        setBusy(false);
        taRef.current?.focus();
      }

      if (aborted) {
        setMessages([
          ...history,
          { role: "assistant", content: acc, interrupted: true },
        ]);
        if (acc.trim()) await persist("assistant", acc);
        return;
      }

      const finalText = acc.trim() ? acc : "_(sem resposta)_";
      setMessages([...history, { role: "assistant", content: finalText }]);
      await persist("assistant", finalText);
    },
    [persist],
  );

  const send = useCallback(
    async (text: string) => {
      const content = text.trim();
      if (!content || busy || loading) return;

      // Creates the session on the 1st message of a new conversation and reflects it in the URL.
      if (!sessionIdRef.current) {
        try {
          const created = await createChatSession();
          sessionIdRef.current = created.id;
          setTitle(created.title?.trim() || "Nova sessão");
          // New session shows up in the sidebar right away, without a full reload.
          notifySessionsChanged();
          router.replace(`/chat?s=${encodeURIComponent(created.id)}` as Route, {
            scroll: false,
          });
        } catch {
          // No persistence: the conversation goes on in memory in this tab.
        }
      }

      const next: Msg[] = [...messages, { role: "user", content }];
      setMessages(next);
      setInput("");
      await persist("user", content);
      await run(next);
    },
    [busy, loading, messages, persist, run, router],
  );

  // Resends from the user's last question (after an error or interruption).
  const retry = useCallback(
    (assistantIndex: number) => {
      if (busy) return;
      let question = "";
      for (let j = assistantIndex - 1; j >= 0; j--) {
        if (messages[j].role === "user") {
          question = messages[j].content;
          break;
        }
      }
      const history = messages.slice(0, assistantIndex);
      setMessages(history);
      if (question) void run(history);
    },
    [busy, messages, run],
  );

  function stop() {
    abortRef.current?.abort();
  }

  function startNew() {
    if (sessionIdRef.current || sessionParam) {
      router.push("/chat" as Route);
      return;
    }
    setMessages([]);
    setInput("");
    setTitle("Nova sessão");
  }

  const empty = messages.length === 0;
  const hasInput = input.trim().length > 0;

  return (
    <div style={{ height: "100%", display: "flex", flexDirection: "column", background: "var(--canvas)" }}>
      <div
        style={{
          display: "flex",
          alignItems: "center",
          justifyContent: "space-between",
          padding: "16px 28px",
          borderBottom: empty ? "none" : "1px solid var(--border)",
        }}
      >
        <div style={{ fontSize: 13, color: "var(--muted)", letterSpacing: "-0.005em" }}>{title}</div>
        {!empty && (
          <button type="button" className="btn btn-ghost btn-sm" onClick={startNew}>
            {sessionParam ? "Nova sessão" : "Limpar"}
          </button>
        )}
      </div>

      <div ref={scrollRef} style={{ flex: 1, overflowY: "auto" }}>
        {loading ? (
          <div
            style={{
              minHeight: "100%",
              display: "flex",
              alignItems: "center",
              justifyContent: "center",
              color: "var(--muted)",
              fontSize: 13,
            }}
          >
            Carregando conversa…
          </div>
        ) : empty ? (
          <div
            style={{
              minHeight: "100%",
              display: "flex",
              flexDirection: "column",
              alignItems: "center",
              justifyContent: "center",
              gap: 24,
              padding: "32px 24px 120px",
            }}
          >
            <ShaderOrb size={120} speed={1} intensity={1} />
            <div style={{ textAlign: "center", maxWidth: 460 }}>
              <h1
                style={{
                  fontFamily: "var(--display)",
                  fontSize: 28,
                  fontWeight: 500,
                  letterSpacing: "-0.025em",
                  color: "var(--ink)",
                  margin: "0 0 8px",
                }}
              >
                Como posso ajudar?
              </h1>
              <p style={{ fontSize: 14, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
                Pergunte sobre suas reuniões, action items ou projetos. A Nora usa o contexto do seu workspace.
              </p>
            </div>
            <div style={{ display: "flex", flexWrap: "wrap", gap: 8, justifyContent: "center", maxWidth: 580 }}>
              {SUGGESTIONS.map((s) => (
                <button
                  key={s}
                  type="button"
                  onClick={() => void send(s)}
                  style={{
                    fontFamily: "var(--sans)",
                    fontSize: 12.5,
                    color: "var(--ink)",
                    background: "var(--sidebar)",
                    border: "1px solid var(--border)",
                    borderRadius: 999,
                    padding: "7px 14px",
                    cursor: "pointer",
                  }}
                >
                  {s}
                </button>
              ))}
            </div>
          </div>
        ) : (
          <div style={{ maxWidth: 720, margin: "0 auto", padding: "32px 24px 160px", display: "flex", flexDirection: "column", gap: 22 }}>
            {messages.map((m, i) => (
              <ChatBubble
                key={i}
                msg={m}
                streaming={busy && i === messages.length - 1 && m.role === "assistant"}
                onRetry={m.interrupted ? () => retry(i) : undefined}
              />
            ))}
          </div>
        )}
      </div>

      <div
        style={{
          padding: "0 24px 28px",
          display: "flex",
          justifyContent: "center",
          background: empty ? "transparent" : "linear-gradient(to top, var(--canvas) 72%, transparent)",
        }}
      >
        <div
          style={{
            width: "100%",
            maxWidth: 720,
            display: "flex",
            alignItems: "flex-end",
            gap: 10,
            padding: "10px 10px 10px 18px",
            background: "var(--canvas)",
            border: "1px solid var(--border)",
            borderRadius: 22,
            boxShadow: "0 4px 14px -8px rgba(15,23,42,0.08)",
          }}
        >
          <textarea
            ref={taRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                void send(input);
              }
            }}
            placeholder="Pergunte qualquer coisa…"
            rows={1}
            style={{
              flex: 1,
              resize: "none",
              border: "none",
              outline: "none",
              fontFamily: "var(--sans)",
              fontSize: 14,
              lineHeight: 1.5,
              background: "transparent",
              color: "var(--ink)",
              padding: "8px 0",
              maxHeight: 200,
            }}
          />
          <SendButton
            active={hasInput}
            busy={busy}
            onClick={() => (busy ? stop() : void send(input))}
          />
        </div>
      </div>
    </div>
  );
}

function ChatFallback() {
  return (
    <div
      style={{
        height: "100%",
        display: "flex",
        alignItems: "center",
        justifyContent: "center",
        background: "var(--canvas)",
        color: "var(--muted)",
        fontSize: 13,
      }}
    >
      Carregando…
    </div>
  );
}

function ChatBubble({
  msg,
  streaming,
  onRetry,
}: {
  msg: Msg;
  streaming: boolean;
  onRetry?: () => void;
}) {
  const isUser = msg.role === "user";
  if (isUser) {
    return (
      <div style={{ display: "flex", justifyContent: "flex-end" }}>
        <div style={{ maxWidth: "78%", padding: "10px 14px", background: "var(--chip)", borderRadius: 14, fontSize: 14.5, lineHeight: 1.6, color: "var(--ink)", whiteSpace: "pre-wrap" }}>
          {msg.content}
        </div>
      </div>
    );
  }
  return (
    <div style={{ display: "flex", flexDirection: "column", gap: 10 }}>
      {msg.reasoning && <ReasoningBlock text={msg.reasoning} streaming={streaming && !msg.content} />}
      <div style={{ display: "flex", justifyContent: "flex-start" }}>
        <div className="nora-prose" style={{ maxWidth: "100%", fontSize: 14.5, lineHeight: 1.65, color: "var(--ink)" }}>
          {msg.content ? (
            <ReactMarkdown remarkPlugins={[remarkGfm]}>{msg.content}</ReactMarkdown>
          ) : streaming && !msg.reasoning ? (
            <ThinkingDots />
          ) : null}
        </div>
      </div>
      {onRetry && (
        <div style={{ display: "flex", alignItems: "center", gap: 10, fontSize: 12.5, color: "var(--muted)" }}>
          <span>Resposta interrompida.</span>
          <button
            type="button"
            onClick={onRetry}
            style={{
              display: "inline-flex",
              alignItems: "center",
              gap: 6,
              fontSize: 12.5,
              fontWeight: 500,
              color: "var(--ink)",
              background: "var(--sidebar)",
              border: "1px solid var(--border)",
              borderRadius: 999,
              padding: "5px 12px",
              cursor: "pointer",
            }}
          >
            <svg width="12" height="12" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M1 4v6h6" />
              <path d="M3.5 15a9 9 0 1 0 2-9.4L1 10" />
            </svg>
            Tentar de novo
          </button>
        </div>
      )}
    </div>
  );
}

function ThinkingDots() {
  return (
    <span style={{ display: "inline-flex", gap: 4, alignItems: "center", color: "var(--muted)" }}>
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          style={{
            width: 5,
            height: 5,
            borderRadius: "50%",
            background: "currentColor",
            animation: `noraBlink 1.2s ${i * 0.18}s infinite ease-in-out`,
          }}
        />
      ))}
    </span>
  );
}

function SendButton({ active, busy, onClick }: { active: boolean; busy: boolean; onClick: () => void }) {
  const filled = busy || active;
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={!busy && !active}
      aria-label={busy ? "Parar" : "Enviar"}
      style={{
        width: 36,
        height: 36,
        borderRadius: "50%",
        background: filled ? "var(--ink)" : "var(--chip)",
        border: "none",
        cursor: busy || active ? "pointer" : "default",
        padding: 0,
        display: "grid",
        placeItems: "center",
        transition: "background 180ms ease",
        flexShrink: 0,
      }}
    >
      {busy ? (
        <svg width="12" height="12" viewBox="0 0 24 24" fill="white">
          <rect x="5" y="5" width="14" height="14" rx="2" />
        </svg>
      ) : (
        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke={active ? "white" : "var(--muted)"} strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
          <path d="M12 19V5M5 12l7-7 7 7" />
        </svg>
      )}
    </button>
  );
}

/**
 * The model's chain of thought while it works, rendered apart from the answer.
 *
 * Open by default WHILE it streams and collapsed once the answer starts: the reasoning is the
 * only thing on screen during the first several seconds — measured, 105 reasoning chunks before
 * the first content token — so hiding it there would reproduce the empty bubble this whole
 * change exists to remove. Once there is an answer, the thinking is reference material.
 *
 * Deliberately NOT markdown-rendered: it is raw model output, and running it through the same
 * prose renderer as the answer would make half-finished syntax flicker as it streams.
 */
function ReasoningBlock({ text, streaming }: { text: string; streaming: boolean }) {
  const [open, setOpen] = useState(true);
  const wasStreaming = useRef(streaming);
  useEffect(() => {
    // Collapse exactly once, on the transition out of streaming — not on every render, or the
    // user could never re-open it while the answer is still arriving.
    if (wasStreaming.current && !streaming) setOpen(false);
    wasStreaming.current = streaming;
  }, [streaming]);

  return (
    <div
      style={{
        border: "1px solid var(--line)",
        borderRadius: 10,
        background: "var(--surface-2, rgba(0,0,0,0.02))",
        fontSize: 12.5,
        color: "var(--muted)",
      }}
    >
      <button
        type="button"
        onClick={() => setOpen((v) => !v)}
        style={{
          display: "flex",
          alignItems: "center",
          gap: 8,
          width: "100%",
          padding: "8px 12px",
          background: "none",
          border: "none",
          cursor: "pointer",
          color: "inherit",
          font: "inherit",
          textAlign: "left",
        }}
        aria-expanded={open}
      >
        <span style={{ opacity: 0.7 }}>{open ? "▾" : "▸"}</span>
        <span>{streaming ? "Raciocinando…" : "Raciocínio"}</span>
      </button>
      {open && (
        <div
          style={{
            padding: "0 12px 10px 12px",
            whiteSpace: "pre-wrap",
            lineHeight: 1.6,
            maxHeight: 260,
            overflowY: "auto",
          }}
        >
          {text}
        </div>
      )}
    </div>
  );
}
