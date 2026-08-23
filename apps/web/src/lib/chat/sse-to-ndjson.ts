/**
 * Provider SSE in, NORA NDJSON out — the body half of `POST /api/chat`.
 *
 * It lives here rather than inside the route handler for one reason: a route module may only
 * export the HTTP verbs, so anything defined in it is unreachable from a test. This transform is
 * the most fragile contract in the product (reasoning versus answer, partial SSE fragments, the
 * idle budget below) and it had no test at all while it sat there.
 */
import { encodeChatFrame } from '@/lib/chat/ndjson';

export interface StreamUsage {
  promptTokens: number;
  completionTokens: number;
}

/**
 * What the stream loop actually saw, logged once per request when it closes.
 *
 * It exists because this route is opaque from outside: Next logs no requests in production, so
 * a stream that ends carrying nothing looks identical to one that was never started. Two wrong
 * diagnoses were shipped by reading the shape of a timeout instead of the contents of the loop.
 */
export interface StreamTrace {
  startedAt: number;
  firstByteMs: number | null;
  sseLines: number;
  reasoningChunks: number;
  contentChunks: number;
  /** Set when the idle budget below ended the stream instead of the provider closing it. */
  idleTimedOut?: boolean;
}

export interface SseToNdjsonOptions {
  /** Called exactly once, on close, with the tokens the provider reported. */
  onComplete: (usage: StreamUsage) => void;
  trace: StreamTrace;
  /**
   * How long the loop waits between two upstream chunks before it gives up.
   *
   * The header deadline in the route bounds the wait for the FIRST byte and is cleared the moment
   * the headers land, which left the body unbounded: a provider that sends one token and then
   * stops held the request until the edge killed it two minutes later, with the socket to the
   * provider open for the whole window.
   *
   * What is bounded is IDLENESS, not total length, and the distinction is the whole design. A
   * total cap would cut a long but healthy answer off mid-sentence — the same reason the route
   * uses a controller for the headers instead of `AbortSignal.timeout`. A stream that is still
   * producing tokens is working, however long it takes; a stream that has produced nothing for
   * this many milliseconds is not coming back.
   */
  idleTimeoutMs: number;
  /**
   * Markdown appended as a last content frame when the idle budget expires, so the half-written
   * answer on screen does not read as a finished one. Passed in rather than written here because
   * it is user-facing copy, and user-facing copy is pt-BR and lives with the route.
   */
  idleNotice: string;
}

type ReadOutcome =
  | { kind: 'chunk'; value: Uint8Array }
  | { kind: 'done' }
  | { kind: 'idle' };

/**
 * Turns the SSE stream (OpenAI-compatible) into NDJSON frames and captures the final `usage`
 * block (stream_options.include_usage). Calls `onComplete` exactly once on close, with the
 * accumulated tokens.
 */
