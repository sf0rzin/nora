#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod audio_capture;
mod audio_resample;
pub mod commands;
mod http_proxy;
mod live_analysis;
mod secrets;
mod speech_token;
mod stt_sidecar;
#[cfg(test)]
mod stt_sidecar_test;
mod system_audio;

use commands::CaptureState;
use live_analysis::LiveHighlightsState;
use std::sync::{Arc, Mutex};
use tauri::Manager;

pub type SidecarState = Arc<Mutex<Vec<stt_sidecar::SidecarHandle>>>;

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let capture_state: CaptureState = Arc::new(Mutex::new(audio_capture::AudioCapture::new()));
    let sidecar_state: SidecarState = Arc::new(Mutex::new(Vec::new()));
    let live_state: LiveHighlightsState = Arc::new(Mutex::new(None));

    let api_base_url = std::env::var("NORA_API_BASE_URL")
        .unwrap_or_else(|_| "http://localhost:8080".to_string());
    let base_url = url::Url::parse(&api_base_url)
        .expect("Invalid NORA_API_BASE_URL");

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(capture_state)
        .manage(sidecar_state)
        .manage(live_state)
        .manage(http_proxy::ApiBaseUrl(base_url))
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                if window.label() == "main" {
                    window.app_handle().exit(0);
                }
            }
        })
        .setup(|app| {
            let config_dir = app
                .path()
                .app_config_dir()
                .expect("failed to resolve app config dir");
            app.manage(secrets::SecretStore::new(config_dir));
            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::list_audio_devices,
            commands::start_recording,
            commands::stop_recording,
            commands::get_recording_status,
            commands::upload_meeting,
            commands::check_system_audio_prerequisites,
            http_proxy::http_proxy,
            secrets::secret_set,
            secrets::secret_get,
            secrets::secret_has,
            secrets::secret_delete,
            live_analysis::analyze_live,
            live_analysis::toggle_overlay,
            live_analysis::get_live_highlights_snapshot,
            live_analysis::get_overlay_position,
            live_analysis::set_overlay_position,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
