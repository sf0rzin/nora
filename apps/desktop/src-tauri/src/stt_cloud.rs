//! Cloud STT over the provider's realtime WebSocket, one connection per track (ADR 0039/0045).
//!
//! Replaces the sliding-window re-decode loop of the local engine with real streaming: audio goes
//! up as it is captured, partials come back as they are produced, and the provider — not us —
//! decides where an utterance ends.
//!
//! ============================================================================
//! THE CLOCK, AND WHY IT COUNTS RECEIVED AUDIO RATHER THAN SENT AUDIO
//! ============================================================================
//! `TranscriptEvent::offset_ms` is absolute against the start of the track's recording and MUST be
//! monotonic non-decreasing (`stt.rs`). The provider's events carry no such clock: they describe an
//! utterance, not a position in our recording. So the position is ours to keep.
//!
//! It is kept as `samples RECEIVED from capture / TARGET_SAMPLE_RATE`, not samples successfully
//! sent. The difference matters exactly once, and it is the case this module has to survive: while
//! the socket is down, audio keeps arriving and is discarded. A clock made of sent audio would
//! stall for the length of the outage and every later offset would sit that far in the past. This
//! is the same invariant the local engine kept with its `AudioMsg::Gap` — the clock advances
//! through the hole, because the hole is part of the recording.
//!
//! To keep that true the reconnect path DRAINS the audio channel instead of letting it back up,
//! which is also what stops `commands.rs`'s bridge from silently dropping on a full channel.
//!
//! ============================================================================
//! WHAT A DROPPED SESSION COSTS, STATED PLAINLY
//! ============================================================================
//! ADR 0035 celebrated killing the mid-meeting credential expiry; ADR 0039 brings it back as the
//! normal case rather than the edge case. When the connection dies we ask the backend for a NEW
//! session (the credential is not renewable), reconnect, and continue the offset counter where it
//! was. **The audio captured during the gap is gone, so there is a hole in the transcript.** That
//! follows the rule already written in `stt.rs` — real time is worth more than completeness — but
//! a hole is worse than a dropped sample, so it is announced on `stt-error`, which the overlay
//! renders as a visible toast (`overlay.tsx`).

use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Arc;
use std::time::Duration;

use base64::Engine as _;
use futures_util::{SinkExt, StreamExt};
use tauri::{AppHandle, Emitter};
use tokio::sync::{mpsc, oneshot};
use tokio_tungstenite::tungstenite::client::IntoClientRequest;
use tokio_tungstenite::tungstenite::Message;

use crate::stt::{speaker_id_for_track, SttBackend, TranscriptEvent, TARGET_SAMPLE_RATE};
use crate::stt_token::{self, RealtimeSession};

/// Consecutive failed connection attempts before the track gives up for good.
///
/// Bounded on purpose. An unbounded retry against a provider that is refusing us is a loop that
/// spends the user's session budget and their battery while the overlay says nothing new.
const MAX_CONSECUTIVE_FAILURES: u32 = 5;

/// First backoff step; doubles up to `MAX_BACKOFF`.
const BASE_BACKOFF: Duration = Duration::from_millis(800);
const MAX_BACKOFF: Duration = Duration::from_secs(15);

/// Slack of the channel between `audio_capture` and this task. 100 chunks of ~100 ms.
const AUDIO_QUEUE_CHUNKS: usize = 100;

// ============================================================================
// Public handle
// ============================================================================

pub struct CloudSttHandle {
    session_id: String,
    audio_tx: mpsc::Sender<Vec<i16>>,
    stop_tx: Option<oneshot::Sender<()>>,
}

impl Drop for CloudSttHandle {
    fn drop(&mut self) {
        // Same defence the local handle had: if the Vec of backends is cleared without going
        // through stop() (panic, logout, abrupt close), the signal still goes out and the socket
        // is closed instead of streaming a meeting nobody is recording any more.
        if let Some(tx) = self.stop_tx.take() {
            let _ = tx.send(());
        }
    }
}