export function openAiSseToNdjson(
  upstream: ReadableStream<Uint8Array>,
  options: SseToNdjsonOptions,
): ReadableStream<Uint8Array> {
  const { onComplete, trace, idleTimeoutMs, idleNotice } = options;
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  let buffer = '';
  const usage: StreamUsage = { promptTokens: 0, completionTokens: 0 };
  let finished = false;
  const reader = upstream.getReader();

  const frame = (kind: 'r' | 'c', text: string) => encoder.encode(encodeChatFrame(kind, text));

  const finish = (controller: ReadableStreamDefaultController<Uint8Array>) => {
    if (finished) return;
    finished = true;
    try {
      onComplete(usage);
    } catch {
      /* never propagates */
    }
    // ONE line per request, and it exists because its absence cost two wrong fixes. This route
    // is a black box from outside: Next logs no requests in production, so a stream that ends
    // with nothing in it looks exactly like a stream that was never asked for. Twice I inferred
    // a cause from the shape of a timeout instead of from what the loop actually saw.
    console.log(
      `[chat] upstream done: reasoning=${trace.reasoningChunks} content=${trace.contentChunks} ` +
        `sse=${trace.sseLines} firstByteMs=${trace.firstByteMs ?? 'never'} ` +
        `totalMs=${Date.now() - trace.startedAt} tokens=${usage.completionTokens} ` +
        `idle=${trace.idleTimedOut === true}`,
    );
    controller.close();
  };

  /**
   * One `reader.read()` under the idle budget. The losing read is not awaited again: the
   * `cancel()` on the timeout path settles it, and the timer is cleared on every outcome so a
   * healthy stream never accumulates one pending timer per chunk.
   */
  const readWithIdleBudget = async (): Promise<ReadOutcome> => {
    let timer: ReturnType<typeof setTimeout> | undefined;
    const idle = new Promise<ReadOutcome>((resolve) => {
      timer = setTimeout(() => resolve({ kind: 'idle' }), idleTimeoutMs);
    });
    try {
      return await Promise.race([
        reader.read().then<ReadOutcome>(({ done, value }) =>
          done || value === undefined ? { kind: 'done' } : { kind: 'chunk', value },
        ),
        idle,
      ]);
    } finally {
      if (timer !== undefined) clearTimeout(timer);
    }
  };

  return new ReadableStream<Uint8Array>({
    // THE SILENCE IS THE BUG, and the fix is to stop having one rather than to paper over it.
    //
    // The configured model REASONS before it answers: its deltas carry `reasoning_content` for
    // several seconds before the first `content` token. Measured against the deployed provider
    // on a one-line question: fields `role, content, reasoning_content`, 105 reasoning chunks
    // ahead of 48 content chunks. The previous loop enqueued only on `content`, so that whole
    // stretch produced no bytes, the response headers were never flushed, and Caddy killed the
    // request with `net/http: timeout awaiting response headers`. Through the edge: 504 at
    // 120,064ms. The chat was not slow, it was unreachable.
    //
    // An earlier attempt emitted a single zero-width space here purely to flush the headers. It
    // worked in the narrow sense — 504 became 200 — and was still the wrong shape: it hid the
    // symptom and left the user watching an empty bubble for two minutes. The reasoning IS what
    // there is to show while the model thinks.
    //
    // Frames are NDJSON, one `{"t":"r"|"c","c":"..."}` per line, because reasoning has to reach
    // the UI as something it can render apart from the answer. Appended into the same string it
    // would BE the answer, which is worse than showing nothing at all.
    // `start`, NOT `pull`, and that is the whole of the second fix.
    //
    // `pull` runs only when the CONSUMER asks for data. Phase markers on the deployed route
    // showed the request completing every step — session, model, context, redaction, provider
    // headers, stream built, Response returned — in about 600ms, and then, for roughly two
    // requests in five, nothing further: no `upstream done`, 0% CPU, and the edge killing the
    // request at exactly 120s. The stream was returned and never drained. `pull` was never
    // called, so nothing was enqueued, so the headers were never flushed, so the client had
    // nothing to read and the loop had no reason to run. A deadlock, not a stall.
    //
    // `start` pushes as soon as the stream exists, whether or not anyone has pulled yet. The
    // first chunk lands in the queue, Next flushes the headers, and the request stops depending
    // on a consumer that may not arrive.
    //
    // Everything upstream of this was measured and cleared first: the provider answers 12/12 in
    // under 700ms from inside this container, all six internal calls in under 62ms, DNS in 8ms,
    // TCP in 5ms, and the same request straight at web:3000 hangs at the same rate as through
    // Caddy — so it was never the network, the provider or the proxy.
    async start(controller) {
      for (;;) {
        const outcome = await readWithIdleBudget();
        if (outcome.kind === 'idle') {
          // The provider went quiet mid-body. Say so in the answer instead of closing on a
          // half-sentence that reads like a complete one, then release the upstream socket.
          trace.idleTimedOut = true;
          if (idleNotice) controller.enqueue(frame('c', idleNotice));
          void reader.cancel().catch(() => undefined);
          finish(controller);
          return;
        }
        if (outcome.kind === 'done') {
          finish(controller);
          return;
        }
        if (trace.firstByteMs === null) trace.firstByteMs = Date.now() - trace.startedAt;
        buffer += decoder.decode(outcome.value, { stream: true });
        const lines = buffer.split('\n');
        buffer = lines.pop() ?? '';
        for (const line of lines) {
          const trimmed = line.trim();
          if (!trimmed.startsWith('data:')) continue;
          trace.sseLines += 1;
          const payload = trimmed.slice(5).trim();
          if (payload === '[DONE]') {
            finish(controller);
            return;
          }
          try {
            const json = JSON.parse(payload) as {
              choices?: Array<{ delta?: { content?: string; reasoning_content?: string } }>;
              usage?: { prompt_tokens?: number; completion_tokens?: number };
            };
            const delta = json.choices?.[0]?.delta;
            const thinking = delta?.reasoning_content;
            if (thinking) {
              trace.reasoningChunks += 1;
              controller.enqueue(frame('r', thinking));
            }
            const text = delta?.content;
            if (text) {
              trace.contentChunks += 1;
              controller.enqueue(frame('c', text));
            }
            if (json.usage) {
              usage.promptTokens = json.usage.prompt_tokens ?? usage.promptTokens;
              usage.completionTokens = json.usage.completion_tokens ?? usage.completionTokens;
            }
          } catch {
            // partial fragment — ignore, the next chunk completes it
          }
        }
      }
    },
    cancel() {
      void reader.cancel();
    },
  });
}
