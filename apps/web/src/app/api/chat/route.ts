/**
 * NORA Core — endpoint de chat com IA (streaming).
 *
 * BFF: roda 100% server-side. A chave do LLM NUNCA é exposta ao browser.
 * Provider-agnóstico (ADR 0004) + control plane (ADR 0024):
 *  - Se o control plane está plugado (NORA_PLATFORM_INTERNAL_TOKEN presente), o
 *    modelo do serviço `chat` é resolvido em runtime via
 *    GET /internal/platform/llm-config?service=chat (operador troca sem deploy).
 *  - Senão, cai no env LLM_* (comportamento legacy = OpenAI). Fallback SOFT: se a
 *    resolução falhar, usa o env. Nunca derruba o chat.
 *  - A chave é resolvida por provider (LLM_KEY_<PROVIDER>), com fallback p/ LLM_API_KEY.
 *  - Telemetria: captura tokens (stream_options.include_usage) e reporta
 *    POST /internal/platform/usage (fire-and-forget).
 *
 * Contexto do workspace: best-effort de reuniões + action items (cookies da sessão),
 * injetado no system prompt. LGPD/PII: usa só RESUMOS já tratados pelo PII Shield.
 */
import { cookies } from "next/headers";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const LLM_BASE_URL = (process.env.LLM_BASE_URL ?? "https://api.openai.com/v1").replace(/\/$/, "");
const LLM_API_KEY = process.env.LLM_API_KEY ?? "";
const LLM_MODEL = process.env.LLM_MODEL ?? "gpt-4o-mini";
const LLM_PROVIDER = process.env.LLM_PROVIDER ?? "openai";
const API_BASE_URL = (
  process.env.API_BASE_URL ??
  process.env.NEXT_PUBLIC_API_BASE_URL ??
  "http://localhost:8080"
).replace(/\/$/, "");
const PLATFORM_TOKEN = process.env.NORA_PLATFORM_INTERNAL_TOKEN ?? "";

type Role = "system" | "user" | "assistant";
interface ChatMessage {
  role: Role;
  content: string;
}

interface ModelConfig {
  provider: string;
  model: string;
  baseUrl: string;
  enabled: boolean;
}

interface Usage {
  promptTokens: number;
  completionTokens: number;
}

const SYSTEM_PROMPT = `Você é a NORA, copiloto pessoal de reuniões do plano Core.

Personalidade e regras:
- Responda SEMPRE em português brasileiro, de forma direta, clara e concisa.
- Você ajuda o usuário a entender suas reuniões: resumos, decisões, action items, prazos e projetos.
- Use markdown leve (negrito, listas) quando ajudar a leitura. Não invente dados.
- Se a resposta depende de uma reunião específica e ela não está no contexto, diga que não encontrou e sugira abrir/enviar a reunião.
- Nunca exponha dados sensíveis (PII). O conteúdo que você recebe já foi tratado pelo PII Shield.
- Quando citar uma reunião, use o título dela.`;

/** Config do env (fallback SOFT e comportamento legacy quando o control plane não está plugado). */
function envConfig(): ModelConfig {
  return { provider: LLM_PROVIDER, model: LLM_MODEL, baseUrl: LLM_BASE_URL, enabled: true };
}

/**
 * Resolve o modelo do serviço `chat`. Com o control plane plugado, pergunta ao
 * resolver server-side (cache 60s lá). Sem token, ou em qualquer falha, usa o env.
 */
async function resolveChatModel(): Promise<ModelConfig> {
  if (!PLATFORM_TOKEN) return envConfig();
  try {
    const res = await fetch(`${API_BASE_URL}/internal/platform/llm-config?service=chat`, {
      headers: { Accept: "application/json", "X-Internal-Token": PLATFORM_TOKEN },
      cache: "no-store",
    });
    if (!res.ok) return envConfig();
    const cfg = (await res.json()) as Partial<ModelConfig>;
    return {
      provider: cfg.provider || LLM_PROVIDER,
      model: cfg.model || LLM_MODEL,
      baseUrl: (cfg.baseUrl || LLM_BASE_URL).replace(/\/$/, ""),
      enabled: cfg.enabled !== false,
    };
  } catch {
    return envConfig();
  }
}

/** Chave por provider (LLM_KEY_<PROVIDER>), com fallback p/ a chave única legacy. */
function resolveKey(provider: string): string {
  const byProvider = process.env[`LLM_KEY_${provider.toUpperCase()}`];
  return (byProvider && byProvider.trim()) || LLM_API_KEY;
}

function tenantIdFromCookies(): string | null {
  const raw = cookies().get("nora_user")?.value;
  if (!raw) return null;
  try {
    return (JSON.parse(decodeURIComponent(raw)) as { tenantId?: string }).tenantId ?? null;
  } catch {
    return null;
  }
}

