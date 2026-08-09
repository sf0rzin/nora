use tauri::{AppHandle, Manager, PhysicalPosition};

#[cfg(target_os = "windows")]
use tauri::WebviewWindow;

/// Removes the border/outline that Windows 11's DWM draws around ANY
/// top-level window — including transparent undecorated windows (dock,
/// overlay). Without this a ghost rounded rectangle shows up around the
/// glass bar (reported bug: "an outline as if it were a Windows
/// window"). We paint the border color as DWMWA_COLOR_NONE.
///
/// Best-effort: on old Windows versions the attribute is ignored, so
/// we only log the error in debug and move on.
#[cfg(target_os = "windows")]
pub fn remove_window_border(window: &WebviewWindow) {
    use windows::Win32::Foundation::HWND;
    use windows::Win32::Graphics::Dwm::{DwmSetWindowAttribute, DWMWA_BORDER_COLOR};

    // DWMWA_COLOR_NONE — turns off border drawing by the DWM.
    const DWMWA_COLOR_NONE: u32 = 0xFFFF_FFFE;

    let hwnd_raw = match window.hwnd() {
        Ok(h) => h,
        Err(e) => {
            #[cfg(debug_assertions)]
            eprintln!("[windows] remove_window_border: failed to get HWND: {}", e);
            let _ = e;
            return;
        }
    };
    // Tauri depends on windows 0.61; our Cargo.toml uses 0.62 — we rebuild the
    // HWND of the correct version from the raw pointer (same pattern as stealth).
    let hwnd = HWND(hwnd_raw.0);
    let color = DWMWA_COLOR_NONE;

    unsafe {
        if let Err(e) = DwmSetWindowAttribute(
            hwnd,
            DWMWA_BORDER_COLOR,
            &color as *const u32 as *const core::ffi::c_void,
            std::mem::size_of::<u32>() as u32,
        ) {
            #[cfg(debug_assertions)]
            eprintln!("[windows] DwmSetWindowAttribute(BORDER_COLOR) failed: {:?}", e);
            let _ = e;
        }
    }
}

/// Shows/hides the floating dock window.
///
/// The dock sits at the top-center of the primary monitor, with a
/// small vertical offset so it does not touch the edge. Positioning
/// only happens when it appears — after that the user can drag it
/// freely by the drag handle. The bar is resizable from JS
/// (setSize on expand/collapse), so we use the window's current width
/// (outer_size) to center it correctly in any state.
#[tauri::command]
pub fn toggle_dock(app_handle: AppHandle, show: bool) -> Result<(), String> {
    let Some(window) = app_handle.get_webview_window("dock") else {
        return Err("Dock window not found".to_string());
    };

    if show {
        // Repositions at top-center on every show — the user can drag it
        // afterwards, but the "appears at the top" default is more predictable
        // (Cluely style) than inheriting the previous position.
        if let Some(monitor) = window.current_monitor().map_err(|e| e.to_string())? {
            let scale = monitor.scale_factor();
            let monitor_size = monitor.size();
            let monitor_pos = monitor.position();
            let window_size = window.outer_size().map_err(|e| e.to_string())?;
            let top_margin = (24.0 * scale) as i32;
            let x = monitor_pos.x
                + ((monitor_size.width as i32 - window_size.width as i32) / 2);
            let y = monitor_pos.y + top_margin;
            let _ = window.set_position(PhysicalPosition { x, y });
        }
        let _ = window.show();
        // Kills the DWM ghost outline now that the window has an HWND and is
        // visible (calling it with the window hidden forces the handle and can
        // paint white — same care as stealth).
        #[cfg(target_os = "windows")]
        remove_window_border(&window);
        // We do not call set_focus — we want the dock visible but
        // without stealing focus from the foreground app (Meet/Zoom/Teams).
    } else {
        let _ = window.hide();
    }
    Ok(())
}

/// Shows and focuses the main window (NORA Desktop).
#[tauri::command]
pub fn focus_main_window(app_handle: AppHandle) -> Result<(), String> {
    let Some(window) = app_handle.get_webview_window("main") else {
        return Err("Main window not found".to_string());
    };
    let _ = window.show();
    let _ = window.unminimize();
    let _ = window.set_focus();
    Ok(())
}

/// Shows and focuses the overlay window.
#[tauri::command]
pub fn focus_overlay_window(app_handle: AppHandle) -> Result<(), String> {
    let Some(window) = app_handle.get_webview_window("overlay") else {
        return Err("Overlay window not found".to_string());
    };
    let _ = window.show();
    let _ = window.set_focus();
    Ok(())
}

/// Opens the file explorer at the app's log folder.
///
/// In release the binary runs with `windows_subsystem = "windows"` (no console),
/// so the `desktop.log` written by `start_recording` is the only way for the
/// user to diagnose "I clicked start and nothing happened". This command opens
/// the log folder in Explorer/Finder via the shell plugin — best-effort, but
/// returns an error to JS if the folder does not exist or the open fails.
#[tauri::command]
pub fn open_log_dir(app: AppHandle) -> Result<(), String> {
    use tauri_plugin_shell::ShellExt;

    let dir = app
        .path()
        .app_log_dir()
        .map_err(|e| format!("failed to resolve log dir: {}", e))?;
    // Ensures the folder exists before trying to open it.
    std::fs::create_dir_all(&dir).map_err(|e| format!("failed to create log dir: {}", e))?;

    let dir_str = dir.to_string_lossy().to_string();
    app.shell()
        .open(dir_str, None)
        .map_err(|e| format!("failed to open log dir: {}", e))?;
    Ok(())
}
