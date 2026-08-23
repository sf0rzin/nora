/**
 * Upload of a finished recording to the NORA API.
 *
 * The module used to open with three more wrappers — listMeetings, getMeeting and
 * reprocessMeeting — that went through `apiClient.request` and therefore through the
 * `http_proxy` Tauri command. None of the three had a caller: the screens that would have
 * listed and reopened meetings live in the web app the main window loads, not here. They were
 * deleted together with api-client.ts, auth.ts, secrets.ts and types.ts, which existed only to
 * serve them (desktop audit #15).
 *
 * What survives is the one live path: uploadTranscript, called by use-recording.ts when the dock
 * stops a recording, and again by its retry worker for anything queued offline. It does NOT go
 * through the proxy — `invoke("upload_meeting")` posts the multipart body from Rust, which is
 * where the session cookie is read.
 */
import { invoke } from "@tauri-apps/api/core";

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
