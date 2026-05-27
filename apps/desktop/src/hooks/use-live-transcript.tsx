import { useEffect, useRef, useState } from "react";
import { listen } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";

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
  partial: string;
  isRecording: boolean;
  startedAt: number | null;
  micDevice: string;
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
    partial: "",
    isRecording: false,
    startedAt: null,
    micDevice: "",
    sampleRate: 0,
  });
  const wasRecordingRef = useRef(false);

  useEffect(() => {
    // Transcript stream
    const unTranscript = listen<unknown>("transcript", (event) => {
      const payload = event.payload as {
        text: string;
        isFinal: boolean;
        speaker: string | null;
        speakerId: string | null;
        track: string;
      };
      if (payload.isFinal) {
        const line: LiveTranscriptLine = {
          id: crypto.randomUUID(),
          text: payload.text,
          speaker: payload.speaker,
          speakerId: payload.speakerId,
          track: payload.track,
          timestamp: Date.now(),
        };
        setState((prev) => ({
          ...prev,
          lines: [...prev.lines, line],
          partial: "",
        }));
      } else {
        setState((prev) => ({ ...prev, partial: payload.text }));
      }
    });

    // Recording status
    const unStatus = listen<RecordingStatus>("recording-status", (event) => {
      const s = event.payload;
      setState((prev) => {
        // Started: capture startedAt and clear stale transcript from previous run
        if (s.isRecording && !wasRecordingRef.current) {
          wasRecordingRef.current = true;
          return {
            lines: [],
            partial: "",
            isRecording: true,
            startedAt: Date.now(),
            micDevice: s.micDevice || "",
            sampleRate: s.sampleRate || 0,
          };
        }
        // Stopped: keep lines, drop partial
        if (!s.isRecording && wasRecordingRef.current) {
          wasRecordingRef.current = false;
          return { ...prev, isRecording: false, partial: "" };
        }
        return { ...prev, isRecording: !!s.isRecording };
      });
    });

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
            sampleRate: s.sampleRate || 0,
          }));
        }
      })
      .catch(() => {});

    return () => {
      unTranscript.then((fn) => fn()).catch(() => {});
      unStatus.then((fn) => fn()).catch(() => {});
    };
  }, []);

  // Live-updating duration in seconds
  const [tick, setTick] = useState(0);
  useEffect(() => {
    if (!state.isRecording || state.startedAt == null) return;
    const id = setInterval(() => setTick((t) => t + 1), 500);
    return () => clearInterval(id);
  }, [state.isRecording, state.startedAt]);

  const duration =
    state.startedAt == null ? 0 : Math.floor((Date.now() - state.startedAt) / 1000);
  // referencia tick pra forçar re-render do timer (eslint silenciador)
  void tick;

  return {
    lines: state.lines,
    partial: state.partial,
    isRecording: state.isRecording,
    startedAt: state.startedAt,
    duration,
    micDevice: state.micDevice,
    sampleRate: state.sampleRate,
  };
}
