import { useEffect, useRef, useState } from "react";
import { useAuth } from "@/hooks/use-auth";
import { ShaderOrb } from "@/components/brand/shader-orb";
import { Avatar } from "@/components/brand/avatar";
import { Spinner } from "@/components/spinner";
import { Button, IconButton, Textarea } from "../components/ui";

interface ChatMessage {
  id: number;
  role: "user" | "assistant";
  content: string;
  ts: number;
}

const SUGGESTIONS = [
  "Resuma a última reunião com a TOTVS",
  "Quais action items eu tenho pra hoje?",
  "O que decidimos sobre o conector Protheus?",
];

// Placeholder enquanto o backend de chat não existe.
// Quando expor `/v1/chat` no backend, substituir aqui pela chamada real.
async function fakeAssistantReply(userText: string): Promise<string> {
  await new Promise((r) => setTimeout(r, 1100 + Math.random() * 600));
  const trimmed = userText.trim().toLowerCase();
  if (trimmed.startsWith("oi") || trimmed === "olá") {
    return "Oi! O chat com a Nora tá em construção — em breve vou conseguir responder com base nas suas reuniões, action items e contexto do workspace.";
  }
  if (trimmed.includes("totvs")) {
    return "Quando o chat estiver no ar, vou puxar a última reunião TOTVS, decisões registradas e action items pendentes — tudo passando pelo PII Shield antes de subir pra LLM.";
  }
  return "Chat com a Nora em construção. Por enquanto, abra qualquer reunião pra ver o resumo, decisões e action items que ela já gera. Quando o endpoint estiver no ar essa conversa fica disponível.";
}

function ThinkingDots() {
  return (
    <span className="inline-flex items-center gap-1">
      {[0, 1, 2].map((i) => (
        <span
          key={i}
          style={{
            width: 5,
            height: 5,
            borderRadius: "50%",
            background: "var(--muted)",
            animation: `thinkBlink 1.2s ${i * 0.18}s infinite ease-in-out`,
          }}
        />
      ))}
    </span>
  );
}

function SendOrbButton({
  onClick,
  active,
  busy,
}: {
  onClick: () => void;
  active: boolean;
  busy: boolean;
}) {
  return (
    <IconButton
      onClick={onClick}
      disabled={!active || busy}
      aria-label="Enviar"
      className="shrink-0 rounded-pill"
      style={{
        background: active ? "var(--ink)" : "var(--chip)",
        color: active ? "var(--canvas)" : "var(--muted)",
      }}
    >
      {busy ? (
        <Spinner size={14} color="rgba(253,253,252,0.35)" topColor="var(--canvas)" />
      ) : (
        <svg
          width="14"
          height="14"
          viewBox="0 0 24 24"
          fill="none"
          stroke="currentColor"
          strokeWidth="2.2"
          strokeLinecap="round"
          strokeLinejoin="round"
        >
          <path d="M12 19V5M5 12l7-7 7 7" />
        </svg>
      )}
    </IconButton>
  );
}

function MessageBubble({
  m,
  thinking,
  authorAvatar,
}: {
  m: ChatMessage;
  thinking?: boolean;
  authorAvatar?: React.ReactNode;
}) {
  const isUser = m.role === "user";
  return (
    <div
      className={`flex ${isUser ? "justify-end" : "justify-start"}`}
      style={{ gap: 12 }}
    >
      {!isUser && (
        <div className="shrink-0" style={{ marginTop: 2 }}>
          {authorAvatar ?? (
            <div
              style={{
                width: 28,
                height: 28,
                borderRadius: "50%",
                overflow: "hidden",
                background: "var(--chip)",
                display: "grid",
                placeItems: "center",
              }}
            >
              <ShaderOrb size={28} speed={0.8} intensity={0.8} />
            </div>
          )}
        </div>
      )}
      <div
        style={{
          maxWidth: isUser ? "76%" : "calc(100% - 40px)",
          padding: isUser ? "10px 14px" : "2px 0",
          background: isUser ? "var(--chip)" : "transparent",
          borderRadius: isUser ? "var(--radius-md)" : 0,
          fontSize: 14.5,
          lineHeight: 1.6,
          color: "var(--ink)",
          whiteSpace: "pre-wrap",
        }}
      >
        {thinking ? <ThinkingDots /> : m.content}
      </div>
    </div>
  );
}

