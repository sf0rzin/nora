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
mod stealth_mode;
mod system_audio;
mod windows;

use commands::CaptureState;
use live_analysis::LiveHighlightsState;
use stealth_mode::StealthModeState;
use std::sync::{Arc, Mutex, OnceLock};
use tauri::Manager;

pub type SidecarState = Arc<Mutex<Vec<stt_sidecar::SidecarHandle>>>;

/// Lê a URL base da API do tauri.conf.json (compile-time).
/// O campo deve estar em `plugins.nora.apiBaseUrl`.
pub fn api_base_url() -> String {
    static URL: OnceLock<String> = OnceLock::new();
    URL.get_or_init(|| {
        const CONFIG_JSON: &str = include_str!("../tauri.conf.json");
        let config: serde_json::Value = match serde_json::from_str(CONFIG_JSON) {
            Ok(c) => c,
            Err(e) => {
                eprintln!("[nora] failed to parse tauri.conf.json: {}, using default", e);
                return "http://localhost:8080".to_string();
            }
        };
        config
            .get("plugins")
            .and_then(|p| p.get("nora"))
            .and_then(|n| n.get("apiBaseUrl"))
            .and_then(|v| v.as_str())
            .unwrap_or("http://localhost:8080")
            .to_string()
    })
    .clone()
}

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let capture_state: CaptureState = Arc::new(Mutex::new(audio_capture::AudioCapture::new()));
    let sidecar_state: SidecarState = Arc::new(Mutex::new(Vec::new()));
    let live_state: LiveHighlightsState = Arc::new(Mutex::new(None));
    let stealth_state: StealthModeState = Arc::new(Mutex::new(false));

    let api_base_url = api_base_url();
    #[cfg(debug_assertions)]
    eprintln!("[nora] api_base_url={}", api_base_url);
    let base_url = url::Url::parse(&api_base_url)
        .expect("Invalid apiBaseUrl in tauri.conf.json");

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(capture_state)
        .manage(sidecar_state)
        .manage(live_state)
        .manage(stealth_state)
        .manage(http_proxy::ApiBaseUrl(base_url))
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                if window.label() == "main" {
                    window.app_handle().exit(0);
                }
            }
        })
        .setup(|app| {
            app.manage(secrets::SecretStore::new());
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
            live_analysis::clear_live_highlights,
            live_analysis::get_overlay_position,
            live_analysis::set_overlay_position,
            stealth_mode::set_stealth_mode,
            stealth_mode::get_stealth_mode,
            windows::toggle_dock,
            windows::focus_main_window,
            windows::focus_overlay_window,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
