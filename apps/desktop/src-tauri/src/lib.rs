// NB: `windows_subsystem = "windows"` mora em main.rs (crate-root do binario). Aqui na lib
// ele nao tem efeito sobre o subsistema do .exe.

mod audio_capture;
mod audio_resample;
mod auth_bridge;
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

/// Resolve a URL base da API uma vez (memoizada). Prioridade:
/// 1) env `NORA_API_BASE_URL` injetada em build-time pelo build.rs (CI/produção);
/// 2) campo `plugins.nora.apiBaseUrl` do tauri.conf.json (default dev = localhost).
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
        // Auto-update: o plugin updater checa/baixa/instala releases assinados
        // (pubkey + endpoint em tauri.conf.json). O plugin process expõe o
        // relaunch() que o front chama depois de instalar. A UI fica na sidebar
        // do web remoto (nora.systems) — ver capabilities/updater-remote.json.
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_process::init())
        .manage(capture_state)
        .manage(sidecar_state)
        .manage(live_state)
        .manage(stealth_state)
        .manage(http_proxy::ApiBaseUrl(base_url))
        .manage(secrets::SecretStore::new())
        .setup(|app| {
            // Bandeja do sistema: ponto de entrada nativo pra abrir a janela
            // principal (web) e disparar a gravacao (mostra a dock flutuante).
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