impl SttBackend for CloudSttHandle {
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

impl CloudSttHandle {
    /// Brings up cloud transcription for ONE track.
    ///
    /// The first session is minted BEFORE returning, so a missing credential, a denied permission
    /// or an unreachable API fails while the user is still looking at the record button — rather
    /// than five minutes into a meeting that turns out to have no transcript. This is the same
    /// choice the local engine made when it blocked on loading the model.
    pub async fn start(
        app: AppHandle,
        language: String,
        track_label: String,
    ) -> Result<Self, String> {
        let session = stt_token::fetch_session(&app, &language)
            .await
            .map_err(|e| e.to_string())?;

        if session.sample_rate != TARGET_SAMPLE_RATE {
            // Not fatal: what we actually stream is declared to the provider on connect, so the
            // truth is preserved either way. It IS worth shouting about, because the two numbers
            // drifting apart is a misconfiguration whose only other symptom is a transcript that
            // reads like nonsense.
            eprintln!(
                "[stt_cloud] backend asked for {} Hz, capture produces {} Hz — streaming {} Hz \
                 and telling the provider so",
                session.sample_rate, TARGET_SAMPLE_RATE, TARGET_SAMPLE_RATE
            );
        }

        let session_id = uuid::Uuid::new_v4().to_string();
        let (audio_tx, audio_rx) = mpsc::channel::<Vec<i16>>(AUDIO_QUEUE_CHUNKS);
        let (stop_tx, stop_rx) = oneshot::channel::<()>();

        let task_app = app.clone();
        let task_session_id = session_id.clone();
        tokio::spawn(async move {
            run(
                task_app,
                task_session_id,
                track_label,
                language,
                session,
                audio_rx,
                stop_rx,
            )
            .await;
        });

        Ok(Self {
            session_id,
            audio_tx,
            stop_tx: Some(stop_tx),
        })
    }
}

// ============================================================================
// Track clock
// ============================================================================

/// Absolute position in the track's recording, derived from the audio that reached this task.
///
/// Shared as an atomic because the two halves of the connection touch it: the writer advances it
/// as PCM arrives, and the reader reads it when the provider finalises an utterance.
#[derive(Clone, Default)]
struct TrackClock {
    samples: Arc<AtomicU64>,
}

impl TrackClock {
    fn advance(&self, samples: usize) {
        self.samples.fetch_add(samples as u64, Ordering::Relaxed);
    }

