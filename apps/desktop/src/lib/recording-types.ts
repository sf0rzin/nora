export interface TranscriptLine {
  id: string;
  text: string;
  isFinal: boolean;
  speaker: string | null;
  speakerId: string | null;
  timestamp: number;
}

export interface RecordingStatus {
  is_recording: boolean;
  device_name: string;
  sample_rate: number;
  channels: number;
}

export interface SpeakerMap {
  [speakerId: string]: string;
}
