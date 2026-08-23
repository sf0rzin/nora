/**
 * The wire format of `POST /api/chat`, written once and imported by both ends.
 *
 * The body is NDJSON: one `{"t":"r"|"c","c":"…"}` object per line. `r` is the model thinking out
 * loud, `c` is the answer, and they are separate frames because folding the reasoning into the
 * answer would make the thinking READ as the answer — a reasoning model spends far more tokens
 * thinking than replying.
 *
 * Encoder and decoder live in the same module on purpose. They were previously two hand-written
 * halves — a template literal in the route handler and an inline `JSON.parse` loop in the screen
 * — with nothing tying them together, so the only thing keeping the format from drifting was that
 * one person had both files open. This module is what `src/lib/chat/ndjson.test.ts` pins.
 */

/** `r` = reasoning (the chain of thought), `c` = content (the answer). */
export type ChatFrameKind = 'r' | 'c';

export interface ChatFrame {
  kind: ChatFrameKind;
  text: string;
}

/** One frame, terminated by the newline that separates it from the next one. */
export function encodeChatFrame(kind: ChatFrameKind, text: string): string {
  return `${JSON.stringify({ t: kind, c: text })}\n`;
}

export interface ChatFrameDecoder {
  /**
   * Feeds one decoded chunk of the body and returns the frames that completed with it. A chunk
   * that ends mid-line contributes nothing until the rest of that line arrives.
   */
  push(chunk: string): ChatFrame[];
  /** Whatever is still buffered when the body ends, if it happens to be a whole frame. */
  flush(): ChatFrame[];
}

/**
 * Stateful decoder over a body that arrives in arbitrary pieces.
 *
 * Two properties matter and neither is obvious from the format. A chunk cuts on byte boundaries,
 * not line boundaries, so the tail of every chunk is assumed to be PARTIAL and held back until a
 * newline confirms it — dropping it instead would lose a delta on roughly every chunk. And a line
 * that does not parse is discarded rather than surfaced: printing raw protocol at the user is a
 * worse failure than losing one delta.
 */
export function createChatFrameDecoder(): ChatFrameDecoder {
  let pending = '';

  const parse = (line: string, out: ChatFrame[]): void => {
    if (!line.trim()) return;
    let raw: { t?: unknown; c?: unknown };
    try {
      raw = JSON.parse(line) as { t?: unknown; c?: unknown };
    } catch {
      return;
    }
    if (raw.t !== 'r' && raw.t !== 'c') return;
    if (typeof raw.c !== 'string' || raw.c === '') return;
    out.push({ kind: raw.t, text: raw.c });
  };

  return {
    push(chunk: string): ChatFrame[] {
      pending += chunk;
      const lines = pending.split('\n');
      pending = lines.pop() ?? '';
      const frames: ChatFrame[] = [];
      for (const line of lines) parse(line, frames);
      return frames;
    },
    flush(): ChatFrame[] {
      const frames: ChatFrame[] = [];
      parse(pending, frames);
      pending = '';
      return frames;
    },
  };
}

/** Accumulated reasoning and answer, in the shape the chat bubble renders. */
export interface ChatStreamState {
  reasoning: string;
  content: string;
}

/** Folds frames into the running state. Kept here so both ends agree on which one is which. */
export function applyChatFrames(state: ChatStreamState, frames: readonly ChatFrame[]): void {
  for (const frame of frames) {
    if (frame.kind === 'r') state.reasoning += frame.text;
    else state.content += frame.text;
  }
}