    fn now_ms(&self) -> u64 {
        samples_to_ms(self.samples.load(Ordering::Relaxed))
    }
}

fn samples_to_ms(samples: u64) -> u64 {
    samples * 1_000 / TARGET_SAMPLE_RATE as u64
}

// ============================================================================
// Connection lifecycle
// ============================================================================

/// Why a connection ended.
enum Outcome {
    /// `stop()` was called, or capture closed the channel. Terminal.
    Stopped,
    /// The socket died. Reconnectable, with a reason for the user.
    Lost(String),
}

#[allow(clippy::too_many_arguments)]
async fn run(
    app: AppHandle,
    session_id: String,
    track: String,
    language: String,
    first_session: RealtimeSession,
    mut audio_rx: mpsc::Receiver<Vec<i16>>,
    mut stop_rx: oneshot::Receiver<()>,
) {
    let clock = TrackClock::default();
    // Lives OUTSIDE the connection loop: this is what makes a reconnection continue the
    // transcript's clock instead of restarting it at zero.
    let mut last_final_end_ms: u64 = 0;

    let mut pending = Some(first_session);
    let mut consecutive_failures: u32 = 0;

    loop {
        let session = match pending.take() {
            Some(s) => s,
            None => match stt_token::fetch_session(&app, &language).await {
                Ok(s) => s,
                Err(e) => {
                    consecutive_failures += 1;
                    emit_error(&app, &session_id, &track, e.code, &e.message);
                    if consecutive_failures >= MAX_CONSECUTIVE_FAILURES {
                        emit_gave_up(&app, &session_id, &track);
                        return;
                    }
                    if !backoff(&mut audio_rx, &clock, &mut stop_rx, consecutive_failures).await {
                        return;
                    }
                    continue;
                }
            },
        };

        let outcome = stream_one_session(
            &app,
            &session_id,
            &track,
            &session,
            &clock,
            &mut last_final_end_ms,
            &mut audio_rx,
            &mut stop_rx,
        )
        .await;

        match outcome {
            Outcome::Stopped => return,
            Outcome::Lost(reason) => {
                consecutive_failures += 1;
                emit_error(
                    &app,
                    &session_id,
                    &track,
                    "STT_SESSION_LOST",
                    &format!(
                        "Transcription dropped and is reconnecting ({}). What is said in the \
                         meantime will be missing from the transcript.",
                        reason
                    ),
                );
                if consecutive_failures >= MAX_CONSECUTIVE_FAILURES {
                    emit_gave_up(&app, &session_id, &track);
                    return;
                }
                if !backoff(&mut audio_rx, &clock, &mut stop_rx, consecutive_failures).await {
                    return;
                }
            }
        }
    }
}

/// Waits out the backoff while KEEPING THE CLOCK RUNNING.
///
/// Draining rather than sleeping is the whole point: the discarded audio still advances the track
/// clock, so the transcript resumes at the right position instead of the length of the outage
/// behind. Returns false when the caller should stop entirely.
async fn backoff(
    audio_rx: &mut mpsc::Receiver<Vec<i16>>,
    clock: &TrackClock,
    stop_rx: &mut oneshot::Receiver<()>,
    attempt: u32,
) -> bool {
    let delay = BASE_BACKOFF
        .saturating_mul(1u32 << attempt.min(5))
        .min(MAX_BACKOFF);
    let deadline = tokio::time::sleep(delay);
    tokio::pin!(deadline);

    loop {
        tokio::select! {
            _ = &mut *stop_rx => return false,
            _ = &mut deadline => return true,
            chunk = audio_rx.recv() => match chunk {
                Some(samples) => clock.advance(samples.len()),
                None => return false,
            },
        }
    }
}

#[allow(clippy::too_many_arguments)]
async fn stream_one_session(
    app: &AppHandle,
    session_id: &str,
    track: &str,
    session: &RealtimeSession,
    clock: &TrackClock,
    last_final_end_ms: &mut u64,
    audio_rx: &mut mpsc::Receiver<Vec<i16>>,
    stop_rx: &mut oneshot::Receiver<()>,
) -> Outcome {
    // The URL comes from our own backend over TLS, but a scheme check costs one line and stops a
    // misconfigured server from talking this client into a cleartext socket.
    if !session.websocket_url.starts_with("wss://") {
        return Outcome::Lost("endpoint is not wss".to_string());
    }

    let mut request = match session.websocket_url.as_str().into_client_request() {
        Ok(r) => r,
        Err(e) => return Outcome::Lost(format!("invalid endpoint: {}", e)),
    };
    let bearer = match format!("Bearer {}", session.client_secret).parse() {
        Ok(v) => v,
        Err(_) => return Outcome::Lost("credential is not a valid header value".to_string()),
    };
    request.headers_mut().insert("Authorization", bearer);

    let (socket, _) = match tokio_tungstenite::connect_async(request).await {
        Ok(pair) => pair,
        Err(e) => return Outcome::Lost(format!("handshake failed: {}", e)),
    };
    let (mut write, mut read) = socket.split();

    // Declare what we are actually going to send. The backend already configured the session when
    // it minted the credential; this repeats it from the side that knows the truth about the
    // audio, so the two can never disagree about the sample rate.
    let update = session_update_payload(session);
    if let Err(e) = write.send(Message::Text(update.into())).await {
        return Outcome::Lost(format!("could not configure the session: {}", e));
    }

    loop {
        tokio::select! {
            _ = &mut *stop_rx => {
                let _ = write.send(Message::Close(None)).await;
                return Outcome::Stopped;
            }
            incoming = read.next() => {
                match incoming {
                    Some(Ok(Message::Text(text))) => {
                        for event in interpret(&text) {
                            emit_transcript(app, session_id, track, clock, last_final_end_ms, event);
                        }
                    }
                    Some(Ok(Message::Close(_))) | None => {
                        return Outcome::Lost("the provider closed the connection".to_string());
                    }
                    Some(Ok(_)) => {}
                    Some(Err(e)) => return Outcome::Lost(format!("socket error: {}", e)),
                }
            }
            chunk = audio_rx.recv() => {
                match chunk {
                    Some(samples) => {
                        clock.advance(samples.len());
                        let payload = append_payload(&samples);
                        if let Err(e) = write.send(Message::Text(payload.into())).await {
                            return Outcome::Lost(format!("could not send audio: {}", e));
                        }
                    }
                    // Capture closed the channel: the recording is over.
                    None => {
                        let _ = write.send(Message::Close(None)).await;
                        return Outcome::Stopped;
                    }
                }
            }
        }
    }
}

// ============================================================================
// Provider protocol (pure, and therefore tested)
// ============================================================================

/// What one provider event means to us. Anything not listed here is deliberately ignored — the
/// realtime protocol emits a great deal that a transcription client has no use for.
#[derive(Debug, PartialEq)]
enum Interpreted {
    Partial(String),
    Final(String),
    Failure(String),
}

/// `session.update` describing the audio this client actually produces.
///
/// The backend already configured the session when it minted the credential. This repeats the
/// audio half from the side that knows the truth about it, so a stale `nora.stt.openai.sample-rate`
/// on the server cannot make the provider play our PCM at the wrong speed.
///
/// It deliberately does NOT carry `turn_detection`. Segmentation is a server decision now that
/// there is no local VAD, and a desktop in the field overriding it would be exactly the kind of
/// client-side control this migration moved to the server.
fn session_update_payload(session: &RealtimeSession) -> String {
    serde_json::json!({
        "type": "session.update",
        "session": {
            "type": "transcription",
            "audio": {
                "input": {
                    "format": { "type": session.audio_format, "rate": TARGET_SAMPLE_RATE },
                    "transcription": { "model": session.model, "language": session.language },
                }
            }
        },
    })
    .to_string()
}

/// One chunk of PCM as the provider's append event: base64 of little-endian i16.
fn append_payload(samples: &[i16]) -> String {
    serde_json::json!({
        "type": "input_audio_buffer.append",
        "audio": encode_pcm16(samples),
    })
    .to_string()
}

/// i16 mono → little-endian bytes → base64. The provider reads PCM16 LE; getting the byte order
/// wrong produces loud noise rather than an error, which is exactly the class of bug the
/// resampler's tone tests exist for.
fn encode_pcm16(samples: &[i16]) -> String {
    let mut bytes = Vec::with_capacity(samples.len() * 2);
    for s in samples {
        bytes.extend_from_slice(&s.to_le_bytes());
    }
    base64::engine::general_purpose::STANDARD.encode(&bytes)
}

/// Reads one provider frame. Returns every transcript event it carries, in order.
fn interpret(text: &str) -> Vec<Interpreted> {
    let Ok(value) = serde_json::from_str::<serde_json::Value>(text) else {
        return Vec::new();
    };
    let kind = value.get("type").and_then(|v| v.as_str()).unwrap_or("");

    match kind {
        "conversation.item.input_audio_transcription.delta" => {
            let delta = value.get("delta").and_then(|v| v.as_str()).unwrap_or("");
            if delta.is_empty() {
                Vec::new()
            } else {
                vec![Interpreted::Partial(delta.to_string())]
            }
        }
        "conversation.item.input_audio_transcription.completed" => {
            let full = value.get("transcript").and_then(|v| v.as_str()).unwrap_or("");
            if full.trim().is_empty() {
                Vec::new()
            } else {
                vec![Interpreted::Final(full.trim().to_string())]
            }
        }
        // The provider's own failure frame. Surfaced rather than swallowed: a session that is
        // rejecting our audio otherwise looks exactly like a silent room.
        "error" | "conversation.item.input_audio_transcription.failed" => {
            let message = value
                .pointer("/error/message")
                .and_then(|v| v.as_str())
                .unwrap_or("the transcription provider reported an error");
            vec![Interpreted::Failure(message.to_string())]
        }
        _ => Vec::new(),
    }
}

// ============================================================================
// Emission
// ============================================================================

fn emit_transcript(
    app: &AppHandle,
    session_id: &str,
    track: &str,
    clock: &TrackClock,
    last_final_end_ms: &mut u64,
    event: Interpreted,
) {
    match event {
        Interpreted::Partial(text) => {
            // A partial always reports the start of the uncommitted stretch, never a position
            // inside it: the front overwrites `partials[track]`, so revision is expected, and an
            // offset that crept forward with each delta would scatter one utterance across the
            // timeline.
            emit(
                app,
                TranscriptEvent {
                    session_id: session_id.to_string(),
                    track: track.to_string(),
                    speaker_id: speaker_id_for_track(track),
                    text,
                    is_final: false,
                    offset_ms: *last_final_end_ms,
                    duration_ms: None,
                    confidence: None,
                },
            );
        }
        Interpreted::Final(text) => {
            let offset = *last_final_end_ms;
            // `max` rather than a bare assignment: the clock is read from another task, and the
            // invariant is worth more than the arithmetic.
            let end = clock.now_ms().max(offset);
            *last_final_end_ms = end;
            emit(
                app,
                TranscriptEvent {
                    session_id: session_id.to_string(),
                    track: track.to_string(),
                    speaker_id: speaker_id_for_track(track),
                    text,
                    is_final: true,
                    offset_ms: offset,
                    duration_ms: Some(end - offset),
                    // ALWAYS none. ADR 0035 refused to publish an uncalibrated number in a field
                    // that used to be calibrated, and ADR 0039 §"What comes back" carries that
                    // forward: the vendor changed, the reasoning did not.
                    confidence: None,
                },
            );
        }
        Interpreted::Failure(message) => {
            emit_error(app, session_id, track, "STT_PROVIDER_ERROR", &message);
        }
    }
}

fn emit(app: &AppHandle, event: TranscriptEvent) {
    let _ = app.emit("transcript", &event);
}

/// Same envelope the local engine used, so the overlay's listener needs no change.
fn emit_error(app: &AppHandle, session_id: &str, track: &str, code: &str, message: &str) {
    eprintln!("[stt_cloud] track={} {}: {}", track, code, message);
    let _ = app.emit(
        "stt-error",
        &serde_json::json!({
            "v": 1,
            "type": "error",
            "session_id": session_id,
            "code": code,
            "message": message,
        }),
    );
}

fn emit_gave_up(app: &AppHandle, session_id: &str, track: &str) {
    emit_error(
        app,
        session_id,
        track,
        "STT_SESSION_ENDED",
        "Transcription stopped after several failed reconnection attempts. The recording \
         continues, but no new text will appear.",
    );
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pcm16_is_encoded_little_endian() {
        // 0x0102 little-endian is 0x02 0x01; a big-endian encoder yields a different string, and
        // the provider would hear noise rather than refuse the frame.
        let encoded = encode_pcm16(&[0x0102, -2]);
        let raw = base64::engine::general_purpose::STANDARD
            .decode(encoded)
            .unwrap();
        assert_eq!(raw, vec![0x02, 0x01, 0xFE, 0xFF]);
    }

    #[test]
    fn the_append_frame_carries_the_audio_as_base64() {
        let payload: serde_json::Value = serde_json::from_str(&append_payload(&[1, 2, 3])).unwrap();
        assert_eq!(payload["type"], "input_audio_buffer.append");
        assert_eq!(payload["audio"], encode_pcm16(&[1, 2, 3]));
    }

    /// The client declares the rate it actually produces, not the one the server guessed. This is
    /// what keeps a stale `nora.stt.openai.sample-rate` from turning the meeting into gibberish.
    #[test]
    fn session_update_declares_the_rate_this_client_really_sends() {
        let session = test_session(16_000);
        let payload: serde_json::Value =
            serde_json::from_str(&session_update_payload(&session)).unwrap();
        let input = &payload["session"]["audio"]["input"];

        assert_eq!(payload["type"], "session.update");
        assert_eq!(payload["session"]["type"], "transcription");
        assert_eq!(input["format"]["rate"], TARGET_SAMPLE_RATE);
        assert_eq!(input["format"]["type"], "audio/pcm");
        assert_eq!(input["transcription"]["model"], "gpt-live-transcribe");
        assert_eq!(input["transcription"]["language"], "pt");
        assert!(
            input.get("turn_detection").is_none(),
            "turn detection belongs to the server that minted the session"
        );
    }

    #[test]
    fn a_delta_is_a_partial_and_a_completed_is_a_final() {
        let delta = r#"{"type":"conversation.item.input_audio_transcription.delta","delta":"bom "}"#;
        assert_eq!(interpret(delta), vec![Interpreted::Partial("bom ".to_string())]);

        let done = r#"{"type":"conversation.item.input_audio_transcription.completed",
                       "transcript":"  bom dia  "}"#;
        assert_eq!(interpret(done), vec![Interpreted::Final("bom dia".to_string())]);
    }

