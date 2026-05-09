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

export interface UploadTranscriptOptions {
  /** Maximum retry attempts on transient failures (network errors / 5xx). Default: 3. */
  maxRetries?: number;
  /** Initial backoff delay in ms. Default: 500. Doubles each attempt. */
  initialBackoffMs?: number;
  /** Optional callback fired before each retry: (attempt, delayMs, error). */
  onRetry?: (attempt: number, delayMs: number, error: unknown) => void;
}

/**
 * Determines if a thrown error from apiClient is transient and worth retrying.
 * Retries: invoke/network failures (string errors from Tauri), and 5xx HTTP responses.
 * Does NOT retry: 4xx (auth, validation), 401 (already handled by apiClient).
 */
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
      return await apiClient.request<{ meetingId: string }>("/meetings/upload", {
        method: "POST",
        body: data,
      });
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
