#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod audio_capture;
mod audio_resample;
pub mod commands;
mod http_proxy;
mod secrets;
mod stt_sidecar;
mod system_audio;

use commands::CaptureState;
use std::sync::{Arc, Mutex};

pub type SidecarState = Arc<Mutex<Vec<stt_sidecar::SidecarHandle>>>;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let capture_state: CaptureState = Arc::new(Mutex::new(audio_capture::AudioCapture::new()));
    let sidecar_state: SidecarState = Arc::new(Mutex::new(Vec::new()));
    
    let api_base_url = std::env::var("NORA_API_BASE_URL")
        .unwrap_or_else(|_| "http://localhost:8080".to_string());
    let base_url = url::Url::parse(&api_base_url)
        .expect("Invalid NORA_API_BASE_URL");

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(capture_state)
        .manage(sidecar_state)
        .manage(http_proxy::ApiBaseUrl(base_url))
        .manage(secrets::SecretStore::new())
        .invoke_handler(tauri::generate_handler![
            commands::list_audio_devices,
            commands::start_recording,
            commands::stop_recording,
            http_proxy::http_proxy,
            secrets::secret_set,
            secrets::secret_has,
            secrets::secret_delete,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
