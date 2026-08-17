use crate::audio_capture::{AudioCapture, CaptureSinks, RecordingStatus};
use crate::stt::SttBackend;
use crate::SidecarState;
use serde::Deserialize;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Manager, State};

pub type CaptureState = Arc<Mutex<AudioCapture>>;

/// Brings up ONE STT backend for a track.
///
/// The event contract for the front end is `transcript` with a `TranscriptEvent`
/// payload, and it is what any backend has to keep emitting. There is one: the
/// cloud client, which fetches a short-lived session credential from the NORA API
/// and then streams straight to the provider (ADR 0039/0045). The `match` over
/// `SttBackendKind` that used to live here went with the enum — see `stt.rs`.
///
/// Failing here fails `start_recording`, on purpose: a recording that turns out
/// to have no transcript is worse than a record button that refuses.
async fn start_stt_backend(
    app_handle: &AppHandle,
    language: &str,
    track_label: &str,
) -> Result<Box<dyn SttBackend>, String> {
    let handle = crate::stt_cloud::CloudSttHandle::start(
        app_handle.clone(),
        language.to_string(),
        track_label.to_string(),
    )
    .await?;
    Ok(Box::new(handle))
}

/// Best-effort file logger for the recording flow.
///
/// In release the binary runs with `windows_subsystem = "windows"`, no console —
/// so `eprintln!` vanishes and the user gets no signal at all when "I click
/// start and nothing happens". This function appends a line to
/// `<app_log_dir>/desktop.log` with a simple timestamp.
///
/// RULE: it can NEVER make the caller fail. Any IO/path resolution error is
/// silently ignored (the recording matters more than the log).
fn log_line(app_handle: &AppHandle, msg: &str) {
    use std::io::Write;

    // Simple relative timestamp: seconds since the UNIX epoch. We do not need
    // a formatted wall-clock — only order + delta between lines for debugging.
    let ts = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or(0.0);

    // Resolve the log dir; if it fails, give up silently.
    let Ok(dir) = app_handle.path().app_log_dir() else {
        return;
    };
    // Create the dir best-effort; ignore the error (the open below will simply fail).
    let _ = std::fs::create_dir_all(&dir);
    let path = dir.join("desktop.log");

    if let Ok(mut file) = std::fs::OpenOptions::new()
        .create(true)
        .append(true)
        .open(&path)
    {
        // Ignore write error — best-effort.
        let _ = writeln!(file, "[{:.3}] {}", ts, msg);
    }
}

