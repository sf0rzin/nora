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
  streamChat,
} from "@/lib/api/client";
import { applyChatFrames, createChatFrameDecoder } from "@/lib/chat/ndjson";
import { applyStoredReasoning, rememberReasoning } from "@/lib/chat/reasoning-store";
import { notifySessionsChanged } from "@/lib/chat-sessions-sync";
import { errorCopy } from "@/lib/strings";

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

/**
 * A refusal for budget reasons, kept apart from every other failure.
 *
 * `/api/chat` answers 429 from its own per-principal budget, and the semantic search behind it
 * answers `MEETING_RATE_LIMITED`. Neither is a malfunction, and the generic wrapper the catch
 * puts around an error reports them as one.
 */
class ChatRateLimitedError extends Error {}

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
  /**
   * The question typed into the landing composer, which travels `/?q=…` → `/auth/signup?q=…` →
   * here. This page read only `?s=` (a session id), so the parameter arrived and was dropped: the
   * landing invited a visitor to start typing and the product then showed them an empty box.
   *
   * It SEEDS the composer instead of sending itself. A URL that fires an LLM call on load is a
   * link anybody can hand somebody else, and the last step of the promise is the user pressing
   * send — not the product answering a question they can no longer see.
   */
  const seedParam = searchParams.get("q");

  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const [title, setTitle] = useState("Nova sessão");
  const [loading, setLoading] = useState(false);
  /**
   * The conversation is running but nothing is reaching the history — either the session could
   * not be created or an append failed. It used to be two silent `catch` blocks, so the user kept
   * talking to a sidebar entry that did not exist and lost everything on reload. Degradation is
   * acceptable here; degradation nobody is told about is not.
   */
  const [persistenceOff, setPersistenceOff] = useState(false);

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
    setPersistenceOff(false);
    getChatSession(sessionParam)
      .then((detail) => {
        if (cancelled) return;
        // `applyStoredReasoning` puts the chain of thought back on the bubbles it belongs to.
        // The backend message carries role and content only, so without it a reloaded session
        // shows the answers and none of the thinking the user watched arrive.
        setMessages(applyStoredReasoning(sessionParam, detail.messages));
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

  // Seeds the composer from `?q=` and takes the parameter out of the URL, so a reload does not
  // overwrite whatever the user has typed since. Capped at the same 280 chars the auth screen
  // shows, and only on a fresh conversation — landing on `?s=` means they came back to a session.
  useEffect(() => {
    const seed = seedParam?.trim().slice(0, 280);
    if (!seed) return;
    if (!sessionParam) setInput(seed);
    router.replace((sessionParam ? `/chat?s=${encodeURIComponent(sessionParam)}` : "/chat") as Route, {
      scroll: false,
    });
  }, [seedParam, sessionParam, router]);

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
  // `index` is the position the message takes in the session's history, and it is what the
  // reasoning is filed under — the backend message has no field for it.
  const persist = useCallback(
    async (role: Role, content: string, index: number, reasoning?: string) => {
      const id = sessionIdRef.current;
      if (!id) {
        setPersistenceOff(true);
        return;
      }
      try {
        await appendChatMessage(id, { role, content });
        if (role === "assistant") rememberReasoning(id, index, content, reasoning ?? "");
        // Live sidebar: the title (derived from the 1st message), the snippet and
        // the ordering by updatedAt change on every persisted message.
        notifySessionsChanged();
      } catch {
        // Still best-effort — the conversation must not stop because the history did — but the
        // failure is now visible in the topbar instead of being swallowed here.
        setPersistenceOff(true);
      }
    },
    [],
  );

  const run = useCallback(
    async (history: Msg[]) => {
      setBusy(true);
      // assistant bubble we keep filling in as the stream arrives.
      setMessages([...history, { role: "assistant", content: "" }]);

      const controller = new AbortController();
      abortRef.current = controller;
      // Where this answer lands in the persisted history — the reasoning is filed under it.
      const answerIndex = history.length;
      const stream = { reasoning: "", content: "" };
      let aborted = false;
      let failure: string | null = null;

      try {
        // `streamChat`, not a raw `fetch`: it is the only path with the 401 interceptor and the
        // single-flight refresh. Called directly, an expired access token turned into a
        // permanent error bubble in a tab that stayed "signed in" as far as the middleware was
        // concerned, and only F5 recovered it.
        const res = await streamChat({ messages: history }, controller.signal);

        if (!res.ok || !res.body) {
          const err = (await res.json().catch(() => ({}))) as { error?: string; code?: string };
          // 429 gets said plainly, not wrapped in "não consegui responder (…)". The request
          // budget and the semantic search's own `MEETING_RATE_LIMITED` both land here, and both
          // mean the same thing to the user: nothing is broken, wait and ask again. A body that
          // arrives without text still gets pt-BR copy rather than a bare status number.
          if (res.status === 429) {
            throw new ChatRateLimitedError(err.error ?? errorCopy.MEETING_RATE_LIMITED);
          }
          throw new Error(err.error ?? `Erro ${res.status}`);
        }

        // The body is NDJSON: one `{"t":"r"|"c","c":"..."}` per line, decoded by the module that
        // also writes it (`@/lib/chat/ndjson`), so the two ends of the format cannot drift.
        // Reasoning and answer are accumulated apart because the reasoning must never end up
        // inside `content` — it would read as the answer, and a reasoning model spends far more
        // tokens thinking than replying.
        const reader = res.body.getReader();
        const bytes = new TextDecoder();
        const frames = createChatFrameDecoder();
        for (;;) {
          const { done, value } = await reader.read();
          if (done) {
            applyChatFrames(stream, frames.flush());
            break;
          }
          applyChatFrames(stream, frames.push(bytes.decode(value, { stream: true })));
          setMessages([
            ...history,
            {
              role: "assistant",
              content: stream.content,
              reasoning: stream.reasoning || undefined,
            },
          ]);
        }
      } catch (e) {
        if (e instanceof DOMException && e.name === "AbortError") {
          aborted = true;
        } else if (e instanceof ChatRateLimitedError) {
          // Already a complete sentence in pt-BR, and nothing failed — wrapping it in "não
          // consegui responder (…)" would report a working limit as a malfunction.
          failure = e.message;
        } else {
          const reason = e instanceof Error ? e.message : "erro desconhecido";
          failure = `Não consegui responder agora (${reason}).`;
        }
      } finally {
        abortRef.current = null;
        setBusy(false);
        taRef.current?.focus();
      }

      // A failed answer is persisted like any other. It used to `return` before the persist, so
      // the saved history showed the question with nothing after it — indistinguishable from a
      // conversation still in progress.
      if (failure !== null) {
        setMessages([...history, { role: "assistant", content: failure, interrupted: true }]);
        await persist("assistant", failure, answerIndex);
        return;
      }

      if (aborted) {
        setMessages([
          ...history,
          {
            role: "assistant",
            content: stream.content,
            reasoning: stream.reasoning || undefined,
            interrupted: true,
          },
        ]);
        if (stream.content.trim()) {
          await persist("assistant", stream.content, answerIndex, stream.reasoning);
        }
        return;
      }

      const finalText = stream.content.trim() ? stream.content : "_(sem resposta)_";
      setMessages([
        ...history,
        {
          role: "assistant",
          content: finalText,
          reasoning: stream.reasoning || undefined,
        },
      ]);
      await persist("assistant", finalText, answerIndex, stream.reasoning);
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
          setPersistenceOff(false);
        } catch {
          // The conversation goes on in memory in this tab — but it says so now. Silently
          // running unsaved meant the sidebar never showed the session and a reload took the
          // whole conversation with it, with nothing on screen having hinted at either.
          setPersistenceOff(true);
        }
      }

      const userIndex = messages.length;
      const next: Msg[] = [...messages, { role: "user", content }];
      setMessages(next);
      setInput("");
      await persist("user", content, userIndex);
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
        <div style={{ display: "flex", alignItems: "center", gap: 12, minWidth: 0 }}>
          <div style={{ fontSize: 13, color: "var(--muted)", letterSpacing: "-0.005em" }}>{title}</div>
          {persistenceOff && (
            <span
              role="status"
              title="A conversa continua funcionando nesta aba, mas não vai aparecer no histórico."
              style={{
                fontSize: 11.5,
                color: "var(--warn)",
                border: "1px solid var(--border)",
                borderRadius: 999,
                padding: "3px 10px",
                whiteSpace: "nowrap",
              }}
            >
              Esta conversa não está sendo salva
            </span>
          )}
        </div>
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
          // `log` + `polite`, and both halves are deliberate. The answer arrives token by token,
          // so an assertive region would interrupt the screen reader on every delta and read the
          // reply several times over; `polite` lets it finish the current utterance and then
          // announce what was appended. `log` is the role for a running transcript — additions at
          // the end, older entries not re-read — which is exactly what this list is.
          <div
            role="log"
            aria-live="polite"
            aria-relevant="additions text"
            aria-busy={busy}
            aria-label="Conversa com a Nora"
            style={{ maxWidth: 720, margin: "0 auto", padding: "32px 24px 160px", display: "flex", flexDirection: "column", gap: 22 }}
          >
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
          {/* A placeholder is not a label: it disappears on the first keystroke and is not
              associated with the field, so a screen reader announces an unnamed edit box. The
              visible design has no room for a caption, so the name is attached rather than
              drawn — the one case where aria-label is the right tool instead of a shortcut. */}
          <textarea
            ref={taRef}
            id="chat-input"
            aria-label="Pergunte qualquer coisa para a Nora"
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
