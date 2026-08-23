/**
 * The NDJSON framing between `POST /api/chat` and the chat screen.
 *
 * This is the contract the last four commits on the chat kept changing, and it had no test of any
 * kind: the format was a template literal on one side of the wire and a hand-written `JSON.parse`
 * loop on the other, with nothing tying them together. What the tests below pin is not the shape
 * of the JSON — that is one line and nobody gets it wrong — but the four behaviours that are easy
 * to get wrong and invisible when you do.
 *
 * 1. Reasoning and answer never mix. A reasoning model emits far more thinking than reply
 *    (measured: 105 reasoning chunks before the first content token), so a decoder that folds `r`
 *    into `c` makes the chain of thought READ as the answer.
 * 2. A chunk that ends mid-line holds back its tail. Bodies cut on byte boundaries, never on line
 *    boundaries, and a decoder that assumes otherwise loses a delta on roughly every chunk.
 * 3. A line that does not parse is dropped, not surfaced. Printing raw protocol at the user is a
 *    worse failure than losing one token.
 * 4. Encoder and decoder are inverses. Anything the route can emit, the screen can read.
 */
import { describe, expect, it } from 'vitest';

import {
  applyChatFrames,
  createChatFrameDecoder,
  encodeChatFrame,
  type ChatStreamState,
} from '@/lib/chat/ndjson';

/** Feeds a whole body through the decoder in the given pieces, exactly as the screen does. */
function decodeAll(chunks: string[]): ChatStreamState {
  const state: ChatStreamState = { reasoning: '', content: '' };
  const decoder = createChatFrameDecoder();
  for (const chunk of chunks) applyChatFrames(state, decoder.push(chunk));
  applyChatFrames(state, decoder.flush());
  return state;
}

describe('encodeChatFrame', () => {
  it('writes one newline-terminated object per frame', () => {
    expect(encodeChatFrame('c', 'hello')).toBe('{"t":"c","c":"hello"}\n');
  });

  it('escapes a payload that would otherwise break the line framing', () => {
    const line = encodeChatFrame('c', 'first\nsecond "quoted"');
    // Exactly one newline, at the end: an unescaped one would be read as two frames.
    expect(line.indexOf('\n')).toBe(line.length - 1);
    expect(decodeAll([line]).content).toBe('first\nsecond "quoted"');
  });
});

describe('createChatFrameDecoder', () => {
  it('keeps reasoning out of the answer', () => {
    const body = [
      encodeChatFrame('r', 'thinking about it'),
      encodeChatFrame('r', ' some more'),
      encodeChatFrame('c', 'The answer'),
    ].join('');

    expect(decodeAll([body])).toEqual({
      reasoning: 'thinking about it some more',
      content: 'The answer',
    });
  });

  it('holds back a frame split across two chunks instead of losing it', () => {
    const whole = encodeChatFrame('c', 'complete');
    const cut = Math.floor(whole.length / 2);

    const decoder = createChatFrameDecoder();
    // The first half is not a frame yet, and must not be reported as one.
    expect(decoder.push(whole.slice(0, cut))).toEqual([]);
    expect(decoder.push(whole.slice(cut))).toEqual([{ kind: 'c', text: 'complete' }]);
  });

  it('reassembles a body cut at every single character boundary', () => {
    const body =
      encodeChatFrame('r', 'step one') + encodeChatFrame('c', 'answer') + encodeChatFrame('c', '!');
    const chunks = [...body].map((ch) => ch);

    expect(decodeAll(chunks)).toEqual({ reasoning: 'step one', content: 'answer!' });
  });

  it('drops a malformed line and keeps decoding the ones around it', () => {
    const body = [
      encodeChatFrame('c', 'before'),
      'not json at all\n',
      '{"t":"c"\n',
      encodeChatFrame('c', 'after'),
    ].join('');

    expect(decodeAll([body]).content).toBe('beforeafter');
  });

  it('ignores frames of an unknown kind rather than treating them as content', () => {
    // A future frame type must degrade to nothing on an old client, never to text in the bubble.
    const body = '{"t":"x","c":"metadata"}\n' + encodeChatFrame('c', 'answer');

    expect(decodeAll([body])).toEqual({ reasoning: '', content: 'answer' });
  });

  it('ignores blank lines and a body that ends without a trailing newline', () => {
    const decoder = createChatFrameDecoder();
    decoder.push('\n\n' + encodeChatFrame('c', 'one'));
    // No newline after this one — only `flush` can complete it.
    const pushed = decoder.push('{"t":"c","c":"two"}');
    expect(pushed).toEqual([]);
    expect(decoder.flush()).toEqual([{ kind: 'c', text: 'two' }]);
  });

  it('reports nothing on flush when the tail is not a whole frame', () => {
    const decoder = createChatFrameDecoder();
    decoder.push('{"t":"c","c":"trun');
    expect(decoder.flush()).toEqual([]);
  });
});
