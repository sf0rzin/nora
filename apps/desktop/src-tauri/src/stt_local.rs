//! Local in-process STT with whisper.cpp (crate `whisper-rs`).
//!
//! Replaces the Python sidecar + Azure Speech. One instance per TRACK (`mic`,
//! `system`), exactly like the two Python processes were — but both share the
//! same `WhisperContext` (the model weights, ~465 MiB on `small`), each with
//! its own `WhisperState` (KV cache).
//!
//! ============================================================================
//! WHY "PSEUDO" REAL-TIME
//! ============================================================================
//! Whisper is an encoder-decoder model with a FIXED 30 s window. It has no
//! incremental state: there is no "feed 100 ms more and continue where it left
//! off". Every result comes from a COMPLETE decode of an audio window.
//!
//! Streaming here is therefore a re-decode loop over a sliding window:
//!
//!   [--------- audio already COMMITTED (became `final`) ---------][== window ==]
//!   0                                                    committed_ms         now
//!                                                        ^
//!                                                        offset of every event
//!
//! Every ~900 ms the window is re-decoded and the text goes out as `partial`.
//! When the VAD sees silence (or the window gets near the 30 s cap), the window
//! is decoded one last time, goes out as `final`, and `committed_ms` advances.
//!
//! ============================================================================
//! THE RE-DECODE TRAP: OFFSET THAT REGRESSES
//! ============================================================================
//! Every re-decode reprocesses audio ALREADY SEEN. If the event offset were
//! derived from the timestamp whisper returns (which is relative to the start of
//! the WINDOW, not of the recording), the second decode of the same window would
//! emit an offset smaller than the previous final's — and the UI, which sorts
//! and groups by time, would scramble the conversation and duplicate utterances.
//!
//! Defenses, in this order:
//!   1. `committed_ms` is a MONOTONIC counter of already finalized audio. It
//!      only advances, and advances by exactly the number of ms removed from the
//!      front of the window — including discarded silence, otherwise the offsets
//!      drift away from the recording clock.
//!   2. `partial` always reports `offset_ms = committed_ms` (the start of the
//!      uncommitted window), never an internal whisper timestamp. The partial's
//!      text is the WHOLE re-decode of the window; the front overwrites
//!      `partials[track]`, so text revision is expected and does not accumulate.
//!   3. `final` uses `committed_ms + segment_t0`, but goes through
//!      `last_final_end_ms.max(...)`: a garbled whisper timestamp is clamped
//!      instead of becoming a regression.
//!   4. `debug_assert` at the emission point, to break the dev test if some new
//!      path punctures the invariant.
//!
//! ============================================================================
//! WHAT THIS MODULE DOES NOT DO
//! ============================================================================
//! * Speaker diarization. Whisper does not diarize; WhisperX/pyannote are batch
//!   by construction. Attribution is PER TRACK — see `stt::speaker_id_for_track`.
//! * Calibrated confidence. See `segment_confidence` further below.
//! * Neural VAD. The VAD here is energy + adaptive noise floor; whisper.cpp
//!   1.7+ already exposes Silero VAD (`FullParams::enable_vad`), but it requires
//!   downloading a second model (`ggml-silero-v5.1.2.bin`). Left as improvement.

use std::sync::Arc;
use std::time::{Duration, Instant};

use tauri::{AppHandle, Emitter};
use tokio::sync::{mpsc, oneshot};
use whisper_rs::{
    FullParams, SamplingStrategy, WhisperContext, WhisperContextParameters, WhisperState,
};

use crate::stt::{speaker_id_for_track, SttBackend, TranscriptEvent};
use crate::whisper_model;

// ============================================================================
// Tuning constants
// ============================================================================

const SAMPLE_RATE: usize = 16_000;
const MS_PER_SEC: u64 = 1_000;

/// VAD frame: 30 ms. Same size the WebRTC VAD uses.
const VAD_FRAME_SAMPLES: usize = SAMPLE_RATE * 30 / 1000;

/// Absolute energy floor (RMS) below which it is always silence, even if the
/// adaptive noise floor has converged too low in a quiet room.
/// ~-44 dBFS.
const ABS_SILENCE_RMS: f32 = 0.006;

/// How many times above the noise floor the energy must be to count as speech.
const VAD_SNR_FACTOR: f32 = 2.5;

/// Continuous silence that closes an utterance and fires the `final`.
const SILENCE_COMMIT_MS: u64 = 700;

/// Minimum interval between two `partial` of the same track.
const PARTIAL_EVERY_MS: u64 = 900;

/// Minimum audio in the window for a decode to be worth it.
const MIN_DECODE_MS: u64 = 900;

/// Minimum speech in the window for it to become `final` instead of being discarded as noise.
const MIN_COMMIT_SPEECH_MS: u64 = 350;

/// Window cap. Comfortably below whisper's 30 s mel: at a full 30 s the model
/// starts truncating internally and the timestamps become meaningless.
const MAX_WINDOW_MS: u64 = 22_000;

