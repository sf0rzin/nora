#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod audio_capture;
pub mod commands;

use commands::CaptureState;
use std::sync::{Arc, Mutex};

#[cfg_attr(mobile, tauri::mobile_entry_point)]
pub fn run() {
    let capture_state: CaptureState = Arc::new(Mutex::new(audio_capture::AudioCapture::new()));

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        .manage(capture_state)
        .invoke_handler(tauri::generate_handler![
            commands::greet,
            commands::list_audio_devices,
            commands::start_recording,
            commands::stop_recording,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
