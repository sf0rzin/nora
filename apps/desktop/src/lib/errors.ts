/**
 * Extracts a friendly message from an `unknown` error to show in the UI.
 * Never JSON.stringify the raw error (it leaked internal structure on screen).
 * Order: Error.message → object with a `message` string → fallback. Audit #63.
 */
export function toUserMessage(err: unknown, fallback: string): string {
  if (err instanceof Error && err.message.trim()) return err.message;
  if (err && typeof err === "object" && "message" in err) {
    const m = (err as { message: unknown }).message;
    if (typeof m === "string" && m.trim()) return m;
  }
  return fallback;
}
