import { createContext, useContext, useState, useCallback, type ReactNode } from "react";

interface RecordingContextType {
  isRecording: boolean;
  deviceName: string;
  sampleRate: number;
  setRecordingState: (isRecording: boolean, deviceName: string, sampleRate: number) => void;
}

const RecordingContext = createContext<RecordingContextType | null>(null);

export function RecordingProvider({ children }: { children: ReactNode }) {
  const [isRecording, setIsRecording] = useState(false);
  const [deviceName, setDeviceName] = useState("");
  const [sampleRate, setSampleRate] = useState(0);

  const setRecordingState = useCallback((recording: boolean, device: string, rate: number) => {
    setIsRecording(recording);
    setDeviceName(device);
    setSampleRate(rate);
  }, []);

  return (
    <RecordingContext.Provider value={{ isRecording, deviceName, sampleRate, setRecordingState }}>
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