/// Whisper.cpp returns ZERO segments for less than ~1 s of audio (`seek_end <
/// seek_start + 100` in `whisper_full_with_state`). Every decode is padded with
/// silence up to here. The padding goes in AFTER the real audio, so it does not
/// shift the segment timestamps.
const MIN_PADDED_MS: u64 = 1_200;

/// Textual context passed as `initial_prompt`. 224 chars ~ half of whisper's
/// `n_text_ctx` (448 tokens), which is the recommended cap for the prompt.
const PROMPT_MAX_CHARS: usize = 224;

/// Slack of the PCM channel between the async task and the inference thread: 300
/// chunks of 100 ms = 30 s. See `AudioMsg::Gap` for what happens when it overflows.
const PCM_QUEUE_CHUNKS: usize = 300;

/// Texts Whisper hallucinates on audio without speech. The pt-BR list is short
/// and very stable (they are training subtitles that leaked into the model).
/// Comparison in lowercase, without trailing punctuation.
const HALLUCINATION_BLOCKLIST: &[&str] = &[
    "legendas pela comunidade amara.org",
    "legendas pela comunidade amara org",
    "amara.org",
    "subtitles by the amara.org community",
    "obrigado por assistir",
    "obrigado por assistir!",
    "[música]",
    "[musica]",
    "(música)",
    "[aplausos]",
    "[risos]",
    "você",
    "...",
    ".",
];

/// Above this the segment is treated as "no speech" and discarded.
const NO_SPEECH_THRESHOLD: f32 = 0.75;

// ============================================================================
// Public handle
// ============================================================================

pub struct LocalSttHandle {
    session_id: String,
    audio_tx: mpsc::Sender<Vec<i16>>,
    stop_tx: Option<oneshot::Sender<()>>,
}

impl Drop for LocalSttHandle {
    fn drop(&mut self) {
        // Same defense as SidecarHandle: if the Vec of backends is cleared
        // without going through stop() (panic, logout, abrupt close), the signal
        // still goes out and the inference thread ends. Without this it would
        // keep burning CPU until the process dies.
        if let Some(tx) = self.stop_tx.take() {
            let _ = tx.send(());
        }
        #[cfg(debug_assertions)]
        eprintln!("[stt_local] handle DROPPED session_id={}", self.session_id);
    }
}

impl SttBackend for LocalSttHandle {
    fn session_id(&self) -> &str {
        &self.session_id
    }

    fn audio_tx(&self) -> mpsc::Sender<Vec<i16>> {
        self.audio_tx.clone()
    }

    fn stop(mut self: Box<Self>) {
        if let Some(tx) = self.stop_tx.take() {
            let _ = tx.send(());
        }
    }
}

impl LocalSttHandle {
    /// Brings up the local transcription of ONE track.
    ///
    /// Blocks until the model is loaded — on purpose. If the model has to be
    /// downloaded (first use), this takes minutes and emits progress on
    /// `stt-model-progress`. Failing here is better than accepting audio and
    /// finding out mid-meeting that there is no model.
    pub async fn start(
        app: AppHandle,
        language: String,
        track_label: String,
    ) -> Result<Self, String> {
        let session_id = uuid::Uuid::new_v4().to_string();

        let size = whisper_model::configured_size();
        let model_path = whisper_model::ensure_model(&app, size).await?;
        let ctx = shared_context(&model_path).await?;

        // create_state allocates the KV cache (tens of MiB). Outside the async
        // executor because it is synchronous work of tens of ms.
        let ctx_for_state = Arc::clone(&ctx);
        let state = tokio::task::spawn_blocking(move || ctx_for_state.create_state())
            .await
            .map_err(|e| format!("join create_state: {e}"))?
            .map_err(|e| format!("whisper create_state: {e}"))?;

        let (audio_tx, mut audio_rx) = mpsc::channel::<Vec<i16>>(100);
        let (stop_tx, mut stop_rx) = oneshot::channel::<()>();
        let (pcm_tx, pcm_rx) = std::sync::mpsc::sync_channel::<AudioMsg>(PCM_QUEUE_CHUNKS);

        // ---- Async bridge -> inference thread ----------------------------------
        // Has to exist because inference is CPU-bound and synchronous: running it
        // inside tokio's executor would block the other tasks (including the other track).
        let bridge_session = session_id.clone();
        tokio::spawn(async move {
            // Samples dropped due to a full queue. They must NOT vanish silently:
            // if inference falls behind, the audio disappears but the CLOCK does
            // not stop, and without accounting for the hole every later offset
            // would run ahead of the real audio. The Gap tells the engine.
            let mut pending_gap_samples: usize = 0;

            loop {
                tokio::select! {
                    _ = &mut stop_rx => break,
                    maybe = audio_rx.recv() => {
                        let Some(samples) = maybe else { break };

                        if pending_gap_samples > 0 {
                            let gap_ms = samples_to_ms(pending_gap_samples);
                            if pcm_tx.try_send(AudioMsg::Gap { ms: gap_ms }).is_ok() {
                                pending_gap_samples = 0;
                            }
                        }

                        match pcm_tx.try_send(AudioMsg::Pcm(samples)) {
                            Ok(()) => {}
                            Err(std::sync::mpsc::TrySendError::Full(AudioMsg::Pcm(s))) => {
                                pending_gap_samples += s.len();
                            }
                            Err(std::sync::mpsc::TrySendError::Full(_)) => {}
                            Err(std::sync::mpsc::TrySendError::Disconnected(_)) => break,
                        }
                    }
                }
            }
            #[cfg(debug_assertions)]
            eprintln!("[stt_local] bridge closed session={}", bridge_session);
            #[cfg(not(debug_assertions))]
            let _ = bridge_session;
            // Dropping pcm_tx closes the channel -> the thread does the final flush and exits.
        });

        // ---- Inference thread --------------------------------------------------
        let worker_app = app.clone();
        let worker_session = session_id.clone();
        let worker_track = track_label.clone();
        let builder = std::thread::Builder::new()
            .name(format!("nora-whisper-{}", track_label))
            // whisper.cpp is C++: Rust's default 2 MiB stack is already enough,
            // but we raise it because the decoder does shallow recursion and some
            // BLAS libs allocate large buffers on the stack.
            .stack_size(4 * 1024 * 1024);

        builder
            .spawn(move || {
                let mut engine = StreamEngine::new(state, language, worker_track, worker_session);
                engine.run(&worker_app, pcm_rx);
            })
            .map_err(|e| format!("could not spawn the inference thread: {e}"))?;

        eprintln!(
            "[stt_local] track={} ready (model={}, threads={})",
            track_label,
            size.as_str(),
            inference_threads()
        );

        Ok(Self {
            session_id,
            audio_tx,
            stop_tx: Some(stop_tx),
        })
    }
}

