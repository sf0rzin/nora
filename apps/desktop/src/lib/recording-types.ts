export interface TranscriptLine {
  id: string;
  text: string;
  isFinal: boolean;
  speaker: string | null;
  speakerId: string | null;
  track: string;
  timestamp: number;
}

export interface RecordingStatus {
  isRecording: boolean;
  micDevice: string;
  systemAudioDevice: string | null;
  sampleRate: number;
}

export interface SpeakerMap {
  [speakerId: string]: string;
}
