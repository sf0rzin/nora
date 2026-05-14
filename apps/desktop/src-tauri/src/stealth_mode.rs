use std::sync::{Arc, Mutex};
use tauri::AppHandle;

pub type StealthModeState = Arc<Mutex<bool>>;

#[tauri::command]
pub fn set_stealth_mode(
    app_handle: AppHandle,
    state: tauri::State<'_, StealthModeState>,
    enabled: bool,
) -> Result<(), String> {
    #[cfg(not(target_os = "windows"))]
    {
        let _ = (app_handle, state, enabled);
        return Err(
            "Stealth mode is only supported on Windows. \
             On Linux and macOS, the display server does not provide an API \
             for applications to exclude themselves from screen capture."
                .to_string(),
        );
    }

    #[cfg(target_os = "windows")]
    {
        use tauri::{Manager, WebviewWindow};

        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] set_stealth_mode called, enabled={}", enabled);

        {
            let mut s = state.lock().map_err(|e| {
                #[cfg(debug_assertions)]
                eprintln!("[stealth_mode] failed to lock state: {}", e);
                e.to_string()
            })?;
            *s = enabled;
        }

        if let Some(main) = app_handle.get_webview_window("main") {
            #[cfg(debug_assertions)]
            eprintln!("[stealth_mode] applying to main window");
            set_stealth_for_window(&main, enabled)?;
        }
        if let Some(overlay) = app_handle.get_webview_window("overlay") {
            #[cfg(debug_assertions)]
            eprintln!("[stealth_mode] applying to overlay window");
            // Overlay hidden pode não ter handle nativo ainda — ignoramos erro específico
            if let Err(e) = set_stealth_for_window(&overlay, enabled) {
                #[cfg(debug_assertions)]
                eprintln!("[stealth_mode] overlay window error: {}", e);
            }
        }

        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] set_stealth_mode completed successfully");
        Ok(())
    }
}

#[tauri::command]
pub fn get_stealth_mode(state: tauri::State<'_, StealthModeState>) -> Result<bool, String> {
    #[cfg(not(target_os = "windows"))]
    {
        let _ = state;
        return Ok(false);
    }

    #[cfg(target_os = "windows")]
    {
        let s = state.lock().map_err(|e| e.to_string())?;
        #[cfg(debug_assertions)]
        eprintln!("[stealth_mode] get_stealth_mode returning {}", *s);
        Ok(*s)
    }
}

#[cfg(target_os = "windows")]
pub fn set_stealth_for_window(window: &WebviewWindow, enabled: bool) -> Result<(), String> {
    use windows::Win32::UI::WindowsAndMessaging::{
        SetWindowDisplayAffinity, WDA_EXCLUDEFROMCAPTURE, WDA_NONE,
    };

    let hwnd = window.hwnd().map_err(|e| format!("Failed to get HWND: {}", e))?;

    unsafe {
        SetWindowDisplayAffinity(
            hwnd,
            if enabled {
                WDA_EXCLUDEFROMCAPTURE
            } else {
                WDA_NONE
            },
        )
        .map_err(|e| format!("SetWindowDisplayAffinity failed: {:?}", e))?;
    }

    Ok(())
}