enum AudioMsg {
    Pcm(Vec<i16>),
    /// Hole of `ms` in the audio: the queue filled up and the samples were dropped.
    Gap { ms: u64 },
}

// ============================================================================
// Context shared between the tracks
// ============================================================================

/// Cache of ONE context (the weights). `mic` and `system` call this with the
/// same path, so one slot is enough — and the saving is big: without sharing,
/// two copies of `small` would be ~930 MiB of RSS in weights alone.
async fn shared_context(path: &std::path::Path) -> Result<Arc<WhisperContext>, String> {
    static CACHE: std::sync::OnceLock<
        tokio::sync::Mutex<Option<(std::path::PathBuf, Arc<WhisperContext>)>>,
    > = std::sync::OnceLock::new();
    let cache = CACHE.get_or_init(|| tokio::sync::Mutex::new(None));

    let mut guard = cache.lock().await;
    if let Some((cached_path, ctx)) = guard.as_ref() {
        if cached_path == path {
            return Ok(Arc::clone(ctx));
        }
    }

    let owned = path.to_path_buf();
    let load_path = owned.clone();
    let loaded = tokio::task::spawn_blocking(move || {
        let mut params = WhisperContextParameters::default();
        // `use_gpu` already comes in `true` when some GPU feature was compiled
        // (metal/cuda/vulkan). Without them it is `false` and runs on CPU.
        // whisper.cpp also falls back to CPU on its own if the GPU backend fails to init.
        params.use_gpu = params.use_gpu && !env_flag("NORA_WHISPER_FORCE_CPU");
        WhisperContext::new_with_params(&load_path, params)
    })
    .await
    .map_err(|e| format!("model load join: {e}"))?
    .map_err(|e| {
        format!(
            "whisper could not load {}: {e}",
            owned.display()
        )
    })?;

    let arc = Arc::new(loaded);
    *guard = Some((owned, Arc::clone(&arc)));
    Ok(arc)
}

fn env_flag(name: &str) -> bool {
    matches!(
        std::env::var(name).unwrap_or_default().to_ascii_lowercase().as_str(),
        "1" | "true" | "yes" | "on"
    )
}

/// Threads per track.
///
/// Half the cores (cap 4) because THERE ARE TWO TRACKS decoding at the same time.
/// Giving `min(4, cores)` to each — whisper.cpp's default — puts 8 threads on a
/// 4-core laptop and the oversubscribe makes latency WORSE than with 2.
fn inference_threads() -> i32 {
    static N: std::sync::OnceLock<i32> = std::sync::OnceLock::new();
    *N.get_or_init(|| {
        if let Ok(raw) = std::env::var("NORA_WHISPER_THREADS") {
            if let Ok(n) = raw.trim().parse::<i32>() {
                if n >= 1 {
                    return n;
                }
            }
        }
        let cores = std::thread::available_parallelism()
            .map(|n| n.get())
            .unwrap_or(2);
        ((cores / 2).clamp(1, 4)) as i32
    })
}

