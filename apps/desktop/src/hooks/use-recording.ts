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
    let mounted = true;
    const unlisten = listen<unknown>("transcript", (event) => {
      if (!mounted) return;
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
      if (!mounted) return;
      const s = event.payload;
      setRecordingState(s.isRecording, s.micDevice, s.sampleRate);
    });

    const checkStatus = async () => {
      try {
        const status = await invoke<RecordingStatus>("get_recording_status");
        if (mounted && status.isRecording) {
          setRecordingState(true, status.micDevice, status.sampleRate);
        }
      } catch (e) {
        console.error("[recording] failed to get status:", e);
      }
    };
    checkStatus();

    return () => {
      mounted = false;
      unlisten.then((fn) => fn()).catch(() => {});
      unlistenStatus.then((fn) => fn()).catch(() => {});
    };
    // setters do contexto e addTranscriptLine são estáveis (vêm do useRecordingContext)
    // eslint-disable-next-line react-hooks/exhaustive-deps
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

    // Idempotency key estável. Antes era gerada *apenas no fallback offline*,
    // então retries pós-erro de rede criavam outro UUID = reunião duplicada
    // no backend. Agora a chave vive na request desde o primeiro POST.
    const clientId = crypto.randomUUID();
    // Nome de arquivo seguro para FSs Windows: ':' e outros caracteres reservados
    // do ISO timestamp não podem aparecer em FAT/NTFS.
    const safeStamp = new Date()
      .toISOString()
      .replace(/[:.]/g, "-")
      .replace(/[^A-Za-z0-9_-]/g, "_");
    const safeTitle = (title || "reuniao").replace(/[^A-Za-z0-9 _-]/g, "_").trim() || "reuniao";
    const payload = {
      title: title || "Reunião sem título",
      startedAt,
      transcriptFormat: "TXT" as const,
      fileContent: transcript,
      fileName: `${safeTitle}_${safeStamp}.txt`,
      endedAt: new Date().toISOString(),
      participants: participants.length > 0 ? participants : undefined,
      clientId,
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
        id: clientId,
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

  // Set de uploads em voo: antes o interval disparava `uploadTranscript` em
  // paralelo a cada 30s para a mesma reunião, sem mutex. Em rede ruim isso
  // gerava N requests simultâneos do mesmo payload. Agora cada meeting só
  // permite UMA tentativa em voo por vez (dedup local + Idempotency-Key no payload).
  const inFlightRef = useRef<Set<string>>(new Set());

  useEffect(() => {
    loadDevices().catch((e) => {
      console.error("[recording] failed to load devices on mount:", e);
      setError("Falha ao carregar dispositivos de áudio");
    });

    setPendingCount(getPendingMeetings().filter((m) => m.status === "pending").length);

    const retryInterval = setInterval(() => {
      const inFlight = inFlightRef.current;
      const pending = getPendingMeetings().filter(
        (m) => m.status === "pending" && m.retryCount < 10 && !inFlight.has(m.id),
      );
      pending.forEach(async (meeting) => {
        inFlight.add(meeting.id);
        try {
          // Garante clientId presente no retry (versões antigas no localStorage podem
          // não ter — usamos o id do envelope como fallback).
          const payload = meeting.payload.clientId
            ? meeting.payload
            : { ...meeting.payload, clientId: meeting.id };
          await uploadTranscript(payload);
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
        } finally {
          inFlight.delete(meeting.id);
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
