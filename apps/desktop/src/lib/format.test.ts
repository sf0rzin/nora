import assert from "node:assert/strict";
import { test } from "node:test";

import { formatDuration, relTime } from "./format.ts";

// These two helpers draw the recording timer in the dock and the timestamp of every line in
// the overlay feed. The dock's own copy of formatDuration used to break past one hour, which is
// exactly the length a meeting has to reach for anybody to notice.

test("formatDuration pads to M:SS below one hour", () => {
  assert.equal(formatDuration(0), "00:00");
  assert.equal(formatDuration(9), "00:09");
  assert.equal(formatDuration(75), "01:15");
  assert.equal(formatDuration(599), "09:59");
});

test("formatDuration grows to H:MM:SS instead of counting minutes forever", () => {
  assert.equal(formatDuration(3600), "1:00:00");
  assert.equal(formatDuration(3661), "1:01:01");
  // The two-hour meeting the STT reconnection budget is sized for.
  assert.equal(formatDuration(7325), "2:02:05");
});

test("relTime renders the feed offset as MM:SS from milliseconds", () => {
  assert.equal(relTime(0), "00:00");
  assert.equal(relTime(1500), "00:01");
  assert.equal(relTime(61_000), "01:01");
  // Past an hour it deliberately keeps counting minutes: the feed's offsets are read against
  // the start of the recording, and "75:00" is easier to match to a transcript line than
  // "1:15:00" would be next to a wall clock.
  assert.equal(relTime(4_500_000), "75:00");
});
