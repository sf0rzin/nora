use crate::audio_capture::{AudioCapture, CaptureSinks, RecordingStatus};
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

    // Create channels for mic and system audio
    let (mic_tx, _mic_rx) = tokio::sync::mpsc::channel::<Vec<i16>>(100);
    let (system_tx, _system_rx) = tokio::sync::mpsc::channel::<Vec<i16>>(100);

    let sinks = CaptureSinks {
        mic_tx,
        system_tx: if request.capture_system_audio.unwrap_or(false) {
            Some(system_tx)
        } else {
            None
        },
    };

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
            sinks,
        ).map_err(|e| {
            #[cfg(debug_assertions)]
            eprintln!("[commands] capture.start FAILED: {}", e);
            e
        })?;

        status
    };

    #[cfg(debug_assertions)]
    eprintln!("[commands] capture started ok - mic: {}, system: {:?}, sr: {}", 
        status.mic_device, status.system_audio_device, status.sample_rate);

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
