use crate::audio_capture::{AudioCapture, RecordingStatus};
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

    // TODO: Integrate with stt_sidecar (Issue #11 follow-up)
    // For now, just start audio capture without STT
    let status = {
        let capture = state.lock().map_err(|e| {
            #[cfg(debug_assertions)]
            eprintln!("[commands] failed to lock capture state: {}", e);
            e.to_string()
        })?;

        #[cfg(debug_assertions)]
        eprintln!("[commands] calling capture.start()...");

        let status = capture.start(
            app_handle.clone(),
            request.device_name.clone(),
            request.capture_system_audio.unwrap_or(false),
            request.system_audio_device.clone(),
            None,
        ).map_err(|e| {
            #[cfg(debug_assertions)]
            eprintln!("[commands] capture.start FAILED: {}", e);
            e
        })?;

        status
    };

    #[cfg(debug_assertions)]
    eprintln!("[commands] capture started ok - device: {}, sr: {}, ch: {}", status.device_name, status.sample_rate, status.channels);

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
