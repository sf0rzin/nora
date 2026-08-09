import { useEffect, useRef, useState } from "react";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import { useNow } from "@/hooks/use-now";

export interface LiveTranscriptLine {
  id: string;
  text: string;
  speaker: string | null;
  speakerId: string | null;
  track: string;
  timestamp: number;
}

interface RecordingStatus {
  isRecording: boolean;
  micDevice?: string;
  systemAudioDevice?: string | null;
  sampleRate?: number;
}

interface State {
  lines: LiveTranscriptLine[];
  /**
   * Partial text ("typing") per track. Each sidecar (mic / system) emits
   * its own partial concurrently — keeping a single string made one
   * overwrite the other and the bubble show up on the wrong side. Keyed by track.
   */
  partials: Record<string, string>;
  isRecording: boolean;
  startedAt: number | null;
  micDevice: string;
  systemAudioDevice: string | null;
  sampleRate: number;
}

/**
 * Listens to Tauri transcript + recording-status events.
 * Designed for the overlay window which doesn't share React state with main.
 * Each instance keeps its own line buffer.
 */
export function useLiveTranscript() {
  const [state, setState] = useState<State>({
    lines: [],
    partials: {},
    isRecording: false,
    startedAt: null,
    micDevice: "",
    systemAudioDevice: null,
    sampleRate: 0,
  });
  const wasRecordingRef = useRef(false);

  useEffect(() => {
    let cancelled = false;
    const stored: UnlistenFn[] = [];
    const attach = (p: Promise<UnlistenFn>) => {
      p.then((fn) => {
        if (cancelled) fn();
        else stored.push(fn);
      }).catch(() => {});
    };

    // Transcript stream
    attach(listen<unknown>("transcript", (event) => {
      const payload = event.payload as {
        text: string;
        isFinal: boolean;
        speaker: string | null;
        speakerId: string | null;
        track: string;
      };
      const track = payload.track || "mic";
      if (payload.isFinal) {
        const line: LiveTranscriptLine = {
          id: crypto.randomUUID(),
          text: payload.text,
          speaker: payload.speaker,
          speakerId: payload.speakerId,
          track,
          timestamp: Date.now(),
        };
        setState((prev) => {
          // Clear only THIS track's partial — the other track may still be
          // talking and its "typing" must stay visible.
          const { [track]: _drop, ...partials } = prev.partials;
          void _drop;
          return { ...prev, lines: [...prev.lines, line], partials };
        });
      } else {
        setState((prev) => ({
          ...prev,
          partials: { ...prev.partials, [track]: payload.text },
        }));
      }
    }));

    // Clears the transcript when a session is discarded/saved/restarted.
    // clear_live_highlights (Rust) emits 'clear-highlights' on every
    // stop/cancel/start — without it, reopening the overlay showed the old chat.
    // Does NOT touch wasRecordingRef: on START the 'clear-highlights' arrives AFTER
    // the 'recording-status' true, so resetting the ref here would break start/stop
    // transition detection. The recording-status branches own that
    // flag; here we only zero the visible buffer.
    attach(listen("clear-highlights", () => {
      setState((prev) => ({ ...prev, lines: [], partials: {} }));
    }));

    // Recording status
    attach(listen<RecordingStatus>("recording-status", (event) => {
      const s = event.payload;
      setState((prev) => {
        // Started: capture startedAt and clear stale transcript from previous run
        if (s.isRecording && !wasRecordingRef.current) {
          wasRecordingRef.current = true;
          return {
            lines: [],
            partials: {},
            isRecording: true,
            startedAt: Date.now(),
            micDevice: s.micDevice || "",
            systemAudioDevice: s.systemAudioDevice ?? null,
            sampleRate: s.sampleRate || 0,
          };
        }
        // Stopped: drop partials (lines are cleared via 'clear-highlights')
        if (!s.isRecording && wasRecordingRef.current) {
          wasRecordingRef.current = false;
          return { ...prev, isRecording: false, partials: {} };
        }
        return {
          ...prev,
          isRecording: !!s.isRecording,
          micDevice: s.micDevice ?? prev.micDevice,
          systemAudioDevice: s.systemAudioDevice ?? prev.systemAudioDevice,
        };
      });
    }));

    // Pull initial status in case we mounted mid-recording
    invoke<RecordingStatus>("get_recording_status")
      .then((s) => {
        if (s.isRecording) {
          wasRecordingRef.current = true;
          setState((prev) => ({
            ...prev,
            isRecording: true,
            startedAt: prev.startedAt ?? Date.now(),
            micDevice: s.micDevice || "",
            systemAudioDevice: s.systemAudioDevice ?? null,
            sampleRate: s.sampleRate || 0,
          }));
        }
      })
      .catch(() => {});

    return () => {
      cancelled = true;
      stored.forEach((fn) => fn());
    };
  }, []);

  // Live-updating duration in seconds — useNow forces the timer re-render.
  useNow(state.isRecording && state.startedAt != null);
  const duration =
    state.startedAt == null ? 0 : Math.floor((Date.now() - state.startedAt) / 1000);

  return {
    lines: state.lines,
    partials: state.partials,
    isRecording: state.isRecording,
    startedAt: state.startedAt,
    duration,
    micDevice: state.micDevice,
    systemAudioDevice: state.systemAudioDevice,
    sampleRate: state.sampleRate,
  };
}