/** Reporta uso ao control plane (fire-and-forget). No-op sem token de plataforma. */
function recordUsage(
  cfg: ModelConfig,
  tenantId: string | null,
  usage: Usage,
  latencyMs: number,
  status: string,
): void {
  if (!PLATFORM_TOKEN) return;
  void fetch(`${API_BASE_URL}/internal/platform/usage`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Internal-Token": PLATFORM_TOKEN },
    cache: "no-store",
    body: JSON.stringify({
      service: "chat",
      provider: cfg.provider,
      model: cfg.model,
      tenantId,
      promptTokens: usage.promptTokens,
      completionTokens: usage.completionTokens,
      // costUsd omitido — o backend recalcula a partir dos tokens + catálogo.
      latencyMs,
      status,
    }),
  }).catch(() => {
    // telemetria é best-effort; nunca afeta a resposta do chat.
  });
}

interface MeetingContextItem {
  title?: string;
  summarySnippet?: string;
  startedAt?: string;
}

/**
 * RAG: busca as reuniões RELEVANTES à pergunta por similaridade semântica
 * (GET /meetings/search). Fallback pras mais recentes quando a busca volta vazia
 * (embeddings desligados/sem indexação ainda) ou indisponível.
 */
async function fetchContextMeetings(
  headers: Record<string, string>,
  query: string,
): Promise<{ label: string; items: MeetingContextItem[] }> {
  if (query.trim()) {
    try {
      const r = await fetch(
        `${API_BASE_URL}/meetings/search?q=${encodeURIComponent(query)}&k=6`,
        { headers, cache: "no-store" },
      );
      if (r.ok) {
        const d = (await r.json()) as { items?: MeetingContextItem[] };
        if (d.items && d.items.length > 0) {
          return { label: "REUNIÕES RELEVANTES À PERGUNTA (busca semântica):", items: d.items };
        }
      }
    } catch {
      // cai no fallback de recentes
    }
  }
  try {
    const r = await fetch(`${API_BASE_URL}/meetings?size=12`, { headers, cache: "no-store" });
    if (r.ok) {
      const d = (await r.json()) as { items?: MeetingContextItem[] };
      return { label: "REUNIÕES RECENTES DO WORKSPACE:", items: (d.items ?? []).slice(0, 12) };
    }
  } catch {
    // sem contexto de reuniões
  }
  return { label: "", items: [] };
}

async function buildWorkspaceContext(cookieHeader: string, query: string): Promise<string> {
  const parts: string[] = [];
  try {
    const headers = { Cookie: cookieHeader, Accept: "application/json" };
    const [meetings, tRes] = await Promise.all([
      fetchContextMeetings(headers, query),
      fetch(`${API_BASE_URL}/tasks`, { headers, cache: "no-store" }),
    ]);

    if (meetings.label && meetings.items.length > 0) {
      parts.push(meetings.label);
      for (const m of meetings.items) {
        const date = m.startedAt ? ` (${m.startedAt.slice(0, 10)})` : "";
        parts.push(`- "${m.title ?? "sem título"}"${date}: ${m.summarySnippet ?? "sem resumo"}`);
      }
    }

    if (tRes.ok) {
      const data = (await tRes.json()) as {
        items?: Array<{
          title?: string;
          status?: string;
          priority?: string;
          dueDate?: string;
          meetingTitle?: string;
        }>;
      };
      const items = (data.items ?? []).slice(0, 20);
      if (items.length > 0) {
        parts.push("\nACTION ITEMS ABERTOS:");
        for (const t of items) {
          const due = t.dueDate ? ` (vence ${t.dueDate.slice(0, 10)})` : "";
          parts.push(
            `- [${t.status ?? "?"}/${t.priority ?? "?"}] ${t.title ?? "?"}${due} — reunião: ${
              t.meetingTitle ?? "?"
            }`,
          );
        }
      }
    }
  } catch {
    // backend indisponível — segue sem contexto de workspace
  }
  return parts.join("\n");
}

/**
 * Transforma o stream SSE (OpenAI-compatible) em texto puro (deltas) e captura o
 * bloco `usage` final (stream_options.include_usage). Chama `onComplete` uma única
 * vez ao encerrar, com os tokens acumulados.
 */
