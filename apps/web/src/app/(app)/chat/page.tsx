"use client";

/**
 * NORA Core — Chat IA (Nova sessão).
 *
 * Centro do Core: conversa com a NORA sobre as reuniões/action items/projetos do
 * workspace. Consome o stream de texto de `/api/chat` (server-side, OpenAI via
 * ADR 0004). Renderiza markdown nas respostas. A chave do LLM nunca toca o client.
 */
import { useEffect, useRef, useState } from "react";
import ReactMarkdown from "react-markdown";
import remarkGfm from "remark-gfm";

import { ShaderOrb } from "@/components/brand/shader-orb";

type Role = "user" | "assistant";
interface Msg {
  role: Role;
  content: string;
}

const SUGGESTIONS = [
  "Resuma a última reunião com a TOTVS",
  "Quais action items eu tenho pra hoje?",
  "O que ficou pendente do meu último 1:1?",
  "Quais decisões vieram do ADR 0012?",
];

export default function ChatPage() {
  const [messages, setMessages] = useState<Msg[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const scrollRef = useRef<HTMLDivElement>(null);
  const taRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (scrollRef.current) scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
  }, [messages, busy]);

  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = `${Math.min(ta.scrollHeight, 200)}px`;
  }, [input]);

  async function send(text: string) {
    const content = text.trim();
    if (!content || busy) return;
    const next: Msg[] = [...messages, { role: "user", content }];
    setMessages(next);
    setInput("");
    setBusy(true);

    // mensagem do assistant que vamos preencher conforme o stream chega
    setMessages([...next, { role: "assistant", content: "" }]);

    try {
      const res = await fetch("/api/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ messages: next }),
      });

      if (!res.ok || !res.body) {
        const err = (await res.json().catch(() => ({}))) as { error?: string };
        throw new Error(err.error ?? `Erro ${res.status}`);
      }

      const reader = res.body.getReader();
      const decoder = new TextDecoder();
      let acc = "";
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        acc += decoder.decode(value, { stream: true });
        setMessages([...next, { role: "assistant", content: acc }]);
      }
      if (!acc.trim()) {
        setMessages([...next, { role: "assistant", content: "_(sem resposta)_" }]);
      }
    } catch (e) {
      const reason = e instanceof Error ? e.message : "erro desconhecido";
      setMessages([
        ...next,
        {
          role: "assistant",
          content: `Não consegui responder agora (${reason}). Tente de novo em alguns segundos.`,
        },
      ]);
    } finally {
      setBusy(false);
      taRef.current?.focus();
    }
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
        <div style={{ fontSize: 13, color: "var(--muted)", letterSpacing: "-0.005em" }}>Nova sessão</div>
        {!empty && (
          <button
            type="button"
            onClick={() => setMessages([])}
            style={{ fontSize: 12.5, color: "var(--muted)", background: "transparent", border: "1px solid var(--border)", borderRadius: 7, padding: "5px 10px", cursor: "pointer" }}
          >
            Limpar
          </button>
        )}
      </div>

      <div ref={scrollRef} style={{ flex: 1, overflowY: "auto" }}>
        {empty ? (
          <div style={{ minHeight: "100%", display: "flex", flexDirection: "column", alignItems: "center", justifyContent: "center", gap: 24, padding: "32px 24px 120px" }}>
            <ShaderOrb size={120} speed={1} intensity={1} />
            <div style={{ textAlign: "center", maxWidth: 460 }}>
              <h1 style={{ fontFamily: "var(--display)", fontSize: 28, fontWeight: 500, letterSpacing: "-0.025em", color: "var(--ink)", margin: "0 0 8px" }}>
                Como posso ajudar?
              </h1>
              <p style={{ fontSize: 14, color: "var(--muted)", margin: 0, lineHeight: 1.55 }}>
                Pergunte sobre suas reuniões, action items ou projetos. A NORA usa o contexto do seu workspace.
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
              <ChatBubble key={i} msg={m} streaming={busy && i === messages.length - 1 && m.role === "assistant"} />
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
          <SendButton active={hasInput && !busy} busy={busy} onClick={() => void send(input)} />
        </div>
      </div>
    </div>
  );
}

function ChatBubble({ msg, streaming }: { msg: Msg; streaming: boolean }) {
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
    <div style={{ display: "flex", justifyContent: "flex-start" }}>
      <div className="nora-prose" style={{ maxWidth: "100%", fontSize: 14.5, lineHeight: 1.65, color: "var(--ink)" }}>
        {msg.content ? (
          <ReactMarkdown remarkPlugins={[remarkGfm]}>{msg.content}</ReactMarkdown>
        ) : streaming ? (
          <ThinkingDots />
        ) : null}
      </div>
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
  return (
    <button
      type="button"
      onClick={onClick}
      disabled={!active}
      aria-label="Enviar"
      style={{
        width: 36,
        height: 36,
        borderRadius: "50%",
        background: active ? "var(--ink)" : "var(--chip)",
        border: "none",
        cursor: active ? "pointer" : "default",
        padding: 0,
        display: "grid",
        placeItems: "center",
        transition: "background 180ms ease",
        opacity: busy ? 0.7 : 1,
        flexShrink: 0,
      }}
    >
      <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke={active ? "white" : "var(--muted)"} strokeWidth="2.2" strokeLinecap="round" strokeLinejoin="round">
        <path d="M12 19V5M5 12l7-7 7 7" />
      </svg>
    </button>
  );
}
