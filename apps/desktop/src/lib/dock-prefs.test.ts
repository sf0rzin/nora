import assert from "node:assert/strict";
import { beforeEach, test } from "node:test";

import { getDockVisible, setDockVisible } from "./dock-prefs.ts";

// The module talks to localStorage, which does not exist in the test runner. This is the whole
// of the API it uses, and it is also what lets the "storage is unavailable" branch be tested at
// all — that branch is why the dock still appears in a webview with storage disabled.
class MemoryStorage {
  private map = new Map<string, string>();
  getItem(key: string): string | null {
    return this.map.has(key) ? (this.map.get(key) as string) : null;
  }
  setItem(key: string, value: string): void {
    this.map.set(key, value);
  }
}

class ThrowingStorage {
  getItem(): string {
    throw new Error("storage disabled");
  }
  setItem(): void {
    throw new Error("storage disabled");
  }
}

function useStorage(storage: unknown): void {
  (globalThis as { localStorage?: unknown }).localStorage = storage;
}

beforeEach(() => useStorage(new MemoryStorage()));

test("the dock is visible until the user says otherwise", () => {
  assert.equal(getDockVisible(), true);
});

test("the preference survives a round trip in both directions", () => {
  setDockVisible(false);
  assert.equal(getDockVisible(), false);
  setDockVisible(true);
  assert.equal(getDockVisible(), true);
});

test("a stored value that is not the codec's own reads as hidden, never as junk", () => {
  const storage = new MemoryStorage();
  storage.setItem("nora.dock.visible", "true");
  useStorage(storage);
  // "1"/"0" is the codec. Anything else is a value this app did not write, and the safe answer
  // is the explicit one rather than a truthy string.
  assert.equal(getDockVisible(), false);
});

test("storage being unavailable falls back to visible rather than throwing", () => {
  useStorage(new ThrowingStorage());
  assert.equal(getDockVisible(), true);
  assert.doesNotThrow(() => setDockVisible(false));
});