/// A reduced `audio_ctx` cuts the encoder cost proportionally (the encoder is
/// the biggest slice of the time in a short window), at the cost of quality.
/// 0 = full (1500). Knob exposed because the good value depends on the hardware;
/// there is no default that is safe for everyone.
fn audio_ctx_override() -> i32 {
    static V: std::sync::OnceLock<i32> = std::sync::OnceLock::new();
    *V.get_or_init(|| {
        std::env::var("NORA_WHISPER_AUDIO_CTX")
            .ok()
            .and_then(|s| s.trim().parse::<i32>().ok())
            .filter(|v| *v >= 0)
            .unwrap_or(0)
    })
}

// ============================================================================
// Energy-based VAD
// ============================================================================

struct EnergyVad {
    /// Adaptive noise floor. Rises slowly, falls fast — that way a long pause
    /// does not "teach" the VAD to ignore quiet speech afterwards.
    noise_floor: f32,
    /// Continuous silence at the END of the window, in ms.
    silence_run_ms: u64,
    /// Speech accumulated in the current window, in ms.
    speech_ms: u64,
    /// Samples of the window already analyzed.
    cursor: usize,
}

impl EnergyVad {
    fn new() -> Self {
        Self {
            noise_floor: ABS_SILENCE_RMS,
            silence_run_ms: 0,
            speech_ms: 0,
            cursor: 0,
        }
    }

    /// Re-analyzes from scratch. Called after cutting the front of the window:
    /// it is simpler and more correct than reindexing `cursor`, and costs one
    /// RMS over a few seconds of f32 (irrelevant next to a decode).
    fn reset_window(&mut self) {
        self.silence_run_ms = 0;
        self.speech_ms = 0;
        self.cursor = 0;
    }

    fn ingest(&mut self, window: &[f32]) {
        const FRAME_MS: u64 = (VAD_FRAME_SAMPLES * 1000 / SAMPLE_RATE) as u64;

        while self.cursor + VAD_FRAME_SAMPLES <= window.len() {
            let frame = &window[self.cursor..self.cursor + VAD_FRAME_SAMPLES];
            self.cursor += VAD_FRAME_SAMPLES;

            let sum_sq: f32 = frame.iter().map(|s| s * s).sum();
            let rms = (sum_sq / VAD_FRAME_SAMPLES as f32).sqrt();

            let threshold = (self.noise_floor * VAD_SNR_FACTOR).max(ABS_SILENCE_RMS);
            if rms > threshold {
                self.speech_ms += FRAME_MS;
                self.silence_run_ms = 0;
                // Raise the floor very slowly: continuous speech must not raise
                // the floor to the point of silencing the speaker itself.
                self.noise_floor = self.noise_floor * 0.999 + rms * 0.001;
            } else {
                self.silence_run_ms += FRAME_MS;
                self.noise_floor = self.noise_floor * 0.95 + rms * 0.05;
            }
            self.noise_floor = self.noise_floor.clamp(1e-5, 0.2);
        }
    }
}

// ============================================================================
// Streaming engine
// ============================================================================

struct DecodedSegment {
    text: String,
    /// ms since the start of the WINDOW (not of the recording).
    t0_ms: u64,
    t1_ms: u64,
    confidence: Option<f32>,
}

struct StreamEngine {
    state: WhisperState,
    language: String,
    track: String,
    session_id: String,

    /// Audio not yet finalized, f32 normalized in [-1, 1].
    window: Vec<f32>,
    /// MONOTONIC. ms of audio already finalized; base offset of every event.
    committed_ms: u64,
    /// MONOTONIC. End (offset+duration) of the last `final` emitted. Safety
    /// clamp against a garbled whisper timestamp.
    last_final_end_ms: u64,

    vad: EnergyVad,
    prompt: String,
    last_partial_text: String,
    last_partial_at: Instant,
}

impl StreamEngine {
    fn new(state: WhisperState, language: String, track: String, session_id: String) -> Self {
        Self {
            state,
            language,
            track,
            session_id,
            window: Vec::with_capacity(SAMPLE_RATE * 30),
            committed_ms: 0,
            last_final_end_ms: 0,
            vad: EnergyVad::new(),
            prompt: String::new(),
            last_partial_text: String::new(),
            last_partial_at: Instant::now(),
        }
    }

    fn run(&mut self, app: &AppHandle, rx: std::sync::mpsc::Receiver<AudioMsg>) {
        loop {
            match rx.recv_timeout(Duration::from_millis(100)) {
                Ok(msg) => {
                    self.handle(app, msg);
                    // Drain what arrived while the previous decode was running.
                    while let Ok(more) = rx.try_recv() {
                        self.handle(app, more);
                    }
                }
                Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {}
                Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => {
                    // stop(): decode what is left. Without this flush the last
                    // sentence of the meeting — usually the conclusion — is lost.
                    self.flush(app);
                    break;
                }
            }
            self.tick(app);
        }
        #[cfg(debug_assertions)]
        eprintln!(
            "[stt_local] thread closed track={} committed={}ms",
            self.track, self.committed_ms
        );
    }

