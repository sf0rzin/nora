/**
 * NORA Core — endpoint de chat com IA (streaming).
 *
 * BFF: roda 100% server-side. A chave do LLM (`LLM_API_KEY`) NUNCA é exposta ao
 * browser. Provider-agnóstico (ADR 0004): fala com qualquer API compatível com
 * OpenAI via `LLM_BASE_URL` (default OpenAI `gpt-4o-mini`).
 *
 * Contexto do workspace: faz best-effort de buscar reuniões + action items do
 * usuário no backend (encaminhando os cookies httpOnly da sessão) e injeta como
 * contexto no system prompt — é isso que faz a NORA "saber" das reuniões. Se o
 * backend estiver indisponível, degrada graciosamente pra um assistente geral.
 *
 * Importante (LGPD/PII): o contexto usa apenas RESUMOS já processados (que
 * passaram pelo PII Shield no worker), nunca transcrições brutas.
 */
import { cookies } from "next/headers";

export const runtime = "nodejs";
export const dynamic = "force-dynamic";

const LLM_BASE_URL = (process.env.LLM_BASE_URL ?? "https://api.openai.com/v1").replace(/\/$/, "");
const LLM_API_KEY = process.env.LLM_API_KEY ?? "";
const LLM_MODEL = process.env.LLM_MODEL ?? "gpt-4o-mini";
const API_BASE_URL = (
  process.env.API_BASE_URL ??
  process.env.NEXT_PUBLIC_API_BASE_URL ??
  "http://localhost:8080"
).replace(/\/$/, "");

type Role = "system" | "user" | "assistant";
interface ChatMessage {
  role: Role;
  content: string;
}

const SYSTEM_PROMPT = `Você é a NORA, copiloto pessoal de reuniões do plano Core.

Personalidade e regras:
- Responda SEMPRE em português brasileiro, de forma direta, clara e concisa.
- Você ajuda o usuário a entender suas reuniões: resumos, decisões, action items, prazos e projetos.
- Use markdown leve (negrito, listas) quando ajudar a leitura. Não invente dados.
- Se a resposta depende de uma reunião específica e ela não está no contexto, diga que não encontrou e sugira abrir/enviar a reunião.
- Nunca exponha dados sensíveis (PII). O conteúdo que você recebe já foi tratado pelo PII Shield.
- Quando citar uma reunião, use o título dela.`;

async function buildWorkspaceContext(cookieHeader: string): Promise<string> {
  const parts: string[] = [];
  try {
    const headers = { Cookie: cookieHeader, Accept: "application/json" };
    const [mRes, tRes] = await Promise.all([
      fetch(`${API_BASE_URL}/meetings?size=12`, { headers, cache: "no-store" }),
      fetch(`${API_BASE_URL}/tasks`, { headers, cache: "no-store" }),
    ]);

    if (mRes.ok) {
      const data = (await mRes.json()) as {
        items?: Array<{
          title?: string;
          summarySnippet?: string;
          processingStatus?: string;
          startedAt?: string;
          actionItemCount?: number;
          riskCount?: number;
          opportunityCount?: number;
        }>;
      };
      const items = (data.items ?? []).slice(0, 12);
      if (items.length > 0) {
        parts.push("REUNIÕES RECENTES DO WORKSPACE:");
        for (const m of items) {
          const date = m.startedAt ? ` (${m.startedAt.slice(0, 10)})` : "";
          parts.push(
            `- [${m.processingStatus ?? "?"}] "${m.title ?? "sem título"}"${date}: ${
              m.summarySnippet ?? "sem resumo"
            } — ${m.actionItemCount ?? 0} action items, ${m.riskCount ?? 0} riscos, ${
              m.opportunityCount ?? 0
            } oportunidades`,
          );
        }
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

/** Transforma o stream SSE do OpenAI em stream de texto puro (deltas). */
function openAiSseToText(upstream: ReadableStream<Uint8Array>): ReadableStream<Uint8Array> {
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  let buffer = "";
  const reader = upstream.getReader();

  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      const { done, value } = await reader.read();
      if (done) {
        controller.close();
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
          controller.close();
          return;
        }
        try {
          const json = JSON.parse(payload) as {
            choices?: Array<{ delta?: { content?: string } }>;
          };
          const text = json.choices?.[0]?.delta?.content;
          if (text) controller.enqueue(encoder.encode(text));
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
  if (!LLM_API_KEY) {
    return new Response(JSON.stringify({ error: "LLM não configurado (LLM_API_KEY ausente)." }), {
      status: 503,
      headers: { "Content-Type": "application/json" },
    });
  }

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

  const cookieHeader = cookies()
    .getAll()
    .map((c) => `${c.name}=${c.value}`)
    .join("; ");
  const workspaceContext = await buildWorkspaceContext(cookieHeader);

  const systemContent = workspaceContext
    ? `${SYSTEM_PROMPT}\n\n--- CONTEXTO DO WORKSPACE (use quando relevante) ---\n${workspaceContext}`
    : `${SYSTEM_PROMPT}\n\n(Sem contexto de workspace disponível agora — responda de forma geral e, se precisar de dados de reuniões, peça pro usuário abrir/enviar a reunião.)`;

  const messages: ChatMessage[] = [{ role: "system", content: systemContent }, ...history];

  let upstream: Response;
  try {
    upstream = await fetch(`${LLM_BASE_URL}/chat/completions`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${LLM_API_KEY}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: LLM_MODEL,
        messages,
        stream: true,
        temperature: 0.4,
        max_tokens: 900,
      }),
    });
  } catch {
    return new Response(JSON.stringify({ error: "Falha ao contatar o provedor de IA." }), {
      status: 502,
      headers: { "Content-Type": "application/json" },
    });
  }

  if (!upstream.ok || !upstream.body) {
    const detail = await upstream.text().catch(() => "");
    return new Response(
      JSON.stringify({ error: `Provedor de IA retornou ${upstream.status}.`, detail: detail.slice(0, 300) }),
      { status: 502, headers: { "Content-Type": "application/json" } },
    );
  }

  return new Response(openAiSseToText(upstream.body), {
    headers: {
      "Content-Type": "text/plain; charset=utf-8",
      "Cache-Control": "no-store",
      "X-Accel-Buffering": "no",
    },
  });
}
