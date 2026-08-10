//! Common contract of the desktop speech-to-text backends.
//!
//! There are two, chosen at RUNTIME (not at build-time):
//!
//!   * `local` — whisper.cpp in-process, see `stt_local.rs`. Default since
//!               leaving Azure. Uses no network, no `/speech/token`, spawns no
//!               subprocess.
//!   * `azure` — Python sidecar + Azure Speech SDK, see `stt_sidecar.rs`. LEGACY,
//!               kept behind the Cargo feature `stt-azure` during the
//!               transition.
//!
//! THE CONTRACT WITH THE FRONT DOES NOT CHANGE. Both emit:
//!   * `transcript` — `TranscriptEvent` payload (camelCase), consumed by
//!     `use-live-transcript.tsx`, `use-recording.ts` and `overlay.tsx`;
//!   * `stt-error`  — opaque JSON object with `code` and `message`.
//!
//! No file under `apps/desktop/src/` had to change because of this swap.

use tokio::sync::mpsc;

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
    /// INVARIANT: monotonic non-decreasing per track. See the re-decode
    /// discussion in `stt_local.rs` — the trap of streaming mode is reprocessing
    /// the same window and emitting an offset smaller than the previous final's.
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
/// Tauri state, mixing backends if needed. Synchronous methods so as not to
/// drag in `async-trait` (the only async point is `start`, which lives in the
/// concrete factories).
pub trait SttBackend: Send {
    /// Id of that track's session. Used only in log/diagnostics.
    fn session_id(&self) -> &str;

    /// Channel through which `audio_capture` pushes PCM 16 kHz / mono / i16.
    ///
    /// The caller uses `try_send` and DROPS the sample when full: real time is
    /// worth more than completeness. See `commands.rs`.
    fn audio_tx(&self) -> mpsc::Sender<Vec<i16>>;

    /// Ends the session. Consumes the handle (`Box<Self>`) to prevent use after
    /// the stop. The backends do a final flush before dying, so there MAY be
    /// one last `transcript` event after this call — the Azure sidecar already
    /// behaved this way (`stop_continuous_recognition` flushes).
    fn stop(self: Box<Self>);
}

/// Which implementation is active.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum SttBackendKind {
    Local,
    Azure,
}

impl SttBackendKind {
    pub fn as_str(self) -> &'static str {
        match self {
            SttBackendKind::Local => "local",
            SttBackendKind::Azure => "azure",
        }
    }
}

/// Effective backend, memoized. Priority:
///
/// 1. env `NORA_STT_BACKEND` at runtime — only works when the app is launched
///    from a shell (dev). App opened from Finder/Explorer does NOT inherit the user env.
/// 2. `NORA_STT_BACKEND` injected at build-time by `build.rs` (CI/release).
/// 3. `plugins.nora.sttBackend` in `tauri.conf.json`.
/// 4. Default: `local` if the `stt-local` feature is compiled in, else `azure`.
///
/// Unknown value falls back to the default with a warning — never kills the app.
pub fn configured_backend() -> SttBackendKind {
    static KIND: std::sync::OnceLock<SttBackendKind> = std::sync::OnceLock::new();
    *KIND.get_or_init(|| {
        let raw = std::env::var("NORA_STT_BACKEND")
            .ok()
            .filter(|s| !s.is_empty())
            .or_else(|| option_env!("NORA_STT_BACKEND").map(str::to_string))
            .or_else(|| crate::nora_config_str("sttBackend"))
            .unwrap_or_default();

        let resolved = match raw.trim().to_ascii_lowercase().as_str() {
            "" => default_backend(),
            "local" | "whisper" => SttBackendKind::Local,
            "azure" | "sidecar" => SttBackendKind::Azure,
            other => {
                eprintln!(
                    "[stt] NORA_STT_BACKEND unknown: {:?} — using {}",
                    other,
                    default_backend().as_str()
                );
                default_backend()
            }
        };

        // Asked for a backend that was not compiled into this binary: degrade to
        // the one that exists instead of blowing up mid-recording.
        let available = match resolved {
            SttBackendKind::Local => cfg!(feature = "stt-local"),
            SttBackendKind::Azure => cfg!(feature = "stt-azure"),
        };
        if !available {
            eprintln!(
                "[stt] backend {:?} was not compiled into this binary — falling back to default",
                resolved.as_str()
            );
            return default_backend();
        }

        eprintln!("[stt] active backend: {}", resolved.as_str());
        resolved
    })
}

const fn default_backend() -> SttBackendKind {
    if cfg!(feature = "stt-local") {
        SttBackendKind::Local
    } else {
        SttBackendKind::Azure
    }
}

/// Credentials only the azure backend needs. `None` in local mode — that is why
/// `commands.rs` does not call `fetch_speech_token` when the backend is local.
#[allow(dead_code)]
pub struct AzureStartParams {
    pub region: String,
    pub auth_token: String,
    pub backend_url: String,
    pub access_token: String,
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

    #[test]
    fn backend_kind_round_trip() {
        assert_eq!(SttBackendKind::Local.as_str(), "local");
        assert_eq!(SttBackendKind::Azure.as_str(), "azure");
    }
}
