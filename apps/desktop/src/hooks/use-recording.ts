import { useState, useEffect, useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type { RecordingStatus } from "@/lib/recording-types";
import { uploadTranscript } from "@/lib/meetings";
import { savePendingMeeting, removePendingMeeting, getPendingMeetings } from "@/lib/pending-meetings";
import { useRecordingContext } from "./use-recording-context";
import { useLiveHighlights, useLiveAnalysisTrigger } from "./use-live-highlights";

interface UseRecordingOptions {
  language?: string;
  captureSystemAudio?: boolean;
  systemAudioDevice?: string | null;
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
    const unlisten = listen<unknown>("transcript", (event) => {
      console.log("[transcript event]", event.payload);
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
    });

    const unlistenStatus = listen<RecordingStatus>("recording-status", (event) => {
      const s = event.payload;
      setRecordingState(s.isRecording, s.micDevice, s.sampleRate);
    });

    const checkStatus = async () => {
      try {
        const status = await invoke<RecordingStatus>("get_recording_status");
        console.log("[recording] status check:", status);
        if (status.isRecording) {
          setRecordingState(true, status.micDevice, status.sampleRate);
          console.log("[recording] restored recording state");
        }
      } catch (e) {
        console.error("[recording] failed to get status:", e);
      }
    };
    checkStatus();

    return () => {
      unlisten.then((fn) => fn());
      unlistenStatus.then((fn) => fn());
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

  const startRecording = useCallback(async () => {
    setError(null);
    clearTranscript();
    setDuration(0);

    const req = {
      deviceName: selectedDevice,
      language: options.language || "pt-BR",
      captureSystemAudio: options.captureSystemAudio ?? false,
      systemAudioDevice: options.systemAudioDevice ?? null,
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
    // Se for do microfone, sempre mostrar "Eu"
    if (track === "mic") return "Eu";
    if (!speakerId) return speaker;
    // Se o speakerId for "UNKNOWN", mostrar como "Desconhecido"
    if (speakerId === "UNKNOWN") return "Desconhecido";
    // Retornar o nome mapeado, ou o speakerId original
    return speakerMap[speakerId] || speakerId;
  }, [speakerMap]);

  const saveMeeting = useCallback(async (title: string) => {
    if (transcriptLines.length === 0) {
      setError("Nenhuma transcrição para salvar");
      return;
    }

    setIsSaving(true);
    setSaveError(null);
    setError(null);

    const transcript = transcriptLines
      .map((l) => {
        const speakerName = getSpeakerName(l.speakerId, l.speaker, l.track);
        return (speakerName ? `[${speakerName}] ` : "") + l.text;
      })
      .join("\n");

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
      setPendingCount(getPendingMeetings().filter((m) => m.status === "pending").length);
      setError("Falha ao salvar — reunião armazenada localmente. Tentaremos enviar automaticamente.");
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
    setPendingCount(getPendingMeetings().filter((m) => m.status === "pending").length);

    // Retry worker: attempts to send pending meetings every 30 seconds
    const retryInterval = setInterval(() => {
      const pending = getPendingMeetings().filter((m) => m.status === "pending" && m.retryCount < 10);
      pending.forEach(async (meeting) => {
        try {
          await uploadTranscript(meeting.payload);
          removePendingMeeting(meeting.id);
          setPendingCount(getPendingMeetings().filter((m) => m.status === "pending").length);
          console.log("[recording] queued meeting sent successfully:", meeting.id);
        } catch (e) {
          console.error("[recording] retry failed for queued meeting:", meeting.id, e);
          savePendingMeeting({
            ...meeting,
            retryCount: meeting.retryCount + 1,
            lastError: e instanceof Error ? e.message : String(e),
          });
        }
      });
    }, 30000);

    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
      clearInterval(retryInterval);
    };
  }, [loadDevices]);

  const fullTranscript = transcriptLines
    .map((l) => {
      const speakerName = getSpeakerName(l.speakerId, l.speaker, l.track);
      return (speakerName ? `[${speakerName}] ` : "") + l.text;
    })
    .join("\n");

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
