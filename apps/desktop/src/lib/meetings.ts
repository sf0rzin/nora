import { apiClient } from "./api-client";
import { invoke } from "@tauri-apps/api/core";
import type { MeetingsPage, MeetingDetail } from "./types";

export async function listMeetings(params?: {
  page?: number;
  size?: number;
  search?: string;
  status?: string;
  tag?: string;
}): Promise<MeetingsPage> {
  const searchParams = new URLSearchParams();
  if (params?.page !== undefined) searchParams.set("page", String(params.page));
  if (params?.size !== undefined) searchParams.set("size", String(params.size));
  if (params?.search) searchParams.set("search", params.search);
  if (params?.status) searchParams.set("status", params.status);
  if (params?.tag) searchParams.set("tag", params.tag);

  const qs = searchParams.toString();
  return apiClient.request<MeetingsPage>(`/meetings${qs ? `?${qs}` : ""}`);
}

export async function getMeeting(meetingId: string): Promise<MeetingDetail> {
  return apiClient.request<MeetingDetail>(`/meetings/${meetingId}`);
}

/**
 * Reenfileira a análise NLP de uma reunião que falhou (processingStatus FAILED).
 * Backend valida o estado, volta pra PENDING e re-dispara o pipeline async.
 */
export async function reprocessMeeting(
  meetingId: string,
): Promise<{ processingStatus: string }> {
  return apiClient.request<{ processingStatus: string }>(
    `/meetings/${meetingId}/reprocess`,
    { method: "POST" },
  );
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

export interface UploadTranscriptOptions {
  /** Maximum retry attempts on transient failures (network errors / 5xx). Default: 3. */
  maxRetries?: number;
  /** Initial backoff delay in ms. Default: 500. Doubles each attempt. */
  initialBackoffMs?: number;
  /** Optional callback fired before each retry: (attempt, delayMs, error). */
  onRetry?: (attempt: number, delayMs: number, error: unknown) => void;
}

function isTransient(err: unknown): boolean {
  if (err == null) return false;
  if (typeof err === "string") return true;
  if (err instanceof Error) return true;
  if (typeof err === "object") {
    const status = (err as { status?: number }).status;
    if (typeof status === "number") return status >= 500 && status < 600;
  }
  return false;
}

export async function uploadTranscript(
  data: UploadTranscriptRequest,
  options: UploadTranscriptOptions = {}
): Promise<{ meetingId: string }> {
  const maxRetries = options.maxRetries ?? 3;
  const initialBackoffMs = options.initialBackoffMs ?? 500;

  let lastError: unknown;
  for (let attempt = 0; attempt <= maxRetries; attempt++) {
    try {
      const response = await invoke<{ meetingId: string; processingStatus: string }>(
        "upload_meeting",
        {
          request: {
            title: data.title,
            startedAt: data.startedAt,
            endedAt: data.endedAt,
            language: "pt-BR",
            transcriptFormat: data.transcriptFormat,
            tags: data.tags ?? [],
            participants: data.participants ?? [],
            fileContent: data.fileContent,
            fileName: data.fileName,
          },
        }
      );
      return { meetingId: response.meetingId };
    } catch (err) {
      lastError = err;
      if (attempt === maxRetries || !isTransient(err)) {
        throw err;
      }
      const delayMs = initialBackoffMs * Math.pow(2, attempt);
      options.onRetry?.(attempt + 1, delayMs, err);
      console.warn(
        `[meetings] uploadTranscript attempt ${attempt + 1} failed, retrying in ${delayMs}ms`,
        err
      );
      await new Promise((resolve) => setTimeout(resolve, delayMs));
    }
  }
  throw lastError;
}
