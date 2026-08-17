//! Common contract of the desktop speech-to-text backend.
//!
//! There is one — `stt_cloud.rs`, streaming to the provider's realtime API over a
//! WebSocket with a short-lived credential minted by the NORA backend (ADR 0039,
//! ADR 0045). The in-process whisper.cpp engine that used to sit here went with
//! that migration, and the Azure Speech sidecar before it.
//!
//! ## The selection machinery is gone, and that was the point of keeping it
//!
//! `SttBackendKind`, `configured_backend()` and `NORA_STT_BACKEND` existed so that
//! cloud transcription could arrive as a second backend without disturbing
//! `commands.rs`. It has arrived, the local one has left, and a one-variant enum
//! resolved from three configuration sources is a mechanism that decides nothing.
//! What survives is the part that was actually load-bearing: the `SttBackend` trait
//! below, which is why `commands.rs` can hold one handle per track without knowing
//! what is behind them.
//!
//! THE CONTRACT WITH THE FRONT DOES NOT CHANGE. It emits:
//!   * `transcript` — `TranscriptEvent` payload (camelCase), consumed by
//!     `use-live-transcript.tsx`, `use-recording.ts` and `overlay.tsx`;
//!   * `stt-error`  — opaque JSON object with `code` and `message`.
//!
//! No file under `apps/desktop/src/` had to change because of this swap either.

use tokio::sync::mpsc;

/// Sample rate, in Hz, that every capture path resamples to and every backend receives.
///
/// ONE number, in one place, because it used to be the literal `16000` written out at seven
/// separate call sites in `audio_capture.rs` and `system_audio.rs` — a shape in which changing the
/// pipeline's target means finding all seven and getting none of them wrong.
///
/// It has to agree with `nora.stt.openai.sample-rate` on the backend, which is what the session is
/// minted with. A disagreement does not fail anywhere: it plays the audio to the provider at the
/// wrong speed, and the transcript comes back as confident nonsense. `stt_cloud` therefore warns on
/// the mismatch and declares THIS number to the provider on connect, so what we send and what we
/// say we send can never diverge.
///
/// 24 kHz, not the 16 kHz the local Whisper engine wanted: that is the rate the provider's realtime
/// transcription session takes. Retargeting the capture is one resample from the device's rate,
/// where keeping 16 kHz would have meant two — device to 16 k, then 16 k up to 24 k — and the
/// second one would invent nothing while throwing away the 8-12 kHz band on the way.
pub const TARGET_SAMPLE_RATE: u32 = 24_000;

/// Event emitted to the front on every partial/final of transcription.
///
/// Serialized in camelCase: `sessionId`, `speakerId`, `isFinal`, `offsetMs`,
/// `durationMs`. There is NO `speaker` field — the front reads `payload.speaker`
/// and always gets `undefined`; this is pre-existing and is documented in
/// `SYSTEM_SPEAKER_ID` below because it has a real consequence.
#[derive(Debug, serde::Serialize, Clone)]
#[serde(rename_all = "camelCase")]
#[allow(dead_code)]
pub struct TranscriptEvent {
    pub session_id: String,
    pub track: String,
    pub speaker_id: Option<String>,
    pub text: String,
    pub is_final: bool,
    /// ABSOLUTE offset in ms since the start of that track's recording.
    ///
    /// INVARIANT: monotonic non-decreasing per track. The deleted local engine
    /// broke it by re-decoding a sliding window; the cloud client breaks it by
    /// deriving the position from audio it managed to SEND rather than audio it
    /// received. Both end the same way — the UI sorts and groups by time, so a
    /// regressed offset scrambles the conversation. See the clock discussion in
    /// `stt_cloud.rs`.
    pub offset_ms: u64,
    pub duration_ms: Option<u64>,
    pub confidence: Option<f32>,
}

/// `speaker_id` of the `mic` track.
///
/// `None` on purpose, and that is correct: the front treats `track === "mic"`
/// as the local user BEFORE looking at speaker_id — `getSpeakerName()`
/// returns "Eu" (use-recording.ts:228) and `getDisplayName()` returns "Voce"
/// (overlay.tsx:60) — and `detectedSpeakers` skips the mic track explicitly
/// (overlay.tsx:1115). Emitting an id here would only pollute the SpeakerMap
/// with a useless renameable entry.
pub const MIC_SPEAKER_ID: Option<&str> = None;