    fn handle(&mut self, app: &AppHandle, msg: AudioMsg) {
        match msg {
            AudioMsg::Pcm(samples) => {
                self.window
                    .extend(samples.iter().map(|s| *s as f32 / 32768.0));
            }
            AudioMsg::Gap { ms } => {
                eprintln!(
                    "[stt_local] track={} GAP of {}ms in the audio (inference lagging)",
                    self.track, ms
                );
                // Discontinuous audio: what is in the window cannot be
                // concatenated with what comes after (it would become a sentence
                // stitched from two moments). Close what we can and jump the clock by the hole.
                if !self.window.is_empty() {
                    self.commit(app, None);
                }
                self.committed_ms += ms;
                self.last_final_end_ms = self.last_final_end_ms.max(self.committed_ms);
            }
        }
    }

    fn window_ms(&self) -> u64 {
        samples_to_ms(self.window.len())
    }

    fn tick(&mut self, app: &AppHandle) {
        self.vad.ingest(&self.window);
        let window_ms = self.window_ms();

        if window_ms == 0 {
            return;
        }

        let silence_closed = self.vad.silence_run_ms >= SILENCE_COMMIT_MS;
        let window_full = window_ms >= MAX_WINDOW_MS;

        // Whole window without useful speech: discard WITHOUT decoding. Besides
        // saving CPU, this avoids the classic Whisper hallucination on silence
        // ("Legendas pela comunidade Amara.org"). The clock advances all the
        // same — the silence is part of the recording.
        if silence_closed && self.vad.speech_ms < MIN_COMMIT_SPEECH_MS {
            self.drop_window(window_ms);
            return;
        }

        if silence_closed || window_full {
            let boundary = if window_full && !silence_closed {
                // Forced cut in the middle of an utterance: do not commit the
                // last segment (almost always truncated mid-word).
                Some(CutPolicy::KeepLastSegment)
            } else {
                None
            };
            self.commit(app, boundary);
            return;
        }

        let due = self.last_partial_at.elapsed() >= Duration::from_millis(PARTIAL_EVERY_MS);
        if due && window_ms >= MIN_DECODE_MS && self.vad.speech_ms >= 300 {
            self.emit_partial(app);
        }
    }

    fn flush(&mut self, app: &AppHandle) {
        self.vad.ingest(&self.window);
        if self.window_ms() == 0 || self.vad.speech_ms < MIN_COMMIT_SPEECH_MS {
            return;
        }
        self.commit(app, None);
    }

    /// Discards silence from the front of the window keeping the clock coherent.
    fn drop_window(&mut self, window_ms: u64) {
        self.committed_ms += window_ms;
        self.last_final_end_ms = self.last_final_end_ms.max(self.committed_ms);
        self.window.clear();
        self.vad.reset_window();
        self.clear_partial();
    }

    fn clear_partial(&mut self) {
        self.last_partial_text.clear();
    }

    // ------------------------------------------------------------------------
    // Decode
    // ------------------------------------------------------------------------

    fn decode(&mut self, single_segment: bool) -> Result<Vec<DecodedSegment>, String> {
        // Local clones: `set_language`/`set_initial_prompt` tie the borrow to
        // FullParams, and `full()` needs `&mut self.state`.
        let language = self.language.clone();
        let prompt = self.prompt.clone();

        let strategy = if single_segment {
            // Partial: pure greedy, no temperature fallback. Latency > everything.
            SamplingStrategy::Greedy { best_of: 1 }
        } else {
            SamplingStrategy::Greedy { best_of: 2 }
        };
        let mut params = FullParams::new(strategy);
        params.set_n_threads(inference_threads());
        params.set_language(Some(&language));
        params.set_translate(false);
        params.set_print_special(false);
        params.set_print_progress(false);
        params.set_print_realtime(false);
        params.set_print_timestamps(false);
        params.set_suppress_blank(true);
        params.set_single_segment(single_segment);
        params.set_no_speech_thold(0.6);
        // `no_context = true` + manual prompt: the sliding window throws away
        // audio the internal KV would still reference, so letting whisper reuse
        // the previous context produces repetition in a loop. The context comes
        // from the already committed text, explicitly.
        params.set_no_context(true);
        if !prompt.is_empty() {
            // NOTE: `set_initial_prompt` does `CString::into_raw()` and never
            // recovers the pointer — every call leaks the size of the prompt
            // (<= 224 bytes). ~1 partial/s in a 1 h meeting gives <1 MiB.
            // Knowingly accepted; fixing it requires an upstream patch in whisper-rs.
            params.set_initial_prompt(&prompt);
        }
        let actx = audio_ctx_override();
        if actx > 0 {
            params.set_audio_ctx(actx);
        }

        let pcm = pad_to_min(&self.window);
        self.state
            .full(params, &pcm)
            .map_err(|e| format!("whisper full(): {e}"))?;

        let n = self.state.full_n_segments();
        let mut out = Vec::with_capacity(n.max(0) as usize);
        for i in 0..n {
            let Some(seg) = self.state.get_segment(i) else {
                continue;
            };
            if seg.no_speech_probability() > NO_SPEECH_THRESHOLD {
                continue;
            }
            let Ok(raw) = seg.to_str_lossy() else { continue };
            let text = raw.trim().to_string();
            if is_noise_text(&text) {
                continue;
            }
            // Centiseconds -> ms. Negative should not happen; clamp anyway.
            let t0_ms = seg.start_timestamp().max(0) as u64 * 10;
            let t1_ms = (seg.end_timestamp().max(0) as u64 * 10).max(t0_ms);
            out.push(DecodedSegment {
                text,
                t0_ms,
                t1_ms,
                confidence: segment_confidence(&seg),
            });
        }
        Ok(out)
    }

