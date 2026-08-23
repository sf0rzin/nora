// NB: `windows_subsystem = "windows"` lives in main.rs (crate-root of the binary). Here in the lib
// it has no effect on the .exe subsystem.

mod audio_capture;
mod audio_resample;
mod auth_bridge;
pub mod commands;
mod http_proxy;
mod live_analysis;
mod stealth_mode;
mod stt;
mod stt_cloud;
mod stt_token;
mod system_audio;
mod windows;

use commands::CaptureState;
use live_analysis::LiveHighlightsState;
use std::sync::{Arc, Mutex, OnceLock};
use stealth_mode::StealthModeState;
use tauri::Manager;

/// Live STT backends (one per track). Still `Box<dyn SttBackend>` with one
/// implementation: the box is what lets `commands.rs` keep mic and system in one
/// `Vec` and stop them uniformly. The name stayed `SidecarState` to avoid spreading
/// a rename everywhere; nothing here has been a sidecar since the Python one went.
pub type SidecarState = Arc<Mutex<Vec<Box<dyn stt::SttBackend>>>>;

// `mod secrets` used to sit in the list above, and five commands — `http_proxy` plus
// `secret_set/get/has/delete` — used to sit in `generate_handler!` below. They are gone, and
// what they were is worth stating so nobody re-adds them out of habit.
//
// The path had neither end. The login form that wrote the keyring's `access-token` was deleted
// with the local UI (PR #465); the `apiClient` that read it back through `http_proxy` had no
// live caller. `stt_token.rs` recorded that measurement and then built on `auth_bridge`
// instead. Registering a command is what exposes it to the IPC, so five commands with access to
// the Windows Credential Manager and to the authenticated API stayed reachable for a year
// without a single caller (audit #15). The keyring dependency went with them.
//
// The reqwest client that lived in the same module DID have callers — `commands.rs`,
// `live_analysis.rs` and `stt_token.rs` — and it stayed. That is all `http_proxy.rs` holds now.

// `nora_config_str` used to live here, reading arbitrary `plugins.nora` keys out of the
// bundled `tauri.conf.json`. Its two callers were the STT backend selector and the Whisper
// model size, and both questions went with ADR 0039 — one backend, and the model is chosen
// by the server that pays for it. `api_base_url` below parses the same file directly, for
// the one key that is still a build-time decision.

/// Resolves the API base URL once (memoized). Priority:
/// 1) env `NORA_API_BASE_URL` injected at build-time by build.rs (CI/production);
/// 2) `plugins.nora.apiBaseUrl` field of tauri.conf.json (dev default = localhost).
pub fn api_base_url() -> String {
    static URL: OnceLock<String> = OnceLock::new();
    URL.get_or_init(|| {
        if let Some(url) = option_env!("NORA_API_BASE_URL") {
            if !url.is_empty() {
                return url.to_string();
            }
        }
        const CONFIG_JSON: &str = include_str!("../tauri.conf.json");
        let config: serde_json::Value = match serde_json::from_str(CONFIG_JSON) {
            Ok(c) => c,
            Err(e) => {
                eprintln!(
                    "[nora] failed to parse tauri.conf.json: {}, using default",
                    e
                );
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

    // Parsed here rather than merely printed: `api_base_url()` memoizes a string that every
    // network call in the app then joins paths onto, and a typo in tauri.conf.json should fail
    // at startup instead of at the first upload. The parsed value itself is no longer kept as
    // managed state — its only reader was the deleted `http_proxy` command.
    let api_base_url = api_base_url();
    url::Url::parse(&api_base_url).expect("Invalid apiBaseUrl in tauri.conf.json");
    #[cfg(debug_assertions)]
    eprintln!("[nora] api_base_url={}", api_base_url);

    tauri::Builder::default()
        .plugin(tauri_plugin_shell::init())
        // Auto-update: the updater plugin checks/downloads/installs signed releases
        // (pubkey + endpoint in tauri.conf.json). The process plugin exposes the
        // relaunch() the front end calls after installing. The UI lives in the sidebar
        // of the remote web (nora.systems) — see capabilities/updater-remote.json.
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .manage(capture_state)
        .manage(sidecar_state)
        .manage(live_state)
        .manage(stealth_state)
        .setup(|app| {
            // System tray: native entry point to open the main window
            // (web) and trigger the recording (shows the floating dock).
            use tauri::{
                menu::{MenuBuilder, MenuItemBuilder},
                tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
                Manager,
            };
            let open_item = MenuItemBuilder::with_id("open", "Abrir NORA").build(app)?;
            let record_item = MenuItemBuilder::with_id("record", "Gravar reunião").build(app)?;
            let quit_item = MenuItemBuilder::with_id("quit", "Sair").build(app)?;
            let menu = MenuBuilder::new(app)
                .item(&open_item)
                .item(&record_item)
                .separator()
                .item(&quit_item)
                .build()?;
            let _tray = TrayIconBuilder::with_id("nora-tray")
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("NORA Desktop")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "open" => {
                        if let Some(w) = app.get_webview_window("main") {
                            let _ = w.show();
                            let _ = w.unminimize();
                            let _ = w.set_focus();
                        }
                    }
                    "record" => {
                        let _ = windows::toggle_dock(app.clone(), true);
                    }
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click {
                        button: MouseButton::Left,
                        button_state: MouseButtonState::Up,
                        ..
                    } = event
                    {
                        let app = tray.app_handle();
                        if let Some(w) = app.get_webview_window("main") {
                            let _ = w.show();
                            let _ = w.set_focus();
                        }
                    }
                })
                .build(app)?;
            Ok(())
        })
        .on_window_event(|window, event| {
            if let tauri::WindowEvent::CloseRequested { .. } = event {
                if window.label() == "main" {
                    window.app_handle().exit(0);
                }
            }
        })
        .invoke_handler(tauri::generate_handler![
            commands::list_audio_devices,
            commands::start_recording,
            commands::stop_recording,
            commands::get_recording_status,
            commands::upload_meeting,
            live_analysis::analyze_live,
            live_analysis::toggle_overlay,
            live_analysis::clear_live_highlights,
            stealth_mode::set_stealth_mode,
            stealth_mode::get_stealth_mode,
            windows::toggle_dock,
            windows::focus_main_window,
            windows::focus_overlay_window,
            windows::open_log_dir,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
