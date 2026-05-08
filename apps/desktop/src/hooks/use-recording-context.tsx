import { createContext, useContext, useState, useCallback, type ReactNode } from "react";
import type { TranscriptLine } from "@/lib/recording-types";

interface RecordingContextType {
  isRecording: boolean;
  deviceName: string;
  sampleRate: number;
  transcriptLines: TranscriptLine[];
  partialText: string;
  speakerMap: Record<string, string>;
  setRecordingState: (isRecording: boolean, deviceName: string, sampleRate: number) => void;
  addTranscriptLine: (line: TranscriptLine) => void;
  setPartialText: (text: string) => void;
  renameSpeaker: (speakerId: string, newName: string) => void;
  clearTranscript: () => void;
}

const RecordingContext = createContext<RecordingContextType | null>(null);

export function RecordingProvider({ children }: { children: ReactNode }) {
  const [isRecording, setIsRecording] = useState(false);
  const [deviceName, setDeviceName] = useState("");
  const [sampleRate, setSampleRate] = useState(0);
  const [transcriptLines, setTranscriptLines] = useState<TranscriptLine[]>([]);
  const [partialText, setPartialTextState] = useState("");
  const [speakerMap, setSpeakerMap] = useState<Record<string, string>>({});

  const setRecordingState = useCallback((recording: boolean, device: string, rate: number) => {
    setIsRecording(recording);
    setDeviceName(device);
    setSampleRate(rate);
  }, []);

  const addTranscriptLine = useCallback((line: TranscriptLine) => {
    setTranscriptLines((prev) => [...prev, line]);
  }, []);

  const setPartialText = useCallback((text: string) => {
    setPartialTextState(text);
  }, []);

  const renameSpeaker = useCallback((speakerId: string, newName: string) => {
    setSpeakerMap((prev) => ({ ...prev, [speakerId]: newName }));
  }, []);

  const clearTranscript = useCallback(() => {
    setTranscriptLines([]);
    setPartialTextState("");
    setSpeakerMap({});
  }, []);

  return (
    <RecordingContext.Provider value={{ 
      isRecording, 
      deviceName, 
      sampleRate, 
      transcriptLines,
      partialText,
      speakerMap,
      setRecordingState, 
      addTranscriptLine,
      setPartialText,
      renameSpeaker,
      clearTranscript
    }}>
      {children}
    </RecordingContext.Provider>
  );
}

export function useRecordingContext() {
  const context = useContext(RecordingContext);
  if (!context) {
    throw new Error("useRecordingContext must be used within RecordingProvider");
  }
  return context;
}