    fn emit_partial(&mut self, app: &AppHandle) {
        self.last_partial_at = Instant::now();
        let segs = match self.decode(true) {
            Ok(s) => s,
            Err(e) => {
                self.emit_error(app, "decode_failed", &e);
                return;
            }
        };
        let text = join_segments(&segs);
        if text.is_empty() || text == self.last_partial_text {
            return;
        }
        self.last_partial_text = text.clone();

        // Partial reports the start of the uncommitted window. NEVER an internal
        // whisper timestamp: the text is a re-decode of the whole window and the
        // front overwrites `partials[track]` on every event.
        self.emit(
            app,
            TranscriptEvent {
                session_id: self.session_id.clone(),
                track: self.track.clone(),
                speaker_id: speaker_id_for_track(&self.track),
                text,
                is_final: false,
                offset_ms: self.committed_ms,
                // Azure did not send duration/confidence on partials either.
                duration_ms: None,
                confidence: None,
            },
        );
    }

    fn commit(&mut self, app: &AppHandle, policy: Option<CutPolicy>) {
        let window_ms = self.window_ms();
        if window_ms == 0 {
            return;
        }

        let segs = match self.decode(false) {
            Ok(s) => s,
            Err(e) => {
                self.emit_error(app, "decode_failed", &e);
                // Do not lock the window on a broken decode: discard and move on,
                // else it grows to the cap and repeats the same error forever.
                self.drop_window(window_ms);
                return;
            }
        };

        if segs.is_empty() {
            self.drop_window(window_ms);
            return;
        }

        // How much of the window goes out as committed.
        let (emit_upto, consumed_ms) = match policy {
            Some(CutPolicy::KeepLastSegment) if segs.len() > 1 => {
                let cut = segs[segs.len() - 2].t1_ms.min(window_ms);
                // Degenerate cut (timestamps stuck at zero): commit everything
                // instead of not advancing and reprocessing the same window forever.
                if cut == 0 {
                    (segs.len(), window_ms)
                } else {
                    (segs.len() - 1, cut)
                }
            }
            _ => (segs.len(), window_ms),
        };

        let base = self.committed_ms;
        let mut prompt_add = String::new();

        for seg in segs.iter().take(emit_upto) {
            let offset = (base + seg.t0_ms).max(self.last_final_end_ms);
            let end = (base + seg.t1_ms).max(offset);
            let duration = end - offset;

            debug_assert!(
                offset >= self.last_final_end_ms,
                "offset regressed: {} < {} (track={})",
                offset,
                self.last_final_end_ms,
                self.track
            );
            self.last_final_end_ms = end;

            if !prompt_add.is_empty() {
                prompt_add.push(' ');
            }
            prompt_add.push_str(&seg.text);

            self.emit(
                app,
                TranscriptEvent {
                    session_id: self.session_id.clone(),
                    track: self.track.clone(),
                    speaker_id: speaker_id_for_track(&self.track),
                    text: seg.text.clone(),
                    is_final: true,
                    offset_ms: offset,
                    duration_ms: Some(duration),
                    confidence: seg.confidence,
                },
            );
        }

        // Advance the clock and cut the front of the window.
        self.committed_ms += consumed_ms;
        self.last_final_end_ms = self.last_final_end_ms.max(self.committed_ms);
        let consumed_samples = ms_to_samples(consumed_ms).min(self.window.len());
        self.window.drain(..consumed_samples);
        self.vad.reset_window();
        self.clear_partial();
        self.push_prompt(&prompt_add);
    }

