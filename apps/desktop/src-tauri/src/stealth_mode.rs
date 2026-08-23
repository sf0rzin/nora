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

/// The windows that start hidden (`visible: false` in tauri.conf.json) and are shown by their
/// own toggle: the transcript overlay and the floating dock.
///
/// The dock was missing from this list, which was the whole of audit #10. It is the bar that
/// stays on screen for the entire meeting, and it is where the stealth button itself lives — so
/// turning stealth on hid `main` and `overlay` from the capture and left the eye-closed icon
/// visible to the room, announcing to everyone that the user believed they were invisible.
pub(crate) const FLOATING_WINDOWS: &[&str] = &["overlay", "dock"];

#[tauri::command]
pub fn set_stealth_mode(
    window: tauri::Window,
    app_handle: AppHandle,
    state: tauri::State<'_, StealthModeState>,
    enabled: bool,
) -> Result<(), String> {
    crate::commands::ensure_local_window(&window)?;

    #[cfg(debug_assertions)]
    eprintln!(
        "[stealth_mode] set_stealth_mode called, enabled={}",
        enabled
    );

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
    for label in FLOATING_WINDOWS {
        let Some(floating) = app_handle.get_webview_window(label) else {
            continue;
        };
        // We only apply stealth to a floating window if it is visible.
        // If it is hidden, hwnd() forces creation of the native handle and the window
        // shows up white (bug reported on the Windows VM). The window that is hidden now
        // picks the setting up when it is shown — `toggle_overlay` and `toggle_dock` both
        // re-read this state at that moment.
        match floating.is_visible() {
            Ok(true) => {
                #[cfg(debug_assertions)]
                eprintln!("[stealth_mode] applying to visible {} window", label);
                if let Err(e) = set_stealth_for_window(&floating, enabled) {
                    #[cfg(debug_assertions)]
                    eprintln!("[stealth_mode] {} window error: {}", label, e);
                    let _ = e;
                }
            }
            Ok(false) => {
                #[cfg(debug_assertions)]
                eprintln!("[stealth_mode] {} is hidden, skipping stealth apply", label);
            }
            Err(e) => {
                #[cfg(debug_assertions)]
                eprintln!("[stealth_mode] failed to check {} visibility: {}", label, e);
                let _ = e;
            }
        }
    }

    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] set_stealth_mode completed successfully");
    Ok(())
}

#[tauri::command]
pub fn get_stealth_mode(
    window: tauri::Window,
    state: tauri::State<'_, StealthModeState>,
) -> Result<bool, String> {
    crate::commands::ensure_local_window(&window)?;
    let s = state.lock().map_err(|e| e.to_string())?;
    #[cfg(debug_assertions)]
    eprintln!("[stealth_mode] get_stealth_mode returning {}", *s);
    Ok(*s)
}

pub fn set_stealth_for_window(window: &WebviewWindow, enabled: bool) -> Result<(), String> {
    use windows::Win32::UI::WindowsAndMessaging::{
        SetWindowDisplayAffinity, WDA_EXCLUDEFROMCAPTURE, WDA_NONE,
    };

    let hwnd_raw = window
        .hwnd()
        .map_err(|e| format!("Failed to get HWND: {}", e))?;
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

/// Applies the stored stealth setting to a window that has just become visible.
///
/// Every window this module skips while hidden has to come back through here, or the promise
/// the dock's button makes ("invisible in screen capture") is only kept for whichever windows
/// happened to be on screen when the user pressed it.
#[cfg(target_os = "windows")]
pub fn reapply_stealth(app_handle: &AppHandle, window: &WebviewWindow) {
    // Read through `try_state` rather than an injected `State` parameter: `toggle_dock` is also
    // called from the tray menu in `lib.rs`, which holds an AppHandle and no command context.
    let Some(state) = app_handle.try_state::<StealthModeState>() else {
        return;
    };
    let enabled = match state.lock() {
        Ok(s) => *s,
        Err(_) => return,
    };
    // Applies the setting whichever way it points, not only when it is on. A window can be
    // hidden while stealth is turned OFF too, and it would otherwise come back still carrying
    // the WDA_EXCLUDEFROMCAPTURE it was given the last time it was on screen — invisible in the
    // capture while the button says it is not. Safe here in a way it is not in
    // `set_stealth_mode`, because the caller has just shown the window: there is no hidden
    // handle to force into existence.
    #[cfg(debug_assertions)]
    eprintln!(
        "[stealth_mode] reapplying stealth={} to the newly visible {} window",
        enabled,
        window.label()
    );
    let _ = set_stealth_for_window(window, enabled);
}

#[cfg(test)]
mod tests {
    use super::*;

    /// The dock hosts the stealth button and stays on screen for the whole meeting. Leaving it
    /// out of this list is what let the eye-closed icon keep showing in a shared screen.
    #[test]
    fn the_dock_is_one_of_the_windows_stealth_covers() {
        assert!(FLOATING_WINDOWS.contains(&"dock"));
        assert!(FLOATING_WINDOWS.contains(&"overlay"));
    }

    /// `main` is applied directly and unconditionally, because it is the one window that is
    /// visible from startup — it must not be in the deferred list or it would be skipped while
    /// minimised on some window managers.
    #[test]
    fn the_main_window_is_not_deferred() {
        assert!(!FLOATING_WINDOWS.contains(&"main"));
    }
}