function openAiSseToText(
  upstream: ReadableStream<Uint8Array>,
  onComplete: (usage: Usage) => void,
): ReadableStream<Uint8Array> {
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  let buffer = "";
  const usage: Usage = { promptTokens: 0, completionTokens: 0 };
  let finished = false;
  const reader = upstream.getReader();

  const finish = (controller: ReadableStreamDefaultController<Uint8Array>) => {
    if (finished) return;
    finished = true;
    try {
      onComplete(usage);
    } catch {
      /* nunca propaga */
    }
    controller.close();
  };

  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      const { done, value } = await reader.read();
      if (done) {
        finish(controller);
        return;
      }
      buffer += decoder.decode(value, { stream: true });
      const lines = buffer.split("\n");
      buffer = lines.pop() ?? "";
      for (const line of lines) {
        const trimmed = line.trim();
        if (!trimmed.startsWith("data:")) continue;
        const payload = trimmed.slice(5).trim();
        if (payload === "[DONE]") {
          finish(controller);
          return;
        }
        try {
          const json = JSON.parse(payload) as {
            choices?: Array<{ delta?: { content?: string } }>;
            usage?: { prompt_tokens?: number; completion_tokens?: number };
          };
          const text = json.choices?.[0]?.delta?.content;
          if (text) controller.enqueue(encoder.encode(text));
          if (json.usage) {
            usage.promptTokens = json.usage.prompt_tokens ?? usage.promptTokens;
            usage.completionTokens = json.usage.completion_tokens ?? usage.completionTokens;
          }
        } catch {
          // fragmento parcial — ignora, próximo chunk completa
        }
      }
    },
    cancel() {
      void reader.cancel();
    },
  });
}

export async function POST(req: Request): Promise<Response> {
  // Exige sessão: evita uso anônimo do orçamento de IA. O contexto do workspace
  // também depende dos cookies da sessão.
  if (!cookies().get("nora_access")?.value) {
    return new Response(JSON.stringify({ error: "Não autenticado." }), {
      status: 401,
      headers: { "Content-Type": "application/json" },
    });
  }

  let body: { messages?: ChatMessage[] };
  try {
    body = (await req.json()) as { messages?: ChatMessage[] };
  } catch {
    return new Response(JSON.stringify({ error: "JSON inválido." }), {
      status: 400,
      headers: { "Content-Type": "application/json" },
    });
  }

  const history = (body.messages ?? [])
    .filter((m) => m && (m.role === "user" || m.role === "assistant") && typeof m.content === "string")
    .slice(-16);

  if (history.length === 0) {
    return new Response(JSON.stringify({ error: "Nenhuma mensagem enviada." }), {
      status: 400,
      headers: { "Content-Type": "application/json" },
    });
  }

  const cfg = await resolveChatModel();
  if (!cfg.enabled) {
    return new Response(
      JSON.stringify({ error: "O chat está temporariamente desativado pelo operador." }),
      { status: 503, headers: { "Content-Type": "application/json" } },
    );
  }

  const apiKey = resolveKey(cfg.provider);
  if (!apiKey) {
    return new Response(
      JSON.stringify({ error: `LLM não configurado (sem chave para o provider "${cfg.provider}").` }),
      { status: 503, headers: { "Content-Type": "application/json" } },
    );
  }

  const cookieHeader = cookies()
    .getAll()
    .map((c) => `${c.name}=${c.value}`)
    .join("; ");
  // RAG: a última mensagem do usuário vira a query da busca semântica de reuniões.
  const lastUserMsg = [...history].reverse().find((m) => m.role === "user")?.content ?? "";
  const workspaceContext = await buildWorkspaceContext(cookieHeader, lastUserMsg);

  const systemContent = workspaceContext
    ? `${SYSTEM_PROMPT}\n\n--- CONTEXTO DO WORKSPACE (use quando relevante) ---\n${workspaceContext}`
    : `${SYSTEM_PROMPT}\n\n(Sem contexto de workspace disponível agora — responda de forma geral e, se precisar de dados de reuniões, peça pro usuário abrir/enviar a reunião.)`;

  const messages: ChatMessage[] = [{ role: "system", content: systemContent }, ...history];
  const tenantId = tenantIdFromCookies();
  const startedAt = Date.now();

  let upstream: Response;
  try {
    upstream = await fetch(`${cfg.baseUrl}/chat/completions`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: cfg.model,
        messages,
        stream: true,
        stream_options: { include_usage: true },
        temperature: 0.4,
        max_tokens: 900,
      }),
    });
  } catch {
    recordUsage(cfg, tenantId, { promptTokens: 0, completionTokens: 0 }, Date.now() - startedAt, "error");
    return new Response(JSON.stringify({ error: "Falha ao contatar o provedor de IA." }), {
      status: 502,
      headers: { "Content-Type": "application/json" },
    });
  }

  if (!upstream.ok || !upstream.body) {
    const detail = await upstream.text().catch(() => "");
    recordUsage(cfg, tenantId, { promptTokens: 0, completionTokens: 0 }, Date.now() - startedAt, "error");
    return new Response(
      JSON.stringify({ error: `Provedor de IA retornou ${upstream.status}.`, detail: detail.slice(0, 300) }),
      { status: 502, headers: { "Content-Type": "application/json" } },
    );
  }

  const stream = openAiSseToText(upstream.body, (usage) => {
    recordUsage(cfg, tenantId, usage, Date.now() - startedAt, "ok");
  });

  return new Response(stream, {
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Accel-Buffering": "no",
    },
  });
}
