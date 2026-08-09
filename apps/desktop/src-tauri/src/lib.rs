// NB: `windows_subsystem = "windows"` lives in main.rs (crate-root of the binary). Here in the lib
// it has no effect on the .exe subsystem.

mod audio_capture;
mod audio_resample;
mod auth_bridge;
pub mod commands;
mod http_proxy;
mod live_analysis;
mod secrets;
// `speech_token` only exists for the azure backend. In the pure-local build
// (`--no-default-features --features stt-local`) it does not even enter the binary — that is
// why the app has no way to fail trying to talk to `/speech/token`.
#[cfg(feature = "stt-azure")]
mod speech_token;
mod stt;
#[cfg(feature = "stt-local")]
mod stt_local;
#[cfg(feature = "stt-azure")]
mod stt_sidecar;
#[cfg(all(test, feature = "stt-azure"))]
mod stt_sidecar_test;
#[cfg(feature = "stt-local")]
mod whisper_model;
mod stealth_mode;
mod system_audio;
mod windows;

use commands::CaptureState;
use live_analysis::LiveHighlightsState;
use stealth_mode::StealthModeState;
use std::sync::{Arc, Mutex, OnceLock};
use tauri::Manager;

/// Live STT backends (one per track). `Box<dyn SttBackend>` because the backend
/// is chosen at runtime — see `stt::configured_backend`. The name stayed
/// `SidecarState` to avoid spreading a rename everywhere; not every backend here is
/// a sidecar (the local one runs in-process).
pub type SidecarState = Arc<Mutex<Vec<Box<dyn stt::SttBackend>>>>;

/// Reads a `plugins.nora` key from the `tauri.conf.json` embedded at build-time.
///
/// Exists because a runtime env var does not reach an app opened from Finder/Explorer:
/// product config (STT backend, model size) has to come from the bundle.
pub fn nora_config_str(key: &str) -> Option<String> {
    const CONFIG_JSON: &str = include_str!("../tauri.conf.json");
    let config: serde_json::Value = serde_json::from_str(CONFIG_JSON).ok()?;
    config
        .get("plugins")?
        .get("nora")?
        .get(key)?
        .as_str()
        .map(str::to_string)
}

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
        .manage(http_proxy::ApiBaseUrl(base_url))
        .manage(secrets::SecretStore::new())
        .setup(|app| {
            // System tray: native entry point to open the main window
            // (web) and trigger the recording (shows the floating dock).
            use tauri::{
                menu::{MenuBuilder, MenuItemBuilder},
                tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
                Manager,
            };
            let abrir = MenuItemBuilder::with_id("abrir", "Abrir NORA").build(app)?;
            let gravar = MenuItemBuilder::with_id("gravar", "Gravar reunião").build(app)?;
            let sair = MenuItemBuilder::with_id("sair", "Sair").build(app)?;
            let menu = MenuBuilder::new(app)
                .item(&abrir)
                .item(&gravar)
                .separator()
                .item(&sair)
                .build()?;
            let _tray = TrayIconBuilder::with_id("nora-tray")
                .icon(app.default_window_icon().unwrap().clone())
                .tooltip("NORA Desktop")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id().as_ref() {
                    "abrir" => {
                        if let Some(w) = app.get_webview_window("main") {
                            let _ = w.show();
                            let _ = w.unminimize();
                            let _ = w.set_focus();
                        }
                    }
                    "gravar" => {
                        let _ = windows::toggle_dock(app.clone(), true);
                    }
                    "sair" => app.exit(0),
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
            commands::check_system_audio_prerequisites,
            http_proxy::http_proxy,
            secrets::secret_set,
            secrets::secret_get,
            secrets::secret_has,
            secrets::secret_delete,
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