/// `speaker_id` of the `system` track (remote participants of the call).
///
/// ================== DELIBERATE DEVIATION FROM THE SPEC ==================
///
/// The product decision is "attribution per track, `speaker_id = None` inside
/// the track". Emitting literally `None` HERE silently breaks two consumers
/// that already exist in the front — the opposite of what the decision wanted
/// to preserve:
///
///   1. `overlay.tsx:1111-1125` builds the renameable list with
///      `const id = l.speakerId || l.speaker; if (!id) continue;`
///      Rust never emits the `speaker` field (it does not exist in
///      `TranscriptEvent`), so with a null `speakerId` `id` is `undefined` and
///      the line is SKIPPED: the rename-speaker UI stays permanently empty.
///
///   2. `use-recording.ts:255` derives `participants` from
///      `Object.entries(speakerMap)`, and `speakerMap` only gains an entry when
///      the user renames someone in the UI of item 1. With no line to rename,
///      `participants` is always `undefined` on the meeting upload.
///
///   3. `buildTranscript` (use-recording.ts:18) prefixes `[nome] ` only when
///      `getSpeakerName` returns something; with a null speakerId and track !=
///      mic it returns `speaker` (undefined) and the TXT comes out with NO
///      prefix at all on the remote utterances.
///
/// A STABLE id per track delivers exactly what the product decision asked for
/// — zero online diarization, zero label churn, one label per track — and
/// keeps the three consumers working without touching a line of the front.
///
/// The value shows up raw in the UI until the user renames it
/// (`speakerMap[id] || id`), which is why it is a readable pt-BR string and not a UUID.
///
/// If one day the front starts treating `speakerId === null && track === "system"`
/// as an implicit speaker, switching this back to `None` becomes possible again.
pub const SYSTEM_SPEAKER_ID: &str = "Participantes";

/// Resolves the `speaker_id` to emit for a track. See the constants above.
pub fn speaker_id_for_track(track: &str) -> Option<String> {
    match track {
        "system" => Some(SYSTEM_SPEAKER_ID.to_string()),
        _ => MIC_SPEAKER_ID.map(str::to_string),
    }
}

/// Live handle of a transcription session of ONE track.
///
/// Object-safe on purpose: `commands.rs` keeps `Vec<Box<dyn SttBackend>>` in the
/// Tauri state, one entry per track. Synchronous methods so as not to drag in
/// `async-trait` (the only async point is `start`, which lives in the concrete
/// factory).
pub trait SttBackend: Send {
    /// Id of that track's session. Used only in log/diagnostics.
    fn session_id(&self) -> &str;

    /// Channel through which `audio_capture` pushes mono i16 PCM at
    /// [`TARGET_SAMPLE_RATE`].
    ///
    /// The caller uses `try_send` and DROPS the sample when full: real time is
    /// worth more than completeness. See `commands.rs`.
    fn audio_tx(&self) -> mpsc::Sender<Vec<i16>>;

    /// Ends the session. Consumes the handle (`Box<Self>`) to prevent use after
    /// the stop. The backend does a final flush before dying, so there MAY be
    /// one last `transcript` event after this call.
    fn stop(self: Box<Self>);
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn mic_does_not_generate_speaker_map_entry() {
        assert_eq!(speaker_id_for_track("mic"), None);
    }

    #[test]
    fn system_generates_stable_nonempty_id() {
        // Stability is the point: two events from the same track have to land in
        // the SAME SpeakerMap bucket, else the overlay lists N phantom speakers.
        let a = speaker_id_for_track("system");
        let b = speaker_id_for_track("system");
        assert_eq!(a, b);
        assert_eq!(a.as_deref(), Some(SYSTEM_SPEAKER_ID));
        assert!(!SYSTEM_SPEAKER_ID.is_empty(), "empty id is falsy in JS and disappears from detectedSpeakers");
    }

    /// The number the whole pipeline agrees on. It is asserted rather than merely declared
    /// because a change here is silent everywhere else: the capture path would resample to the
    /// new rate, `stt_cloud` would declare the new rate to the provider, everything would keep
    /// working — and the provider would be receiving audio at a rate the backend was not
    /// configured to mint sessions for. Changing this line means changing
    /// `nora.stt.openai.sample-rate` in the same PR.
    #[test]
    fn the_pipeline_targets_the_rate_the_provider_takes() {
        assert_eq!(TARGET_SAMPLE_RATE, 24_000);
    }
}