export function ChatPage() {
  const { user } = useAuth();
  const [messages, setMessages] = useState<ChatMessage[]>([]);
  const [input, setInput] = useState("");
  const [busy, setBusy] = useState(false);
  const scrollRef = useRef<HTMLDivElement | null>(null);
  const taRef = useRef<HTMLTextAreaElement | null>(null);
  const idRef = useRef(0);

  // Auto-scroll
  useEffect(() => {
    const el = scrollRef.current;
    if (el) el.scrollTop = el.scrollHeight;
  }, [messages, busy]);

  // Auto-resize textarea
  useEffect(() => {
    const ta = taRef.current;
    if (!ta) return;
    ta.style.height = "auto";
    ta.style.height = `${Math.min(ta.scrollHeight, 200)}px`;
  }, [input]);

  const empty = messages.length === 0;
  const hasInput = input.trim().length > 0;

  async function send() {
    const text = input.trim();
    if (!text || busy) return;
    const userMsg: ChatMessage = { id: idRef.current++, role: "user", content: text, ts: Date.now() };
    setMessages((prev) => [...prev, userMsg]);
    setInput("");
    setBusy(true);
    try {
      const reply = await fakeAssistantReply(text);
      setMessages((prev) => [...prev, { id: idRef.current++, role: "assistant", content: reply, ts: Date.now() }]);
    } catch {
      setMessages((prev) => [
        ...prev,
        {
          id: idRef.current++,
          role: "assistant",
          content:
            "Não consegui responder agora. O chat com a Nora ainda está sendo integrado — tenta de novo em alguns segundos.",
          ts: Date.now(),
        },
      ]);
    } finally {
      setBusy(false);
      setTimeout(() => taRef.current?.focus(), 30);
    }
  }

  const firstName = user?.displayName?.split(" ")[0];

  return (
    <main
      className="flex-1 h-full flex flex-col"
      style={{ background: "var(--canvas)", minWidth: 0 }}
    >
      <div
        className="flex items-center justify-between"
        style={{
          padding: "16px 28px",
          borderBottom: empty ? "none" : "1px solid var(--border)",
        }}
      >
        <div
          style={{ fontSize: 13, color: "var(--muted)", letterSpacing: "-0.005em" }}
        >
          Nova sessão
        </div>
        {!empty && (
          <Button variant="ghost" size="sm" onClick={() => setMessages([])}>
            <svg
              width="14"
              height="14"
              viewBox="0 0 24 24"
              fill="none"
              stroke="currentColor"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            >
              <path d="M3 6h18M8 6V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2m3 0v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6h14z" />
            </svg>
            Limpar conversa
          </Button>
        )}
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto">
        {empty ? (
          <div
            className="flex flex-col items-center justify-center text-center"
            style={{
              minHeight: "100%",
              padding: "24px 24px 140px",
              gap: 22,
            }}
          >
            <div
              className="relative grid place-items-center"
              style={{ width: 160, height: 160 }}
            >
              <div
                className="absolute inset-0 rounded-full"
                style={{
                  background:
                    "radial-gradient(circle at 50% 50%, var(--accent-soft) 0%, transparent 60%)",
                  opacity: 0.7,
                  filter: "blur(10px)",
                }}
              />
              <ShaderOrb
                size={132}
                speed={1}
                intensity={1}
                style={{ position: "relative", zIndex: 1 }}
              />
            </div>
            <div style={{ maxWidth: 480 }}>
              <h1
                style={{
                  fontFamily: "var(--display)",
                  fontSize: 30,
                  fontWeight: 500,
                  letterSpacing: "-0.028em",
                  color: "var(--ink)",
                  margin: "0 0 10px",
                  lineHeight: 1.1,
                }}
              >
                {firstName ? `Como posso ajudar, ${firstName}?` : "Como posso ajudar?"}
              </h1>
              <p
                style={{
                  fontSize: 14.5,
                  color: "var(--muted)",
                  margin: 0,
                  lineHeight: 1.55,
                }}
              >
                Pergunte sobre suas reuniões, decisões, action items ou o
                contexto do seu workspace.
              </p>
            </div>
            <div
              className="flex flex-wrap gap-2 justify-center"
              style={{ maxWidth: 560 }}
            >
              {SUGGESTIONS.map((s) => (
                <Button
                  key={s}
                  variant="secondary"
                  size="sm"
                  className="rounded-pill"
                  onClick={() => {
                    setInput(s);
                    setTimeout(() => taRef.current?.focus(), 0);
                  }}
                >
                  {s}
                </Button>
              ))}
            </div>
            <div
              style={{
                fontSize: 11,
                color: "var(--muted)",
                marginTop: 14,
                letterSpacing: "0.04em",
              }}
            >
              Chat Nora em construção · respostas reais em breve
            </div>
          </div>
        ) : (
          <div
            style={{
              maxWidth: 760,
              margin: "0 auto",
              padding: "32px 24px 200px",
              display: "flex",
              flexDirection: "column",
              gap: 22,
            }}
          >
            {messages.map((m) => (
              <MessageBubble
                key={m.id}
                m={m}
                authorAvatar={
                  m.role === "user" ? (
                    <Avatar
                      name={user?.displayName || "Você"}
                      size={28}
                      me
                    />
                  ) : undefined
                }
              />
            ))}
            {busy && (
              <MessageBubble
                m={{ id: -1, role: "assistant", content: "", ts: Date.now() }}
                thinking
              />
            )}
          </div>
        )}
      </div>

      {/* Composer */}
      <div
        className="flex justify-center"
        style={{
          padding: "0 24px 28px",
          background: empty
            ? "transparent"
            : "linear-gradient(to top, var(--canvas) 60%, transparent)",
          pointerEvents: "none",
        }}
      >
        <div
          className="w-full flex items-end gap-2"
          style={{
            maxWidth: 720,
            padding: "10px 10px 10px 18px",
            background: "var(--canvas)",
            border: "1px solid var(--border)",
            borderRadius: 22,
            boxShadow:
              "0 8px 22px -14px rgba(15,23,42,0.10), 0 1px 0 rgba(255,255,255,0.6) inset",
            pointerEvents: "auto",
          }}
        >
          <Textarea
            ref={taRef}
            value={input}
            onChange={(e) => setInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter" && !e.shiftKey) {
                e.preventDefault();
                send();
              }
            }}
            placeholder="Pergunte qualquer coisa…"
            rows={1}
            style={{
              flex: 1,
              fontSize: 14,
              background: "transparent",
              border: "none",
              borderRadius: 0,
              boxShadow: "none",
              padding: "8px 0",
              maxHeight: 200,
            }}
          />
          <SendOrbButton onClick={send} active={hasInput} busy={busy} />
        </div>
      </div>
    </main>
  );
}
