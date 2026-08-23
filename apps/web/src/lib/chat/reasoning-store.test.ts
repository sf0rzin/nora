/**
 * @vitest-environment jsdom
 *
 * Keeping the model's reasoning across a reload.
 *
 * The chat shows the chain of thought as part of the answer while it streams and then threw it
 * away on every reload: the persisted message carries `role` and `content` and nothing else, so a
 * re-opened conversation rebuilt the bubbles from content alone. This module is the part of the
 * fix that does not need the backend to grow a column.
 *
 * The property that carries the risk is the re-attachment. Entries are keyed by POSITION, and a
 * message that failed to persist shifts every position after it — so the head of the content is
 * stored alongside and checked before anything is shown. A bubble without its reasoning is a small
 * loss; somebody's reasoning under the wrong answer is a lie.
 */
import { beforeEach, describe, expect, it } from 'vitest';

import { applyStoredReasoning, forgetReasoning, rememberReasoning } from '@/lib/chat/reasoning-store';

const SESSION = 's-1';

beforeEach(() => {
  window.localStorage.clear();
});

function history(...contents: string[]) {
  return contents.map((content, i) => ({
    role: (i % 2 === 0 ? 'user' : 'assistant') as 'user' | 'assistant',
    content,
  }));
}

describe('rememberReasoning / applyStoredReasoning', () => {
  it('puts the reasoning back on the message it was saved for', () => {
    rememberReasoning(SESSION, 1, 'The answer', 'first I considered');

    const hydrated = applyStoredReasoning(SESSION, history('the question', 'The answer'));

    expect(hydrated[1].reasoning).toBe('first I considered');
    expect(hydrated[0].reasoning).toBeUndefined();
  });

  it('leaves the messages alone when nothing was stored for the session', () => {
    const messages = history('q', 'a');
    expect(applyStoredReasoning(SESSION, messages)).toEqual(messages);
  });

  it('keeps sessions apart', () => {
    rememberReasoning(SESSION, 1, 'The answer', 'thinking');

    expect(applyStoredReasoning('s-2', history('q', 'The answer'))[1].reasoning).toBeUndefined();
  });

  it('drops the entry when the message at that position is a different answer', () => {
    rememberReasoning(SESSION, 1, 'The answer', 'thinking');

    // An append that failed shifted the history: position 1 is now somebody else's text.
    const hydrated = applyStoredReasoning(SESSION, history('the question', 'Something else'));

    expect(hydrated[1].reasoning).toBeUndefined();
  });

  it('never attaches reasoning to a user message', () => {
    rememberReasoning(SESSION, 0, 'the question', 'thinking');

    expect(applyStoredReasoning(SESSION, history('the question', 'a'))[0].reasoning).toBeUndefined();
  });

  it('overwrites the entry at a position instead of accumulating', () => {
    rememberReasoning(SESSION, 1, 'The answer', 'first take');
    rememberReasoning(SESSION, 1, 'The answer', 'second take');

    expect(applyStoredReasoning(SESSION, history('q', 'The answer'))[1].reasoning).toBe(
      'second take',
    );
  });

  it('erases the entry when the reasoning is empty, rather than leaving a stale one', () => {
    rememberReasoning(SESSION, 1, 'The answer', 'thinking');
    rememberReasoning(SESSION, 1, 'The answer', '   ');

    expect(applyStoredReasoning(SESSION, history('q', 'The answer'))[1].reasoning).toBeUndefined();
  });

  it('forgets a whole conversation on request', () => {
    rememberReasoning(SESSION, 1, 'The answer', 'thinking');
    forgetReasoning(SESSION);

    expect(applyStoredReasoning(SESSION, history('q', 'The answer'))[1].reasoning).toBeUndefined();
  });

  it('evicts the least recently written conversations past the cap', () => {
    // Twenty-one sessions, written oldest first; the first one must be the one that goes.
    for (let i = 0; i < 21; i += 1) {
      rememberReasoning(`s-${i}`, 1, 'The answer', `thinking ${i}`);
    }

    expect(applyStoredReasoning('s-0', history('q', 'The answer'))[1].reasoning).toBeUndefined();
    expect(applyStoredReasoning('s-20', history('q', 'The answer'))[1].reasoning).toBe(
      'thinking 20',
    );
  });

  it('survives a corrupted store instead of taking the chat down with it', () => {
    window.localStorage.setItem('nora:chat-reasoning', 'not json');

    expect(() => applyStoredReasoning(SESSION, history('q', 'a'))).not.toThrow();
    expect(() => rememberReasoning(SESSION, 1, 'a', 'thinking')).not.toThrow();
    // And the write repairs it.
    expect(applyStoredReasoning(SESSION, history('q', 'a'))[1].reasoning).toBe('thinking');
  });
});
