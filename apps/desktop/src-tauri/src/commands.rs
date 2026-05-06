use crate::audio_capture::{AudioCapture, RecordingStatus};
use crate::azure_speech::AzureSpeechClient;
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
    pub azure_speech_key: Option<String>,
    pub azure_region: Option<String>,
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
        eprintln!("[commands] azure_region: {:?}", request.azure_region);
        eprintln!("[commands] azure_key present: {}", request.azure_speech_key.is_some());
        eprintln!("[commands] language: {:?}", request.language);
        eprintln!("[commands] capture_system_audio: {:?}", request.capture_system_audio);
        eprintln!("[commands] system_audio_device: {:?}", request.system_audio_device);
    }

    let (tx, rx) = tokio::sync::mpsc::channel::<Vec<f32>>(100);

    let maybe_speech = if let (Some(key), Some(region)) = (request.azure_speech_key, request.azure_region) {
        let lang = request.language.unwrap_or_else(|| "pt-BR".into());
        let speech_client = AzureSpeechClient::new(region, key, lang);

        #[cfg(debug_assertions)]
        eprintln!("[commands] testing azure connection...");

        match speech_client.test_connection().await {
            Ok(()) => {
                #[cfg(debug_assertions)]
                eprintln!("[commands] azure connection ok");
                Some(speech_client)
            }
            Err(e) => {
                #[cfg(debug_assertions)]
                eprintln!("[commands] azure connection FAILED: {}", e);
                return Err(format!("Azure Speech connection failed: {}", e));
            }
        }
    } else {
        #[cfg(debug_assertions)]
        eprintln!("[commands] no azure credentials - audio capture only");
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

        let status = capture.start(
            app_handle.clone(),
            request.device_name.clone(),
            request.capture_system_audio.unwrap_or(false),
            request.system_audio_device.clone(),
            Some(tx),
        ).map_err(|e| {
            #[cfg(debug_assertions)]
            eprintln!("[commands] capture.start FAILED: {}", e);
            e
        })?;

        status
    }; // mutex released here before I/O

    #[cfg(debug_assertions)]
    eprintln!("[commands] capture started ok - device: {}, sr: {}, ch: {}", status.device_name, status.sample_rate, status.channels);

    if let Some(speech_client) = maybe_speech {
        let handle = app_handle.clone();
        let sr = status.sample_rate;
        let ch = status.channels;

        tokio::spawn(async move {
            #[cfg(debug_assertions)]
            eprintln!("[azure-speech] task started");
            if let Err(e) = speech_client.recognize_stream(handle, rx, sr, ch).await {
                #[cfg(debug_assertions)]
                eprintln!("[azure-speech] error: {}", e);
            }
            #[cfg(debug_assertions)]
            eprintln!("[azure-speech] task ended");
        });
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
