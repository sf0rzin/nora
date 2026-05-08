use crate::audio_capture::{AudioCapture, CaptureSinks, RecordingStatus};
use crate::secrets::SecretStore;
use crate::speech_token::fetch_speech_token;
use crate::stt_sidecar::SidecarHandle;
use crate::SidecarState;
use serde::Deserialize;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, State};

pub type CaptureState = Arc<Mutex<AudioCapture>>;

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
    secrets: State<'_, SecretStore>,
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

    let access_token = secrets
        .get("access-token")
        .map_err(|e| format!("Failed to get access token: {}", e))?
        .ok_or("Not authenticated. Please login first.")?;
    let backend_url = std::env::var("NORA_API_BASE_URL")
        .unwrap_or_else(|_| "http://localhost:8080".to_string());
    
    let speech_token = fetch_speech_token(
        &backend_url,
        &access_token,
        None, // Use default region from backend
    )
    .await
    .map_err(|e| format!("Failed to fetch speech token: {}", e))?;
    
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

    // Start sidecars with auth token
    let mic_sidecar = SidecarHandle::start(
        app_handle.clone(),
        speech_token.region.clone(),
        speech_token.token.clone(),
        language.clone(),
        "mic".to_string(),
    )
    .await
    .map_err(|e| format!("Failed to start mic sidecar: {}", e))?;

    let system_sidecar = if capture_system {
        Some(
            SidecarHandle::start(
                app_handle.clone(),
                speech_token.region,
                speech_token.token,
                language,
                "system".to_string(),
            )
            .await
            .map_err(|e| format!("Failed to start system sidecar: {}", e))?,
        )
    } else {
        None
    };

    // Spawn bridge tasks to feed audio from channels to sidecars
    let mic_bridge = {
        let sidecar = mic_sidecar.audio_tx.clone();
        tokio::spawn(async move {
            while let Some(samples) = mic_rx.recv().await {
                let _ = sidecar.try_send(samples);
            }
        })
    };

    let system_bridge = if let Some(ref sidecar) = system_sidecar {
        let tx = sidecar.audio_tx.clone();
        Some(tokio::spawn(async move {
            while let Some(samples) = system_rx.recv().await {
                let _ = tx.try_send(samples);
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
                e
            })?;

        status
    };

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

    // Bridge tasks are now owned by the async runtime and will keep running
    // They will be dropped when the channels close (when recording stops)
    tokio::spawn(mic_bridge);
    if let Some(bridge) = system_bridge {
        tokio::spawn(bridge);
    }

    #[cfg(debug_assertions)]
    eprintln!("[commands] start_recording returning ok");
    Ok(status)
}

#[tauri::command]
pub fn stop_recording(
    app_handle: AppHandle,
    state: State<'_, CaptureState>,
) -> Result<(), String> {
    #[cfg(debug_assertions)]
    eprintln!("[commands] stop_recording called");
    let capture = state.lock().map_err(|e| e.to_string())?;
    capture.stop(app_handle)
}

#[tauri::command]
pub fn get_recording_status(
    state: State<'_, CaptureState>,
) -> Result<RecordingStatus, String> {
    let capture = state.lock().map_err(|e| e.to_string())?;
    Ok(capture.get_status())
}