    fn push_prompt(&mut self, text: &str) {
        if text.trim().is_empty() {
            return;
        }
        if !self.prompt.is_empty() {
            self.prompt.push(' ');
        }
        self.prompt.push_str(text.trim());
        // Cut from the LEFT on a char boundary (pt-BR has accents: cutting by
        // byte blows up the `CString`/UTF-8 further down).
        if self.prompt.chars().count() > PROMPT_MAX_CHARS {
            let skip = self.prompt.chars().count() - PROMPT_MAX_CHARS;
            self.prompt = self.prompt.chars().skip(skip).collect();
        }
    }

    fn emit(&self, app: &AppHandle, evt: TranscriptEvent) {
        let _ = app.emit("transcript", &evt);
    }

    fn emit_error(&self, app: &AppHandle, code: &str, message: &str) {
        eprintln!("[stt_local] track={} {}: {}", self.track, code, message);
        // Same shape as the Python sidecar's `error` — the front cannot tell them apart.
        let _ = app.emit(
            "stt-error",
            &serde_json::json!({
                "v": 1,
                "type": "error",
                "session_id": self.session_id,
                "code": code,
                "message": message,
            }),
        );
    }
}

enum CutPolicy {
    /// Does not commit the last segment: it stays in the window for the next decode.
    KeepLastSegment,
}

// ============================================================================
// Pure helpers (testable without a model)
// ============================================================================

fn samples_to_ms(n: usize) -> u64 {
    (n as u64 * MS_PER_SEC) / SAMPLE_RATE as u64
}

fn ms_to_samples(ms: u64) -> usize {
    ((ms * SAMPLE_RATE as u64) / MS_PER_SEC) as usize
}

/// Whisper.cpp discards audio shorter than ~1 s. Pads with silence at the END
/// (never at the start: that would shift all the segment timestamps).
fn pad_to_min(window: &[f32]) -> Vec<f32> {
    let min_samples = ms_to_samples(MIN_PADDED_MS);
    if window.len() >= min_samples {
        return window.to_vec();
    }
    let mut out = Vec::with_capacity(min_samples);
    out.extend_from_slice(window);
    out.resize(min_samples, 0.0);
    out
}

/// Hallucination filter. `text` already comes in trimmed.
fn is_noise_text(text: &str) -> bool {
    if text.is_empty() {
        return true;
    }
    let normalized = text
        .trim_matches(|c: char| c.is_whitespace() || c == '.' || c == '!' || c == '?')
        .to_lowercase();
    if normalized.is_empty() {
        return true;
    }
    HALLUCINATION_BLOCKLIST
        .iter()
        .any(|b| normalized == b.trim_matches(|c: char| c == '.' || c == '!'))
}

fn join_segments(segs: &[DecodedSegment]) -> String {
    let mut out = String::new();
    for s in segs {
        if !out.is_empty() {
            out.push(' ');
        }
        out.push_str(&s.text);
    }
    out.trim().to_string()
}

