/**
 * Browser-local memory of the reasoning attached to each persisted assistant message.
 *
 * The chat presents the chain of thought as part of the answer while it streams, and then lost it
 * on every reload: `POST /chat/sessions/{id}/messages` carries `role` and `content` and nothing
 * else, so re-opening a conversation rebuilt the bubbles from content alone and half of what the
 * user had watched arrive was simply gone.
 *
 * The durable fix is a column and a DTO field on the backend, and that is where it belongs. This
 * is the part that is honestly buildable on this side of the wire, and it covers the symptom that
 * was actually reported — the SAME BROWSER re-opening the SAME conversation. It does not follow
 * the user to another device, and it must never be the only copy of anything: the reasoning is
 * reference material, the answer is the record.
 *
 * Keyed by position, verified by content. A message that failed to persist shifts every index
 * after it, so an index alone would eventually pin somebody's thinking under the wrong reply;
 * `head` is the first characters of the content the reasoning was saved with, and a mismatch drops
 * the entry rather than showing it in the wrong place.
 */

const STORAGE_KEY = 'nora:chat-reasoning';

/** Conversations kept at once. Past this, the least recently written one is dropped. */
const MAX_SESSIONS = 20;

/** Per-message ceiling. A runaway chain of thought must not be what fills the origin's quota. */
const MAX_REASONING_CHARS = 20_000;

/** Characters of the answer stored alongside, as the check that the index still lines up. */
const HEAD_CHARS = 64;

interface StoredReasoning {
  index: number;
  head: string;
  text: string;
}

interface SessionEntry {
  items: StoredReasoning[];
}

type Store = Record<string, SessionEntry>;

function head(content: string): string {
  return content.slice(0, HEAD_CHARS);
}

/**
 * Every access is wrapped: `localStorage` throws on a disabled origin and on a full quota, and
 * neither is a reason for the chat to stop working. A store that cannot be read is an empty one.
 */
function readStore(): Store {
  if (typeof window === 'undefined') return {};
  try {
    const raw = window.localStorage.getItem(STORAGE_KEY);
    if (!raw) return {};
    const parsed = JSON.parse(raw) as unknown;
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return {};
    return parsed as Store;
  } catch {
    return {};
  }
}

function writeStore(store: Store): void {
  if (typeof window === 'undefined') return;
  try {
    window.localStorage.setItem(STORAGE_KEY, JSON.stringify(store));
  } catch {
    // Quota or a disabled origin. Losing the reasoning of a reload is the acceptable failure
    // here; throwing out of the persist path would take the message with it.
  }
}

/**
 * Drops the least recently written conversations.
 *
 * Recency is KEY ORDER, not a timestamp, because `Date.now()` cannot separate two writes in the
 * same millisecond and the eviction would then depend on how the sort broke the tie. Every write
 * re-inserts its session at the end, and JSON round-trips preserve insertion order for
 * non-integer-like keys — session ids are UUIDs, so that holds.
 */
function prune(store: Store): Store {
  const ids = Object.keys(store);
  if (ids.length <= MAX_SESSIONS) return store;
  const next: Store = {};
  for (const id of ids.slice(ids.length - MAX_SESSIONS)) next[id] = store[id];
  return next;
}

/**
 * Records the reasoning of the assistant message that has just been persisted at `index`.
 * An empty reasoning erases whatever was there instead of leaving a stale one behind.
 */
export function rememberReasoning(
  sessionId: string,
  index: number,
  content: string,
  reasoning: string,
): void {
  if (!sessionId || index < 0) return;
  const store = readStore();
  const items = (store[sessionId]?.items ?? []).filter((item) => item.index !== index);
  const text = reasoning.trim();
  if (text) {
    items.push({ index, head: head(content), text: text.slice(0, MAX_REASONING_CHARS) });
  }
  // Deleted and re-added rather than assigned in place: the key order IS the recency order that
  // `prune` evicts by, and assigning to an existing key would leave it where it was.
  delete store[sessionId];
  if (items.length > 0) store[sessionId] = { items };
  writeStore(prune(store));
}

/** Forgets a whole conversation — used when the session itself is deleted. */
export function forgetReasoning(sessionId: string): void {
  const store = readStore();
  if (!(sessionId in store)) return;
  delete store[sessionId];
  writeStore(store);
}

export interface HydratedMessage {
  role: 'user' | 'assistant';
  content: string;
  reasoning?: string;
}

/**
 * Re-attaches the stored reasoning to a freshly loaded history. Messages whose content no longer
 * starts the way it did when the reasoning was saved come back without it — a bubble with no
 * chain of thought is a small loss, one under the wrong answer is a lie.
 */
export function applyStoredReasoning(
  sessionId: string,
  messages: readonly { role: 'user' | 'assistant'; content: string }[],
): HydratedMessage[] {
  const items = readStore()[sessionId]?.items ?? [];
  if (items.length === 0) return messages.map((m) => ({ role: m.role, content: m.content }));
  const byIndex = new Map(items.map((item) => [item.index, item]));
  return messages.map((message, index) => {
    const stored = byIndex.get(index);
    const matches =
      stored !== undefined && message.role === 'assistant' && head(message.content) === stored.head;
    return matches
      ? { role: message.role, content: message.content, reasoning: stored.text }
      : { role: message.role, content: message.content };
  });
}
