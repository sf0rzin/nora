export interface PendingMeeting {
  id: string;
  status: "pending" | "failed_permanently" | "completed";
  payload: {
    title: string;
    startedAt: string;
    endedAt?: string;
    transcriptFormat: string;
    fileContent: string;
    fileName: string;
    participants?: { displayName: string; email?: string }[];
    tags?: string[];
    /** Idempotency key — quando presente, o retry usa essa chave para evitar dup. */
    clientId?: string;
  };
  createdAt: string;
  retryCount: number;
  lastError?: string;
}

const STORAGE_KEY = "nora-pending-meetings";

export function getPendingMeetings(): PendingMeeting[] {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (!raw) return [];
    const parsed = JSON.parse(raw);
    if (Array.isArray(parsed)) return parsed;
    return [];
  } catch {
    return [];
  }
}

export function savePendingMeeting(meeting: PendingMeeting): void {
  const meetings = getPendingMeetings();
  const existing = meetings.findIndex((m) => m.id === meeting.id);
  if (existing >= 0) {
    meetings[existing] = meeting;
  } else {
    meetings.push(meeting);
  }
  localStorage.setItem(STORAGE_KEY, JSON.stringify(meetings));
}

export function removePendingMeeting(id: string): void {
  const meetings = getPendingMeetings().filter((m) => m.id !== id);
  localStorage.setItem(STORAGE_KEY, JSON.stringify(meetings));
}

export function getPendingCount(): number {
  return getPendingMeetings().filter((m) => m.status === "pending").length;
}

export function hasPendingMeetings(): boolean {
  return getPendingCount() > 0;
}