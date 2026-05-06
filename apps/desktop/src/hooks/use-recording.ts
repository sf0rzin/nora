import { useState, useEffect, useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type { TranscriptLine, RecordingStatus } from "@/lib/recording-types";

interface UseRecordingOptions {
  azureSpeechKey?: string;
  azureRegion?: string;
  language?: string;
}

export function useRecording(options: UseRecordingOptions = {}) {
  const [isRecording, setIsRecording] = useState(false);
  const [deviceName, setDeviceName] = useState("");
  const [sampleRate, setSampleRate] = useState(0);
  const [transcriptLines, setTranscriptLines] = useState<TranscriptLine[]>([]);
  const [partialText, setPartialText] = useState("");
  const [devices, setDevices] = useState<string[]>([]);
  const [selectedDevice, setSelectedDevice] = useState<string | null>(null);
  const [duration, setDuration] = useState(0);
  const [error, setError] = useState<string | null>(null);
  const startTimeRef = useRef<number | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    const unlisten = listen<TranscriptLine>("transcript", (event) => {
      const payload = event.payload as unknown as {
        text: string;
        is_final: boolean;
        speaker: string | null;
      };

      console.log("[recording] transcript event:", payload);

      if (payload.is_final) {
        setTranscriptLines((prev) => [
          ...prev,
          {
            id: crypto.randomUUID(),
            text: payload.text,
            isFinal: true,
            speaker: payload.speaker,
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
      console.log("[recording] status event:", s);
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
      console.log("[recording] loading devices...");
      const list = await invoke<string[]>("list_audio_devices");
      console.log("[recording] devices:", list);
      setDevices(list);
    } catch (e) {
      console.error("[recording] failed to list devices:", e);
    }
  }, []);

  const startRecording = useCallback(async () => {
    console.log("[recording] startRecording called");
    setError(null);
    setTranscriptLines([]);
    setPartialText("");
    setDuration(0);

    const req = {
      deviceName: selectedDevice,
      azureSpeechKey: options.azureSpeechKey,
      azureRegion: options.azureRegion,
      language: options.language || "pt-BR",
    };
    console.log("[recording] invoke start_recording with:", JSON.stringify(req, null, 2));

    try {
      const result = await invoke<RecordingStatus>("start_recording", { request: req });
      console.log("[recording] start_recording result:", result);

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
    console.log("[recording] stopRecording called");
    try {
      await invoke("stop_recording");
      console.log("[recording] stop_recording ok");
    } catch (e) {
      console.error("[recording] stop_recording FAILED:", e);
      setError(String(e));
    }
    if (timerRef.current) clearInterval(timerRef.current);
    setIsRecording(false);
  }, []);

  useEffect(() => {
    loadDevices();
    return () => {
      if (timerRef.current) clearInterval(timerRef.current);
    };
  }, [loadDevices]);

  const fullTranscript = transcriptLines
    .map((l) => (l.speaker ? `[${l.speaker}] ` : "") + l.text)
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
    startRecording,
    stopRecording,
    loadDevices,
  };
}