#[tauri::command]
pub fn list_audio_devices() -> Result<Vec<String>, String> {
    AudioCapture::list_devices()
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StartRecordingRequest {
    pub device_name: Option<String>,
    pub language: Option<String>,
    pub capture_system_audio: Option<bool>,
    pub system_audio_device: Option<String>,
}

#[tauri::command]
pub async fn start_recording(
    app_handle: AppHandle,
    state: State<'_, CaptureState>,
    sidecar_state: State<'_, SidecarState>,
    request: StartRecordingRequest,
) -> Result<RecordingStatus, String> {
    #[cfg(debug_assertions)]
    {
        eprintln!("[commands] start_recording called");
        eprintln!("[commands] device_name: {:?}", request.device_name);
        eprintln!("[commands] language: {:?}", request.language);
        eprintln!("[commands] capture_system_audio: {:?}", request.capture_system_audio);
        eprintln!("[commands] system_audio_device: {:?}", request.system_audio_device);
    }

    log_line(
        &app_handle,
        &format!(
            "start_recording: begin (device={:?}, lang={:?}, system_audio={:?})",
            request.device_name, request.language, request.capture_system_audio
        ),
    );

    // Refuse to start without a web session: a recording nobody can upload is worse
    // than a clear error up front. Only the CHECK matters here — the token itself is
    // not carried into the recording, because `upload_meeting` resolves its own token
    // and base URL at the moment it needs them.
    //
    // Since ADR 0039 this is no longer the only network dependency of starting a
    // recording: `start_stt_backend` below mints a transcription session per track,
    // so the API has to be reachable, not merely logged into. This check stays anyway
    // because it fails with a message about logging in, which is the common case and
    // the one the user can act on.
    if let Err(e) = crate::auth_bridge::web_session_jwt(&app_handle) {
        log_line(&app_handle, &format!("start_recording: auth ERROR: {}", e));
        return Err(e);
    }
    log_line(&app_handle, "start_recording: auth ok");

    let language = request.language.unwrap_or_else(|| "pt-BR".to_string());
    let capture_system = request.capture_system_audio.unwrap_or(false);

    // Create channels for mic and system audio
    let (mic_tx, mut mic_rx) = tokio::sync::mpsc::channel::<Vec<i16>>(100);
    let (system_tx, mut system_rx) = tokio::sync::mpsc::channel::<Vec<i16>>(100);

    let sinks = CaptureSinks {
        mic_tx,
        system_tx: if capture_system {
            Some(system_tx)
        } else {
            None
        },
    };

    // One backend per track. The `mic` track is the local user; the `system` track is
    // the loopback audio (remote participants). Speaker attribution is PER
    // TRACK — see crate::stt::SYSTEM_SPEAKER_ID.
    let mic_sidecar = match start_stt_backend(&app_handle, &language, "mic").await {
        Ok(s) => {
            log_line(&app_handle, "start_recording: stt mic ok");
            s
        }
        Err(e) => {
            log_line(&app_handle, &format!("start_recording: stt mic ERROR: {}", e));
            return Err(format!("Failed to start mic sidecar: {}", e));
        }
    };

    let system_sidecar = if capture_system {
        match start_stt_backend(&app_handle, &language, "system").await {
            Ok(s) => {
                log_line(&app_handle, "start_recording: stt system ok");
                Some(s)
            }
            Err(e) => {
                log_line(
                    &app_handle,
                    &format!("start_recording: stt system ERROR: {}", e),
                );
                return Err(format!("Failed to start system sidecar: {}", e));
            }
        }
    } else {
        None
    };

    // Spawn bridge tasks to feed audio from channels to sidecars
    let mic_bridge = {
        let sidecar = mic_sidecar.audio_tx();
        tokio::spawn(async move {
            while let Some(samples) = mic_rx.recv().await {
                match sidecar.try_send(samples) {
                    Ok(()) => {}
                    // Backpressure: sidecar does not consume fast enough. Drops the
                    // sample (real time > completeness) — expected under load.
                    Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {}
                    // Sidecar died: shut down the bridge instead of spinning for nothing.
                    Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => break,
                }
            }
        })
    };

    let system_bridge = if let Some(ref sidecar) = system_sidecar {
        let tx = sidecar.audio_tx();
        Some(tokio::spawn(async move {
            while let Some(samples) = system_rx.recv().await {
                match tx.try_send(samples) {
                    Ok(()) => {}
                    Err(tokio::sync::mpsc::error::TrySendError::Full(_)) => {}
                    Err(tokio::sync::mpsc::error::TrySendError::Closed(_)) => break,
                }
            }
        }))
    } else {
        None
    };

    let status = {
        let capture = state.lock().map_err(|e| {
            #[cfg(debug_assertions)]
            eprintln!("[commands] failed to lock capture state: {}", e);
            e.to_string()
        })?;

        #[cfg(debug_assertions)]
        eprintln!("[commands] calling capture.start()...");

        let status = capture
            .start(
                app_handle.clone(),
                request.device_name.clone(),
                capture_system,
                request.system_audio_device.clone(),
                sinks,
            )
            .map_err(|e| {
                #[cfg(debug_assertions)]
                eprintln!("[commands] capture.start FAILED: {}", e);
                log_line(
                    &app_handle,
                    &format!("start_recording: capture start ERROR: {}", e),
                );
                e
            })?;

        status
    };

    log_line(&app_handle, "start_recording: capture start ok");

    #[cfg(debug_assertions)]
    eprintln!(
        "[commands] capture started ok - mic: {}, system: {:?}, sr: {}",
        status.mic_device, status.system_audio_device, status.sample_rate
    );

    // Store sidecars in app state so they stay alive
    {
        let mut sidecars = sidecar_state.lock().map_err(|e| e.to_string())?;
        sidecars.push(mic_sidecar);
        if let Some(s) = system_sidecar {
            sidecars.push(s);
        }
    }

    // Bridge tasks were already spawned above (mic_bridge / system_bridge are
    // JoinHandle from tokio::spawn). We do NOT call `tokio::spawn(mic_bridge)`
    // again — that would create a second task that only awaits the JoinHandle
    // and ends as soon as the bridge ends, with no functional benefit.
    // We keep the handles in scope, dropping them via `let _ =` to signal
    // fire-and-forget intent.
    let _ = mic_bridge;
    if let Some(bridge) = system_bridge {
        let _ = bridge;
    }

    #[cfg(debug_assertions)]
    eprintln!("[commands] start_recording returning ok");
    log_line(
        &app_handle,
        &format!(
            "start_recording: returning ok (mic={}, system={:?}, sr={})",
            status.mic_device, status.system_audio_device, status.sample_rate
        ),
    );
    Ok(status)
}

#[tauri::command]
pub fn stop_recording(
    app_handle: AppHandle,
    state: State<'_, CaptureState>,
    sidecar_state: State<'_, SidecarState>,
) -> Result<(), String> {
    #[cfg(debug_assertions)]
    eprintln!("[commands] stop_recording called");

    // Stop audio capture first
    let capture = state.lock().map_err(|e| e.to_string())?;
    capture.stop(app_handle)?;

    // Stop and clear all sidecars to prevent zombie processes
    let mut sidecars = sidecar_state.lock().map_err(|e| e.to_string())?;
    for sidecar in sidecars.drain(..) {
        #[cfg(debug_assertions)]
        eprintln!("[commands] stopping stt session_id={}", sidecar.session_id());
        // The backend closes its socket after this signal, so one last `transcript`
        // event can still arrive — the provider may already have a completed
        // utterance in flight.
        sidecar.stop();
    }

    Ok(())
}

#[tauri::command]
pub fn get_recording_status(
    state: State<'_, CaptureState>,
) -> Result<RecordingStatus, String> {
    let capture = state.lock().map_err(|e| e.to_string())?;
    Ok(capture.get_status())
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadMeetingRequest {
    pub title: String,
    pub started_at: String,
    pub ended_at: Option<String>,
    pub language: Option<String>,
    pub transcript_format: String,
    pub tags: Option<Vec<String>>,
    pub participants: Option<Vec<UploadParticipant>>,
    pub file_content: String,
    pub file_name: String,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadParticipant {
    pub display_name: String,
    pub email: Option<String>,
}

#[derive(serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UploadMeetingResponse {
    pub meeting_id: String,
    pub processing_status: String,
}

#[tauri::command]
pub async fn upload_meeting(
    app_handle: AppHandle,
    request: UploadMeetingRequest,
) -> Result<UploadMeetingResponse, String> {
    let access_token = crate::auth_bridge::web_session_jwt(&app_handle)?;

    let backend_url = crate::api_base_url();

    let metadata = serde_json::json!({
        "title": request.title,
        "startedAt": request.started_at,
        "endedAt": request.ended_at,
        "language": request.language.unwrap_or_else(|| "pt-BR".to_string()),
        "transcriptFormat": request.transcript_format,
        "tags": request.tags.unwrap_or_default(),
        "participants": request.participants.unwrap_or_default().iter().map(|p| {
            serde_json::json!({
                "displayName": p.display_name,
                "email": p.email,
            })
        }).collect::<Vec<_>>(),
    });

    let metadata_bytes = serde_json::to_vec(&metadata)
        .map_err(|e| format!("Failed to serialize metadata: {}", e))?;

    let file_bytes = request.file_content.into_bytes();

    let form = reqwest::multipart::Form::new()
        .part("metadata", reqwest::multipart::Part::bytes(metadata_bytes)
            .mime_str("application/json")
            .map_err(|e| e.to_string())?)
        .part("file", reqwest::multipart::Part::bytes(file_bytes)
            .file_name(request.file_name)
            .mime_str("text/plain")
            .map_err(|e| e.to_string())?);

    let response = crate::http_proxy::http_client()
        .post(format!("{}/meetings", backend_url))
        .header("Authorization", format!("Bearer {}", access_token))
        .multipart(form)
        .timeout(std::time::Duration::from_secs(120))
        .send()
        .await
        .map_err(|e| format!("Upload request failed: {}", e))?;

    let status = response.status();
    let body_text = response.text().await
        .map_err(|e| format!("Failed to read response body: {}", e))?;

    if !status.is_success() {
        return Err(format!("Upload failed ({}): {}", status, body_text));
    }

    let json: serde_json::Value = serde_json::from_str(&body_text)
        .map_err(|e| format!("Failed to parse response: {} — body: {}", e, body_text))?;

    let meeting_id = json["id"]
        .as_str()
        .ok_or_else(|| format!("Upload: backend response missing 'id': {}", body_text))?
        .to_string();
    Ok(UploadMeetingResponse {
        meeting_id,
        processing_status: json["processingStatus"].as_str().unwrap_or("PENDING").to_string(),
    })
}

// `check_system_audio_prerequisites` used to live here and was deleted with the
// Windows-only cut. It branched three ways: the macOS arm reported whether the user had
// installed the BlackHole virtual driver and carried a `supportsScreenCaptureKit` flag for
// an integration nobody was going to write, the Linux arm reported PulseAudio, and the
// Windows arm answered a hardcoded yes — WASAPI loopback is part of the OS and needs no
// driver, no permission prompt and no user setup. Dropping the other two platforms left a
// probe whose every field was a compile-time constant, and both of its callers went at the
// same time: the overlay's "install BlackHole" notice here, and `src/pages/settings.tsx`
// with the local UI (PR #465). Bring it back if there is ever something it can answer "no"
// to.
