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
 * Re-queues the NLP analysis of a meeting that failed (processingStatus FAILED).
 * Backend validates the state, goes back to PENDING and re-fires the async pipeline.
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

/** Extracts an HTTP status from the error: from the `.status` field or the "(NNN)"
 *  in the message (the Rust upload returns "Upload failed (404): ..."). */
function extractStatus(err: unknown): number | undefined {
  if (err && typeof err === "object") {
    const s = (err as { status?: number }).status;
    if (typeof s === "number") return s;
  }
  const msg =
    typeof err === "string" ? err : err instanceof Error ? err.message : "";
  const m = msg.match(/\((\d{3})\)/);
  return m ? Number(m[1]) : undefined;
}

function isTransient(err: unknown): boolean {
  if (err == null) return false;
  const status = extractStatus(err);
  // With a detectable status: only 5xx is worth retrying; 4xx (auth/validation) is permanent.
  // Before, ANY string/Error was "transient" → retried 4xx for nothing.
  if (typeof status === "number") return status >= 500 && status < 600;
  // No status (network failure/timeout) → transient.
  return typeof err === "string" || err instanceof Error;
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
