import { useState, useEffect, useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import type { RecordingStatus } from "@/lib/recording-types";
import { uploadTranscript } from "@/lib/meetings";
import { savePendingMeeting, removePendingMeeting, getPendingMeetings, getPendingCount } from "@/lib/pending-meetings";
import { useRecordingContext } from "./use-recording-context";
import { useLiveHighlights, useLiveAnalysisTrigger } from "./use-live-highlights";

interface UseRecordingOptions {
  language?: string;
  captureSystemAudio?: boolean;
  systemAudioDevice?: string | null;
}

/** Builds the transcript with a [speaker] prefix per line. Shared between
 *  saveMeeting and fullTranscript (they were identical). Desktop audit #90. */
function buildTranscript(
  lines: { text: string; speaker: string | null; speakerId: string | null; track: string }[],
  getSpeakerName: (speakerId: string | null, speaker: string | null, track?: string) => string | null,
): string {
  return lines
    .map((l) => {
      const speakerName = getSpeakerName(l.speakerId, l.speaker, l.track);
      return (speakerName ? `[${speakerName}] ` : "") + l.text;
    })
    .join("\n");
}

export function useRecording(options: UseRecordingOptions = {}) {
  const { 
    isRecording, 
    deviceName, 
    sampleRate, 
    transcriptLines,
    partialText: contextPartialText,
    speakerMap,
    setRecordingState,
    addTranscriptLine,
    setPartialText: setContextPartialText,
    renameSpeaker: contextRenameSpeaker,
    clearTranscript
  } = useRecordingContext();
  
  const [devices, setDevices] = useState<string[]>([]);
  const [selectedDevice, setSelectedDevice] = useState<string | null>(null);
  const [duration, setDuration] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [savedMeetingId, setSavedMeetingId] = useState<string | null>(null);
  const [saveError, setSaveError] = useState<string | null>(null);
  const [pendingCount, setPendingCount] = useState(0);
  const startTimeRef = useRef<number | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);
  // Persistent across re-runs of the retry effect so uploads aren't duplicated
  // if the effect is recreated (desktop audit #87).
  const inFlightIdsRef = useRef<Set<string>>(new Set());

  const { clearHighlights, highlights } = useLiveHighlights();
  const { triggerAnalysis, resetTrigger } = useLiveAnalysisTrigger();

  const transcriptLinesRef = useRef(transcriptLines);
  const highlightsRef = useRef(highlights);

  useEffect(() => {
    transcriptLinesRef.current = transcriptLines;
  }, [transcriptLines]);

  useEffect(() => {
    highlightsRef.current = highlights;
  }, [highlights]);

  useEffect(() => {
    let cancelled = false;
    const stored: UnlistenFn[] = [];
    const attach = (p: Promise<UnlistenFn>) => {
      p.then((fn) => {
        if (cancelled) fn();
        else stored.push(fn);
      }).catch(() => {});
    };

    attach(
      listen<unknown>("transcript", (event) => {
        const payload = event.payload as {
          text: string;
          isFinal: boolean;
          speaker: string | null;
          speakerId: string | null;
          track: string;
        };

        if (payload.isFinal) {
          const newLine = {
            id: crypto.randomUUID(),
            text: payload.text,
            isFinal: true,
            speaker: payload.speaker,
            speakerId: payload.speakerId,
            track: payload.track,
            timestamp: Date.now(),
          };
          addTranscriptLine(newLine);
          setContextPartialText("");
        } else {
          setContextPartialText(payload.text);
        }
      }),
    );

    attach(
      listen<RecordingStatus>("recording-status", (event) => {
        const s = event.payload;
        setRecordingState(s.isRecording, s.micDevice, s.sampleRate);
      }),
    );

    const checkStatus = async () => {
      try {
        const status = await invoke<RecordingStatus>("get_recording_status");
        if (status.isRecording) {
          setRecordingState(true, status.micDevice, status.sampleRate);
        }
      } catch (e) {
        console.error("[recording] failed to get status:", e);
      }
    };
    checkStatus();

    return () => {
      cancelled = true;
      stored.forEach((fn) => fn());
    };
  }, []);

  useEffect(() => {
    if (!isRecording || transcriptLines.length === 0) return;
    triggerAnalysis(transcriptLines, highlightsRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [transcriptLines.length, isRecording]);

  useEffect(() => {
    if (!isRecording) return;
    const interval = setInterval(() => {
      if (transcriptLinesRef.current.length > 0) {
        triggerAnalysis(transcriptLinesRef.current, highlightsRef.current, true);
      }
    }, 15000);
    return () => clearInterval(interval);
  }, [isRecording, triggerAnalysis]);

  const loadDevices = useCallback(async () => {
    try {
      const list = await invoke<string[]>("list_audio_devices");
      setDevices(list);
    } catch (e) {
      console.error("[recording] failed to list devices:", e);
    }
  }, []);

  const startRecording = useCallback(async (overrides?: {
    deviceName?: string | null;
    captureSystemAudio?: boolean;
    systemAudioDevice?: string | null;
    language?: string;
  }) => {
    setError(null);
    clearTranscript();
    setDuration(0);
    setSavedMeetingId(null);
    setSaveError(null);

    const req = {
      deviceName: overrides?.deviceName ?? selectedDevice,
      language: overrides?.language ?? options.language ?? "pt-BR",
      captureSystemAudio:
        overrides?.captureSystemAudio ?? options.captureSystemAudio ?? false,
      systemAudioDevice:
        overrides?.systemAudioDevice ?? options.systemAudioDevice ?? null,
    };

    try {
      const result = await invoke<RecordingStatus>("start_recording", { request: req });

      setRecordingState(true, result.micDevice, result.sampleRate);
      startTimeRef.current = Date.now();
      timerRef.current = setInterval(() => {
        if (startTimeRef.current) {
          setDuration(Math.floor((Date.now() - startTimeRef.current) / 1000));
        }
      }, 1000);

      clearHighlights();
      invoke("clear_live_highlights").catch(() => {});
      resetTrigger();
      invoke("toggle_overlay", { show: true }).catch((e) =>
        console.error("[recording] failed to open overlay:", e),
      );
    } catch (e) {
      console.error("[recording] start_recording FAILED:", e);
      setError(String(e));
      setRecordingState(false, "", 0);
    }
  }, [selectedDevice, options]);

  const stopRecording = useCallback(async () => {
    try {
      await invoke("stop_recording");
    } catch (e) {
      console.error("[recording] stop_recording FAILED:", e);
      setError(String(e));
    }
    if (timerRef.current) clearInterval(timerRef.current);
    setRecordingState(false, "", 0);

    invoke("toggle_overlay", { show: false }).catch(() => {});
    clearHighlights();
    invoke("clear_live_highlights").catch(() => {});
    resetTrigger();
  }, []);

  const renameSpeaker = useCallback((speakerId: string, newName: string) => {
    contextRenameSpeaker(speakerId, newName);
  }, []);

  const getSpeakerName = useCallback((speakerId: string | null, speaker: string | null, track?: string) => {
    // If it comes from the microphone, always show "Eu"
    if (track === "mic") return "Eu";
    if (!speakerId) return speaker;
    // If the speakerId is "UNKNOWN", show it as "Desconhecido"
    if (speakerId === "UNKNOWN") return "Desconhecido";
    // Return the mapped name, or the original speakerId
    return speakerMap[speakerId] || speakerId;
  }, [speakerMap]);

  const saveMeeting = useCallback(async (title: string): Promise<
    { ok: true; meetingId: string } | { ok: false; error: string }
  > => {
    if (transcriptLines.length === 0) {
      const msg = "Nenhuma transcrição para salvar";
      setError(msg);
      return { ok: false, error: msg };
    }

    setIsSaving(true);
    setSaveError(null);
    setError(null);

    const transcript = buildTranscript(transcriptLines, getSpeakerName);

    const startedAt = new Date(
      Date.now() - (duration * 1000)
    ).toISOString();

    const participants = Object.entries(speakerMap).map(([id, name]) => ({
      displayName: name || id,
    }));

    const meetingId = crypto.randomUUID();
    const payload = {
      title: title || "Reunião sem título",
      startedAt,
      transcriptFormat: "TXT" as const,
      fileContent: transcript,
      fileName: `${title || "reuniao"}_${new Date().toISOString()}.txt`,
      endedAt: new Date().toISOString(),
      participants: participants.length > 0 ? participants : undefined,
    };

    try {
      const result = await uploadTranscript(payload);
      setSavedMeetingId(result.meetingId);
      setSaveError(null);
      return { ok: true, meetingId: result.meetingId };
    } catch (e) {
      console.error("[recording] save meeting FAILED, queueing locally:", e);
      const msg = e instanceof Error ? e.message : String(e);
      setSaveError(msg);
      savePendingMeeting({
        id: meetingId,
        status: "pending",
        payload,
        createdAt: new Date().toISOString(),
        retryCount: 0,
        lastError: msg,
      });
      setPendingCount(getPendingCount());
      setError("Falha ao salvar — reunião armazenada localmente. Tentaremos enviar automaticamente.");
      return { ok: false, error: msg };
    } finally {
      setIsSaving(false);
    }
  }, [transcriptLines, speakerMap, duration, getSpeakerName]);

  useEffect(() => {
    loadDevices().catch((e) => {
      console.error("[recording] failed to load devices on mount:", e);
      setError("Falha ao carregar dispositivos de áudio");
    });

    // Update pending count on mount
    setPendingCount(getPendingCount());

    // Retry worker: attempts to send pending meetings every 30 seconds.
    // inFlightIds guarantees the same meeting doesn't fire 2x if an upload
    // takes longer than the retry interval (uploadTranscript already has
    // internal backoff up to ~7.5s + a reqwest timeout that can exceed 30s).
    // Without it, duplicate meetings were created in the backend when the second
    // tick fired the same payload while the first was still processing.
    const inFlightIds = inFlightIdsRef.current;
    const retryInterval = setInterval(() => {
      const pending = getPendingMeetings().filter(
        (m) =>
          m.status === "pending" &&
          m.retryCount < 10 &&
          !inFlightIds.has(m.id),
      );
      pending.forEach(async (meeting) => {
        inFlightIds.add(meeting.id);
        try {
          await uploadTranscript(meeting.payload);
          removePendingMeeting(meeting.id);
          setPendingCount(getPendingCount());
        } catch (e) {
          console.error("[recording] retry failed for queued meeting:", meeting.id, e);
          const nextRetry = meeting.retryCount + 1;
          savePendingMeeting({
            ...meeting,
            // After 10 attempts, mark permanent failure instead of leaving the
            // meeting a "pending" zombie re-filtered forever. Audit #91.
            status: nextRetry >= 10 ? "failed_permanently" : "pending",
            retryCount: nextRetry,
            lastError: e instanceof Error ? e.message : String(e),
          });
        } finally {
          inFlightIds.delete(meeting.id);
        }
      });
    }, 30000);

    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
      clearInterval(retryInterval);
    };
  }, [loadDevices]);

  const fullTranscript = buildTranscript(transcriptLines, getSpeakerName);

  return {
    isRecording,
    deviceName,
    sampleRate,
    transcriptLines,
    partialText: contextPartialText,
    fullTranscript,
    devices,
    selectedDevice,
    setSelectedDevice,
    duration,
    error,
    speakerMap,
    renameSpeaker,
    getSpeakerName,
    isSaving,
    savedMeetingId,
    saveError,
    pendingCount,
    startRecording,
    stopRecording,
    saveMeeting,
    loadDevices,
  };
}
