use crate::audio_capture::{AudioCapture, RecordingStatus};
use serde::Deserialize;
use std::sync::{Arc, Mutex};
use tauri::{AppHandle, State};

pub type CaptureState = Arc<Mutex<AudioCapture>>;

#[tauri::command]
pub fn greet(name: &str) -> String {
    format!("Hello, {}! Welcome to NORA Desktop.", name)
}

#[tauri::command]
pub fn list_audio_devices() -> Result<Vec<String>, String> {
    AudioCapture::list_devices()
}

#[derive(Deserialize)]
pub struct StartRecordingRequest {
    pub device_name: Option<String>,
}

#[tauri::command]
pub fn start_recording(
    app_handle: AppHandle,
    state: State<'_, CaptureState>,
    request: StartRecordingRequest,
) -> Result<RecordingStatus, String> {
    let capture = state.lock().map_err(|e| e.to_string())?;
    capture.start(app_handle, request.device_name)
}

#[tauri::command]
pub fn stop_recording(
    app_handle: AppHandle,
    state: State<'_, CaptureState>,
) -> Result<(), String> {
    let capture = state.lock().map_err(|e| e.to_string())?;
    capture.stop(app_handle)
}
