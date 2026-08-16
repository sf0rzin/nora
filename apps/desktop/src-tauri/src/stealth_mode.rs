//! Stealth mode: excludes the application's windows from screen capture.
//!
//! WINDOWS ONLY, and now unconditionally so. `SetWindowDisplayAffinity` has no equivalent
//! on the other desktops — neither Quartz nor X11/Wayland lets an application take itself
//! out of a capture — so the two commands below used to carry a
//! `#[cfg(not(target_os = "windows"))]` arm that returned an error explaining that. With
//! macOS and Linux dropped from the client, those arms could only ever have been reached
//! by a build that no longer exists, so they are gone and the guards with them.

use std::sync::{Arc, Mutex};
use tauri::{AppHandle, Manager, WebviewWindow};

pub type StealthModeState = Arc<Mutex<bool>>;

#[tauri::command]
pub fn set_stealth_mode(
    app_handle: AppHandle,
    state: tauri::State<'_, StealthModeState>,
    enabled: bool,
) -> Result<(), String> {
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
        // We only apply stealth to the overlay if it is visible.
        // If it is hidden, hwnd() forces creation of the native handle and the window
        // shows up white (bug reported on the Windows VM).
        match overlay.is_visible() {
            Ok(true) => {
                #[cfg(debug_assertions)]
                eprintln!("[stealth_mode] applying to visible overlay window");
                if let Err(e) = set_stealth_for_window(&overlay, enabled) {
                    #[cfg(debug_assertions)]
                    eprintln!("[stealth_mode] overlay window error: {}", e);
                }
            }
            Ok(false) => {
                #[cfg(debug_assertions)]
                eprintln!("[stealth_mode] overlay is hidden, skipping stealth apply");
            }
            Err(e) => {
                #[cfg(debug_assertions)]
                eprintln!("[stealth_mode] failed to check overlay visibility: {}", e);
            }
        }
    }

    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] set_stealth_mode completed successfully");
    Ok(())
}

#[tauri::command]
pub fn get_stealth_mode(state: tauri::State<'_, StealthModeState>) -> Result<bool, String> {
    let s = state.lock().map_err(|e| e.to_string())?;
    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] get_stealth_mode returning {}", *s);
    Ok(*s)
}

pub fn set_stealth_for_window(window: &WebviewWindow, enabled: bool) -> Result<(), String> {
    use windows::Win32::UI::WindowsAndMessaging::{
        SetWindowDisplayAffinity, WDA_EXCLUDEFROMCAPTURE, WDA_NONE,
    };

    let hwnd_raw = window.hwnd().map_err(|e| format!("Failed to get HWND: {}", e))?;
    // Tauri depends on windows 0.61; our Cargo.toml uses 0.62.
    // We rebuild the HWND of the correct version from the raw pointer.
    let hwnd = windows::Win32::Foundation::HWND(hwnd_raw.0);

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
