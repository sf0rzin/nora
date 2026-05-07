import { useState, useEffect, useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type { TranscriptLine, RecordingStatus } from "@/lib/recording-types";
import { uploadTranscript } from "@/lib/meetings";

interface UseRecordingOptions {
  azureSpeechKey?: string;
  azureRegion?: string;
  language?: string;
  captureSystemAudio?: boolean;
  systemAudioDevice?: string | null;
}

export function useRecording(options: UseRecordingOptions = {}) {
  const [isRecording, setIsRecording] = useState(false);
  const [deviceName, setDeviceName] = useState("");
  const [sampleRate, setSampleRate] = useState(0);
  const [transcriptLines, setTranscriptLines] = useState<TranscriptLine[]>([]);
  const [partialText, setPartialText] = useState("");
  const [speakerMap, setSpeakerMap] = useState<Record<string, string>>({});
  const [devices, setDevices] = useState<string[]>([]);
  const [selectedDevice, setSelectedDevice] = useState<string | null>(null);
  const [duration, setDuration] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const [isSaving, setIsSaving] = useState(false);
  const [savedMeetingId, setSavedMeetingId] = useState<string | null>(null);
  const startTimeRef = useRef<number | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

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
        setTranscriptLines((prev) => [
          ...prev,
          {
            id: crypto.randomUUID(),
            text: payload.text,
            isFinal: true,
            speaker: payload.speaker,
            speakerId: payload.speakerId,
            track: payload.track,
            timestamp: Date.now(),
          },
        ]);
        setPartialText("");
      } else {
        setPartialText(payload.text);
      }
    });

    const unlistenStatus = listen<RecordingStatus>("recording-status", (event) => {
      const s = event.payload;
      setIsRecording(s.is_recording);
      setDeviceName(s.device_name);
      setSampleRate(s.sample_rate);
    });

    return () => {
      unlisten.then((fn) => fn());
      unlistenStatus.then((fn) => fn());
    };
  }, []);

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
    setTranscriptLines([]);
    setPartialText("");
    setDuration(0);

    const req = {
      deviceName: selectedDevice,
      azureKey: options.azureSpeechKey,
      azureRegion: options.azureRegion,
      language: options.language || "pt-BR",
      captureSystemAudio: options.captureSystemAudio ?? false,
      systemAudioDevice: options.systemAudioDevice ?? null,
    };

    try {
      const result = await invoke<RecordingStatus>("start_recording", { request: req });

      setIsRecording(true);
      setDeviceName(result.device_name);
      setSampleRate(result.sample_rate);
      startTimeRef.current = Date.now();
      timerRef.current = setInterval(() => {
        if (startTimeRef.current) {
          setDuration(Math.floor((Date.now() - startTimeRef.current) / 1000));
        }
      }, 1000);
    } catch (e) {
      console.error("[recording] start_recording FAILED:", e);
      setError(String(e));
      setIsRecording(false);
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
    setIsRecording(false);
  }, []);

  const renameSpeaker = useCallback((speakerId: string, newName: string) => {
    setSpeakerMap((prev) => ({ ...prev, [speakerId]: newName }));
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
    setError(null);

    try {
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

      const result = await uploadTranscript({
        title: title || "Reunião sem título",
        startedAt,
        transcriptFormat: "text/plain",
        fileContent: transcript,
        fileName: `${title || "reuniao"}_${new Date().toISOString()}.txt`,
        endedAt: new Date().toISOString(),
        participants: participants.length > 0 ? participants : undefined,
      });

      setSavedMeetingId(result.meetingId);
    } catch (e) {
      console.error("[recording] save meeting FAILED:", e);
      setError(String(e));
    } finally {
      setIsSaving(false);
    }
  }, [transcriptLines, speakerMap, duration, getSpeakerName]);

  useEffect(() => {
    loadDevices().catch((e) => {
      console.error("[recording] failed to load devices on mount:", e);
      setError("Falha ao carregar dispositivos de áudio");
    });
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
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
    partialText,
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
    startRecording,
    stopRecording,
    saveMeeting,
    loadDevices,
  };
}
