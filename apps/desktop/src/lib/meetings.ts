import { apiClient } from "./api-client";
import type { MeetingsPage, MeetingDetail } from "./types";

export async function listMeetings(params?: {
  page?: number;
  size?: number;
  q?: string;
  tag?: string;
}): Promise<MeetingsPage> {
  const searchParams = new URLSearchParams();
  if (params?.page !== undefined) searchParams.set("page", String(params.page));
  if (params?.size !== undefined) searchParams.set("size", String(params.size));
  if (params?.q) searchParams.set("q", params.q);
  if (params?.tag) searchParams.set("tag", params.tag);

  const qs = searchParams.toString();
  return apiClient.request<MeetingsPage>(`/meetings${qs ? `?${qs}` : ""}`);
}

export async function getMeeting(meetingId: string): Promise<MeetingDetail> {
  return apiClient.request<MeetingDetail>(`/meetings/${meetingId}`);
}

export async function uploadTranscript(
  _title: string,
  _startedAt: string,
  _transcriptFormat: string,
  _fileContent: Blob,
  _fileName: string,
  _extra?: {
    endedAt?: string;
    tags?: string[];
    participants?: { displayName: string; email?: string }[];
  },
): Promise<unknown> {
  throw new Error("Upload via desktop not yet implemented — use the web app");
}
