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

export interface UploadTranscriptRequest {
  title: string;
  startedAt: string;
  transcriptFormat: string;
  fileContent: string;
  fileName: string;
  endedAt?: string;
  tags?: string[];
  participants?: { displayName: string; email?: string }[];
}

export async function uploadTranscript(
  data: UploadTranscriptRequest
): Promise<{ meetingId: string }> {
  return apiClient.request<{ meetingId: string }>("/meetings/upload", {
    method: "POST",
    body: data,
  });
}