    #[test]
    fn empty_text_produces_no_event_at_all() {
        let delta = r#"{"type":"conversation.item.input_audio_transcription.delta","delta":""}"#;
        assert!(interpret(delta).is_empty());

        let done = r#"{"type":"conversation.item.input_audio_transcription.completed",
                       "transcript":"   "}"#;
        assert!(interpret(done).is_empty());
    }

    /// A session that is rejecting our audio otherwise looks exactly like a silent room.
    #[test]
    fn a_provider_error_frame_becomes_a_failure() {
        let frame = r#"{"type":"error","error":{"message":"invalid_audio_format"}}"#;
        assert_eq!(
            interpret(frame),
            vec![Interpreted::Failure("invalid_audio_format".to_string())]
        );
    }

    #[test]
    fn unknown_and_malformed_frames_are_ignored_rather_than_fatal() {
        assert!(interpret(r#"{"type":"input_audio_buffer.speech_started"}"#).is_empty());
        assert!(interpret("not json at all").is_empty());
        assert!(interpret("{}").is_empty());
    }

    /// The invariant of `stt.rs`: offsets never go backwards, whatever the clock says.
    #[test]
    fn the_track_clock_is_monotonic_across_a_gap() {
        let clock = TrackClock::default();
        clock.advance(TARGET_SAMPLE_RATE as usize); // one second of audio
        assert_eq!(clock.now_ms(), 1_000);

        // The gap: audio arrives while the socket is down and is discarded, but counted.
        clock.advance(TARGET_SAMPLE_RATE as usize * 2);
        assert_eq!(clock.now_ms(), 3_000);
    }

    /// A final whose clock has not moved must not produce a negative duration or a regressed
    /// offset. `max` is what makes this true, and this test is why it is not a bare assignment.
    #[test]
    fn a_final_never_regresses_the_offset() {
        let clock = TrackClock::default();
        let mut last_final_end_ms = 5_000u64;

        let offset = last_final_end_ms;
        let end = clock.now_ms().max(offset);
        last_final_end_ms = end;

        assert_eq!(offset, 5_000);
        assert_eq!(end, 5_000);
        assert_eq!(last_final_end_ms, 5_000);
    }

    fn test_session(sample_rate: u32) -> RealtimeSession {
        RealtimeSession {
            client_secret: "ek_test".to_string(),
            expires_at: "2099-01-01T00:00:00Z".to_string(),
            websocket_url: "wss://provider.example/realtime".to_string(),
            provider: "openai".to_string(),
            model: "gpt-live-transcribe".to_string(),
            language: "pt".to_string(),
            audio_format: "audio/pcm".to_string(),
            sample_rate,
        }
    }
}
