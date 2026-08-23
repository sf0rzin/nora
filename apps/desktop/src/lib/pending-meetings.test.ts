import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";

import {
  getPendingCount,
  getPendingMeetings,
  removePendingMeeting,
  savePendingMeeting,
  type PendingMeeting,
} from "./pending-meetings.ts";

// This queue is the last copy of a meeting that failed to upload. Everything below is about the
// two ways it can lose one: junk in storage taking the whole list down with it, and a save that
// duplicates instead of updating (which is what produced duplicate meetings in the backend).

const KEY = "nora-pending-meetings";

class MemoryStorage {
  private map = new Map<string, string>();
  getItem(key: string): string | null {
    return this.map.has(key) ? (this.map.get(key) as string) : null;
  }
  setItem(key: string, value: string): void {
    this.map.set(key, value);
  }
}

function storage(): MemoryStorage {
  return (globalThis as { localStorage?: MemoryStorage }).localStorage as MemoryStorage;
}

function meeting(id: string, overrides: Partial<PendingMeeting> = {}): PendingMeeting {
  return {
    id,
    status: "pending",
    payload: {
      title: "Reunião",
      startedAt: "2026-08-23T10:00:00.000Z",
      transcriptFormat: "TXT",
      fileContent: "[Eu] bom dia",
      fileName: "reuniao.txt",
    },
    createdAt: "2026-08-23T11:00:00.000Z",
    retryCount: 0,
    ...overrides,
  };
}

beforeEach(() => {
  (globalThis as { localStorage?: unknown }).localStorage = new MemoryStorage();
});

test("an empty store yields an empty queue rather than a crash", () => {
  assert.deepEqual(getPendingMeetings(), []);
  assert.equal(getPendingCount(), 0);
});

test("saving the same id twice updates the entry instead of queueing it again", () => {
  savePendingMeeting(meeting("a"));
  savePendingMeeting(meeting("a", { retryCount: 3, lastError: "timeout" }));

  const queued = getPendingMeetings();
  assert.equal(queued.length, 1);
  assert.equal(queued[0].retryCount, 3);
  assert.equal(queued[0].lastError, "timeout");
});

test("only entries still marked pending are counted for the retry worker", () => {
  savePendingMeeting(meeting("a"));
  savePendingMeeting(meeting("b", { status: "failed_permanently", retryCount: 10 }));
  savePendingMeeting(meeting("c", { status: "completed" }));

  assert.equal(getPendingMeetings().length, 3);
  assert.equal(getPendingCount(), 1);
});

test("removing an entry leaves the rest of the queue alone", () => {
  savePendingMeeting(meeting("a"));
  savePendingMeeting(meeting("b"));
  removePendingMeeting("a");

  assert.deepEqual(
    getPendingMeetings().map((m) => m.id),
    ["b"],
  );
});

test("junk in storage is filtered out entry by entry, not by dropping the queue", () => {
  storage().setItem(
    KEY,
    JSON.stringify([{ id: "no-payload" }, meeting("real"), "not even an object", null]),
  );

  const queued = getPendingMeetings();
  assert.equal(queued.length, 1);
  assert.equal(queued[0].id, "real");
});

test("storage holding something that is not an array reads as an empty queue", () => {
  storage().setItem(KEY, "{ not json");
  assert.deepEqual(getPendingMeetings(), []);

  storage().setItem(KEY, JSON.stringify({ id: "a" }));
  assert.deepEqual(getPendingMeetings(), []);
});
