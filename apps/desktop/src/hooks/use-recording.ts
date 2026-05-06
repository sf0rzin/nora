import { useState, useEffect, useCallback, useRef } from "react";
import { invoke } from "@tauri-apps/api/core";
import { listen } from "@tauri-apps/api/event";
import type { TranscriptLine, RecordingStatus } from "@/lib/recording-types";

interface UseRecordingOptions {
  azureSpeechKey?: string;
  azureEndpoint?: string;
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
  const startTimeRef = useRef<number | null>(null);
  const timerRef = useRef<ReturnType<typeof setInterval> | null>(null);

  useEffect(() => {
    const unlisten = listen<TranscriptLine>("transcript", (event) => {
      const payload = event.payload as unknown as {
        text: string;
        is_final: boolean;
        speaker: string | null;
      };

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
      console.error("Failed to list devices:", e);
    }
  }, []);

  const startRecording = useCallback(async () => {
    setTranscriptLines([]);
    setPartialText("");
    setDuration(0);
    startTimeRef.current = Date.now();
    timerRef.current = setInterval(() => {
      if (startTimeRef.current) {
        setDuration(Math.floor((Date.now() - startTimeRef.current) / 1000));
      }
    }, 1000);

    try {
      await invoke("start_recording", {
        request: {
          deviceName: selectedDevice,
          azureSpeechKey: options.azureSpeechKey,
          azureEndpoint: options.azureEndpoint,
          language: options.language || "pt-BR",
        },
      });
    } catch (e) {
      console.error("Failed to start recording:", e);
      setIsRecording(false);
      if (timerRef.current) clearInterval(timerRef.current);
    }
  }, [selectedDevice, options]);

  const stopRecording = useCallback(async () => {
    try {
      await invoke("stop_recording");
    } catch (e) {
      console.error("Failed to stop recording:", e);
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
    startRecording,
    stopRecording,
    loadDevices,
  };
}