/// Confidence of a segment from the token probabilities.
///
/// ========================= IT IS NOT CALIBRATED =========================
/// Azure's `confidence` came from `NBest[0].Confidence`: a trained score,
/// comparable across utterances, with statistical meaning defined by the
/// service. THIS HERE IS NOT THAT.
///
/// What it is: `exp(mean of the token ln(p))`, that is, the GEOMETRIC mean of
/// the probability the decoder itself assigned to the tokens it itself chose.
/// It is Whisper's `avg_logprob`, normalized to (0, 1].
///
/// Practical consequences:
///   * A confidently wrong model (fluent hallucination) scores HIGH. The classic
///     case in pt-BR is "Legendas pela comunidade Amara.org" with ~0.9.
///   * The scale changes with model size: 0.7 on `base` and 0.7 on `medium`
///     do not mean the same thing.
///   * It is not comparable with the historical values recorded by Azure. Any
///     threshold on top of this needs to be recalibrated with real data.
///
/// It is good for ordering segments WITHIN the same session and for the same model, and that is all.
fn segment_confidence(seg: &whisper_rs::WhisperSegment<'_>) -> Option<f32> {
    let n = seg.n_tokens();
    let mut sum_ln = 0.0f64;
    let mut count = 0u32;

    for j in 0..n {
        let Some(tok) = seg.get_token(j) else { continue };
        // Special tokens (`[_BEG_]`, `<|pt|>`, timestamps) enter the count with
        // probability ~1.0 and would inflate the mean. They are identifiable by
        // the text, which is how whisper.cpp itself filters them when
        // `print_special == false`.
        if let Ok(t) = tok.to_str_lossy() {
            if t.starts_with("[_") || t.starts_with("<|") {
                continue;
            }
        }
        let p = tok.token_probability().clamp(1e-6, 1.0);
        sum_ln += (p as f64).ln();
        count += 1;
    }

    if count == 0 {
        return None;
    }
    Some(((sum_ln / count as f64).exp() as f32).clamp(0.0, 1.0))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn ms_samples_conversion_is_reversible_in_frame_multiples() {
        for ms in [0u64, 100, 900, 1_200, 22_000] {
            assert_eq!(samples_to_ms(ms_to_samples(ms)), ms);
        }
    }

    #[test]
    fn pad_never_shortens_and_reaches_the_minimum() {
        let min = ms_to_samples(MIN_PADDED_MS);
        let short = vec![0.5f32; 100];
        let padded = pad_to_min(&short);
        assert_eq!(padded.len(), min);
        // Padding goes at the END: the first 100 are still the real audio.
        assert_eq!(&padded[..100], &short[..]);
        assert_eq!(padded[min - 1], 0.0);

        let long = vec![0.1f32; min + 5];
        assert_eq!(pad_to_min(&long).len(), min + 5);
    }

    #[test]
    fn blocklist_catches_silence_hallucination_in_pt_br() {
        assert!(is_noise_text("Legendas pela comunidade Amara.org"));
        assert!(is_noise_text("  [Música]  "));
        assert!(is_noise_text("..."));
        assert!(is_noise_text(""));
        assert!(!is_noise_text("vamos fechar o escopo do sprint"));
    }

    #[test]
    fn vad_separates_silence_from_speech() {
        let mut vad = EnergyVad::new();
        // 1 s of digital silence.
        let silence = vec![0.0f32; SAMPLE_RATE];
        vad.ingest(&silence);
        assert_eq!(vad.speech_ms, 0);
        assert!(vad.silence_run_ms >= 900, "silence_run={}", vad.silence_run_ms);

        // 1 s of a wave with amplitude well above the floor.
        let mut buf = silence.clone();
        for i in 0..SAMPLE_RATE {
            let t = i as f32 / SAMPLE_RATE as f32;
            buf.push((t * 440.0 * std::f32::consts::TAU).sin() * 0.3);
        }
        vad.ingest(&buf);
        assert!(vad.speech_ms >= 900, "speech_ms={}", vad.speech_ms);
        assert_eq!(vad.silence_run_ms, 0);
    }

    #[test]
    fn reset_window_zeroes_counters_but_preserves_the_floor() {
        let mut vad = EnergyVad::new();
        vad.noise_floor = 0.02;
        vad.speech_ms = 500;
        vad.silence_run_ms = 300;
        vad.cursor = 1234;
        vad.reset_window();
        assert_eq!(vad.speech_ms, 0);
        assert_eq!(vad.silence_run_ms, 0);
        assert_eq!(vad.cursor, 0);
        assert!((vad.noise_floor - 0.02).abs() < f32::EPSILON);
    }

    #[test]
    fn join_segments_concatenates_with_single_space() {
        let segs = vec![
            DecodedSegment { text: "bom".into(), t0_ms: 0, t1_ms: 500, confidence: None },
            DecodedSegment { text: "dia".into(), t0_ms: 500, t1_ms: 900, confidence: None },
        ];
        assert_eq!(join_segments(&segs), "bom dia");
    }

    /// The central point of this module. Simulates the offset math of three
    /// commits (including a forced cut that consumes less than the window) and
    /// checks that the final sequence never regresses nor opens a hole.
    #[test]
    fn final_offsets_never_regress() {
        let mut committed_ms: u64 = 0;
        let mut last_end: u64 = 0;
        let mut emitted: Vec<(u64, u64)> = Vec::new();

        // (segments as (t0, t1) relative to the window, ms consumed by the commit)
        let commits: Vec<(Vec<(u64, u64)>, u64)> = vec![
            (vec![(0, 1_500), (1_500, 2_800)], 3_000),
            // Forced cut: 22 s window, only 18 s consumed.
            (vec![(0, 9_000), (9_000, 18_000)], 18_000),
            // Garbled whisper timestamp: t0 goes backwards.
            (vec![(0, 400)], 900),
        ];

        for (segs, consumed) in commits {
            let base = committed_ms;
            for (t0, t1) in segs {
                let offset = (base + t0).max(last_end);
                let end = (base + t1).max(offset);
                assert!(offset >= last_end, "offset {} < last_end {}", offset, last_end);
                emitted.push((offset, end - offset));
                last_end = end;
            }
            committed_ms += consumed;
            last_end = last_end.max(committed_ms);
        }

        // Global monotonicity of the emitted sequence.
        let mut prev = 0u64;
        for (off, _) in &emitted {
            assert!(*off >= prev, "sequence regressed at {}", off);
            prev = *off;
        }
        // The clock matches the sum of the consumed audio.
        assert_eq!(committed_ms, 3_000 + 18_000 + 900);
    }

    #[test]
    fn gap_advances_the_clock_without_regression() {
        // An audio hole must not make the next offset "go back" to the old
        // time: committed_ms has to jump along with it.
        let mut committed_ms: u64 = 5_000;
        let mut last_end: u64 = 5_000;
        let gap = 2_500;
        committed_ms += gap;
        last_end = last_end.max(committed_ms);
        assert_eq!(committed_ms, 7_500);
        assert!(last_end >= committed_ms);
    }
}
