use crate::audio_capture::{AudioCapture, RecordingStatus};
use crate::azure_speech::AzureSpeechClient;
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
    pub azure_speech_key: Option<String>,
    pub azure_endpoint: Option<String>,
    pub language: Option<String>,
}

#[tauri::command]
pub async fn start_recording(
    app_handle: AppHandle,
    state: State<'_, CaptureState>,
    request: StartRecordingRequest,
) -> Result<RecordingStatus, String> {
    let (tx, rx) = tokio::sync::mpsc::channel::<Vec<f32>>(100);

    let capture = state.lock().map_err(|e| e.to_string())?;
    let status = capture.start(app_handle.clone(), request.device_name, Some(tx))?;

    if let (Some(key), Some(endpoint)) = (request.azure_speech_key, request.azure_endpoint) {
        let lang = request.language.unwrap_or_else(|| "pt-BR".into());
        let speech_client = AzureSpeechClient::new(key, endpoint, lang);
        let handle = app_handle.clone();
        let sr = status.sample_rate;

        tokio::spawn(async move {
            if let Err(e) = speech_client.recognize_stream(handle, rx, sr).await {
                eprintln!("[azure-speech] error: {}", e);
            }
        });
    }

    Ok(status)
}

#[tauri::command]
pub fn stop_recording(
    app_handle: AppHandle,
    state: State<'_, CaptureState>,
) -> Result<(), String> {
    let capture = state.lock().map_err(|e| e.to_string())?;
    capture.stop(app_handle)
}
